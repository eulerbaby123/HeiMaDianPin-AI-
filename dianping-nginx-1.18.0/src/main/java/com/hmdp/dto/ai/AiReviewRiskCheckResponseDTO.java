package com.hmdp.dto.ai;

import lombok.Data;

import java.util.List;

@Data
public class AiReviewRiskCheckResponseDTO {
    private Boolean pass;
    private String riskLevel;
    private Integer riskScore;
    private List<String> riskTags;
    private List<String> reasons;
    private String suggestion;
    private Boolean fromCache;
    private String generatedAt;
}
