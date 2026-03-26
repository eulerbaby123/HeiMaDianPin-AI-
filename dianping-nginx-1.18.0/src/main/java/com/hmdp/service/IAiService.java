package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.dto.ai.AiAssistantRequestDTO;
import com.hmdp.dto.ai.AiReviewRiskCheckRequestDTO;

public interface IAiService {
    Result getShopSummary(Long shopId, Boolean refresh);

    Result warmupShopSummary(Long shopId);

    Result assistantRecommend(AiAssistantRequestDTO requestDTO);

    Result checkReviewRisk(AiReviewRiskCheckRequestDTO requestDTO);
}
