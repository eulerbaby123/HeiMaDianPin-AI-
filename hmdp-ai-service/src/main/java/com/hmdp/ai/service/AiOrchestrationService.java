package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AiOrchestrationService {

    private static final Pattern SPLIT_PATTERN = Pattern.compile("[,，。！？!?.;；\\n\\r]");
    private static final Pattern AD_LINK_PATTERN = Pattern.compile("(https?://|www\\.|加微|微信|vx|v信|QQ|扣扣|私聊|引流|代理|返利|刷单|推广|代购|点击链接|二维码)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ILLEGAL_PATTERN = Pattern.compile("(赌博|赌钱|毒品|吸毒|枪支|办证|套现|洗钱|发票|违禁|色情)");
    private static final Pattern EXTREME_PATTERN = Pattern.compile("(最便宜|稳赚不赔|包过|百分百|绝对有效|一夜暴富)");
    private static final Pattern CONTACT_PATTERN = Pattern.compile("(\\d{6,}|[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+)");
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)1\\d{10}(?!\\d)");
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");
    private static final Pattern BLOG_TITLE_NOISE_PATTERN = Pattern.compile("(?i)^AI探店-\\d+-\\d+.*");
    private static final Pattern SUMMARY_NOISE_PATTERN = Pattern.compile("(?i)(AI探店-\\d+-\\d+|AI样本店-\\d+-\\d+)");
    private static final Pattern ABUSE_PATTERN = Pattern.compile("(傻逼|脑残|滚开|去死|废物|垃圾人|骗子)");
    private static final Set<String> STRICT_BLOCK_TAGS = new HashSet<>(Arrays.asList("违法违禁", "广告引流", "联系方式", "隐私泄露", "人身攻击"));
    private static final List<String> TOKEN_LEXICON = Arrays.asList(
            "火锅", "烧烤", "烤肉", "水果", "果茶", "果汁", "奶茶", "咖啡", "茶饮", "喝茶",
            "休息", "座位", "空调", "买水", "饮用水", "矿泉水", "清淡", "感冒", "没胃口",
            "轻食", "粥", "汤", "素食", "不想吃肉", "不吃肉", "亲子", "健身", "按摩", "SPA",
            "酒吧", "KTV", "轰趴", "美甲", "美睫", "美发"
    );

    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;
    private final boolean aiEnabled;

    public AiOrchestrationService(ChatClient.Builder chatClientBuilder,
                                  ObjectMapper objectMapper,
                                  @Value("${spring.ai.openai.api-key:}") String openAiApiKey,
                                  @Value("${spring.ai.dashscope.api-key:}") String dashscopeApiKey) {
        this.chatClientBuilder = chatClientBuilder;
        this.objectMapper = objectMapper;
        this.aiEnabled = StringUtils.hasText(openAiApiKey) || StringUtils.hasText(dashscopeApiKey);
    }

    public ChunkSummaryResponse summarizeChunk(ChunkSummaryRequest request) {
        if (request == null || request.getReviews() == null || request.getReviews().isEmpty()) {
            return fallbackChunkSummary(request);
        }
        if (!aiEnabled) {
            return fallbackChunkSummary(request);
        }

        String system = "你是店铺口碑分析助手。请严格输出JSON，不要输出markdown。";
        String user = "请根据以下探店博客，返回JSON，字段必须为："
                + "summary(string),highFrequencyPoints(string数组,<=4),uniquePoints(string数组,<=3),keywords(string数组,<=8)。"
                + "要求：禁止返回博客标题、编号、店铺编号、模板句；只输出可执行的经营/服务/体验信息短句。"
                + "店铺名称：" + request.getShopName() + "；分组：" + request.getChunkIndex() + "/" + request.getTotalChunks()
                + "；探店内容：" + toCompactReviews(request.getReviews());
        ChunkSummaryResponse response = callAiForJson(system, user, ChunkSummaryResponse.class);
        if (!isValidChunkResponse(response)) {
            return fallbackChunkSummary(request);
        }
        return normalizeChunk(response);
    }

    public FinalSummaryResponse summarizeFinal(FinalSummaryRequest request) {
        if (request == null || request.getChunkSummaries() == null || request.getChunkSummaries().isEmpty()) {
            return fallbackFinalSummary(request);
        }
        if (!aiEnabled) {
            return fallbackFinalSummary(request);
        }

        String system = "你是资深大众点评口碑总结助手。请严格输出JSON，不要输出markdown。";
        String user = "请将多组口碑总结聚合，输出JSON字段：summary(string),advice(string),"
                + "highFrequencyPoints(string数组,<=6),uniquePoints(string数组,<=6)。"
                + "要求：不得包含“AI探店-编号”或任何编号信息；不得输出空泛模板句。"
                + "店铺名称：" + request.getShopName() + "；探店数：" + request.getReviewCount()
                + "；分组总结：" + toCompactChunkSummaries(request.getChunkSummaries());
        FinalSummaryResponse response = callAiForJson(system, user, FinalSummaryResponse.class);
        if (response == null || !StringUtils.hasText(response.getSummary())) {
            return fallbackFinalSummary(request);
        }
        if (response.getHighFrequencyPoints() == null) {
            response.setHighFrequencyPoints(Collections.emptyList());
        }
        if (response.getUniquePoints() == null) {
            response.setUniquePoints(Collections.emptyList());
        }
        if (!StringUtils.hasText(response.getAdvice())) {
            response.setAdvice("建议优先关注高频口碑，再结合距离、评分、人均消费做决策。");
        }
        return response;
    }

    public IntentParseResponse parseIntent(IntentParseRequest request) {
        if (request == null || !StringUtils.hasText(request.getQuery())) {
            return fallbackIntent(request);
        }
        if (!aiEnabled) {
            return fallbackIntent(request);
        }

        String system = "你是本地生活推荐意图识别助手。请严格输出JSON，不要输出markdown。";
        String user = "从用户输入中提取推荐意图，输出JSON字段：intentSummary(string),"
                + "typeKeywords(string数组),includeKeywords(string数组),excludeKeywords(string数组)。"
                + "规则：如果用户表达“不要/不想/忌口/避免”，必须把对应排除项放入excludeKeywords。"
                + "可用店铺类型：" + request.getAvailableTypes() + "；用户输入：" + request.getQuery();
        IntentParseResponse response = callAiForJson(system, user, IntentParseResponse.class);
        if (response == null) {
            return fallbackIntent(request);
        }
        if (!StringUtils.hasText(response.getIntentSummary())) {
            response.setIntentSummary("用户希望获取附近推荐");
        }
        if (response.getTypeKeywords() == null) {
            response.setTypeKeywords(Collections.emptyList());
        }
        if (response.getIncludeKeywords() == null) {
            response.setIncludeKeywords(Collections.emptyList());
        }
        if (response.getExcludeKeywords() == null) {
            response.setExcludeKeywords(Collections.emptyList());
        }
        response.setTypeKeywords(mergeDistinct(response.getTypeKeywords(), inferTypeKeywords(request.getQuery())));
        response.setIncludeKeywords(mergeDistinct(response.getIncludeKeywords(),
                mergeDistinct(extractTokens(request.getQuery()), inferIncludeKeywords(request.getQuery()))));
        response.setExcludeKeywords(mergeDistinct(response.getExcludeKeywords(), inferExcludeKeywords(request.getQuery())));
        return response;
    }

    public RecommendReasonResponse recommendReason(RecommendReasonRequest request) {
        if (request == null || request.getShops() == null || request.getShops().isEmpty()) {
            RecommendReasonResponse empty = new RecommendReasonResponse();
            empty.setReasonByShopId(Collections.emptyMap());
            return empty;
        }
        if (!aiEnabled) {
            return fallbackReason(request);
        }

        String system = "你是推荐理由生成助手。请严格输出JSON，不要输出markdown。";
        String user = "基于用户需求和候选店铺，输出JSON字段：reasonByShopId(对象，key为店铺id字符串，value为一句推荐理由)。"
                + "用户输入：" + request.getQuery() + "；候选店铺（含店铺简介和服务信息）：" + toCompactReasonShops(request.getShops());
        RecommendReasonResponse response = callAiForJson(system, user, RecommendReasonResponse.class);
        if (response == null || response.getReasonByShopId() == null || response.getReasonByShopId().isEmpty()) {
            return fallbackReason(request);
        }
        return response;
    }

    public ReviewRiskCheckResponse reviewRiskCheck(ReviewRiskCheckRequest request) {
        if (request == null || !StringUtils.hasText(request.getContent())) {
            ReviewRiskCheckResponse response = new ReviewRiskCheckResponse();
            response.setPass(true);
            response.setRiskLevel("SAFE");
            response.setRiskScore(0);
            response.setRiskTags(Collections.singletonList("内容为空"));
            response.setReasons(Collections.singletonList("未检测到可分析文本，默认放行。"));
            response.setSuggestion("可补充更完整的点评内容。");
            return response;
        }

        ReviewRiskCheckResponse fallback = fallbackRiskCheck(request);
        if (!aiEnabled) {
            return fallback;
        }

        String system = "你是内容风控质检助手。请严格输出JSON，不要输出markdown。";
        String user = "请对用户点评内容做质检与风控，重点识别广告引流、联系方式泄露、违禁违法、辱骂攻击、夸大营销。"
                + "输出JSON字段：pass(boolean),riskLevel(string:SAFE|REVIEW|BLOCK),riskScore(int 0-100),"
                + "riskTags(string数组),reasons(string数组),suggestion(string)。"
                + "高风险直接BLOCK：广告引流、联系方式、违法违禁、姓名手机号地址证件等隐私泄露、人身攻击辱骂。"
                + "场景：" + sanitize(request.getScene()) + "；店铺：" + sanitize(request.getShopName())
                + "；店铺简介：" + sanitize(request.getShopDesc())
                + "；标题：" + sanitize(request.getTitle())
                + "；内容：" + sanitize(request.getContent());
        ReviewRiskCheckResponse response = callAiForJson(system, user, ReviewRiskCheckResponse.class);
        if (!isValidRiskResponse(response)) {
            return fallback;
        }
        return normalizeRiskResponse(response, fallback);
    }

    private ChunkSummaryResponse fallbackChunkSummary(ChunkSummaryRequest request) {
        List<String> sentences = new ArrayList<>();
        if (request != null && request.getReviews() != null) {
            for (ReviewSnippet review : request.getReviews()) {
                String text = buildSummaryReviewText(review);
                String[] parts = SPLIT_PATTERN.split(text);
                for (String part : parts) {
                    String p = part.trim();
                    if (p.length() >= 4 && p.length() <= 60) {
                        sentences.add(p);
                    }
                }
            }
        }

        Map<String, Integer> freq = new HashMap<>();
        for (String sentence : sentences) {
            String normalized = normalize(sentence);
            if (!normalized.isEmpty()) {
                freq.put(normalized, freq.getOrDefault(normalized, 0) + 1);
            }
        }
        Map<String, String> originalByNorm = new HashMap<>();
        for (String sentence : sentences) {
            originalByNorm.putIfAbsent(normalize(sentence), sentence);
        }

        List<String> high = freq.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .map(entry -> originalByNorm.get(entry.getKey()))
                .filter(Objects::nonNull)
                .distinct()
                .limit(4)
                .collect(Collectors.toList());

        List<String> unique = freq.entrySet().stream()
                .filter(entry -> entry.getValue() == 1)
                .map(entry -> originalByNorm.get(entry.getKey()))
                .filter(Objects::nonNull)
                .distinct()
                .limit(3)
                .collect(Collectors.toList());
        high = cleanPointList(high, 4);
        unique = cleanPointList(unique, 3);

        ChunkSummaryResponse response = new ChunkSummaryResponse();
        response.setSummary("本组高频口碑：" + String.join("；", high.isEmpty() ? Collections.singletonList("信息不足") : high));
        response.setHighFrequencyPoints(high);
        response.setUniquePoints(unique);
        response.setKeywords(extractTokens(response.getSummary()));
        return response;
    }

    private FinalSummaryResponse fallbackFinalSummary(FinalSummaryRequest request) {
        List<String> high = new ArrayList<>();
        List<String> unique = new ArrayList<>();
        if (request != null && request.getChunkSummaries() != null) {
            for (ChunkSummaryResponse chunkSummary : request.getChunkSummaries()) {
                if (chunkSummary.getHighFrequencyPoints() != null) {
                    high.addAll(chunkSummary.getHighFrequencyPoints());
                }
                if (chunkSummary.getUniquePoints() != null) {
                    unique.addAll(chunkSummary.getUniquePoints());
                }
            }
        }
        high = cleanPointList(high, 6);
        unique = cleanPointList(unique, 6);

        FinalSummaryResponse response = new FinalSummaryResponse();
        String shopName = request == null ? "该店铺" : request.getShopName();
        String summary = shopName + "整体口碑集中在：" + String.join("；", high);
        if (SUMMARY_NOISE_PATTERN.matcher(summary).find()) {
            summary = shopName + "整体口碑集中在：" + String.join("；", cleanPointList(high, 6));
        }
        response.setSummary(summary);
        response.setAdvice("建议优先参考高频口碑，再结合距离、评分、人均消费综合判断。");
        response.setHighFrequencyPoints(high);
        response.setUniquePoints(unique);
        return response;
    }

    private IntentParseResponse fallbackIntent(IntentParseRequest request) {
        String query = request == null ? "" : Optional.ofNullable(request.getQuery()).orElse("");
        List<String> typeKeywords = inferTypeKeywords(query);
        List<String> include = mergeDistinct(extractTokens(query), inferIncludeKeywords(query));
        List<String> exclude = inferExcludeKeywords(query);

        IntentParseResponse response = new IntentParseResponse();
        response.setIntentSummary("用户希望在附近找到更符合当前需求的店铺");
        response.setTypeKeywords(typeKeywords);
        response.setIncludeKeywords(include);
        response.setExcludeKeywords(exclude);
        return response;
    }

    private RecommendReasonResponse fallbackReason(RecommendReasonRequest request) {
        Map<String, String> map = new HashMap<>();
        for (RecommendReasonShop shop : request.getShops()) {
            String distance = shop.getDistance() == null ? "距离未知" : (shop.getDistance() < 1000
                    ? String.format(Locale.ROOT, "距离%.0fm", shop.getDistance())
                    : String.format(Locale.ROOT, "距离%.1fkm", shop.getDistance() / 1000.0D));
            String score = shop.getScore() == null ? "0.0" : String.format(Locale.ROOT, "%.1f", shop.getScore() / 10.0D);
            String desc = "";
            if (StringUtils.hasText(shop.getShopDesc())) {
                desc = "，简介提到：" + sanitize(shop.getShopDesc());
            }
            String reason = distance + "，评分" + score + desc + "，与“" + request.getQuery() + "”匹配度较高。";
            map.put(String.valueOf(shop.getId()), reason);
        }
        RecommendReasonResponse response = new RecommendReasonResponse();
        response.setReasonByShopId(map);
        return response;
    }

    private ReviewRiskCheckResponse fallbackRiskCheck(ReviewRiskCheckRequest request) {
        String text = sanitize(Optional.ofNullable(request.getTitle()).orElse("") + " " + request.getContent());
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> tags = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        int score = 5;

        if (ILLEGAL_PATTERN.matcher(text).find()) {
            score += 80;
            tags.add("违法违禁");
            reasons.add("文本包含疑似违法违禁关键词。");
        }
        if (AD_LINK_PATTERN.matcher(lower).find()) {
            score += 65;
            tags.add("广告引流");
            reasons.add("文本疑似包含广告推广或引流词。");
        }
        if ((CONTACT_PATTERN.matcher(text).find() || PHONE_PATTERN.matcher(text).find())
                && containsAnyKeyword(lower, Arrays.asList("联系", "咨询", "加", "vx", "微信", "qq", "电话"))) {
            score += 55;
            tags.add("联系方式");
            reasons.add("文本疑似出现联系方式或导流信息。");
        }
        if (ID_CARD_PATTERN.matcher(text).find()
                || containsAnyKeyword(lower, Arrays.asList("身份证", "住址", "详细地址", "真实姓名", "我叫", "手机号", "电话号"))) {
            score += 60;
            tags.add("隐私泄露");
            reasons.add("文本疑似泄露姓名、电话、地址或证件信息。");
        }
        if (ABUSE_PATTERN.matcher(lower).find()) {
            score += 45;
            tags.add("人身攻击");
            reasons.add("文本疑似包含辱骂或攻击性表达。");
        }
        if (EXTREME_PATTERN.matcher(text).find()) {
            score += 20;
            tags.add("夸大营销");
            reasons.add("文本包含夸大承诺类词汇。");
        }
        if (text.contains("！！！") || text.contains("???") || text.contains("￥￥￥")) {
            score += 10;
            tags.add("刷屏噪声");
            reasons.add("文本疑似存在刷屏式表达。");
        }

        score = clampScore(score);
        ReviewRiskCheckResponse response = new ReviewRiskCheckResponse();
        response.setRiskScore(score);
        if (score >= 45 || hasStrictBlockTag(tags)) {
            response.setPass(false);
            response.setRiskLevel("BLOCK");
            response.setSuggestion("内容触发高风险，请移除广告、联系方式、隐私泄露或违法相关描述后再发布。");
        } else if (score >= 30) {
            response.setPass(false);
            response.setRiskLevel("REVIEW");
            response.setSuggestion("内容存在一定风险，请先修改敏感表述后再发布。");
        } else {
            response.setPass(true);
            response.setRiskLevel("SAFE");
            response.setSuggestion("内容整体正常，可发布。");
        }
        if (tags.isEmpty()) {
            tags.add("正常内容");
        }
        if (reasons.isEmpty()) {
            reasons.add("未发现明显违规特征。");
        }
        response.setRiskTags(tags.stream().distinct().limit(4).collect(Collectors.toList()));
        response.setReasons(reasons.stream().distinct().limit(4).collect(Collectors.toList()));
        return response;
    }

    private <T> T callAiForJson(String systemPrompt, String userPrompt, Class<T> clazz) {
        try {
            String raw = chatClientBuilder.build()
                    .prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            return parseModelJson(raw, clazz);
        } catch (Exception e) {
            log.warn("ai call failed: {}", e.getMessage());
            return null;
        }
    }

    private <T> T parseModelJson(String raw, Class<T> clazz) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            String cleaned = raw.trim();
            cleaned = cleaned.replace("```json", "").replace("```", "").trim();
            int start = cleaned.indexOf("{");
            int end = cleaned.lastIndexOf("}");
            if (start >= 0 && end > start) {
                cleaned = cleaned.substring(start, end + 1);
            }
            return objectMapper.readValue(cleaned, clazz);
        } catch (Exception e) {
            log.warn("parse model json failed: {}", e.getMessage());
            return null;
        }
    }

    private String toCompactReviews(List<ReviewSnippet> reviews) {
        return reviews.stream()
                .limit(30)
                .map(review -> "{id=" + review.getBlogId() + ",liked=" + review.getLiked() + ",text=" + buildSummaryReviewText(review) + "}")
                .collect(Collectors.joining(","));
    }

    private String toCompactChunkSummaries(List<ChunkSummaryResponse> chunkSummaries) {
        return chunkSummaries.stream()
                .map(summary -> "{summary=" + summary.getSummary() + ",high=" + summary.getHighFrequencyPoints() + ",unique=" + summary.getUniquePoints() + "}")
                .collect(Collectors.joining(","));
    }

    private String toCompactReasonShops(List<RecommendReasonShop> shops) {
        return shops.stream()
                .map(shop -> "{id=" + shop.getId() + ",name=" + shop.getName() + ",distance=" + shop.getDistance()
                        + ",score=" + shop.getScore() + ",avg=" + shop.getAvgPrice() + ",desc=" + sanitize(shop.getShopDesc()) + "}")
                .collect(Collectors.joining(","));
    }

    private ChunkSummaryResponse normalizeChunk(ChunkSummaryResponse response) {
        if (response.getHighFrequencyPoints() == null) response.setHighFrequencyPoints(Collections.emptyList());
        if (response.getUniquePoints() == null) response.setUniquePoints(Collections.emptyList());
        if (response.getKeywords() == null) response.setKeywords(Collections.emptyList());
        response.setHighFrequencyPoints(cleanPointList(response.getHighFrequencyPoints(), 4));
        response.setUniquePoints(cleanPointList(response.getUniquePoints(), 3));
        response.setKeywords(cleanPointList(response.getKeywords(), 8));
        if (!StringUtils.hasText(response.getSummary()) || SUMMARY_NOISE_PATTERN.matcher(response.getSummary()).find()) {
            response.setSummary("本组口碑整理完成。");
        }
        return response;
    }

    private boolean isValidChunkResponse(ChunkSummaryResponse response) {
        return response != null && (StringUtils.hasText(response.getSummary())
                || (response.getHighFrequencyPoints() != null && !response.getHighFrequencyPoints().isEmpty()));
    }

    private boolean isValidRiskResponse(ReviewRiskCheckResponse response) {
        return response != null
                && StringUtils.hasText(response.getRiskLevel())
                && response.getRiskScore() != null;
    }

    private ReviewRiskCheckResponse normalizeRiskResponse(ReviewRiskCheckResponse response, ReviewRiskCheckResponse fallback) {
        String level = Optional.ofNullable(response.getRiskLevel()).orElse("REVIEW").toUpperCase(Locale.ROOT);
        if (!"SAFE".equals(level) && !"REVIEW".equals(level) && !"BLOCK".equals(level)) {
            level = Optional.ofNullable(fallback.getRiskLevel()).orElse("REVIEW");
        }
        response.setRiskLevel(level);
        response.setRiskScore(clampScore(response.getRiskScore()));
        if (response.getRiskTags() == null || response.getRiskTags().isEmpty()) {
            response.setRiskTags(fallback.getRiskTags());
        } else {
            response.setRiskTags(cleanPointList(response.getRiskTags(), 4));
        }
        if (response.getReasons() == null || response.getReasons().isEmpty()) {
            response.setReasons(fallback.getReasons());
        } else {
            response.setReasons(cleanPointList(response.getReasons(), 4));
        }
        if (!StringUtils.hasText(response.getSuggestion())) {
            response.setSuggestion(fallback.getSuggestion());
        }
        boolean forceBlock = "BLOCK".equals(level)
                || response.getRiskScore() >= 45
                || hasStrictBlockTag(response.getRiskTags());
        if (forceBlock) {
            response.setRiskLevel("BLOCK");
            response.setPass(false);
            response.setSuggestion("内容触发高风险，请移除广告、联系方式、隐私泄露或违法相关描述后再发布。");
            return response;
        }
        if ("REVIEW".equals(level)) {
            response.setPass(false);
        } else if (response.getPass() == null) {
            response.setPass(true);
        }
        return response;
    }

    private String buildSummaryReviewText(ReviewSnippet review) {
        if (review == null) {
            return "";
        }
        String title = sanitize(Optional.ofNullable(review.getTitle()).orElse(""));
        if (BLOG_TITLE_NOISE_PATTERN.matcher(title).matches()) {
            title = "";
        }
        String content = sanitize(Optional.ofNullable(review.getContent()).orElse(""));
        String merged = (title + " " + content).trim();
        if (!StringUtils.hasText(merged)) {
            return "";
        }
        if (merged.length() > 180) {
            merged = merged.substring(0, 180);
        }
        return merged;
    }

    private List<String> cleanPointList(List<String> points, int maxSize) {
        if (points == null || points.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        for (String point : points) {
            if (!isMeaningfulPoint(point)) {
                continue;
            }
            String p = sanitize(point);
            if (p.length() > 60) {
                p = p.substring(0, 60);
            }
            cleaned.add(p);
            if (cleaned.size() >= maxSize) {
                break;
            }
        }
        return new ArrayList<>(cleaned);
    }

    private boolean isMeaningfulPoint(String point) {
        if (!StringUtils.hasText(point)) {
            return false;
        }
        String p = sanitize(point);
        if (p.length() < 4) {
            return false;
        }
        if (SUMMARY_NOISE_PATTERN.matcher(p).find()) {
            return false;
        }
        return !p.matches("^[\\d\\-_/\\s]+$");
    }

    private List<String> inferTypeKeywords(String query) {
        String lower = Optional.ofNullable(query).orElse("").toLowerCase(Locale.ROOT);
        LinkedHashSet<String> type = new LinkedHashSet<>();
        if (containsAnyKeyword(lower, Arrays.asList("火锅", "烧烤", "烤肉", "水果", "奶茶", "咖啡", "清淡", "粥", "汤", "轻食"))) {
            type.add("美食");
        }
        if (containsAnyKeyword(lower, Arrays.asList("ktv", "唱歌"))) type.add("KTV");
        if (containsAnyKeyword(lower, Arrays.asList("美发", "剪发", "发型"))) type.add("美发");
        if (containsAnyKeyword(lower, Arrays.asList("美甲", "美睫"))) {
            type.add("美甲");
            type.add("美睫");
        }
        if (containsAnyKeyword(lower, Arrays.asList("按摩", "足疗"))) {
            type.add("按摩");
            type.add("足疗");
        }
        if (containsAnyKeyword(lower, Arrays.asList("spa", "美容"))) {
            type.add("SPA");
            type.add("美容");
        }
        if (containsAnyKeyword(lower, Arrays.asList("亲子", "遛娃"))) type.add("亲子");
        if (containsAnyKeyword(lower, Arrays.asList("酒吧", "喝酒", "微醺"))) type.add("酒吧");
        if (containsAnyKeyword(lower, Arrays.asList("轰趴", "包场"))) type.add("轰趴");
        if (containsAnyKeyword(lower, Arrays.asList("健身", "训练"))) type.add("健身");
        return new ArrayList<>(type);
    }

    private List<String> inferIncludeKeywords(String query) {
        String lower = Optional.ofNullable(query).orElse("").toLowerCase(Locale.ROOT);
        LinkedHashSet<String> include = new LinkedHashSet<>();
        if (containsAnyKeyword(lower, Arrays.asList("水果", "果茶", "果汁"))) {
            include.addAll(Arrays.asList("水果", "果汁", "果茶", "甜品", "轻食"));
        }
        if (containsAnyKeyword(lower, Arrays.asList("喝茶", "茶饮", "休息"))) {
            include.addAll(Arrays.asList("茶", "茶饮", "休息", "空调", "座位"));
        }
        if (containsAnyKeyword(lower, Arrays.asList("买水", "买瓶水", "饮用水"))) {
            include.addAll(Arrays.asList("饮用水", "热水", "补给", "便利"));
        }
        if (containsAnyKeyword(lower, Arrays.asList("清淡", "感冒", "没胃口", "养胃"))) {
            include.addAll(Arrays.asList("清淡", "轻食", "粥", "汤", "热水"));
        }
        if (containsAnyKeyword(lower, Arrays.asList("不想吃肉", "不吃肉", "素食"))) {
            include.addAll(Arrays.asList("素食", "轻食", "蔬菜", "清淡"));
        }
        return new ArrayList<>(include);
    }

    private List<String> inferExcludeKeywords(String query) {
        String lower = Optional.ofNullable(query).orElse("").toLowerCase(Locale.ROOT);
        LinkedHashSet<String> exclude = new LinkedHashSet<>();
        if (containsAnyKeyword(lower, Arrays.asList("不想吃肉", "不吃肉", "素食", "少肉"))) {
            exclude.addAll(Arrays.asList("火锅", "烧烤", "烤肉", "牛肉", "羊肉", "肉", "串"));
        }
        if (containsAnyKeyword(lower, Arrays.asList("清淡", "感冒", "没胃口"))) {
            exclude.addAll(Arrays.asList("火锅", "烧烤", "重辣", "重油"));
        }
        if (containsAnyKeyword(lower, Arrays.asList("不喝酒", "戒酒"))) {
            exclude.addAll(Arrays.asList("酒吧", "酒精"));
        }
        return new ArrayList<>(exclude);
    }

    private List<String> mergeDistinct(List<String> first, List<String> second) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (first != null) {
            for (String keyword : first) {
                if (StringUtils.hasText(keyword)) {
                    set.add(keyword.trim());
                }
            }
        }
        if (second != null) {
            for (String keyword : second) {
                if (StringUtils.hasText(keyword)) {
                    set.add(keyword.trim());
                }
            }
        }
        return new ArrayList<>(set);
    }

    private boolean hasStrictBlockTag(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return false;
        }
        for (String tag : tags) {
            if (StringUtils.hasText(tag) && STRICT_BLOCK_TAGS.contains(tag.trim())) {
                return true;
            }
        }
        return false;
    }

    private String sanitize(String text) {
        if (text == null) return "";
        String t = text.replaceAll("<[^>]+>", " ");
        t = t.replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
        if (t.length() > 300) return t.substring(0, 300);
        return t;
    }

    private String normalize(String text) {
        if (text == null) return "";
        return text.replaceAll("[\\p{Punct}\\s，。！？；、:：]", "").toLowerCase(Locale.ROOT);
    }

    private List<String> extractTokens(String text) {
        if (!StringUtils.hasText(text)) return Collections.emptyList();
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        String lower = text.toLowerCase(Locale.ROOT);
        for (String lexicon : TOKEN_LEXICON) {
            if (lower.contains(lexicon.toLowerCase(Locale.ROOT))) {
                tokens.add(lexicon);
            }
        }
        String[] arr = text.replaceAll("[,，。！？!?.;；/\\\\]", " ").split("\\s+");
        for (String a : arr) {
            String t = a.trim();
            if (t.length() >= 2) {
                tokens.add(t);
            }
        }
        if (tokens.isEmpty() && text.trim().length() >= 2) {
            tokens.add(text.trim());
        }
        return new ArrayList<>(tokens);
    }

    private int clampScore(Integer score) {
        int safeScore = score == null ? 0 : score;
        if (safeScore < 0) {
            return 0;
        }
        return Math.min(safeScore, 100);
    }

    private boolean containsAnyKeyword(String text, List<String> keywords) {
        if (!StringUtils.hasText(text) || keywords == null || keywords.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
