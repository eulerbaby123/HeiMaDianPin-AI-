package com.hmdp.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.ai.client.AiRemoteClient;
import com.hmdp.ai.client.dto.*;
import com.hmdp.config.properties.AiProperties;
import com.hmdp.dto.Result;
import com.hmdp.dto.ai.AiAssistantRequestDTO;
import com.hmdp.dto.ai.AiAssistantResponseDTO;
import com.hmdp.dto.ai.AiRecommendShopDTO;
import com.hmdp.dto.ai.AiShopSummaryDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IAiService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AiServiceImpl implements IAiService {

    private static final ExecutorService WARMUP_EXECUTOR = Executors.newFixedThreadPool(4);
    private static final Pattern SPLIT_PATTERN = Pattern.compile("[,，。！？!?.;；\\n\\r]");

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IShopService shopService;
    @Resource
    private IBlogService blogService;
    @Resource
    private IShopTypeService shopTypeService;
    @Resource
    private AiRemoteClient aiRemoteClient;
    @Resource
    private AiProperties aiProperties;

    @Override
    public Result getShopSummary(Long shopId, Boolean refresh) {
        if (shopId == null) {
            return Result.fail("shopId不能为空");
        }
        Shop shop = shopService.getById(shopId);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        List<Blog> blogs = blogService.query()
                .eq("shop_id", shopId)
                .orderByDesc("liked")
                .orderByDesc("create_time")
                .last("limit " + aiProperties.getSummaryMaxBlogs())
                .list();

        if (CollUtil.isEmpty(blogs)) {
            return Result.ok(emptySummary(shopId, shop.getName()));
        }

        String fingerprint = buildFingerprint(blogs);
        String summaryKey = RedisConstants.AI_SHOP_SUMMARY_KEY + shopId;
        if (!Boolean.TRUE.equals(refresh)) {
            AiShopSummaryDTO cached = readSummary(summaryKey);
            if (cached != null && StrUtil.equals(cached.getFingerprint(), fingerprint)) {
                cached.setFromCache(true);
                return Result.ok(cached);
            }
        }

        String lockKey = RedisConstants.LOCK_AI_SHOP_SUMMARY_KEY + shopId;
        if (!tryLock(lockKey, 30L)) {
            AiShopSummaryDTO cached = readSummary(summaryKey);
            if (cached != null) {
                cached.setFromCache(true);
                return Result.ok(cached);
            }
            return Result.fail("AI总结正在生成，请稍后重试");
        }

        try {
            AiShopSummaryDTO summary = buildSummary(shop, blogs, fingerprint);
            stringRedisTemplate.opsForValue().set(
                    summaryKey,
                    JSONUtil.toJsonStr(summary),
                    aiProperties.getSummaryTtlMinutes(),
                    TimeUnit.MINUTES
            );
            return Result.ok(summary);
        } finally {
            unlock(lockKey);
        }
    }

    @Override
    public Result warmupShopSummary(Long shopId) {
        if (shopId == null) {
            return Result.fail("shopId不能为空");
        }
        WARMUP_EXECUTOR.submit(() -> {
            try {
                getShopSummary(shopId, true);
            } catch (Exception e) {
                log.warn("warmup shop summary failed, shopId={}, err={}", shopId, e.getMessage());
            }
        });
        return Result.ok("已提交预热任务");
    }

    @Override
    public Result assistantRecommend(AiAssistantRequestDTO requestDTO) {
        if (requestDTO == null || StrUtil.isBlank(requestDTO.getQuery())) {
            return Result.fail("query不能为空");
        }
        String cacheKey = RedisConstants.AI_ASSISTANT_CACHE_KEY + buildAssistantCacheId(requestDTO);
        AiAssistantResponseDTO cached = readAssistantCache(cacheKey);
        if (cached != null) {
            cached.setFromCache(true);
            return Result.ok(cached);
        }

        List<ShopType> allTypes = shopTypeService.query().orderByAsc("sort").list();
        IntentParseResponse intent = parseIntentWithFallback(requestDTO.getQuery(), allTypes);
        List<Long> typeIds = resolveTypeIds(intent, requestDTO.getCurrentTypeId(), allTypes);
        List<CandidateShop> candidates = findCandidates(typeIds, requestDTO.getX(), requestDTO.getY());

        List<String> includeKeywords = mergeKeywords(intent, requestDTO.getQuery());
        for (CandidateShop candidate : candidates) {
            candidate.rankScore = calcRankScore(candidate, includeKeywords, requestDTO.getQuery());
        }
        candidates.sort((a, b) -> {
            int scoreComp = Double.compare(b.rankScore, a.rankScore);
            if (scoreComp != 0) {
                return scoreComp;
            }
            return Double.compare(safeDistance(a.distance), safeDistance(b.distance));
        });

        int topN = Math.min(aiProperties.getAssistantTopN(), candidates.size());
        List<CandidateShop> topCandidates = candidates.subList(0, topN);

        List<AiRecommendShopDTO> recommendShops = topCandidates.stream()
                .map(this::toRecommendShopDTO)
                .collect(Collectors.toList());
        fillReasonWithAiOrFallback(requestDTO.getQuery(), recommendShops);

        AiAssistantResponseDTO responseDTO = new AiAssistantResponseDTO();
        responseDTO.setQuery(requestDTO.getQuery());
        responseDTO.setIntentSummary(intent.getIntentSummary());
        responseDTO.setKeywords(includeKeywords);
        responseDTO.setRecommendShops(recommendShops);
        responseDTO.setGeneratedAt(LocalDateTime.now().toString());
        responseDTO.setFromCache(false);

        stringRedisTemplate.opsForValue().set(
                cacheKey,
                JSONUtil.toJsonStr(responseDTO),
                aiProperties.getAssistantTtlMinutes(),
                TimeUnit.MINUTES
        );
        return Result.ok(responseDTO);
    }

    private AiShopSummaryDTO buildSummary(Shop shop, List<Blog> blogs, String fingerprint) {
        List<List<Blog>> groupedBlogs = splitBlogs(blogs, aiProperties.getSummaryGroupSize());
        List<ChunkSummaryResponse> chunkSummaries = new ArrayList<>(groupedBlogs.size());

        for (int i = 0; i < groupedBlogs.size(); i++) {
            int chunkIndex = i + 1;
            String chunkKey = RedisConstants.AI_SHOP_SUMMARY_CHUNK_KEY
                    + shop.getId() + ":" + fingerprint + ":" + chunkIndex;
            ChunkSummaryResponse chunkSummary = readChunkSummary(chunkKey);
            if (chunkSummary == null) {
                ChunkSummaryRequest request = buildChunkRequest(shop, groupedBlogs.get(i), chunkIndex, groupedBlogs.size());
                chunkSummary = aiRemoteClient.summarizeChunk(request);
                if (!isValidChunkSummary(chunkSummary)) {
                    chunkSummary = localChunkSummary(groupedBlogs.get(i));
                }
                normalizeChunkSummary(chunkSummary);
                stringRedisTemplate.opsForValue().set(
                        chunkKey,
                        JSONUtil.toJsonStr(chunkSummary),
                        aiProperties.getChunkTtlMinutes(),
                        TimeUnit.MINUTES
                );
            }
            chunkSummaries.add(chunkSummary);
        }

        List<String> highFrequency = aggregateHighFrequency(chunkSummaries);
        List<String> uniqueHighlights = aggregateUnique(chunkSummaries);

        FinalSummaryRequest finalRequest = new FinalSummaryRequest();
        finalRequest.setShopId(shop.getId());
        finalRequest.setShopName(shop.getName());
        finalRequest.setReviewCount(blogs.size());
        finalRequest.setChunkSummaries(chunkSummaries);
        FinalSummaryResponse finalSummary = aiRemoteClient.summarizeFinal(finalRequest);
        if (!isValidFinalSummary(finalSummary)) {
            finalSummary = localFinalSummary(shop.getName(), highFrequency, uniqueHighlights);
        }

        if (CollUtil.isEmpty(finalSummary.getHighFrequencyPoints())) {
            finalSummary.setHighFrequencyPoints(highFrequency);
        }
        if (CollUtil.isEmpty(finalSummary.getUniquePoints())) {
            finalSummary.setUniquePoints(uniqueHighlights);
        }

        AiShopSummaryDTO summaryDTO = new AiShopSummaryDTO();
        summaryDTO.setShopId(shop.getId());
        summaryDTO.setShopName(shop.getName());
        summaryDTO.setReviewCount(blogs.size());
        summaryDTO.setChunkCount(groupedBlogs.size());
        summaryDTO.setFinalSummary(finalSummary.getSummary());
        summaryDTO.setAdvice(finalSummary.getAdvice());
        summaryDTO.setHighFrequencyHighlights(finalSummary.getHighFrequencyPoints());
        summaryDTO.setUniqueHighlights(finalSummary.getUniquePoints());
        summaryDTO.setGeneratedAt(LocalDateTime.now().toString());
        summaryDTO.setFingerprint(fingerprint);
        summaryDTO.setFromCache(false);
        return summaryDTO;
    }

    private ChunkSummaryRequest buildChunkRequest(Shop shop, List<Blog> blogs, int chunkIndex, int totalChunks) {
        ChunkSummaryRequest request = new ChunkSummaryRequest();
        request.setShopId(shop.getId());
        request.setShopName(shop.getName());
        request.setChunkIndex(chunkIndex);
        request.setTotalChunks(totalChunks);

        List<ReviewSnippet> snippets = blogs.stream().map(blog -> {
            ReviewSnippet snippet = new ReviewSnippet();
            snippet.setBlogId(blog.getId());
            snippet.setTitle(sanitizeText(blog.getTitle()));
            snippet.setContent(sanitizeText(blog.getContent()));
            snippet.setLiked(blog.getLiked());
            snippet.setCreateTime(blog.getCreateTime() == null ? "" : blog.getCreateTime().toString());
            return snippet;
        }).collect(Collectors.toList());
        request.setReviews(snippets);
        return request;
    }

    private ChunkSummaryResponse localChunkSummary(List<Blog> chunkBlogs) {
        List<SentenceSample> sentenceSamples = collectSentenceSamples(chunkBlogs);
        if (CollUtil.isEmpty(sentenceSamples)) {
            ChunkSummaryResponse response = new ChunkSummaryResponse();
            response.setSummary("本组探店内容较短，暂无明显口碑信息。");
            response.setHighFrequencyPoints(Collections.singletonList("信息量不足，建议补充更多探店内容"));
            response.setUniquePoints(Collections.emptyList());
            response.setKeywords(Collections.emptyList());
            return response;
        }

        Map<String, Integer> freq = new HashMap<>();
        Map<String, Integer> weight = new HashMap<>();
        Map<String, String> original = new HashMap<>();
        for (SentenceSample sample : sentenceSamples) {
            freq.put(sample.normalizedText, freq.getOrDefault(sample.normalizedText, 0) + 1);
            weight.put(sample.normalizedText, Math.max(weight.getOrDefault(sample.normalizedText, 0), sample.weight));
            original.putIfAbsent(sample.normalizedText, sample.originalText);
        }

        List<Map.Entry<String, Integer>> rankedByFreq = freq.entrySet().stream()
                .sorted((a, b) -> {
                    int c = Integer.compare(b.getValue(), a.getValue());
                    if (c != 0) {
                        return c;
                    }
                    return Integer.compare(weight.getOrDefault(b.getKey(), 0), weight.getOrDefault(a.getKey(), 0));
                })
                .collect(Collectors.toList());

        List<String> highFrequencyPoints = rankedByFreq.stream()
                .map(entry -> original.get(entry.getKey()))
                .filter(StrUtil::isNotBlank)
                .limit(4)
                .collect(Collectors.toList());

        List<String> uniquePoints = rankedByFreq.stream()
                .filter(entry -> entry.getValue() == 1)
                .map(entry -> original.get(entry.getKey()))
                .filter(StrUtil::isNotBlank)
                .limit(3)
                .collect(Collectors.toList());

        ChunkSummaryResponse response = new ChunkSummaryResponse();
        response.setSummary("本组高频信息：" + joinPoints(highFrequencyPoints, "；"));
        response.setHighFrequencyPoints(highFrequencyPoints);
        response.setUniquePoints(uniquePoints);
        response.setKeywords(extractKeywords(highFrequencyPoints, uniquePoints));
        return response;
    }

    private FinalSummaryResponse localFinalSummary(String shopName, List<String> highFrequency, List<String> uniqueHighlights) {
        FinalSummaryResponse response = new FinalSummaryResponse();
        String summary = shopName + "整体口碑聚焦在：" + joinPoints(highFrequency, "；");
        if (CollUtil.isNotEmpty(uniqueHighlights)) {
            summary = summary + "。同时有一些小众亮点：" + joinPoints(uniqueHighlights, "；");
        }
        response.setSummary(summary);
        response.setAdvice("建议优先核对高频口碑项，再结合距离、评分、人均消费做决策。");
        response.setHighFrequencyPoints(highFrequency);
        response.setUniquePoints(uniqueHighlights);
        return response;
    }

    private IntentParseResponse parseIntentWithFallback(String query, List<ShopType> allTypes) {
        IntentParseRequest request = new IntentParseRequest();
        request.setQuery(query);
        request.setAvailableTypes(allTypes.stream().map(ShopType::getName).collect(Collectors.toList()));
        IntentParseResponse intent = aiRemoteClient.parseIntent(request);
        if (isValidIntent(intent)) {
            return intent;
        }
        return localIntentParse(query);
    }

    private IntentParseResponse localIntentParse(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        List<String> typeKeywords = new ArrayList<>();
        if (lower.contains("火锅")) typeKeywords.add("火锅");
        if (lower.contains("烧烤")) typeKeywords.add("烧烤");
        if (lower.contains("奶茶") || lower.contains("饮品")) typeKeywords.add("茶");
        if (lower.contains("咖啡")) typeKeywords.add("咖啡");
        if (lower.contains("日料") || lower.contains("寿司")) typeKeywords.add("日");
        if (lower.contains("海鲜")) typeKeywords.add("海鲜");
        if (lower.contains("ktv") || lower.contains("唱歌")) typeKeywords.add("KTV");

        IntentParseResponse response = new IntentParseResponse();
        response.setIntentSummary("基于用户描述推荐附近可能匹配的店铺");
        response.setTypeKeywords(typeKeywords);
        response.setIncludeKeywords(extractQueryTokens(query));
        response.setExcludeKeywords(Collections.emptyList());
        return response;
    }

    private List<Long> resolveTypeIds(IntentParseResponse intent, Long currentTypeId, List<ShopType> allTypes) {
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        List<String> typeKeywords = intent.getTypeKeywords();
        if (CollUtil.isNotEmpty(typeKeywords)) {
            for (String keyword : typeKeywords) {
                if (StrUtil.isBlank(keyword)) {
                    continue;
                }
                for (ShopType shopType : allTypes) {
                    if (StrUtil.containsIgnoreCase(shopType.getName(), keyword) || StrUtil.containsIgnoreCase(keyword, shopType.getName())) {
                        result.add(shopType.getId());
                    }
                }
            }
        }
        if (result.isEmpty() && currentTypeId != null && currentTypeId > 0) {
            result.add(currentTypeId);
        }
        if (result.isEmpty()) {
            for (int i = 0; i < Math.min(3, allTypes.size()); i++) {
                result.add(allTypes.get(i).getId());
            }
        }
        return new ArrayList<>(result);
    }

    private List<CandidateShop> findCandidates(List<Long> typeIds, Double x, Double y) {
        if (x != null && y != null) {
            List<CandidateShop> geoCandidates = findCandidatesByGeo(typeIds, x, y);
            if (CollUtil.isNotEmpty(geoCandidates)) {
                return geoCandidates;
            }
        }
        return findCandidatesByDb(typeIds);
    }

    private List<CandidateShop> findCandidatesByGeo(List<Long> typeIds, Double x, Double y) {
        Map<Long, Double> distanceByShopId = new HashMap<>();
        int radius = aiProperties.getAssistantRadiusMeters();
        int limit = aiProperties.getAssistantCandidateLimit();
        for (Long typeId : typeIds) {
            String key = RedisConstants.SHOP_GEO_KEY + typeId;
            GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                    key,
                    GeoReference.fromCoordinate(x, y),
                    new Distance(radius),
                    RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                            .includeDistance()
                            .sortAscending()
                            .limit(limit)
            );
            if (results == null || results.getContent().isEmpty()) {
                continue;
            }
            for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : results) {
                Long shopId = Long.valueOf(result.getContent().getName());
                double distance = result.getDistance() == null ? 999999D : result.getDistance().getValue();
                Double oldDistance = distanceByShopId.get(shopId);
                if (oldDistance == null || distance < oldDistance) {
                    distanceByShopId.put(shopId, distance);
                }
            }
        }
        if (distanceByShopId.isEmpty()) {
            return Collections.emptyList();
        }

        List<Shop> shops = shopService.list(new QueryWrapper<Shop>().in("id", distanceByShopId.keySet()));
        List<CandidateShop> candidates = new ArrayList<>(shops.size());
        for (Shop shop : shops) {
            CandidateShop candidate = new CandidateShop();
            candidate.shop = shop;
            candidate.distance = distanceByShopId.get(shop.getId());
            candidates.add(candidate);
        }
        return candidates;
    }

    private List<CandidateShop> findCandidatesByDb(List<Long> typeIds) {
        int limit = Math.max(aiProperties.getAssistantCandidateLimit() * Math.max(typeIds.size(), 1), 20);
        QueryWrapper<Shop> wrapper = new QueryWrapper<>();
        if (CollUtil.isNotEmpty(typeIds)) {
            wrapper.in("type_id", typeIds);
        }
        wrapper.orderByDesc("score").orderByDesc("comments").last("limit " + limit);
        List<Shop> shops = shopService.list(wrapper);
        List<CandidateShop> candidates = new ArrayList<>(shops.size());
        for (Shop shop : shops) {
            CandidateShop candidate = new CandidateShop();
            candidate.shop = shop;
            candidate.distance = null;
            candidates.add(candidate);
        }
        return candidates;
    }

    private double calcRankScore(CandidateShop candidate, List<String> includeKeywords, String query) {
        Shop shop = candidate.shop;
        double score = 0D;
        score += safeInt(shop.getScore()) / 10.0D * 2.0D;
        score += Math.log(safeInt(shop.getComments()) + 1) * 0.8D;
        score += Math.log(safeInt(shop.getSold()) + 1) * 0.3D;
        if (candidate.distance != null) {
            score += Math.max(0D, (aiProperties.getAssistantRadiusMeters() - candidate.distance) / 1000D);
        }

        String searchable = (shop.getName() == null ? "" : shop.getName()) + " " + (shop.getAddress() == null ? "" : shop.getAddress());
        for (String keyword : includeKeywords) {
            if (StrUtil.isNotBlank(keyword) && StrUtil.containsIgnoreCase(searchable, keyword)) {
                score += 0.6D;
            }
        }

        if (query.contains("便宜") || query.contains("平价")) {
            if (shop.getAvgPrice() != null && shop.getAvgPrice() <= 80) {
                score += 0.8D;
            }
        }
        if (query.contains("约会") || query.contains("高端")) {
            if (shop.getAvgPrice() != null && shop.getAvgPrice() >= 120) {
                score += 0.8D;
            }
        }
        return score;
    }

    private AiRecommendShopDTO toRecommendShopDTO(CandidateShop candidate) {
        Shop shop = candidate.shop;
        AiRecommendShopDTO dto = new AiRecommendShopDTO();
        dto.setId(shop.getId());
        dto.setTypeId(shop.getTypeId());
        dto.setName(shop.getName());
        dto.setAddress(shop.getAddress());
        dto.setAvgPrice(shop.getAvgPrice());
        dto.setScore(shop.getScore());
        dto.setComments(shop.getComments());
        dto.setSold(shop.getSold());
        dto.setDistance(candidate.distance);
        return dto;
    }

    private void fillReasonWithAiOrFallback(String query, List<AiRecommendShopDTO> recommendShops) {
        if (CollUtil.isEmpty(recommendShops)) {
            return;
        }
        RecommendReasonRequest reasonRequest = new RecommendReasonRequest();
        reasonRequest.setQuery(query);
        List<RecommendReasonShop> reasonShops = recommendShops.stream().map(shop -> {
            RecommendReasonShop reasonShop = new RecommendReasonShop();
            reasonShop.setId(shop.getId());
            reasonShop.setName(shop.getName());
            reasonShop.setAddress(shop.getAddress());
            reasonShop.setAvgPrice(shop.getAvgPrice());
            reasonShop.setScore(shop.getScore());
            reasonShop.setDistance(shop.getDistance());
            return reasonShop;
        }).collect(Collectors.toList());
        reasonRequest.setShops(reasonShops);

        RecommendReasonResponse reasonResponse = aiRemoteClient.recommendReason(reasonRequest);
        Map<String, String> reasonByShopId = reasonResponse == null ? null : reasonResponse.getReasonByShopId();
        for (AiRecommendShopDTO shop : recommendShops) {
            String reason = reasonByShopId == null ? null : reasonByShopId.get(String.valueOf(shop.getId()));
            if (StrUtil.isBlank(reason)) {
                reason = buildLocalReason(query, shop);
            }
            shop.setReason(reason);
        }
    }

    private String buildLocalReason(String query, AiRecommendShopDTO shop) {
        StringBuilder reason = new StringBuilder();
        if (shop.getDistance() != null) {
            reason.append("距离").append(formatDistance(shop.getDistance())).append("，");
        }
        reason.append("评分").append(formatScore(shop.getScore())).append("，评论").append(safeInt(shop.getComments())).append("条");
        if (shop.getAvgPrice() != null) {
            reason.append("，人均约").append(shop.getAvgPrice()).append("元");
        }
        String highlight = readShopHighlight(shop.getId());
        if (StrUtil.isNotBlank(highlight)) {
            reason.append("。口碑高频提到：").append(highlight);
        }
        reason.append("。和你的需求“").append(query).append("”匹配度较高。");
        return reason.toString();
    }

    private String readShopHighlight(Long shopId) {
        String json = stringRedisTemplate.opsForValue().get(RedisConstants.AI_SHOP_SUMMARY_KEY + shopId);
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            AiShopSummaryDTO summaryDTO = JSONUtil.toBean(json, AiShopSummaryDTO.class);
            if (summaryDTO != null && CollUtil.isNotEmpty(summaryDTO.getHighFrequencyHighlights())) {
                return summaryDTO.getHighFrequencyHighlights().get(0);
            }
        } catch (Exception e) {
            log.debug("parse ai summary cache failed, shopId={}", shopId);
        }
        return null;
    }

    private List<String> aggregateHighFrequency(List<ChunkSummaryResponse> chunkSummaries) {
        Map<String, Integer> freq = new HashMap<>();
        Map<String, String> original = new HashMap<>();
        for (ChunkSummaryResponse chunkSummary : chunkSummaries) {
            for (String point : safeList(chunkSummary.getHighFrequencyPoints())) {
                String normalized = normalizeForDedup(point);
                if (StrUtil.isBlank(normalized)) {
                    continue;
                }
                freq.put(normalized, freq.getOrDefault(normalized, 0) + 1);
                original.putIfAbsent(normalized, point.trim());
            }
        }
        List<String> result = freq.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .map(entry -> original.get(entry.getKey()))
                .filter(StrUtil::isNotBlank)
                .limit(6)
                .collect(Collectors.toList());
        if (CollUtil.isEmpty(result)) {
            return Collections.singletonList("高频信息不足");
        }
        return result;
    }

    private List<String> aggregateUnique(List<ChunkSummaryResponse> chunkSummaries) {
        Map<String, Integer> freq = new HashMap<>();
        Map<String, String> original = new HashMap<>();
        for (ChunkSummaryResponse chunkSummary : chunkSummaries) {
            for (String point : safeList(chunkSummary.getUniquePoints())) {
                String normalized = normalizeForDedup(point);
                if (StrUtil.isBlank(normalized)) {
                    continue;
                }
                freq.put(normalized, freq.getOrDefault(normalized, 0) + 1);
                original.putIfAbsent(normalized, point.trim());
            }
        }
        List<String> uniqueOnly = freq.entrySet().stream()
                .filter(entry -> entry.getValue() == 1)
                .map(entry -> original.get(entry.getKey()))
                .filter(StrUtil::isNotBlank)
                .limit(6)
                .collect(Collectors.toList());
        if (CollUtil.isNotEmpty(uniqueOnly)) {
            return uniqueOnly;
        }
        return freq.entrySet().stream()
                .sorted((a, b) -> Integer.compare(a.getValue(), b.getValue()))
                .map(entry -> original.get(entry.getKey()))
                .filter(StrUtil::isNotBlank)
                .limit(3)
                .collect(Collectors.toList());
    }

    private List<List<Blog>> splitBlogs(List<Blog> blogs, int groupSize) {
        int size = Math.max(groupSize, 1);
        List<List<Blog>> groups = new ArrayList<>();
        for (int i = 0; i < blogs.size(); i += size) {
            groups.add(blogs.subList(i, Math.min(i + size, blogs.size())));
        }
        return groups;
    }

    private List<SentenceSample> collectSentenceSamples(List<Blog> blogs) {
        List<SentenceSample> samples = new ArrayList<>();
        for (Blog blog : blogs) {
            int weight = safeInt(blog.getLiked()) + 1;
            String combined = sanitizeText(blog.getTitle()) + "。 " + sanitizeText(blog.getContent());
            String[] parts = SPLIT_PATTERN.split(combined);
            for (String part : parts) {
                String text = StrUtil.trim(part);
                if (text == null || text.length() < 4 || text.length() > 60) {
                    continue;
                }
                String normalized = normalizeForDedup(text);
                if (StrUtil.isBlank(normalized) || normalized.length() < 4) {
                    continue;
                }
                SentenceSample sample = new SentenceSample();
                sample.originalText = text;
                sample.normalizedText = normalized;
                sample.weight = weight;
                samples.add(sample);
            }
        }
        return samples;
    }

    private List<String> extractKeywords(List<String> highFrequencyPoints, List<String> uniquePoints) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String point : safeList(highFrequencyPoints)) {
            set.addAll(extractQueryTokens(point));
        }
        for (String point : safeList(uniquePoints)) {
            set.addAll(extractQueryTokens(point));
        }
        return set.stream().limit(8).collect(Collectors.toList());
    }

    private List<String> mergeKeywords(IntentParseResponse intent, String query) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String keyword : safeList(intent.getIncludeKeywords())) {
            if (StrUtil.isNotBlank(keyword)) {
                set.add(keyword.trim());
            }
        }
        set.addAll(extractQueryTokens(query));
        return new ArrayList<>(set);
    }

    private List<String> extractQueryTokens(String text) {
        if (StrUtil.isBlank(text)) {
            return Collections.emptyList();
        }
        String normalized = text.replaceAll("[,，。！？!?.;；/\\\\]", " ");
        String[] arr = normalized.split("\\s+");
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : arr) {
            String t = StrUtil.trim(token);
            if (StrUtil.isBlank(t) || t.length() < 2) {
                continue;
            }
            tokens.add(t);
        }
        if (tokens.isEmpty() && text.length() >= 2) {
            tokens.add(text.trim());
        }
        return new ArrayList<>(tokens);
    }

    private String sanitizeText(String text) {
        if (text == null) {
            return "";
        }
        String result = text.replaceAll("<[^>]+>", " ");
        result = result.replace("&nbsp;", " ");
        result = result.replaceAll("\\s+", " ").trim();
        if (result.length() > 800) {
            result = result.substring(0, 800);
        }
        return result;
    }

    private String normalizeForDedup(String text) {
        if (StrUtil.isBlank(text)) {
            return "";
        }
        return text.replaceAll("[\\p{Punct}\\s，。！？；、:：]", "").toLowerCase(Locale.ROOT);
    }

    private String buildFingerprint(List<Blog> blogs) {
        String base = blogs.stream()
                .map(blog -> blog.getId() + ":" + (blog.getUpdateTime() == null ? "" : blog.getUpdateTime().toString()))
                .collect(Collectors.joining("|"));
        return SecureUtil.md5(base);
    }

    private String buildAssistantCacheId(AiAssistantRequestDTO requestDTO) {
        String raw = requestDTO.getQuery() + "|" + requestDTO.getX() + "|" + requestDTO.getY() + "|" + requestDTO.getCurrentTypeId();
        return SecureUtil.md5(raw);
    }

    private AiShopSummaryDTO readSummary(String summaryKey) {
        String cached = stringRedisTemplate.opsForValue().get(summaryKey);
        if (StrUtil.isBlank(cached)) {
            return null;
        }
        try {
            return JSONUtil.toBean(cached, AiShopSummaryDTO.class);
        } catch (Exception e) {
            log.warn("parse shop summary cache failed, key={}, err={}", summaryKey, e.getMessage());
            return null;
        }
    }

    private ChunkSummaryResponse readChunkSummary(String chunkKey) {
        String cached = stringRedisTemplate.opsForValue().get(chunkKey);
        if (StrUtil.isBlank(cached)) {
            return null;
        }
        try {
            return JSONUtil.toBean(cached, ChunkSummaryResponse.class);
        } catch (Exception e) {
            log.debug("parse chunk summary cache failed, key={}", chunkKey);
            return null;
        }
    }

    private AiAssistantResponseDTO readAssistantCache(String cacheKey) {
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StrUtil.isBlank(cached)) {
            return null;
        }
        try {
            return JSONUtil.toBean(cached, AiAssistantResponseDTO.class);
        } catch (Exception e) {
            log.warn("parse assistant cache failed, key={}, err={}", cacheKey, e.getMessage());
            return null;
        }
    }

    private boolean tryLock(String key, long seconds) {
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", seconds, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(success);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    private boolean isValidChunkSummary(ChunkSummaryResponse response) {
        if (response == null) {
            return false;
        }
        return StrUtil.isNotBlank(response.getSummary())
                || CollUtil.isNotEmpty(response.getHighFrequencyPoints())
                || CollUtil.isNotEmpty(response.getUniquePoints());
    }

    private boolean isValidFinalSummary(FinalSummaryResponse response) {
        return response != null && StrUtil.isNotBlank(response.getSummary());
    }

    private boolean isValidIntent(IntentParseResponse response) {
        return response != null && (StrUtil.isNotBlank(response.getIntentSummary()) || CollUtil.isNotEmpty(response.getTypeKeywords()));
    }

    private void normalizeChunkSummary(ChunkSummaryResponse response) {
        if (response == null) {
            return;
        }
        response.setSummary(StrUtil.blankToDefault(response.getSummary(), "本组口碑信息已整理。"));
        response.setHighFrequencyPoints(normalizePointList(response.getHighFrequencyPoints(), 4));
        response.setUniquePoints(normalizePointList(response.getUniquePoints(), 3));
        response.setKeywords(normalizePointList(response.getKeywords(), 8));
    }

    private List<String> normalizePointList(List<String> points, int maxSize) {
        if (CollUtil.isEmpty(points)) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String point : points) {
            if (StrUtil.isBlank(point)) {
                continue;
            }
            String p = point.trim();
            if (p.length() > 60) {
                p = p.substring(0, 60);
            }
            if (StrUtil.isNotBlank(p)) {
                set.add(p);
            }
            if (set.size() >= maxSize) {
                break;
            }
        }
        return new ArrayList<>(set);
    }

    private AiShopSummaryDTO emptySummary(Long shopId, String shopName) {
        AiShopSummaryDTO summaryDTO = new AiShopSummaryDTO();
        summaryDTO.setShopId(shopId);
        summaryDTO.setShopName(shopName);
        summaryDTO.setReviewCount(0);
        summaryDTO.setChunkCount(0);
        summaryDTO.setFinalSummary("该店铺暂无可用于总结的探店博客。");
        summaryDTO.setAdvice("建议后续积累更多探店内容后再查看AI总结。");
        summaryDTO.setHighFrequencyHighlights(Collections.emptyList());
        summaryDTO.setUniqueHighlights(Collections.emptyList());
        summaryDTO.setGeneratedAt(LocalDateTime.now().toString());
        summaryDTO.setFingerprint("EMPTY");
        summaryDTO.setFromCache(false);
        return summaryDTO;
    }

    private String joinPoints(List<String> points, String separator) {
        if (CollUtil.isEmpty(points)) {
            return "暂无";
        }
        return points.stream().filter(StrUtil::isNotBlank).collect(Collectors.joining(separator));
    }

    private List<String> safeList(List<String> list) {
        return list == null ? Collections.emptyList() : list;
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private String formatDistance(Double distance) {
        if (distance == null) {
            return "未知";
        }
        if (distance < 1000) {
            return String.format(Locale.ROOT, "%.0fm", distance);
        }
        return String.format(Locale.ROOT, "%.1fkm", distance / 1000.0D);
    }

    private double safeDistance(Double distance) {
        return distance == null ? 999999D : distance;
    }

    private String formatScore(Integer score) {
        if (score == null) {
            return "0.0";
        }
        return String.format(Locale.ROOT, "%.1f", score / 10.0D);
    }

    private static class CandidateShop {
        private Shop shop;
        private Double distance;
        private Double rankScore;
    }

    private static class SentenceSample {
        private String originalText;
        private String normalizedText;
        private int weight;
    }

}
