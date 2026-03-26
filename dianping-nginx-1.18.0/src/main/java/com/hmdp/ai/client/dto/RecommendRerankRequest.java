package com.hmdp.ai.client.dto;

import lombok.Data;

import java.util.List;

@Data
public class RecommendRerankRequest {
    private String query;
    private List<String> includeKeywords;
    private List<String> excludeKeywords;
    private Integer topN;
    private List<RecommendRerankShop> shops;
}

