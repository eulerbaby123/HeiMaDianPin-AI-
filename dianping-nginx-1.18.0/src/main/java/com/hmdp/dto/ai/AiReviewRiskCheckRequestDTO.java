package com.hmdp.dto.ai;

import lombok.Data;

@Data
public class AiReviewRiskCheckRequestDTO {
    private String scene;
    private String title;
    private String content;
    private Long shopId;
}
