package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.config.KnowledgeProperties;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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

    private final ObjectMapper objectMapper;

    public AiClient(RestTemplate restTemplate, KnowledgeProperties properties, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.baseUrl = properties.getAiServiceBaseUrl();
        this.objectMapper = objectMapper;
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
    public Map<String, Object> put(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Object> request = new HttpEntity<Object>(body, headers);
        return restTemplate.exchange(baseUrl + path, HttpMethod.PUT, request, Map.class).getBody();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> delete(String path) {
        return restTemplate.exchange(baseUrl + path, HttpMethod.DELETE, null, Map.class).getBody();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> parse(MultipartFile file) throws IOException {
        return parse(file, null);
    }

    /**
     * Parse with an explicit chunking policy: the knowledge base decides how its own
     * documents are cut, and the parser only applies it.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> parse(MultipartFile file, Map<String, Object> policy) throws IOException {
        return postFile(AiApiPaths.PARSE_DOCUMENT, file, policy);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> inspectOkf(MultipartFile file) throws IOException {
        return postFile(AiApiPaths.INSPECT_OKF, file, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postFile(String path, MultipartFile file, Map<String, Object> policy) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<String, Object>();
        Resource resource = file.getResource();
        body.add("file", resource);
        if (policy != null && !policy.isEmpty()) {
            body.add("policy", objectMapper.writeValueAsString(policy));
        }
        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<MultiValueMap<String, Object>>(body, headers);
        return restTemplate.postForObject(baseUrl + path, request, Map.class);
    }
}
