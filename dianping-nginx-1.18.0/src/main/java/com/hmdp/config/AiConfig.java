package com.hmdp.config;

import com.hmdp.config.properties.AiProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AiConfig {

    @Bean(name = "aiRestTemplate")
    public RestTemplate aiRestTemplate(AiProperties aiProperties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(aiProperties.getConnectTimeoutMs());
        factory.setReadTimeout(aiProperties.getReadTimeoutMs());
        return new RestTemplate(factory);
    }
}

