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
import com.hmdp.dto.ai.AiReviewRiskCheckRequestDTO;
import com.hmdp.dto.ai.AiReviewRiskCheckResponseDTO;
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
    private static final Pattern BLOG_TITLE_NOISE_PATTERN = Pattern.compile("(?i)^AI探店-\\d+-\\d+.*");
    private static final Pattern SUMMARY_NOISE_PATTERN = Pattern.compile("(?i)(AI探店-\\d+-\\d+|AI样本店-\\d+-\\d+)");
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)1\\d{10}(?!\\d)");
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");
    private static final int REVIEW_BLOCK_SCORE = 45;

    private static final Set<String> STRICT_BLOCK_RISK_TAGS = new HashSet<>(Arrays.asList(
            "违法违禁", "广告引流", "联系方式", "隐私泄露", "人身攻击"
    ));
    private static final Set<String> SUMMARY_STOP_PHRASES = new HashSet<>(Arrays.asList(
            "本次重点关注了环境、服务、出品稳定性",
            "整体体验比预期稳定",
            "服务节奏比较顺畅",
            "环境对聊天和休息很友好",
            "出品速度在高峰期也还可以",
            "二次复购意愿较高",
            "高频信息不足",
            "信息量不足，建议补充更多探店内容"
    ));
    private static final List<String> TOKEN_LEXICON = Arrays.asList(
            "火锅", "烧烤", "烤肉", "串串", "麻辣烫", "烤鱼", "羊肉", "牛肉", "海鲜",
            "水果", "果茶", "果汁", "甜品", "奶茶", "咖啡", "茶饮", "喝茶", "热水", "饮用水", "矿泉水",
            "休息", "空调", "座位", "安静", "聊天", "办公",
            "清淡", "少油", "少辣", "养胃", "粥", "汤", "轻食", "素食", "不吃肉", "不想吃肉",
            "感冒", "没胃口", "便宜", "平价", "高端", "约会",
            "亲子", "健身", "按摩", "SPA", "酒吧", "KTV", "轰趴", "美甲", "美睫", "美发"
    );

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
        if (CollUtil.isEmpty(allTypes)) {
            return Result.fail("店铺类型数据为空");
        }
        Map<Long, String> typeNameById = allTypes.stream()
                .filter(t -> t.getId() != null)
                .collect(Collectors.toMap(ShopType::getId, t -> StrUtil.blankToDefault(t.getName(), ""), (a, b) -> a, LinkedHashMap::new));

        IntentParseResponse intent = parseIntentWithFallback(requestDTO.getQuery(), allTypes);
        List<Long> typeIds = resolveTypeIds(intent, requestDTO.getCurrentTypeId(), allTypes, requestDTO.getQuery());
        List<CandidateShop> candidates = findCandidates(typeIds, requestDTO.getX(), requestDTO.getY());
        for (CandidateShop candidate : candidates) {
            candidate.typeName = StrUtil.blankToDefault(typeNameById.get(candidate.shop.getTypeId()), "");
        }

        List<String> includeKeywords = mergeKeywords(intent, requestDTO.getQuery());
        List<String> excludeKeywords = mergeExcludeKeywords(intent, requestDTO.getQuery());
        for (CandidateShop candidate : candidates) {
            candidate.rankScore = calcRankScore(candidate, includeKeywords, excludeKeywords, requestDTO.getQuery());
        }
        boolean strictNeedMatch = shouldStrictRequireMatch(requestDTO.getQuery(), includeKeywords);
        List<CandidateShop> filteredCandidates = candidates.stream()
                .filter(candidate -> !shouldFilterOutCandidate(candidate, strictNeedMatch))
                .collect(Collectors.toList());
        if (CollUtil.isEmpty(filteredCandidates)) {
            filteredCandidates = candidates.stream()
                    .filter(candidate -> candidate.excludeHitCount <= 0)
                    .collect(Collectors.toList());
        }
        if (CollUtil.isEmpty(filteredCandidates)) {
            filteredCandidates = candidates;
        }

        filteredCandidates.sort((a, b) -> {
            int scoreComp = Double.compare(b.rankScore, a.rankScore);
            if (scoreComp != 0) {
                return scoreComp;
            }
            int includeComp = Integer.compare(b.includeHitCount, a.includeHitCount);
            if (includeComp != 0) {
                return includeComp;
            }
            int excludeComp = Integer.compare(a.excludeHitCount, b.excludeHitCount);
            if (excludeComp != 0) {
                return excludeComp;
            }
            return Double.compare(safeDistance(a.distance), safeDistance(b.distance));
        });

        int topN = Math.min(aiProperties.getAssistantTopN(), filteredCandidates.size());
        List<CandidateShop> topCandidates = topN <= 0
                ? Collections.emptyList()
                : filteredCandidates.subList(0, topN);

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

    @Override
    public Result checkReviewRisk(AiReviewRiskCheckRequestDTO requestDTO) {
        if (requestDTO == null || StrUtil.isBlank(requestDTO.getContent())) {
            return Result.fail("content不能为空");
        }
        String cacheKey = RedisConstants.AI_REVIEW_RISK_CACHE_KEY + buildReviewRiskCacheId(requestDTO);
        AiReviewRiskCheckResponseDTO cached = readReviewRiskCache(cacheKey);
        if (cached != null) {
            cached.setFromCache(true);
            return Result.ok(cached);
        }

        ReviewRiskCheckRequest riskRequest = new ReviewRiskCheckRequest();
        riskRequest.setScene(StrUtil.blankToDefault(requestDTO.getScene(), "BLOG_NOTE"));
        riskRequest.setTitle(sanitizeText(requestDTO.getTitle()));
        riskRequest.setContent(sanitizeText(requestDTO.getContent()));

        if (requestDTO.getShopId() != null) {
            Shop shop = shopService.getById(requestDTO.getShopId());
            if (shop != null) {
                riskRequest.setShopName(shop.getName());
                riskRequest.setShopDesc(shop.getShopDesc());
            }
        }

        ReviewRiskCheckResponse remoteResp = aiRemoteClient.reviewRiskCheck(riskRequest);
        AiReviewRiskCheckResponseDTO response = toReviewRiskResponse(remoteResp);
        if (!isValidReviewRiskResponse(response)) {
            response = localReviewRiskFallback(requestDTO);
        }
        normalizeReviewRiskResponse(response);

        stringRedisTemplate.opsForValue().set(
                cacheKey,
                JSONUtil.toJsonStr(response),
                aiProperties.getReviewRiskTtlMinutes(),
                TimeUnit.MINUTES
        );
        return Result.ok(response);
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
        List<String> normalizedHigh = normalizePointList(finalSummary.getHighFrequencyPoints(), 6);
        List<String> normalizedUnique = normalizePointList(finalSummary.getUniquePoints(), 6);
        if (CollUtil.isEmpty(normalizedHigh)) {
            normalizedHigh = normalizePointList(highFrequency, 6);
        }
        if (CollUtil.isEmpty(normalizedUnique)) {
            normalizedUnique = normalizePointList(uniqueHighlights, 6);
        }
        String finalSummaryText = sanitizeSummaryText(finalSummary.getSummary(), shop.getName(), normalizedHigh, normalizedUnique);
        String adviceText = StrUtil.blankToDefault(finalSummary.getAdvice(), "建议优先参考高频口碑，再结合距离、评分、人均消费综合判断。");

        AiShopSummaryDTO summaryDTO = new AiShopSummaryDTO();
        summaryDTO.setShopId(shop.getId());
        summaryDTO.setShopName(shop.getName());
        summaryDTO.setReviewCount(blogs.size());
        summaryDTO.setChunkCount(groupedBlogs.size());
        summaryDTO.setFinalSummary(finalSummaryText);
        summaryDTO.setAdvice(adviceText);
        summaryDTO.setHighFrequencyHighlights(normalizedHigh);
        summaryDTO.setUniqueHighlights(normalizedUnique);
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
            snippet.setTitle(sanitizeBlogTitleForSummary(blog.getTitle()));
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
        List<String> typeKeywords = inferTypeKeywords(query);
        List<String> includeKeywords = mergeDistinct(extractQueryTokens(query), inferIncludeKeywords(query));
        List<String> excludeKeywords = inferExcludeKeywords(query);

        IntentParseResponse response = new IntentParseResponse();
        response.setIntentSummary("基于用户需求筛选附近更匹配的店铺");
        response.setTypeKeywords(typeKeywords);
        response.setIncludeKeywords(includeKeywords);
        response.setExcludeKeywords(excludeKeywords);
        return response;
    }

    private List<Long> resolveTypeIds(IntentParseResponse intent, Long currentTypeId, List<ShopType> allTypes, String query) {
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        List<String> typeKeywords = new ArrayList<>();
        typeKeywords.addAll(safeList(intent.getTypeKeywords()));
        typeKeywords.addAll(inferTypeKeywords(query));
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
        if (result.isEmpty() && currentTypeId != null && currentTypeId > 0 && !hasCrossTypeIntent(query)) {
            result.add(currentTypeId);
        }
        if (result.isEmpty()) {
            for (ShopType type : allTypes) {
                if (type.getId() != null) {
                    result.add(type.getId());
                }
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

    private double calcRankScore(CandidateShop candidate, List<String> includeKeywords, List<String> excludeKeywords, String query) {
        Shop shop = candidate.shop;
        double score = 0D;
        score += safeInt(shop.getScore()) / 10.0D * 2.0D;
        score += Math.log(safeInt(shop.getComments()) + 1) * 0.8D;
        score += Math.log(safeInt(shop.getSold()) + 1) * 0.3D;
        if (candidate.distance != null) {
            score += Math.max(0D, (aiProperties.getAssistantRadiusMeters() - candidate.distance) / 1000D);
        }

        String searchable = buildCandidateSearchable(candidate);
        int includeHit = countKeywordHits(searchable, includeKeywords);
        int excludeHit = countKeywordHits(searchable, excludeKeywords);
        candidate.includeHitCount = includeHit;
        candidate.excludeHitCount = excludeHit;
        score += includeHit * 2.4D;
        score -= excludeHit * 4.5D;

        if (isNoMeatIntent(query) && containsAnyKeyword(searchable, Arrays.asList("火锅", "烧烤", "烤肉", "牛", "羊", "串"))) {
            score -= 12D;
        }
        if (containsAnyKeyword(query, Arrays.asList("水果", "果茶", "买水", "喝茶")) && containsAnyKeyword(searchable, Arrays.asList("茶", "水果", "果", "饮水", "热水", "休息"))) {
            score += 1.5D;
        }
        if (containsAnyKeyword(query, Arrays.asList("清淡", "感冒", "没胃口", "养胃", "粥", "汤"))
                && containsAnyKeyword(searchable, Arrays.asList("清淡", "轻食", "粥", "汤", "热水"))) {
            score += 1.8D;
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
        score += queryDiversityBoost(query, shop.getId());
        return score;
    }

    private String buildCandidateSearchable(CandidateShop candidate) {
        return joinSearchable(candidate.shop.getName(), candidate.shop.getAddress(), candidate.shop.getShopDesc(), candidate.typeName);
    }

    private int countKeywordHits(String searchable, List<String> keywords) {
        if (StrUtil.isBlank(searchable) || CollUtil.isEmpty(keywords)) {
            return 0;
        }
        int hit = 0;
        for (String keyword : keywords) {
            if (StrUtil.isBlank(keyword)) {
                continue;
            }
            String k = keyword.trim();
            if (k.length() < 2 && !Arrays.asList("肉", "辣", "酸", "甜", "茶", "水").contains(k)) {
                continue;
            }
            if (StrUtil.containsIgnoreCase(searchable, k)) {
                hit++;
            }
        }
        return hit;
    }

    private boolean shouldStrictRequireMatch(String query, List<String> includeKeywords) {
        if (CollUtil.isEmpty(includeKeywords)) {
            return false;
        }
        String lower = StrUtil.blankToDefault(query, "").toLowerCase(Locale.ROOT);
        return containsAny(lower, "想吃", "想喝", "不想", "不要", "不吃", "感冒", "没胃口", "清淡", "水果", "喝茶", "买水", "休息");
    }

    private boolean shouldFilterOutCandidate(CandidateShop candidate, boolean strictNeedMatch) {
        if (candidate == null) {
            return true;
        }
        if (candidate.excludeHitCount > 0) {
            return true;
        }
        return strictNeedMatch && candidate.includeHitCount <= 0;
    }

    private boolean hasCrossTypeIntent(String query) {
        String lower = StrUtil.blankToDefault(query, "").toLowerCase(Locale.ROOT);
        return containsAny(lower, "水果", "喝茶", "买水", "休息", "不想吃肉", "清淡", "感冒", "没胃口");
    }

    private List<String> inferTypeKeywords(String query) {
        String lower = StrUtil.blankToDefault(query, "").toLowerCase(Locale.ROOT);
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        if (containsAny(lower, "火锅", "烧烤", "烤肉", "清淡", "水果", "奶茶", "咖啡", "粥", "汤", "寿司", "日料", "海鲜")) {
            keywords.add("美食");
        }
        if (containsAny(lower, "ktv", "唱歌")) {
            keywords.add("KTV");
        }
        if (containsAny(lower, "美发", "剪发", "发型")) {
            keywords.add("美发");
        }
        if (containsAny(lower, "美甲", "美睫")) {
            keywords.add("美甲");
            keywords.add("美睫");
        }
        if (containsAny(lower, "按摩", "足疗")) {
            keywords.add("按摩");
            keywords.add("足疗");
        }
        if (containsAny(lower, "spa", "美容")) {
            keywords.add("SPA");
            keywords.add("美容");
        }
        if (containsAny(lower, "亲子", "遛娃")) {
            keywords.add("亲子");
        }
        if (containsAny(lower, "酒吧", "喝酒", "微醺")) {
            keywords.add("酒吧");
        }
        if (containsAny(lower, "轰趴", "聚会包场")) {
            keywords.add("轰趴");
        }
        if (containsAny(lower, "健身", "训练")) {
            keywords.add("健身");
        }
        return new ArrayList<>(keywords);
    }

    private List<String> inferIncludeKeywords(String query) {
        String lower = StrUtil.blankToDefault(query, "").toLowerCase(Locale.ROOT);
        LinkedHashSet<String> include = new LinkedHashSet<>();
        if (containsAny(lower, "水果", "果茶", "果汁")) {
            include.addAll(Arrays.asList("水果", "果汁", "果茶", "甜品", "轻食"));
        }
        if (containsAny(lower, "喝茶", "茶饮", "休息")) {
            include.addAll(Arrays.asList("茶", "茶饮", "休息", "座位", "空调"));
        }
        if (containsAny(lower, "买水", "买瓶水", "饮用水")) {
            include.addAll(Arrays.asList("饮用水", "热水", "补给", "便利"));
        }
        if (containsAny(lower, "感冒", "没胃口", "清淡", "养胃")) {
            include.addAll(Arrays.asList("清淡", "粥", "汤", "轻食", "热水"));
        }
        if (containsAny(lower, "不想吃肉", "不吃肉", "素食", "少肉")) {
            include.addAll(Arrays.asList("素食", "清淡", "轻食", "蔬菜"));
        }
        return new ArrayList<>(include);
    }

    private List<String> inferExcludeKeywords(String query) {
        String lower = StrUtil.blankToDefault(query, "").toLowerCase(Locale.ROOT);
        LinkedHashSet<String> exclude = new LinkedHashSet<>();
        if (containsAny(lower, "不想吃肉", "不吃肉", "少肉", "素食")) {
            exclude.addAll(Arrays.asList("火锅", "烧烤", "烤肉", "羊肉", "牛肉", "肉", "串"));
        }
        if (containsAny(lower, "清淡", "感冒", "没胃口")) {
            exclude.addAll(Arrays.asList("火锅", "烧烤", "重辣", "重油"));
        }
        if (containsAny(lower, "不喝酒", "戒酒", "不想喝酒")) {
            exclude.addAll(Arrays.asList("酒吧", "酒精"));
        }
        return new ArrayList<>(exclude);
    }

    private List<String> mergeExcludeKeywords(IntentParseResponse intent, String query) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String keyword : safeList(intent.getExcludeKeywords())) {
            if (StrUtil.isNotBlank(keyword)) {
                set.add(keyword.trim());
            }
        }
        set.addAll(inferExcludeKeywords(query));
        return new ArrayList<>(set);
    }

    private List<String> mergeDistinct(List<String> first, List<String> second) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String keyword : safeList(first)) {
            if (StrUtil.isNotBlank(keyword)) {
                set.add(keyword.trim());
            }
        }
        for (String keyword : safeList(second)) {
            if (StrUtil.isNotBlank(keyword)) {
                set.add(keyword.trim());
            }
        }
        return new ArrayList<>(set);
    }

    private boolean containsAnyKeyword(String text, Collection<String> keywords) {
        if (StrUtil.isBlank(text) || CollUtil.isEmpty(keywords)) {
            return false;
        }
        for (String keyword : keywords) {
            if (StrUtil.isBlank(keyword)) {
                continue;
            }
            if (StrUtil.containsIgnoreCase(text, keyword.trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean isNoMeatIntent(String query) {
        String lower = StrUtil.blankToDefault(query, "").toLowerCase(Locale.ROOT);
        return containsAny(lower, "不想吃肉", "不吃肉", "少肉", "素食");
    }

    private double queryDiversityBoost(String query, Long shopId) {
        if (shopId == null) {
            return 0D;
        }
        String hash = SecureUtil.md5(StrUtil.blankToDefault(query, "") + "|" + shopId);
        int bucket = Integer.parseInt(hash.substring(0, 2), 16);
        return bucket / 255.0D * 0.12D;
    }

    private AiRecommendShopDTO toRecommendShopDTO(CandidateShop candidate) {
        Shop shop = candidate.shop;
        AiRecommendShopDTO dto = new AiRecommendShopDTO();
        dto.setId(shop.getId());
        dto.setTypeId(shop.getTypeId());
        dto.setName(shop.getName());
        dto.setAddress(shop.getAddress());
        dto.setShopDesc(shop.getShopDesc());
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
            reasonShop.setShopDesc(shop.getShopDesc());
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
        String descHighlight = extractShopDescHighlight(shop.getShopDesc(), query);
        if (StrUtil.isNotBlank(descHighlight)) {
            reason.append("。店铺信息提到：").append(descHighlight);
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
            String combined = sanitizeText(blog.getContent());
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

    private String joinSearchable(String... parts) {
        return Arrays.stream(parts)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.joining(" "));
    }

    private List<String> extractQueryTokens(String text) {
        if (StrUtil.isBlank(text)) {
            return Collections.emptyList();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String lexicon : TOKEN_LEXICON) {
            if (lower.contains(lexicon.toLowerCase(Locale.ROOT))) {
                tokens.add(lexicon);
            }
        }
        String normalized = text.replaceAll("[,，。！？!?.;；/\\\\]", " ");
        String[] arr = normalized.split("\\s+");
        for (String token : arr) {
            String t = StrUtil.trim(token);
            if (StrUtil.isBlank(t) || t.length() < 2) {
                continue;
            }
            tokens.add(t);
        }
        if (containsAny(lower, "不想", "不要", "不吃", "别")) {
            tokens.addAll(inferExcludeKeywords(text));
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
        String raw = "v2|" + requestDTO.getQuery() + "|" + requestDTO.getX() + "|" + requestDTO.getY() + "|" + requestDTO.getCurrentTypeId();
        return SecureUtil.md5(raw);
    }

    private String buildReviewRiskCacheId(AiReviewRiskCheckRequestDTO requestDTO) {
        String raw = "v2|" + StrUtil.blankToDefault(requestDTO.getScene(), "BLOG_NOTE")
                + "|" + requestDTO.getShopId()
                + "|" + StrUtil.blankToDefault(requestDTO.getTitle(), "")
                + "|" + requestDTO.getContent();
        return SecureUtil.md5(raw);
    }

    private AiShopSummaryDTO readSummary(String summaryKey) {
        String cached = stringRedisTemplate.opsForValue().get(summaryKey);
        if (StrUtil.isBlank(cached)) {
            return null;
        }
        try {
            AiShopSummaryDTO summaryDTO = JSONUtil.toBean(cached, AiShopSummaryDTO.class);
            normalizeCachedSummary(summaryDTO);
            return summaryDTO;
        } catch (Exception e) {
            log.warn("parse shop summary cache failed, key={}, err={}", summaryKey, e.getMessage());
            return null;
        }
    }

    private void normalizeCachedSummary(AiShopSummaryDTO summaryDTO) {
        if (summaryDTO == null) {
            return;
        }
        List<String> high = normalizePointList(summaryDTO.getHighFrequencyHighlights(), 6);
        List<String> unique = normalizePointList(summaryDTO.getUniqueHighlights(), 6);
        summaryDTO.setHighFrequencyHighlights(high);
        summaryDTO.setUniqueHighlights(unique);
        summaryDTO.setFinalSummary(sanitizeSummaryText(
                summaryDTO.getFinalSummary(),
                StrUtil.blankToDefault(summaryDTO.getShopName(), "该店铺"),
                high,
                unique
        ));
        summaryDTO.setAdvice(StrUtil.blankToDefault(summaryDTO.getAdvice(), "建议优先参考高频口碑，再结合距离、评分、人均消费综合判断。"));
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

    private AiReviewRiskCheckResponseDTO readReviewRiskCache(String cacheKey) {
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StrUtil.isBlank(cached)) {
            return null;
        }
        try {
            return JSONUtil.toBean(cached, AiReviewRiskCheckResponseDTO.class);
        } catch (Exception e) {
            log.warn("parse review risk cache failed, key={}, err={}", cacheKey, e.getMessage());
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

    private boolean isValidReviewRiskResponse(AiReviewRiskCheckResponseDTO response) {
        return response != null
                && StrUtil.isNotBlank(response.getRiskLevel())
                && response.getRiskScore() != null;
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
            if (isMeaningfulSummaryPoint(p)) {
                set.add(p);
            }
            if (set.size() >= maxSize) {
                break;
            }
        }
        return new ArrayList<>(set);
    }

    private boolean isMeaningfulSummaryPoint(String point) {
        if (StrUtil.isBlank(point)) {
            return false;
        }
        String p = sanitizeText(point);
        if (p.length() < 4) {
            return false;
        }
        if (SUMMARY_NOISE_PATTERN.matcher(p).find()) {
            return false;
        }
        if (p.matches("^[\\d\\-_/\\s]+$")) {
            return false;
        }
        for (String stop : SUMMARY_STOP_PHRASES) {
            if (StrUtil.containsIgnoreCase(p, stop)) {
                return false;
            }
        }
        return true;
    }

    private String sanitizeSummaryText(String summary, String shopName, List<String> highFrequency, List<String> uniqueHighlights) {
        String normalized = sanitizeText(summary);
        if (StrUtil.isBlank(normalized) || SUMMARY_NOISE_PATTERN.matcher(normalized).find()) {
            String merged = shopName + "整体口碑集中在：" + joinPoints(highFrequency, "；");
            if (CollUtil.isNotEmpty(uniqueHighlights)) {
                merged = merged + "。小众亮点包括：" + joinPoints(uniqueHighlights, "；");
            }
            return merged;
        }
        return normalized;
    }

    private String sanitizeBlogTitleForSummary(String title) {
        String t = sanitizeText(title);
        if (StrUtil.isBlank(t)) {
            return "";
        }
        if (BLOG_TITLE_NOISE_PATTERN.matcher(t).matches()) {
            return "";
        }
        return t;
    }

    private AiReviewRiskCheckResponseDTO toReviewRiskResponse(ReviewRiskCheckResponse remoteResp) {
        if (remoteResp == null) {
            return null;
        }
        AiReviewRiskCheckResponseDTO dto = new AiReviewRiskCheckResponseDTO();
        dto.setPass(remoteResp.getPass());
        dto.setRiskLevel(remoteResp.getRiskLevel());
        dto.setRiskScore(remoteResp.getRiskScore());
        dto.setRiskTags(remoteResp.getRiskTags());
        dto.setReasons(remoteResp.getReasons());
        dto.setSuggestion(remoteResp.getSuggestion());
        dto.setFromCache(false);
        dto.setGeneratedAt(LocalDateTime.now().toString());
        return dto;
    }

    private AiReviewRiskCheckResponseDTO localReviewRiskFallback(AiReviewRiskCheckRequestDTO requestDTO) {
        String text = sanitizeText(StrUtil.blankToDefault(requestDTO.getTitle(), "") + " " + requestDTO.getContent());
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> tags = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        int score = 5;

        if (containsAny(text, "赌博", "毒品", "枪支", "办证", "套现", "发票")) {
            score += 80;
            tags.add("违法违禁");
            reasons.add("内容疑似包含违法违禁信息。");
        }
        if (containsAny(lower, "http://", "https://", "www.", "加微", "微信", "vx", "qq", "私聊", "引流", "推广", "代理")) {
            score += 65;
            tags.add("广告引流");
            reasons.add("内容疑似包含广告引流信息。");
        }
        if ((PHONE_PATTERN.matcher(text).find() || text.matches(".*\\d{6,}.*"))
                && containsAny(lower, "联系", "咨询", "加", "微信", "vx", "qq", "电话")) {
            score += 55;
            tags.add("联系方式");
            reasons.add("内容疑似包含联系方式导流。");
        }
        if (ID_CARD_PATTERN.matcher(text).find()
                || containsAny(lower, "身份证", "住址", "详细地址", "真实姓名", "我叫", "手机号", "电话号")) {
            score += 60;
            tags.add("隐私泄露");
            reasons.add("内容疑似泄露姓名、电话、地址或证件信息。");
        }
        if (containsAny(lower, "傻逼", "滚", "去死", "脑残", "废物", "骗子")) {
            score += 40;
            tags.add("人身攻击");
            reasons.add("内容疑似包含辱骂或攻击性表达。");
        }
        if (containsAny(text, "最便宜", "稳赚不赔", "包过", "百分百", "绝对有效")) {
            score += 20;
            tags.add("夸大营销");
            reasons.add("内容存在夸大营销表达。");
        }
        if (containsAny(text, "！！！", "???", "￥￥￥")) {
            score += 10;
            tags.add("刷屏噪声");
            reasons.add("内容疑似存在刷屏式噪声表达。");
        }

        AiReviewRiskCheckResponseDTO dto = new AiReviewRiskCheckResponseDTO();
        dto.setRiskScore(clampRiskScore(score));
        if (dto.getRiskScore() >= REVIEW_BLOCK_SCORE || hasStrictBlockTag(tags)) {
            dto.setPass(false);
            dto.setRiskLevel("BLOCK");
            dto.setSuggestion("内容触发高风险，请删除广告、联系方式、隐私泄露或违法违规描述后再发布。");
        } else if (dto.getRiskScore() >= 30) {
            dto.setPass(false);
            dto.setRiskLevel("REVIEW");
            dto.setSuggestion("内容存在中风险，请先按建议修改后再发布。");
        } else {
            dto.setPass(true);
            dto.setRiskLevel("SAFE");
            dto.setSuggestion("内容质检通过，可正常发布。");
        }
        if (tags.isEmpty()) {
            tags.add("正常内容");
        }
        if (reasons.isEmpty()) {
            reasons.add("未发现明显违规特征。");
        }
        dto.setRiskTags(tags.stream().distinct().limit(4).collect(Collectors.toList()));
        dto.setReasons(reasons.stream().distinct().limit(4).collect(Collectors.toList()));
        dto.setFromCache(false);
        dto.setGeneratedAt(LocalDateTime.now().toString());
        return dto;
    }

    private void normalizeReviewRiskResponse(AiReviewRiskCheckResponseDTO response) {
        if (response == null) {
            return;
        }
        String level = StrUtil.blankToDefault(response.getRiskLevel(), "REVIEW").toUpperCase(Locale.ROOT);
        if (!Arrays.asList("SAFE", "REVIEW", "BLOCK").contains(level)) {
            level = "REVIEW";
        }
        response.setRiskLevel(level);
        response.setRiskScore(clampRiskScore(response.getRiskScore()));
        response.setRiskTags(normalizePointList(response.getRiskTags(), 4));
        if (CollUtil.isEmpty(response.getRiskTags())) {
            response.setRiskTags(Collections.singletonList("正常内容"));
        }
        response.setReasons(normalizePointList(response.getReasons(), 4));
        if (CollUtil.isEmpty(response.getReasons())) {
            response.setReasons(Collections.singletonList("未发现明显违规特征。"));
        }
        response.setSuggestion(StrUtil.blankToDefault(response.getSuggestion(), "建议根据风险提示调整文本后再发布。"));
        if (response.getFromCache() == null) {
            response.setFromCache(false);
        }
        if (StrUtil.isBlank(response.getGeneratedAt())) {
            response.setGeneratedAt(LocalDateTime.now().toString());
        }
        boolean forceBlock = "BLOCK".equals(level)
                || response.getRiskScore() >= REVIEW_BLOCK_SCORE
                || hasStrictBlockTag(response.getRiskTags());
        if (forceBlock) {
            response.setRiskLevel("BLOCK");
            response.setPass(false);
            response.setSuggestion("内容触发高风险，请删除广告、联系方式、隐私泄露或违法违规描述后再发布。");
            return;
        }
        if ("REVIEW".equals(level)) {
            response.setPass(false);
        } else if (response.getPass() == null) {
            response.setPass(true);
        }
    }

    private boolean hasStrictBlockTag(Collection<String> tags) {
        if (CollUtil.isEmpty(tags)) {
            return false;
        }
        for (String tag : tags) {
            if (StrUtil.isBlank(tag)) {
                continue;
            }
            if (STRICT_BLOCK_RISK_TAGS.contains(tag.trim())) {
                return true;
            }
        }
        return false;
    }

    private int clampRiskScore(Integer score) {
        int val = score == null ? 0 : score;
        if (val < 0) {
            return 0;
        }
        return Math.min(val, 100);
    }

    private boolean containsAny(String text, String... keywords) {
        if (StrUtil.isBlank(text)) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String extractShopDescHighlight(String shopDesc, String query) {
        if (StrUtil.isBlank(shopDesc)) {
            return null;
        }
        List<String> tokens = extractQueryTokens(query);
        String cleanDesc = sanitizeText(shopDesc);
        for (String token : tokens) {
            if (StrUtil.containsIgnoreCase(cleanDesc, token)) {
                return trimHighlight(cleanDesc, token, 50);
            }
        }
        return cleanDesc.length() > 40 ? cleanDesc.substring(0, 40) + "..." : cleanDesc;
    }

    private String trimHighlight(String text, String keyword, int maxLen) {
        if (StrUtil.isBlank(text)) {
            return "";
        }
        int idx = text.toLowerCase(Locale.ROOT).indexOf(keyword.toLowerCase(Locale.ROOT));
        if (idx < 0 || text.length() <= maxLen) {
            return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
        }
        int start = Math.max(0, idx - 12);
        int end = Math.min(text.length(), start + maxLen);
        String sub = text.substring(start, end);
        if (start > 0) {
            sub = "..." + sub;
        }
        if (end < text.length()) {
            sub = sub + "...";
        }
        return sub;
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
        private String typeName;
        private int includeHitCount;
        private int excludeHitCount;
    }

    private static class SentenceSample {
        private String originalText;
        private String normalizedText;
        private int weight;
    }

}
