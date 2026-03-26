package com.hmdp.ai.controller;

import com.hmdp.ai.dto.*;
import com.hmdp.ai.service.AiOrchestrationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/ai")
public class InternalAiController {

    private final AiOrchestrationService aiOrchestrationService;

    public InternalAiController(AiOrchestrationService aiOrchestrationService) {
        this.aiOrchestrationService = aiOrchestrationService;
    }

    @PostMapping("/summarize/chunk")
    public ChunkSummaryResponse summarizeChunk(@RequestBody @Valid ChunkSummaryRequest request) {
        return aiOrchestrationService.summarizeChunk(request);
    }

    @PostMapping("/summarize/final")
    public FinalSummaryResponse summarizeFinal(@RequestBody @Valid FinalSummaryRequest request) {
        return aiOrchestrationService.summarizeFinal(request);
    }

    @PostMapping("/intent/parse")
    public IntentParseResponse parseIntent(@RequestBody @Valid IntentParseRequest request) {
        return aiOrchestrationService.parseIntent(request);
    }

    @PostMapping("/recommend/reason")
    public RecommendReasonResponse recommendReason(@RequestBody @Valid RecommendReasonRequest request) {
        return aiOrchestrationService.recommendReason(request);
    }

    @PostMapping("/review/risk-check")
    public ReviewRiskCheckResponse reviewRiskCheck(@RequestBody @Valid ReviewRiskCheckRequest request) {
        return aiOrchestrationService.reviewRiskCheck(request);
    }
}
