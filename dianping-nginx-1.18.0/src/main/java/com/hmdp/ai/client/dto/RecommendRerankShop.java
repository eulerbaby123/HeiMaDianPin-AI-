package com.hmdp.ai.client.dto;

import lombok.Data;

@Data
public class RecommendRerankShop {
    private Long id;
    private String name;
    private String typeName;
    private String address;
    private String shopDesc;
    private Long avgPrice;
    private Integer score;
    private Integer comments;
    private Integer sold;
    private Double distance;
    private Double baseRankScore;
}

