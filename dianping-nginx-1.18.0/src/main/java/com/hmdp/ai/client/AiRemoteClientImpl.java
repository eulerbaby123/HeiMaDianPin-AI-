package com.hmdp.ai.client;

import com.hmdp.ai.client.dto.*;
import com.hmdp.config.properties.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
public class AiRemoteClientImpl implements AiRemoteClient {

    private final RestTemplate restTemplate;
    private final AiProperties aiProperties;

    public AiRemoteClientImpl(@Qualifier("aiRestTemplate") RestTemplate restTemplate, AiProperties aiProperties) {
        this.restTemplate = restTemplate;
        this.aiProperties = aiProperties;
    }

    @Override
    public ChunkSummaryResponse summarizeChunk(ChunkSummaryRequest request) {
        return post("/internal/ai/summarize/chunk", request, ChunkSummaryResponse.class);
    }

    @Override
    public FinalSummaryResponse summarizeFinal(FinalSummaryRequest request) {
        return post("/internal/ai/summarize/final", request, FinalSummaryResponse.class);
    }

    @Override
    public IntentParseResponse parseIntent(IntentParseRequest request) {
        return post("/internal/ai/intent/parse", request, IntentParseResponse.class);
    }

    @Override
    public RecommendReasonResponse recommendReason(RecommendReasonRequest request) {
        return post("/internal/ai/recommend/reason", request, RecommendReasonResponse.class);
    }

    private <T> T post(String path, Object req, Class<T> clazz) {
        try {
            String url = aiProperties.getBaseUrl() + path;
            ResponseEntity<T> responseEntity = restTemplate.postForEntity(url, req, clazz);
            return responseEntity.getBody();
        } catch (Exception e) {
            log.warn("call ai service failed, path={}, error={}", path, e.getMessage());
            return null;
        }
    }
}

