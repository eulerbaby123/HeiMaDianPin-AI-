package com.hmdp.dto.ai;

import lombok.Data;

import java.util.List;

@Data
public class AiShopSummaryDTO {
    private Long shopId;
    private String shopName;
    private String engine;
    private Integer reviewCount;
    private Integer chunkCount;
    private String finalSummary;
    private String advice;
    private List<String> highFrequencyHighlights;
    private List<String> uniqueHighlights;
    private String generatedAt;
    private String fingerprint;
    private Boolean fromCache;
}
