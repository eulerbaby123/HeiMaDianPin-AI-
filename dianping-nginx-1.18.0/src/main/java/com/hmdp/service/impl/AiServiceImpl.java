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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AiServiceImpl implements IAiService {

    private static final ExecutorService WARMUP_EXECUTOR = Executors.newFixedThreadPool(4);
    private static final Pattern SPLIT_PATTERN = Pattern.compile("[,，。！？!?.;；\\n\\r]");
    private static final Pattern BLOG_TITLE_NOISE_PATTERN = Pattern.compile("(?i)^AI探店-\\d+-\\d+.*");
    private static final Pattern SUMMARY_NOISE_PATTERN = Pattern.compile("(?i)(AI探店-\\d+-\\d+|AI样本店-\\d+-\\d+)");
    private static final Pattern STORE_MAIN_BIZ_NOISE_PATTERN = Pattern.compile("(?:\\u8fd9\\u5bb6|\\u8be5\\u5e97|\\u672c\\u5e97)?\\s*\\u95e8\\u5e97\\s*\\u4e3b\\u6253\\s*[^\\u3002\\uff0c,;\\uFF1B\\n]{0,40}?\\u5e97");
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)1\\d{10}(?!\\d)");
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");
    private static final Pattern SPACED_PHONE_PATTERN = Pattern.compile("1\\s*[3-9](?:\\s*\\d){9}");
    private static final Pattern SPACED_WECHAT_PATTERN = Pattern.compile("(?i)(v\\s*x|w\\s*x|v\\s*\\u4fe1|\\u5fae\\s*\\u4fe1)");
    private static final Pattern BUDGET_NUMBER_PATTERN = Pattern.compile("(?<!\\d)(\\d{2,4})(?!\\d)");
    private static final Pattern DIST_KM_PATTERN = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*(km|\\u516c\\u91cc)");
    private static final Pattern DIST_METER_PATTERN = Pattern.compile("(?<!\\d)(\\d{2,4})\\s*(m|\\u7c73)(?!\\d)");
    private static final int REVIEW_BLOCK_SCORE = 45;
    private static final String ENGINE_LLM = "LLM";
    private static final String ENGINE_FALLBACK = "FALLBACK";
    private static final List<String> BBQ_KEYWORDS = Arrays.asList("\u70e7\u70e4", "\u70e4\u4e32", "\u70e4\u8089", "\u4e32\u4e32", "\u70e4\u9c7c", "bbq");
    private static final List<String> MEAT_HEAVY_KEYWORDS = Arrays.asList("\u706b\u9505", "\u6dae", "\u725b\u8089", "\u7f8a\u8089", "\u732a\u8089", "\u80a5\u725b", "\u8089\u7c7b", "\u70e4\u8089", "\u70e4\u4e32");
    private static final List<String> LIGHT_FOOD_KEYWORDS = Arrays.asList("\u6e05\u6de1", "\u7d20", "\u7ca5", "\u6c64", "\u8f7b\u98df", "\u517b\u80c3", "\u5c11\u6cb9");
    private static final List<String> DRINK_REST_KEYWORDS = Arrays.asList("\u8336", "\u5496\u5561", "\u5976\u8336", "\u996e", "\u70ed\u6c34", "\u4f11\u606f", "\u5ea7\u4f4d", "\u7a7a\u8c03");
    private static final List<String> WATER_KEYWORDS = Arrays.asList("\u6c34", "\u74f6\u6c34", "\u996e\u7528\u6c34", "\u77ff\u6cc9\u6c34", "\u70ed\u6c34");
    private static final List<String> SPICY_KEYWORDS = Arrays.asList("\u8fa3", "\u9ebb\u8fa3", "\u91cd\u8fa3", "\u706b\u8fa3");

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
            candidate.rankScore = calcRankScoreEnhanced(candidate, includeKeywords, excludeKeywords, requestDTO.getQuery());
        }
        boolean strictNeedMatch = shouldStrictRequireMatch(requestDTO.getQuery(), includeKeywords);
        List<CandidateShop> filteredCandidates = candidates.stream()
                .filter(candidate -> !shouldFilterOutCandidate(candidate, strictNeedMatch, requestDTO.getQuery()))
                .collect(Collectors.toList());
        if (CollUtil.isEmpty(filteredCandidates)) {
            filteredCandidates = candidates.stream()
                    .filter(candidate -> candidate.excludeHitCount <= 0)
                    .collect(Collectors.toList());
        }
        filteredCandidates = applyQueryAwareConstraints(filteredCandidates, candidates, requestDTO.getQuery());
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
        Map<String, String> rerankReasonByShopId = new LinkedHashMap<>();
        List<CandidateShop> rerankedCandidates = rerankCandidatesWithAi(
                requestDTO.getQuery(), includeKeywords, excludeKeywords, filteredCandidates, topN, rerankReasonByShopId
        );
        List<CandidateShop> topCandidates = pickDiverseTopCandidates(rerankedCandidates, topN);

        List<AiRecommendShopDTO> recommendShops = topCandidates.stream()
                .map(this::toRecommendShopDTO)
                .collect(Collectors.toList());
        fillReasonWithAiOrFallback(requestDTO.getQuery(), recommendShops, rerankReasonByShopId);

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
        AiReviewRiskCheckResponseDTO localFallback = localReviewRiskFallback(requestDTO);
        if (!isValidReviewRiskResponse(response)) {
            response = localFallback;
        } else {
            response = mergeRiskResponse(response, localFallback);
        }
        normalizeReviewRiskResponse(response);
        response.setEngine(StrUtil.blankToDefault(response.getEngine(), ENGINE_FALLBACK));

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
        summaryDTO.setEngine(StrUtil.blankToDefault(finalSummary.getEngine(), ENGINE_FALLBACK));
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
            response.setEngine(ENGINE_FALLBACK);
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
        response.setEngine(ENGINE_FALLBACK);
        response.setSummary("本组高频信息：" + joinPoints(highFrequencyPoints, "；"));
        response.setHighFrequencyPoints(highFrequencyPoints);
        response.setUniquePoints(uniquePoints);
        response.setKeywords(extractKeywords(highFrequencyPoints, uniquePoints));
        return response;
    }

    private FinalSummaryResponse localFinalSummary(String shopName, List<String> highFrequency, List<String> uniqueHighlights) {
        FinalSummaryResponse response = new FinalSummaryResponse();
        response.setEngine(ENGINE_FALLBACK);
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
        IntentParseResponse local = localIntentParse(query);
        if (!isValidIntent(intent)) {
            return local;
        }
        intent.setIntentSummary(StrUtil.blankToDefault(intent.getIntentSummary(), local.getIntentSummary()));
        intent.setTypeKeywords(mergeDistinct(intent.getTypeKeywords(), local.getTypeKeywords()));
        intent.setIncludeKeywords(mergeDistinct(intent.getIncludeKeywords(), local.getIncludeKeywords()));
        intent.setExcludeKeywords(mergeDistinct(intent.getExcludeKeywords(), local.getExcludeKeywords()));
        return intent;
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

    private double calcRankScoreEnhanced(CandidateShop candidate, List<String> includeKeywords, List<String> excludeKeywords, String query) {
        if (candidate == null || candidate.shop == null) {
            return -9999D;
        }
        Shop shop = candidate.shop;
        String searchable = buildCandidateSearchable(candidate);
        String lowerQuery = StrUtil.blankToDefault(query, "").toLowerCase(Locale.ROOT);

        double score = 0D;
        score += safeInt(shop.getScore()) / 10.0D * 2.4D;
        score += Math.log(safeInt(shop.getComments()) + 1) * 0.9D;
        score += Math.log(safeInt(shop.getSold()) + 1) * 0.35D;
        if (candidate.distance != null) {
            score += Math.max(0D, (aiProperties.getAssistantRadiusMeters() - candidate.distance) / 900D);
        }

        int includeHit = countKeywordHits(searchable, includeKeywords);
        int excludeHit = countKeywordHits(searchable, excludeKeywords);
        candidate.includeHitCount = includeHit;
        candidate.excludeHitCount = excludeHit;
        score += includeHit * 2.6D;
        score -= excludeHit * 6.0D;

        boolean wantsBbq = queryWantsBarbecue(query);
        boolean wantsDrinkOrRest = queryWantsDrinkOrRest(query);
        boolean wantsWater = queryWantsWater(query);
        boolean wantsLightFood = queryWantsLightFood(query);
        boolean avoidSpicy = queryAvoidSpicy(query);
        boolean noMeat = isNoMeatIntent(query) || wantsLightFood;

        if (wantsBbq) {
            score += containsAnyKeyword(searchable, BBQ_KEYWORDS) ? 4.0D : -5.0D;
        }
        if (noMeat && containsAnyKeyword(searchable, MEAT_HEAVY_KEYWORDS)) {
            score -= 11.0D;
        }
        if (wantsLightFood && containsAnyKeyword(searchable, LIGHT_FOOD_KEYWORDS)) {
            score += 2.5D;
        }
        if (wantsDrinkOrRest) {
            score += containsAnyKeyword(searchable, DRINK_REST_KEYWORDS) ? 3.2D : -2.2D;
        }
        if (wantsWater) {
            score += containsAnyKeyword(searchable, WATER_KEYWORDS) ? 2.0D : -1.4D;
        }
        if (avoidSpicy && containsAnyKeyword(searchable, SPICY_KEYWORDS)) {
            score -= 3.5D;
        }

        if (containsAny(lowerQuery, "\u4fbf\u5b9c", "\u5e73\u4ef7", "\u4e0d\u8d35", "\u6027\u4ef7\u6bd4")) {
            if (shop.getAvgPrice() != null && shop.getAvgPrice() <= 80) {
                score += 2.2D;
            } else if (shop.getAvgPrice() != null && shop.getAvgPrice() >= 140) {
                score -= 1.8D;
            }
        }
        Integer budgetUpperBound = parseBudgetUpperBound(lowerQuery);
        if (budgetUpperBound != null && shop.getAvgPrice() != null) {
            if (shop.getAvgPrice() <= budgetUpperBound) {
                score += 2.2D;
            } else {
                score -= Math.min(4.0D, (shop.getAvgPrice() - budgetUpperBound) / 18.0D);
            }
        }
        Integer distanceUpperBoundMeters = parseDistanceUpperBoundMeters(lowerQuery);
        if (distanceUpperBoundMeters != null && candidate.distance != null && candidate.distance > distanceUpperBoundMeters) {
            score -= Math.min(5.0D, (candidate.distance - distanceUpperBoundMeters) / 600.0D);
        }
        if (candidate.distance != null && containsAny(lowerQuery, "\u9644\u8fd1", "\u5c31\u8fd1", "\u8d85\u8fd1")) {
            score += Math.max(0D, (1200D - candidate.distance) / 450D);
        }
        if (isTemplateSampleShop(shop)) {
            score -= 1.2D;
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

    private boolean shouldFilterOutCandidate(CandidateShop candidate, boolean strictNeedMatch, String query) {
        if (candidate == null || candidate.shop == null) {
            return true;
        }
        String searchable = buildCandidateSearchable(candidate);
        if (strictNeedMatch && candidate.includeHitCount <= 0) {
            return true;
        }
        if (candidate.excludeHitCount > 0) {
            if (queryWantsBarbecue(query) && containsAnyKeyword(searchable, BBQ_KEYWORDS)) {
                return false;
            }
            if (queryWantsLightFood(query) && containsAnyKeyword(searchable, LIGHT_FOOD_KEYWORDS)) {
                return false;
            }
            return true;
        }
        if ((isNoMeatIntent(query) || queryWantsLightFood(query))
                && containsAnyKeyword(searchable, MEAT_HEAVY_KEYWORDS)
                && !containsAnyKeyword(searchable, LIGHT_FOOD_KEYWORDS)) {
            return true;
        }
        if (queryWantsBarbecue(query) && !containsAnyKeyword(searchable, BBQ_KEYWORDS)) {
            return true;
        }
        if (queryWantsDrinkOrRest(query) && !containsAnyKeyword(searchable, DRINK_REST_KEYWORDS)) {
            return true;
        }
        if (queryWantsWater(query) && !containsAnyKeyword(searchable, WATER_KEYWORDS)) {
            return true;
        }
        if (queryAvoidSpicy(query) && containsAnyKeyword(searchable, SPICY_KEYWORDS)) {
            return true;
        }
        return false;
    }

    private List<CandidateShop> applyQueryAwareConstraints(List<CandidateShop> current, List<CandidateShop> fallback, String query) {
        List<CandidateShop> base = CollUtil.isNotEmpty(current)
                ? new ArrayList<>(current)
                : (fallback == null ? new ArrayList<>() : new ArrayList<>(fallback));
        if (CollUtil.isEmpty(base)) {
            return Collections.emptyList();
        }
        String lowerQuery = StrUtil.blankToDefault(query, "").toLowerCase(Locale.ROOT);
        List<CandidateShop> working = base;

        if (queryWantsBarbecue(lowerQuery)) {
            working = keepIfAny(working, c -> containsAnyKeyword(buildCandidateSearchable(c), BBQ_KEYWORDS));
        }
        if (queryWantsDrinkOrRest(lowerQuery)) {
            working = keepIfAny(working, c -> containsAnyKeyword(buildCandidateSearchable(c), DRINK_REST_KEYWORDS));
        }
        if (queryWantsWater(lowerQuery)) {
            working = keepIfAny(working, c -> containsAnyKeyword(buildCandidateSearchable(c), WATER_KEYWORDS));
        }
        if (queryWantsLightFood(lowerQuery) || isNoMeatIntent(lowerQuery)) {
            List<CandidateShop> light = working.stream().filter(c -> {
                String searchable = buildCandidateSearchable(c);
                boolean meatHeavy = containsAnyKeyword(searchable, MEAT_HEAVY_KEYWORDS);
                boolean lightFood = containsAnyKeyword(searchable, LIGHT_FOOD_KEYWORDS);
                return !meatHeavy || lightFood;
            }).collect(Collectors.toList());
            if (CollUtil.isNotEmpty(light)) {
                working = light;
            }
        }
        if (queryAvoidSpicy(lowerQuery)) {
            List<CandidateShop> nonSpicy = working.stream()
                    .filter(c -> !containsAnyKeyword(buildCandidateSearchable(c), SPICY_KEYWORDS))
                    .collect(Collectors.toList());
            if (CollUtil.isNotEmpty(nonSpicy)) {
                working = nonSpicy;
            }
        }

        Integer budgetUpperBound = parseBudgetUpperBound(lowerQuery);
        if (budgetUpperBound != null) {
            List<CandidateShop> withinBudget = working.stream()
                    .filter(c -> c.shop.getAvgPrice() == null || c.shop.getAvgPrice() <= budgetUpperBound)
                    .collect(Collectors.toList());
            if (CollUtil.isNotEmpty(withinBudget)) {
                working = withinBudget;
            }
        }
        Integer distanceUpperBound = parseDistanceUpperBoundMeters(lowerQuery);
        if (distanceUpperBound != null) {
            List<CandidateShop> withinDistance = working.stream()
                    .filter(c -> c.distance == null || c.distance <= distanceUpperBound)
                    .collect(Collectors.toList());
            if (CollUtil.isNotEmpty(withinDistance)) {
                working = withinDistance;
            }
        }

        List<CandidateShop> nonTemplate = working.stream()
                .filter(c -> !isTemplateSampleShop(c.shop))
                .collect(Collectors.toList());
        int minKeep = Math.max(2, Math.min(aiProperties.getAssistantTopN(), working.size()));
        if (CollUtil.isNotEmpty(nonTemplate) && (nonTemplate.size() >= minKeep || working.size() <= minKeep)) {
            working = nonTemplate;
        }
        return working;
    }

    private List<CandidateShop> keepIfAny(List<CandidateShop> source, java.util.function.Predicate<CandidateShop> predicate) {
        if (CollUtil.isEmpty(source)) {
            return source;
        }
        List<CandidateShop> matched = source.stream().filter(predicate).collect(Collectors.toList());
        return CollUtil.isEmpty(matched) ? source : matched;
    }

    private boolean queryWantsBarbecue(String query) {
        String q = StrUtil.blankToDefault(query, "");
        return containsAnyKeyword(q, BBQ_KEYWORDS) || containsAny(q, "\u70e7\u70e4", "\u70e4\u4e32", "\u70e4\u8089", "bbq");
    }

    private boolean queryWantsDrinkOrRest(String query) {
        String q = StrUtil.blankToDefault(query, "");
        return containsAnyKeyword(q, DRINK_REST_KEYWORDS)
                || containsAny(q, "\u4f11\u606f", "\u5750\u4e00\u4f1a", "\u559d\u8336", "\u5496\u5561", "\u5976\u8336", "\u7a7a\u8c03");
    }

    private boolean queryWantsWater(String query) {
        String q = StrUtil.blankToDefault(query, "");
        return containsAnyKeyword(q, WATER_KEYWORDS) || containsAny(q, "\u4e70\u6c34", "\u74f6\u6c34", "\u996e\u7528\u6c34");
    }

    private boolean queryWantsLightFood(String query) {
        String q = StrUtil.blankToDefault(query, "");
        return containsAnyKeyword(q, LIGHT_FOOD_KEYWORDS)
                || containsAny(q, "\u6e05\u6de1", "\u5403\u7d20", "\u7d20\u98df", "\u517b\u80c3", "\u80a0\u80c3", "\u6ca1\u80c3\u53e3", "\u4e0d\u60f3\u5403\u8089");
    }

    private boolean queryAvoidSpicy(String query) {
        String q = StrUtil.blankToDefault(query, "");
        return containsAny(q, "\u4e0d\u8fa3", "\u4e0d\u8981\u8fa3", "\u5c11\u8fa3", "\u4e0d\u80fd\u5403\u8fa3", "\u5fcc\u8fa3");
    }

    private Integer parseBudgetUpperBound(String query) {
        String q = StrUtil.blankToDefault(query, "");
        Matcher matcher = BUDGET_NUMBER_PATTERN.matcher(q);
        Integer best = null;
        while (matcher.find()) {
            int val;
            try {
                val = Integer.parseInt(matcher.group(1));
            } catch (Exception ignore) {
                continue;
            }
            if (val < 10 || val > 600) {
                continue;
            }
            int start = Math.max(0, matcher.start() - 5);
            int end = Math.min(q.length(), matcher.end() + 5);
            String around = q.substring(start, end);
            if (containsAny(around, "\u5143", "\u5757", "\u9884\u7b97", "\u4eba\u5747", "\u4ee5\u5185", "\u4ee5\u4e0b", "\u4e0d\u8d85\u8fc7", "\u6700\u591a")) {
                if (best == null || val < best) {
                    best = val;
                }
            }
        }
        if (best != null) {
            return best;
        }
        if (containsAny(q, "\u4fbf\u5b9c", "\u5e73\u4ef7", "\u4e0d\u8d35", "\u5b9e\u60e0", "\u6027\u4ef7\u6bd4")) {
            return 80;
        }
        return null;
    }

    private Integer parseDistanceUpperBoundMeters(String query) {
        String q = StrUtil.blankToDefault(query, "");
        Matcher kmMatcher = DIST_KM_PATTERN.matcher(q);
        Double km = null;
        while (kmMatcher.find()) {
            try {
                double val = Double.parseDouble(kmMatcher.group(1));
                if (val > 0D && val <= 20D) {
                    km = km == null ? val : Math.min(km, val);
                }
            } catch (Exception ignore) {
                // ignore parse errors
            }
        }
        Integer meter = null;
        Matcher mMatcher = DIST_METER_PATTERN.matcher(q);
        while (mMatcher.find()) {
            try {
                int val = Integer.parseInt(mMatcher.group(1));
                if (val >= 50 && val <= 20000) {
                    meter = meter == null ? val : Math.min(meter, val);
                }
            } catch (Exception ignore) {
                // ignore parse errors
            }
        }
        int kmMeters = km == null ? Integer.MAX_VALUE : (int) Math.round(km * 1000D);
        int meterVal = meter == null ? Integer.MAX_VALUE : meter;
        int result = Math.min(kmMeters, meterVal);
        if (result != Integer.MAX_VALUE) {
            return result;
        }
        if (containsAny(q, "\u9644\u8fd1", "\u5c31\u8fd1", "\u8d70\u8def")) {
            return 3000;
        }
        return null;
    }

    private boolean isTemplateSampleShop(Shop shop) {
        if (shop == null || StrUtil.isBlank(shop.getName())) {
            return false;
        }
        String name = shop.getName();
        return StrUtil.containsIgnoreCase(name, "AI \u6837\u672c\u5e97")
                || StrUtil.containsIgnoreCase(name, "AI\u6837\u672c\u5e97")
                || StrUtil.containsIgnoreCase(name, "AI \u63a2\u5e97")
                || StrUtil.containsIgnoreCase(name, "AI\u63a2\u5e97");
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

    private List<CandidateShop> rerankCandidatesWithAi(String query,
                                                        List<String> includeKeywords,
                                                        List<String> excludeKeywords,
                                                        List<CandidateShop> sortedCandidates,
                                                        int topN,
                                                        Map<String, String> reasonByShopId) {
        if (CollUtil.isEmpty(sortedCandidates) || topN <= 0) {
            return sortedCandidates;
        }
        int poolSize = Math.min(sortedCandidates.size(), Math.max(topN * 4, 20));
        List<CandidateShop> pool = new ArrayList<>(sortedCandidates.subList(0, poolSize));

        RecommendRerankRequest rerankRequest = new RecommendRerankRequest();
        rerankRequest.setQuery(query);
        rerankRequest.setIncludeKeywords(includeKeywords);
        rerankRequest.setExcludeKeywords(excludeKeywords);
        rerankRequest.setTopN(Math.min(topN, pool.size()));
        rerankRequest.setShops(pool.stream().map(this::toRerankShop).collect(Collectors.toList()));
        RecommendRerankResponse rerankResponse = aiRemoteClient.recommendRerank(rerankRequest);
        if (rerankResponse == null || CollUtil.isEmpty(rerankResponse.getRankedShopIds())) {
            return sortedCandidates;
        }

        Map<Long, CandidateShop> byId = pool.stream()
                .filter(c -> c.shop != null && c.shop.getId() != null)
                .collect(Collectors.toMap(c -> c.shop.getId(), c -> c, (a, b) -> a, LinkedHashMap::new));
        List<CandidateShop> reranked = new ArrayList<>(sortedCandidates.size());
        Set<Long> used = new HashSet<>();
        for (Long id : rerankResponse.getRankedShopIds()) {
            CandidateShop candidate = byId.get(id);
            if (candidate == null || !used.add(id)) {
                continue;
            }
            reranked.add(candidate);
        }
        for (CandidateShop candidate : pool) {
            Long id = candidate.shop == null ? null : candidate.shop.getId();
            if (id != null && used.contains(id)) {
                continue;
            }
            reranked.add(candidate);
            if (id != null) {
                used.add(id);
            }
        }
        for (int i = poolSize; i < sortedCandidates.size(); i++) {
            CandidateShop candidate = sortedCandidates.get(i);
            Long id = candidate.shop == null ? null : candidate.shop.getId();
            if (id == null || !used.contains(id)) {
                reranked.add(candidate);
                if (id != null) {
                    used.add(id);
                }
            }
        }
        if (reasonByShopId != null && rerankResponse.getReasonByShopId() != null) {
            reasonByShopId.putAll(rerankResponse.getReasonByShopId());
        }
        return reranked;
    }

    private RecommendRerankShop toRerankShop(CandidateShop candidate) {
        RecommendRerankShop shop = new RecommendRerankShop();
        shop.setId(candidate.shop.getId());
        shop.setName(candidate.shop.getName());
        shop.setTypeName(candidate.typeName);
        shop.setAddress(candidate.shop.getAddress());
        shop.setShopDesc(candidate.shop.getShopDesc());
        shop.setAvgPrice(candidate.shop.getAvgPrice());
        shop.setScore(candidate.shop.getScore());
        shop.setComments(candidate.shop.getComments());
        shop.setSold(candidate.shop.getSold());
        shop.setDistance(candidate.distance);
        shop.setBaseRankScore(candidate.rankScore);
        return shop;
    }

    private List<CandidateShop> pickDiverseTopCandidates(List<CandidateShop> sortedCandidates, int topN) {
        if (CollUtil.isEmpty(sortedCandidates) || topN <= 0) {
            return Collections.emptyList();
        }
        List<CandidateShop> result = new ArrayList<>(topN);
        Set<Long> selectedIds = new HashSet<>();
        Set<Long> selectedTypeIds = new HashSet<>();

        for (CandidateShop candidate : sortedCandidates) {
            if (result.size() >= topN) {
                break;
            }
            if (candidate == null || candidate.shop == null || candidate.shop.getId() == null) {
                continue;
            }
            Long shopId = candidate.shop.getId();
            if (!selectedIds.add(shopId)) {
                continue;
            }
            Long typeId = candidate.shop.getTypeId();
            if (typeId != null && selectedTypeIds.contains(typeId)) {
                selectedIds.remove(shopId);
                continue;
            }
            result.add(candidate);
            if (typeId != null) {
                selectedTypeIds.add(typeId);
            }
        }

        if (result.size() < topN) {
            for (CandidateShop candidate : sortedCandidates) {
                if (result.size() >= topN) {
                    break;
                }
                if (candidate == null || candidate.shop == null || candidate.shop.getId() == null) {
                    continue;
                }
                Long shopId = candidate.shop.getId();
                if (!selectedIds.add(shopId)) {
                    continue;
                }
                if (isTemplateSampleShop(candidate.shop) && result.stream().filter(c -> isTemplateSampleShop(c.shop)).count() >= 1) {
                    selectedIds.remove(shopId);
                    continue;
                }
                result.add(candidate);
            }
        }
        return result;
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
        fillReasonWithAiOrFallback(query, recommendShops, Collections.emptyMap());
    }

    private void fillReasonWithAiOrFallback(String query, List<AiRecommendShopDTO> recommendShops, Map<String, String> presetReasonByShopId) {
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
            String key = String.valueOf(shop.getId());
            String reason = presetReasonByShopId == null ? null : presetReasonByShopId.get(key);
            if (StrUtil.isBlank(reason)) {
                reason = reasonByShopId == null ? null : reasonByShopId.get(key);
            }
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
        String raw = "v4|" + requestDTO.getQuery() + "|" + requestDTO.getX() + "|" + requestDTO.getY() + "|" + requestDTO.getCurrentTypeId();
        return SecureUtil.md5(raw);
    }

    private String buildReviewRiskCacheId(AiReviewRiskCheckRequestDTO requestDTO) {
        String raw = "v3|" + StrUtil.blankToDefault(requestDTO.getScene(), "BLOG_NOTE")
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
        summaryDTO.setEngine(StrUtil.blankToDefault(summaryDTO.getEngine(), ENGINE_FALLBACK));
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
            String p = stripSummaryNoiseFragments(point.trim());
            if (StrUtil.isBlank(p)) {
                continue;
            }
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
        String p = stripSummaryNoiseFragments(sanitizeText(point));
        if (p.length() < 4) {
            return false;
        }
        if (STORE_MAIN_BIZ_NOISE_PATTERN.matcher(p).find()) {
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
        String normalized = stripSummaryNoiseFragments(sanitizeText(summary));
        if (StrUtil.isBlank(normalized) || SUMMARY_NOISE_PATTERN.matcher(normalized).find()) {
            String merged = shopName + "整体口碑集中在：" + joinPoints(highFrequency, "；");
            if (CollUtil.isNotEmpty(uniqueHighlights)) {
                merged = merged + "。小众亮点包括：" + joinPoints(uniqueHighlights, "；");
            }
            return merged;
        }
        return normalized;
    }

    private String stripSummaryNoiseFragments(String text) {
        if (StrUtil.isBlank(text)) {
            return "";
        }
        String cleaned = STORE_MAIN_BIZ_NOISE_PATTERN.matcher(text).replaceAll("");
        cleaned = cleaned.replaceAll("\\s*([\\uFF0C\\u3002;\\uFF1B])\\s*", "$1");
        cleaned = cleaned.replaceAll("([\\uFF0C\\u3002;\\uFF1B])[\\uFF0C\\u3002;\\uFF1B]+", "$1");
        cleaned = cleaned.replaceAll("\\s{2,}", " ").trim();
        cleaned = StrUtil.removePrefix(cleaned, "\uFF0C");
        cleaned = StrUtil.removePrefix(cleaned, "\u3002");
        cleaned = StrUtil.removePrefix(cleaned, ";");
        cleaned = StrUtil.removePrefix(cleaned, "\uFF1B");
        cleaned = StrUtil.removeSuffix(cleaned, "\uFF0C");
        cleaned = StrUtil.removeSuffix(cleaned, "\u3002");
        cleaned = StrUtil.removeSuffix(cleaned, ";");
        cleaned = StrUtil.removeSuffix(cleaned, "\uFF1B");
        return cleaned.trim();
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
        dto.setEngine(StrUtil.blankToDefault(remoteResp.getEngine(), ENGINE_LLM));
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

    private AiReviewRiskCheckResponseDTO mergeRiskResponse(AiReviewRiskCheckResponseDTO remote, AiReviewRiskCheckResponseDTO fallback) {
        if (remote == null) {
            return fallback;
        }
        if (fallback == null) {
            return remote;
        }
        AiReviewRiskCheckResponseDTO merged = new AiReviewRiskCheckResponseDTO();
        merged.setEngine(StrUtil.blankToDefault(remote.getEngine(), ENGINE_LLM));
        merged.setFromCache(false);
        merged.setGeneratedAt(LocalDateTime.now().toString());

        int remoteScore = clampRiskScore(remote.getRiskScore());
        int localScore = clampRiskScore(fallback.getRiskScore());
        int mergedScore = Math.max(remoteScore, localScore);

        List<String> mergedTags = new ArrayList<>();
        mergedTags.addAll(safeList(remote.getRiskTags()));
        mergedTags.addAll(safeList(fallback.getRiskTags()));
        mergedTags = mergedTags.stream().filter(StrUtil::isNotBlank).distinct().limit(4).collect(Collectors.toList());

        List<String> mergedReasons = new ArrayList<>();
        mergedReasons.addAll(safeList(remote.getReasons()));
        mergedReasons.addAll(safeList(fallback.getReasons()));
        mergedReasons = mergedReasons.stream().filter(StrUtil::isNotBlank).distinct().limit(4).collect(Collectors.toList());

        String remoteLevel = StrUtil.blankToDefault(remote.getRiskLevel(), "REVIEW").toUpperCase(Locale.ROOT);
        String localLevel = StrUtil.blankToDefault(fallback.getRiskLevel(), "REVIEW").toUpperCase(Locale.ROOT);
        String level = riskLevelWeight(remoteLevel) >= riskLevelWeight(localLevel) ? remoteLevel : localLevel;
        if (hasStrictBlockTag(mergedTags) || mergedScore >= REVIEW_BLOCK_SCORE) {
            level = "BLOCK";
        }

        merged.setRiskLevel(level);
        merged.setRiskScore(mergedScore);
        merged.setRiskTags(mergedTags);
        merged.setReasons(mergedReasons);
        if ("BLOCK".equals(level)) {
            merged.setPass(false);
            merged.setSuggestion(StrUtil.blankToDefault(fallback.getSuggestion(), remote.getSuggestion()));
        } else if ("REVIEW".equals(level)) {
            merged.setPass(false);
            merged.setSuggestion(StrUtil.blankToDefault(remote.getSuggestion(), fallback.getSuggestion()));
        } else {
            merged.setPass(true);
            merged.setSuggestion(StrUtil.blankToDefault(remote.getSuggestion(), fallback.getSuggestion()));
        }
        return merged;
    }

    private int riskLevelWeight(String level) {
        if ("BLOCK".equalsIgnoreCase(level)) {
            return 3;
        }
        if ("REVIEW".equalsIgnoreCase(level)) {
            return 2;
        }
        if ("SAFE".equalsIgnoreCase(level)) {
            return 1;
        }
        return 0;
    }

    private AiReviewRiskCheckResponseDTO localReviewRiskFallback(AiReviewRiskCheckRequestDTO requestDTO) {
        String text = sanitizeText(StrUtil.blankToDefault(requestDTO.getTitle(), "") + " " + requestDTO.getContent());
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> tags = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        int score = 5;
        boolean hasContactHint = containsAny(lower, "\u8054\u7cfb", "\u54a8\u8be2", "\u5fae\u4fe1", "vx", "qq", "\u7535\u8bdd", "\u79c1\u804a", "\u52a0\u6211");
        boolean hasSpacedPhone = SPACED_PHONE_PATTERN.matcher(lower).find();
        boolean hasSpacedWechat = SPACED_WECHAT_PATTERN.matcher(lower).find();
        boolean hasRawPhone = PHONE_PATTERN.matcher(text).find();
        boolean hasLongNumber = text.matches(".*\\d{8,}.*");
        if ((containsAny(lower, "\u6211\u53eb", "\u59d3\u540d", "\u771f\u5b9e\u59d3\u540d", "\u4f4f\u5740", "\u5bb6\u5ead\u4f4f\u5740") && (hasRawPhone || hasLongNumber))
                || hasSpacedPhone) {
            score += 40;
            tags.add("隐私泄露");
            reasons.add("\u5185\u5bb9\u7591\u4f3c\u540c\u65f6\u51fa\u73b0\u771f\u5b9e\u8eab\u4efd\u4e0e\u8054\u7cfb\u65b9\u5f0f\u4fe1\u606f");
        }
        if (containsAny(lower, "\u50bb\u903c", "\u6eda\u5f00", "\u53bb\u6b7b", "\u5e9f\u7269", "\u8111\u6b8b", "\u5783\u573e\u4eba", "\u5168\u5bb6")) {
            score += 45;
            tags.add("人身攻击");
            reasons.add("\u5185\u5bb9\u5305\u542b\u660e\u663e\u4eba\u8eab\u653b\u51fb\u6216\u5a01\u80c1\u6027\u7528\u8bed");
        }
        if (containsAny(lower, "\u50bbx", "\u780d\u5e97", "\u6740", "\u5f04\u6b7b")) {
            score += 45;
            tags.add("人身攻击");
            reasons.add("\u5185\u5bb9\u5305\u542b\u4eba\u8eab\u653b\u51fb\u6216\u66b4\u529b\u503e\u5411\u8868\u8fbe");
        }
        if (hasRawPhone && containsAny(lower, "\u624b\u673a", "\u7535\u8bdd", "\u4f4f\u5728", "\u5730\u5740", "\u8def", "\u53f7")) {
            score += 65;
            tags.add("隐私泄露");
            reasons.add("\u5185\u5bb9\u5305\u542b\u53ef\u8bc6\u522b\u4e2a\u4eba\u9690\u79c1\u4fe1\u606f");
        }
        if (containsAny(lower, "\u9690\u85cf\u8054\u7cfb\u65b9\u5f0f", "\u8bc4\u8bba\u56de\u590d", "\u60f3\u77e5\u9053", "\u5916\u5730\u4e5f\u80fd\u5bc4")) {
            score += 55;
            tags.add("广告引流");
            reasons.add("\u5185\u5bb9\u7591\u4f3c\u8bf1\u5bfc\u79c1\u4e0b\u83b7\u53d6\u8054\u7cfb\u65b9\u5f0f\u6216\u4ea4\u6613");
        }

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
        if (containsAny(lower, "\u516c\u4f17\u53f7", "\u516c\u7cbd\u53f7", "\u641c\u6211", "\u641cv", "\u641cvx", "\u5916\u5356\u7fa4", "\u8fd4\u5229", "\u4ee3\u8d2d", "\u4ee3\u4e0b\u5355")) {
            score += 45;
            tags.add("广告引流");
            reasons.add("\u5185\u5bb9\u7591\u4f3c\u5305\u542b\u9690\u5f0f\u5f15\u6d41\u6216\u53d8\u4f53\u5e7f\u544a\u4fe1\u606f");
        }
        if ((hasRawPhone || hasLongNumber || hasSpacedPhone || hasSpacedWechat) && hasContactHint) {
            score += 65;
            tags.add("联系方式");
            reasons.add("\u5185\u5bb9\u7591\u4f3c\u5305\u542b\u8054\u7cfb\u65b9\u5f0f\u6216\u62c6\u5206\u5f0f\u5bfc\u6d41\u4fe1\u606f");
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
        dto.setEngine(ENGINE_FALLBACK);
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
        response.setEngine(StrUtil.blankToDefault(response.getEngine(), ENGINE_FALLBACK));
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
        summaryDTO.setEngine(ENGINE_FALLBACK);
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
