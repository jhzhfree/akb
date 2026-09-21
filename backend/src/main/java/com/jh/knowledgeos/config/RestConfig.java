package com.jh.knowledgeos.config;

import org.slf4j.MDC;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestTemplate;
import java.time.Duration;

/**
 * Spring MVC configuration for outbound/inbound API behavior.
 */
@Configuration
public class RestConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder, KnowledgeProperties properties) {
        ClientHttpRequestInterceptor traceInterceptor = (request, body, execution) -> {
            String traceId = MDC.get("traceId");
            if (traceId != null && !traceId.trim().isEmpty()) {
                request.getHeaders().set(TraceIdFilter.HEADER, traceId);
            }
            return execution.execute(request, body);
        };
        return builder.setConnectTimeout(Duration.ofMillis(properties.getAiConnectTimeoutMs())).setReadTimeout(Duration.ofMillis(properties.getAiReadTimeoutMs())).additionalInterceptors(traceInterceptor).build();
    }
}
