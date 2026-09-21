package com.jh.knowledgeos.service;

import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.domain.KnowledgeBase;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * External contract over the platform's own capabilities.
 *
 * <p>Everything here delegates to the same application services the console uses, so an
 * external caller can never reach behaviour the UI cannot reach, and vice versa.</p>
 */
@Service
public class ExternalKnowledgeFacade {

    private final KnowledgeService knowledgeService;

    private final KnowledgeBaseService knowledgeBaseService;

    public ExternalKnowledgeFacade(KnowledgeService knowledgeService, KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeService = knowledgeService;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    /**
     * Visible workspaces for the caller: bound key sees its own, otherwise the public ones.
     */
    public List<Map<String, Object>> bases(Long boundKb) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (boundKb != null) {
            KnowledgeBase base = knowledgeBaseService.get(boundKb);
            if (base != null) {
                rows.add(toBaseRow(base));
            }
            return rows;
        }
        for (KnowledgeBase base : knowledgeBaseService.list()) {
            rows.add(toBaseRow(base));
        }
        return rows;
    }

    /**
     * Query on behalf of an external credential.
     *
     * <p>The credential and the channel tag travel with the request so the RETRIEVAL_EXECUTED event
     * published downstream names its origin — otherwise an operator watching the stream could not
     * tell a console search from an MCP-driven one.</p>
     */
    public Map<String, Object> query(ApiKey key, Long kb, String question, Integer topK, List<String> permissionTags, String source) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("knowledgeBaseId", kb);
        payload.put("query", question);
        payload.put("topK", topK == null || topK < 1 ? 10 : Math.min(topK, 50));
        payload.put("source", source == null ? PlatformEventPublisher.SOURCE_REST : source);
        if (key != null) {
            payload.put("externalKeyCode", key.getKeyCode());
            payload.put("externalKeyName", key.getName());
        }
        if (permissionTags != null && !permissionTags.isEmpty()) {
            payload.put("permissionTags", permissionTags);
        }
        return knowledgeService.query(payload);
    }

    /**
     * Legacy signature kept for internal callers that have no credential to report.
     */
    public Map<String, Object> query(Long kb, String question, Integer topK, List<String> permissionTags) throws Exception {
        return query(null, kb, question, topK, permissionTags, PlatformEventPublisher.SOURCE_REST);
    }

    /**
     * Retrieval-only surface: the same pipeline as query minus answer generation detail, so
     * systems that already own their own prompt can assemble context themselves.
     */
    public Map<String, Object> search(ApiKey key, Long kb, String question, Integer topK, List<String> permissionTags, String source) throws Exception {
        Map<String, Object> result = query(key, kb, question, topK, permissionTags, source);
        Map<String, Object> search = new LinkedHashMap<String, Object>();
        search.put("queryId", result.get("queryId"));
        search.put("knowledgeBaseId", kb);
        search.put("question", question);
        search.put("strategies", result.get("strategies"));
        search.put("router", result.get("router"));
        search.put("rewrite", result.get("rewrite"));
        search.put("recall", result.get("recall"));
        search.put("reranker", result.get("reranker"));
        search.put("candidates", result.get("candidates"));
        search.put("citations", result.get("citations"));
        return search;
    }

    /**
     * Legacy signature kept for internal callers that have no credential to report.
     */
    public Map<String, Object> search(Long kb, String question, Integer topK, List<String> permissionTags) throws Exception {
        return search(null, kb, question, topK, permissionTags, PlatformEventPublisher.SOURCE_REST);
    }

    public Object cards(Long kb, List<String> permissionTags) {
        return knowledgeService.cards(kb, tags(permissionTags));
    }

    public Object claims(Long kb, List<String> permissionTags) {
        return knowledgeService.claims(kb, tags(permissionTags));
    }

    public Object objectDetail(Long kb, String type, String code, List<String> permissionTags) {
        return knowledgeService.knowledgeDetail(kb, type.toUpperCase(), code, tags(permissionTags));
    }

    public Object graph(Long kb, String code, Integer depth, Integer limit, List<String> permissionTags) {
        return knowledgeService.graph(kb, code, tags(permissionTags), depth, limit);
    }

    public Object trace(String queryId) {
        return knowledgeService.trace(queryId);
    }

    public Object overview(Long kb, List<String> permissionTags) {
        return knowledgeService.overview(kb, tags(permissionTags));
    }

    /**
     * Ingests a text payload by presenting it to the console's own upload pipeline.
     */
    public Map<String, Object> ingestText(Long kb, String title, String text, String permissionTags) throws Exception {
        String name = (title == null || title.trim().isEmpty() ? "external-ingest" : title.trim());
        if (!name.toLowerCase().endsWith(".txt") && !name.toLowerCase().endsWith(".md")) {
            name = name + ".txt";
        }
        MultipartFile file = new BytesMultipartFile(name, text == null ? "" : text);
        return knowledgeService.upload(kb, file, null, permissionTags);
    }

    private List<String> tags(List<String> permissionTags) {
        return permissionTags == null ? new ArrayList<String>() : permissionTags;
    }

    private Map<String, Object> toBaseRow(KnowledgeBase base) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", base.getId());
        row.put("baseCode", base.getBaseCode());
        row.put("name", base.getName());
        row.put("description", base.getDescription());
        row.put("status", base.getStatus());
        row.put("visibility", base.getVisibility());
        row.put("createdByName", base.getCreatedByName());
        return row;
    }

    /**
     * Minimal in-memory upload so text ingestion reuses the console pipeline unchanged.
     */
    private static class BytesMultipartFile implements MultipartFile {

        private final String name;

        private final byte[] content;

        BytesMultipartFile(String name, String text) {
            this.name = name;
            this.content = text.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getOriginalFilename() {
            return name;
        }

        @Override
        public String getContentType() {
            return "text/plain";
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() {
            return content;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(File dest) throws IOException, IllegalStateException {
            java.nio.file.Files.write(dest.toPath(), content);
        }
    }
}
