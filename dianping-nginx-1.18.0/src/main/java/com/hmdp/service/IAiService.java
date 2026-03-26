package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.dto.ai.AiAssistantRequestDTO;

public interface IAiService {
    Result getShopSummary(Long shopId, Boolean refresh);

    Result warmupShopSummary(Long shopId);

    Result assistantRecommend(AiAssistantRequestDTO requestDTO);
}

