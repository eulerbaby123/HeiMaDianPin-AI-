package com.hmdp.ai.client.dto;

import lombok.Data;

import java.util.List;

@Data
public class IntentParseResponse {
    private String intentSummary;
    private List<String> typeKeywords;
    private List<String> includeKeywords;
    private List<String> excludeKeywords;
}

