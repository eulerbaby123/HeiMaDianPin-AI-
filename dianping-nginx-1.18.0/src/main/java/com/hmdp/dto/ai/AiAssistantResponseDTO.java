package com.hmdp.dto.ai;

import lombok.Data;

import java.util.List;

@Data
public class AiAssistantResponseDTO {
    private String query;
    private String intentSummary;
    private List<String> keywords;
    private List<AiRecommendShopDTO> recommendShops;
    private String generatedAt;
    private Boolean fromCache;
}

