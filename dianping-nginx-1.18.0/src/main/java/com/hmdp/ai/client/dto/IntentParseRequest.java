package com.hmdp.ai.client.dto;

import lombok.Data;

import java.util.List;

@Data
public class IntentParseRequest {
    private String query;
    private List<String> availableTypes;
}

