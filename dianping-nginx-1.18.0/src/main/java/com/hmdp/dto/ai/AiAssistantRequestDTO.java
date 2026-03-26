package com.hmdp.dto.ai;

import lombok.Data;

@Data
public class AiAssistantRequestDTO {
    private String query;
    private Double x;
    private Double y;
    private Long currentTypeId;
}

