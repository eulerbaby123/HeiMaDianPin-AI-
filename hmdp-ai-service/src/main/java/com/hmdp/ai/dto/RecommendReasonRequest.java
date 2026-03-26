package com.hmdp.ai.dto;

import lombok.Data;

import java.util.List;

@Data
public class RecommendReasonRequest {
    private String query;
    private List<RecommendReasonShop> shops;
}

