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
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * HTTP client adapter for synchronous calls from the backend to the AI service.
 */
@Service
public class AiClient {

    private final RestTemplate restTemplate;

    private final String baseUrl;

    private final int readTimeoutMs;

    private final ObjectMapper objectMapper;

    public AiClient(RestTemplate restTemplate, KnowledgeProperties properties, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.baseUrl = properties.getAiServiceBaseUrl();
        this.readTimeoutMs = Math.max(properties.getAiReadTimeoutMs(), 1000);
        this.objectMapper = objectMapper;
    }

    /**
     * Posts a JSON body and consumes a server-sent-event response frame by frame.
     *
     * <p>This deliberately does not go through {@link RestTemplate}: a streaming response has to
     * be read while it is being produced, and every abstraction in front of the socket wants to
     * buffer the body first. The connection is opened here, read line by line, and the frames
     * handed to the handler as they arrive.</p>
     */
    public void postStream(String path, Object body, StreamHandler handler) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setDoOutput(true);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(readTimeoutMs);
            // Buffer the payload up front so the request can be sent with a Content-Length
            // header. The alternative — chunked transfer with an explicit terminating chunk —
            // works for some servers and not for others; getting it wrong here hangs the call
            // until the read timeout, which is hard to tell apart from a real slow LLM call.
            byte[] payloadBytes = objectMapper.writeValueAsBytes(body);
            connection.setRequestProperty("Content-Length", String.valueOf(payloadBytes.length));
            connection.setFixedLengthStreamingMode(payloadBytes.length);
            OutputStream out = connection.getOutputStream();
            out.write(payloadBytes);
            out.flush();
            out.close();
            int status = connection.getResponseCode();
            if (status >= 400) {
                throw new IllegalStateException("AI 服务返回 " + status);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            String event = null;
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    dispatch(handler, event, data.toString());
                    event = null;
                    data.setLength(0);
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).trim());
                }
            }
            dispatch(handler, event, data.toString());
        } catch (IOException e) {
            throw new IllegalStateException("AI 流式服务不可用：" + e.getMessage(), e);
        } catch (Exception e) {
            // A handler that fails has to abort the stream: continuing would leave the caller
            // with half a result and no way to tell it is half.
            throw new IllegalStateException("AI 流式事件处理失败：" + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void dispatch(StreamHandler handler, String event, String data) throws Exception {
        if (event == null || data.isEmpty()) {
            return;
        }
        Object payload = data;
        try {
            payload = objectMapper.readValue(data, Object.class);
            if (payload instanceof Map && ((Map<String, Object>) payload).isEmpty()) {
                payload = data;
            }
        } catch (Exception ignored) {
            // Plain text frames (answer chunks) are passed through as they are.
            payload = data;
        }
        handler.onEvent(event, payload);
    }

    /**
     * Receives one decoded SSE frame at a time.
     */
    public interface StreamHandler {

        void onEvent(String event, Object payload) throws Exception;
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
