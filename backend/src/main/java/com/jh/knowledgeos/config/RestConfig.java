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
        return builder
                .setConnectTimeout(Duration.ofMillis(properties.getAiConnectTimeoutMs()))
                .setReadTimeout(Duration.ofMillis(properties.getAiReadTimeoutMs()))
                .additionalInterceptors(traceInterceptor())
                .build();
    }

    /**
     * Connector-only client that accepts self-signed / hostname-mismatched certificates.
     *
     * <p>Kept as a separate bean so the trust decision stays visible: the default RestTemplate keeps
     * validating normally, and only a connector that asks for it (or the global switch) goes
     * through this one.</p>
     */
    @Bean
    public RestTemplate connectorTrustAllRestTemplate(KnowledgeProperties properties) {
        TrustAllSslRequestFactory factory = new TrustAllSslRequestFactory();
        factory.setConnectTimeout(properties.getAiConnectTimeoutMs());
        factory.setReadTimeout(properties.getAiReadTimeoutMs());
        RestTemplate template = new RestTemplate(factory);
        template.getInterceptors().add(traceInterceptor());
        return template;
    }

    /** Carries the request trace id outbound so a failing upstream call can be found in its own logs. */
    private ClientHttpRequestInterceptor traceInterceptor() {
        return (request, body, execution) -> {
            String traceId = MDC.get("traceId");
            if (traceId != null && !traceId.trim().isEmpty()) {
                request.getHeaders().set(TraceIdFilter.HEADER, traceId);
            }
            return execution.execute(request, body);
        };
    }
}
