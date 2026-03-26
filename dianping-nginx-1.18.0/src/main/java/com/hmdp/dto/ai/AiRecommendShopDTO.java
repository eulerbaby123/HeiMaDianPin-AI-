package com.hmdp.dto.ai;

import lombok.Data;

@Data
public class AiRecommendShopDTO {
    private Long id;
    private Long typeId;
    private String name;
    private String address;
    private String shopDesc;
    private Long avgPrice;
    private Integer score;
    private Integer comments;
    private Integer sold;
    private Double distance;
    private String reason;
}
