package com.hmdp.ai.dto;

import lombok.Data;

import java.util.List;

@Data
public class ChunkSummaryResponse {
    private String summary;
    private List<String> highFrequencyPoints;
    private List<String> uniquePoints;
    private List<String> keywords;
}

