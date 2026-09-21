package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.jh.knowledgeos.domain.SourceFile;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourcePreviewServiceTest {

    @TempDir
    Path storageRoot;

    private KnowledgeMapper mapperReturning(Map<String, Object> row) {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        when(mapper.getSourcePreview(any(), any(), any())).thenReturn(row);
        return mapper;
    }

    private SourcePreviewService service(KnowledgeMapper mapper) {
        return new SourcePreviewService(mapper, new SourceFileStorageService(storageRoot.toString()));
    }

    private Map<String, Object> row(String name, String rawText) {
        Map<String, Object> row = new HashMap<String, Object>();
        row.put("sourceId", 9L);
        row.put("name", name);
        row.put("sourceType", name.substring(name.lastIndexOf('.')));
        row.put("sourceUri", "upload://" + name);
        row.put("status", "ACTIVE");
        row.put("rawText", rawText);
        return row;
    }

    @Test
    void textFileIsPreviewedFromTheOriginalUpload() throws Exception {
        SourceFileStorageService storage = new SourceFileStorageService(storageRoot.toString());
        String relative = storage.store(1L, "SRC_1", "policy.md", "# 报销标准\n\n市内交通 80 元/天".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> row = row("policy.md", "归一化后的文本");
        row.put("storagePath", relative);
        Map<String, Object> preview = service(mapperReturning(row)).preview(1L, 9L, null, null);
        assertEquals("TEXT", preview.get("previewType"));
        assertEquals("SOURCE", preview.get("mode"));
        assertEquals(Boolean.TRUE, preview.get("stored"));
        assertEquals("# 报销标准\n\n市内交通 80 元/天", preview.get("content"));
        assertEquals(Boolean.FALSE, preview.get("truncated"));
    }

    @Test
    void binaryFileFallsBackToParsedText() {
        SourceFileStorageService storage = new SourceFileStorageService(storageRoot.toString());
        String relative = storage.store(1L, "SRC_2", "scan.pdf", "%PDF-1.4 binary".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> row = row("scan.pdf", "解析出来的 PDF 文本");
        row.put("storagePath", relative);
        Map<String, Object> preview = service(mapperReturning(row)).preview(1L, 9L, null, null);
        assertEquals("BINARY", preview.get("previewType"));
        assertEquals("PARSED", preview.get("mode"));
        assertEquals("解析出来的 PDF 文本", preview.get("content"));
    }

    @Test
    void missingFileStillShowsParsedText() {
        Map<String, Object> preview = service(mapperReturning(row("legacy.md", "历史解析文本"))).preview(1L, 9L, null, null);
        assertEquals("MISSING", preview.get("previewType"));
        assertEquals("PARSED", preview.get("mode"));
        assertEquals(Boolean.FALSE, preview.get("stored"));
        assertEquals("历史解析文本", preview.get("content"));
    }

    @Test
    void longContentIsTruncatedToTheRequestedLimit() {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < 500; index++) {
            text.append("知识");
        }
        Map<String, Object> preview = service(mapperReturning(row("big.md", text.toString()))).preview(1L, 9L, 100, null);
        assertEquals(Boolean.TRUE, preview.get("truncated"));
        assertEquals(100, String.valueOf(preview.get("content")).length());
        assertEquals(1000, preview.get("totalChars"));
    }

    @Test
    void unknownOrDeniedSourceIsNotPreviewed() {
        Map<String, Object> preview = service(mapperReturning(null)).preview(1L, 9L, null, null);
        assertEquals(Boolean.FALSE, preview.get("available"));
        assertNotNull(preview.get("reason"));
    }

    @Test
    void storedOriginalCanBeStreamedBack() {
        SourceFileStorageService storage = new SourceFileStorageService(storageRoot.toString());
        String relative = storage.store(1L, "SRC_3", "notes.md", "notes".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> row = row("notes.md", "解析文本");
        row.put("storagePath", relative);
        SourceFile file = service(mapperReturning(row)).file(1L, 9L, null);
        assertNotNull(file);
        assertEquals("notes.md", file.getName());
        assertEquals("text/plain; charset=UTF-8", file.getContentType());
        assertTrue(file.getResource().exists());
    }

    @Test
    void pathsOutsideTheStorageRootAreRefused() throws Exception {
        SourceFileStorageService storage = new SourceFileStorageService(storageRoot.toString());
        Files.write(storageRoot.resolve("inside.txt"), "ok".getBytes(StandardCharsets.UTF_8));
        assertNotNull(storage.resolve("inside.txt"));
        assertNull(storage.resolve("../outside.txt"));
        assertFalse(storage.isText("scan.pdf", new byte[] { 0x25, 0x50, 0x44, 0x46 }));
        assertTrue(storage.isText("policy.md", new byte[] { 0x23 }));
    }
}
