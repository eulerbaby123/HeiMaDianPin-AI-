package com.hmdp.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "hmdp.ai")
public class AiProperties {
    private String baseUrl = "http://127.0.0.1:8090";
    private int connectTimeoutMs = 1500;
    private int readTimeoutMs = 8000;

    private int summaryMaxBlogs = 120;
    private int summaryGroupSize = 20;
    private long summaryTtlMinutes = 720L;
    private long chunkTtlMinutes = 720L;

    private int assistantRadiusMeters = 5000;
    private int assistantCandidateLimit = 20;
    private int assistantTopN = 5;
    private long assistantTtlMinutes = 10L;

    private long reviewRiskTtlMinutes = 30L;
}
