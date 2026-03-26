package com.hmdp.ai.dto;

import lombok.Data;

import java.util.Map;

@Data
public class RecommendReasonResponse {
    private Map<String, String> reasonByShopId;
}

