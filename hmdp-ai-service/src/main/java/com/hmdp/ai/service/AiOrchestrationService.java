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

    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;
    private final boolean aiEnabled;

    public AiOrchestrationService(ChatClient.Builder chatClientBuilder,
                                  ObjectMapper objectMapper,
                                  @Value("${spring.ai.openai.api-key:}") String apiKey) {
        this.chatClientBuilder = chatClientBuilder;
        this.objectMapper = objectMapper;
        this.aiEnabled = StringUtils.hasText(apiKey);
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
                + "用户输入：" + request.getQuery() + "；候选店铺：" + toCompactReasonShops(request.getShops());
        RecommendReasonResponse response = callAiForJson(system, user, RecommendReasonResponse.class);
        if (response == null || response.getReasonByShopId() == null || response.getReasonByShopId().isEmpty()) {
            return fallbackReason(request);
        }
        return response;
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
            String reason = distance + "，评分" + score + "，与“" + request.getQuery() + "”匹配度较高。";
            map.put(String.valueOf(shop.getId()), reason);
        }
        RecommendReasonResponse response = new RecommendReasonResponse();
        response.setReasonByShopId(map);
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
                .map(shop -> "{id=" + shop.getId() + ",name=" + shop.getName() + ",distance=" + shop.getDistance() + ",score=" + shop.getScore() + ",avg=" + shop.getAvgPrice() + "}")
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
}

