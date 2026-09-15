package com.jh.knowledgeos.service;

import com.jh.knowledgeos.config.KnowledgeProperties;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * HTTP client adapter for synchronous calls from the backend to the AI service.
 */
@Service
public class AiClient {
    private final RestTemplate restTemplate;
    private final String baseUrl;

    public AiClient(RestTemplate restTemplate, KnowledgeProperties properties) {
        this.restTemplate = restTemplate;
        this.baseUrl = properties.getAiServiceBaseUrl();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> post(String path, Object body) {
        return restTemplate.postForObject(baseUrl + path, body, Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> get(String path) {
        return restTemplate.getForObject(baseUrl + path, Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> parse(MultipartFile file) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<String, Object>();
        Resource resource = file.getResource();
        body.add("file", resource);

        HttpEntity<MultiValueMap<String, Object>> request =
                new HttpEntity<MultiValueMap<String, Object>>(body, headers);
        return restTemplate.postForObject(baseUrl + AiApiPaths.PARSE_DOCUMENT, request, Map.class);
    }
}
