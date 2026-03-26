package com.hmdp.ai.client.dto;

import lombok.Data;

import java.util.List;

@Data
public class FinalSummaryResponse {
    private String summary;
    private String advice;
    private List<String> highFrequencyPoints;
    private List<String> uniquePoints;
}

