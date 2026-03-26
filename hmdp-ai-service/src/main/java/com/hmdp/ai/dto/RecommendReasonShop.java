package com.hmdp.ai.dto;

import lombok.Data;

@Data
public class RecommendReasonShop {
    private Long id;
    private String name;
    private String address;
    private Long avgPrice;
    private Integer score;
    private Double distance;
}

