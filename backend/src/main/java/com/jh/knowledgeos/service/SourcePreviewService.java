package com.jh.knowledgeos.service;

import com.jh.knowledgeos.domain.SourceFile;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the console preview of an ingested source.
 *
 * <p>Operators need to see the file that was imported, not only the knowledge derived from it.
 * Text formats are previewed from the stored original; binary formats (PDF, DOCX) fall back to
 * the parsed document text, and a missing file degrades to the same fallback instead of
 * failing, because preview is a console aid rather than part of the ingestion contract.</p>
 *
 * <p>Every read is scoped by the caller's permission tags, so a source the caller cannot see
 * is indistinguishable from one that does not exist.</p>
 */
@Service
public class SourcePreviewService {
    private static final int DEFAULT_LIMIT = 20000;
    private static final int MAX_LIMIT = 200000;

    private final KnowledgeMapper mapper;
    private final SourceFileStorageService storage;

    public SourcePreviewService(KnowledgeMapper mapper, SourceFileStorageService storage) {
        this.mapper = mapper;
        this.storage = storage;
    }

    /**
     * Preview one source: original text when it can be rendered, parsed text otherwise.
     *
     * @return a map that always carries {@code available}; {@code false} means the source is
     *         unknown to this knowledge base or outside the caller's permission scope
     */
    public Map<String, Object> preview(
            Long kb, Long sourceId, Integer requestedLimit, List<String> permissionTags) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("knowledgeBaseId", kb);
        result.put("sourceId", sourceId);
        Map<String, Object> row = mapper.getSourcePreview(kb, sourceId, permissionTags);
        if (row == null) {
            result.put("available", false);
            result.put("reason", "资料不存在，或不在当前权限范围内");
            return result;
        }
        int limit = normalizeLimit(requestedLimit);
        String name = text(row.get("name"));
        result.put("available", true);
        result.put("source", sourceMeta(row));
        result.put("document", documentMeta(row));
        result.put("fileUrl", "/api/knowledge/sources/" + kb + "/" + sourceId + "/file");

        Path file = storage.resolve(text(row.get("storagePath")));
        String parsed = text(row.get("rawText"));
        result.put("stored", file != null);
        result.put("parsedAvailable", !parsed.isEmpty());
        result.put("limit", limit);
        if (file == null) {
            result.put("previewType", "MISSING");
            applyParsed(result, parsed, limit);
            return result;
        }
        result.put("sizeBytes", sizeOf(file));
        if (storage.isText(name, storage.head(file))) {
            result.put("previewType", "TEXT");
            result.put("mode", "SOURCE");
            putContent(result, readOriginal(file, limit), limit);
            return result;
        }
        result.put("previewType", "BINARY");
        applyParsed(result, parsed, limit);
        return result;
    }

    /** Stream the stored original upload, or null when it is unknown, unreadable or out of scope. */
    public SourceFile file(Long kb, Long sourceId, List<String> permissionTags) {
        Map<String, Object> row = mapper.getSourcePreview(kb, sourceId, permissionTags);
        if (row == null) return null;
        Path path = storage.resolve(text(row.get("storagePath")));
        if (path == null) return null;
        return new SourceFile(
                text(row.get("name")),
                contentType(text(row.get("name")), path),
                new FileSystemResource(path.toFile()));
    }

    private void applyParsed(Map<String, Object> result, String parsed, int limit) {
        if (parsed.isEmpty()) {
            result.put("mode", "NONE");
            result.put("content", null);
            result.put("truncated", false);
            result.put("totalChars", 0);
            return;
        }
        result.put("mode", "PARSED");
        putContent(result, parsed, limit);
    }

    private void putContent(Map<String, Object> result, String content, int limit) {
        result.put("totalChars", content.length());
        result.put("truncated", content.length() > limit);
        result.put("content", content.length() > limit ? content.substring(0, limit) : content);
    }

    private String readOriginal(Path file, int limit) {
        try {
            return storage.readText(file, limit + 1);
        } catch (IOException ex) {
            return "";
        }
    }

    private Map<String, Object> sourceMeta(Map<String, Object> row) {
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("id", row.get("sourceId"));
        meta.put("sourceCode", text(row.get("sourceCode")));
        meta.put("name", text(row.get("name")));
        meta.put("sourceType", text(row.get("sourceType")));
        meta.put("sourceUri", text(row.get("sourceUri")));
        meta.put("authorityScore", row.get("authorityScore"));
        meta.put("status", text(row.get("status")));
        meta.put("createdTime", row.get("createdTime"));
        return meta;
    }

    private Map<String, Object> documentMeta(Map<String, Object> row) {
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("id", row.get("documentId"));
        meta.put("documentCode", text(row.get("documentCode")));
        meta.put("title", text(row.get("documentTitle")));
        meta.put("mimeType", text(row.get("mimeType")));
        meta.put("parseStatus", text(row.get("parseStatus")));
        meta.put("chunkCount", row.get("chunkCount"));
        return meta;
    }

    private int normalizeLimit(Integer requested) {
        if (requested == null || requested <= 0) return DEFAULT_LIMIT;
        return Math.min(requested, MAX_LIMIT);
    }

    private long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException ex) {
            return 0L;
        }
    }

    private String contentType(String name, Path path) {
        String extension = storage.extension(name);
        if ("md".equals(extension) || "markdown".equals(extension) || "txt".equals(extension)) {
            return "text/plain; charset=UTF-8";
        }
        if ("html".equals(extension) || "htm".equals(extension)) return "text/html; charset=UTF-8";
        if ("json".equals(extension)) return "application/json; charset=UTF-8";
        if ("csv".equals(extension)) return "text/csv; charset=UTF-8";
        if ("pdf".equals(extension)) return "application/pdf";
        try {
            String probed = Files.probeContentType(path);
            if (probed != null && !probed.isEmpty()) return probed;
        } catch (IOException ex) {
            // Fall through to the generic binary type: the browser decides what to do with it.
        }
        return "application/octet-stream";
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
