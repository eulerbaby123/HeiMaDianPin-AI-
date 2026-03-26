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

        String system = "你是餐饮推荐意图识别助手。请严格输出JSON，不要输出markdown。";
        String user = "从用户输入中提取推荐意图，输出JSON字段：intentSummary(string),"
                + "typeKeywords(string数组),includeKeywords(string数组),excludeKeywords(string数组)。"
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
            response.setIncludeKeywords(extractTokens(request.getQuery()));
        }
        if (response.getExcludeKeywords() == null) {
            response.setExcludeKeywords(Collections.emptyList());
        }
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
                String text = sanitize((review.getTitle() == null ? "" : review.getTitle()) + "。" + (review.getContent() == null ? "" : review.getContent()));
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
        high = high.stream().filter(StringUtils::hasText).distinct().limit(6).collect(Collectors.toList());
        unique = unique.stream().filter(StringUtils::hasText).distinct().limit(6).collect(Collectors.toList());

        FinalSummaryResponse response = new FinalSummaryResponse();
        response.setSummary((request == null ? "该店铺" : request.getShopName()) + "整体口碑集中在：" + String.join("；", high));
        response.setAdvice("建议优先参考高频口碑，再结合距离、评分、人均消费综合判断。");
        response.setHighFrequencyPoints(high);
        response.setUniquePoints(unique);
        return response;
    }

    private IntentParseResponse fallbackIntent(IntentParseRequest request) {
        String query = request == null ? "" : Optional.ofNullable(request.getQuery()).orElse("");
        String lower = query.toLowerCase(Locale.ROOT);
        List<String> typeKeywords = new ArrayList<>();
        if (lower.contains("火锅")) typeKeywords.add("火锅");
        if (lower.contains("烧烤")) typeKeywords.add("烧烤");
        if (lower.contains("奶茶")) typeKeywords.add("茶");
        if (lower.contains("咖啡")) typeKeywords.add("咖啡");
        if (lower.contains("日料") || lower.contains("寿司")) typeKeywords.add("日");
        if (lower.contains("ktv")) typeKeywords.add("KTV");

        IntentParseResponse response = new IntentParseResponse();
        response.setIntentSummary("用户希望在附近找到符合口味的店铺");
        response.setTypeKeywords(typeKeywords);
        response.setIncludeKeywords(extractTokens(query));
        response.setExcludeKeywords(Collections.emptyList());
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
            score += 75;
            tags.add("违法违禁");
            reasons.add("文本包含疑似违法违禁关键词。");
        }
        if (AD_LINK_PATTERN.matcher(lower).find()) {
            score += 55;
            tags.add("广告引流");
            reasons.add("文本疑似包含广告推广或引流词。");
        }
        if (CONTACT_PATTERN.matcher(text).find() && containsAnyKeyword(lower, Arrays.asList("联系", "咨询", "加", "vx", "微信", "qq"))) {
            score += 35;
            tags.add("联系方式");
            reasons.add("文本疑似出现联系方式或导流信息。");
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
        if (score >= 70) {
            response.setPass(false);
            response.setRiskLevel("BLOCK");
            response.setSuggestion("内容触发高风险，请移除广告/联系方式/违法相关描述后再发布。");
        } else if (score >= 40) {
            response.setPass(true);
            response.setRiskLevel("REVIEW");
            response.setSuggestion("内容存在一定风险，建议先修改敏感表述后再发布。");
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
                .map(review -> "{id=" + review.getBlogId() + ",liked=" + review.getLiked() + ",text=" + sanitize(review.getTitle() + " " + review.getContent()) + "}")
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
        if (!StringUtils.hasText(response.getSummary())) response.setSummary("本组口碑整理完成。");
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
        if (response.getPass() == null) {
            response.setPass(!"BLOCK".equals(level));
        }
        if (response.getRiskTags() == null || response.getRiskTags().isEmpty()) {
            response.setRiskTags(fallback.getRiskTags());
        } else {
            response.setRiskTags(response.getRiskTags().stream().filter(StringUtils::hasText).distinct().limit(4).collect(Collectors.toList()));
        }
        if (response.getReasons() == null || response.getReasons().isEmpty()) {
            response.setReasons(fallback.getReasons());
        } else {
            response.setReasons(response.getReasons().stream().filter(StringUtils::hasText).distinct().limit(4).collect(Collectors.toList()));
        }
        if (!StringUtils.hasText(response.getSuggestion())) {
            response.setSuggestion(fallback.getSuggestion());
        }
        return response;
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
        String[] arr = text.replaceAll("[,，。！？!?.;；/\\\\]", " ").split("\\s+");
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
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
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
