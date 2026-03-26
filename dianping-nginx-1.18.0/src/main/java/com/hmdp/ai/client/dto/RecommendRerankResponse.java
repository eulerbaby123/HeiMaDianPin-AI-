package com.hmdp.ai.client.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class RecommendRerankResponse {
    private String engine;
    private List<Long> rankedShopIds;
    private Map<String, String> reasonByShopId;
    private Map<String, Integer> scoreByShopId;
}
