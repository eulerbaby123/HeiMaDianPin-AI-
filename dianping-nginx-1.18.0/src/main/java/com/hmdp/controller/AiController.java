package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.dto.ai.AiAssistantRequestDTO;
import com.hmdp.service.IAiService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@RestController
@RequestMapping("/ai")
public class AiController {

    @Resource
    private IAiService aiService;

    @GetMapping("/shop/{shopId}/summary")
    public Result queryShopSummary(@PathVariable("shopId") Long shopId,
                                   @RequestParam(value = "refresh", required = false) Boolean refresh) {
        return aiService.getShopSummary(shopId, refresh);
    }

    @PostMapping("/shop/{shopId}/summary/warmup")
    public Result warmupShopSummary(@PathVariable("shopId") Long shopId) {
        return aiService.warmupShopSummary(shopId);
    }

    @PostMapping("/assistant/recommend")
    public Result assistantRecommend(@RequestBody AiAssistantRequestDTO requestDTO) {
        return aiService.assistantRecommend(requestDTO);
    }
}

