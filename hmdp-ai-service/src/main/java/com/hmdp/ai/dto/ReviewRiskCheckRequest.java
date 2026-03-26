package com.hmdp.ai.dto;

import lombok.Data;

@Data
public class ReviewRiskCheckRequest {
    private String scene;
    private String title;
    private String content;
    private String shopName;
    private String shopDesc;
}
