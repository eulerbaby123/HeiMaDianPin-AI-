package com.hmdp.ai.dto;

import lombok.Data;

import java.util.List;

@Data
public class ReviewRiskCheckResponse {
    private Boolean pass;
    private String riskLevel;
    private Integer riskScore;
    private List<String> riskTags;
    private List<String> reasons;
    private String suggestion;
}
