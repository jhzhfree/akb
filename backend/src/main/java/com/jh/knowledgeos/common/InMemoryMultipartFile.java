package com.jh.knowledgeos.common;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A {@link MultipartFile} backed by bytes already in memory.
 *
 * <p>Reprocessing a failed or dirty document means re-parsing the original upload, but the parser
 * client speaks {@code MultipartFile} while the retry path only has the bytes it reads back from
 * source storage. Wrapping them keeps one parsing path instead of duplicating the whole ingestion
 * flow for retries.</p>
 */
public class InMemoryMultipartFile implements MultipartFile {

    private final String name;

    private final String originalFilename;

    private final String contentType;

    private final byte[] bytes;

    public InMemoryMultipartFile(String originalFilename, String contentType, byte[] bytes) {
        this.name = "file";
        this.originalFilename = originalFilename;
        this.contentType = contentType == null || contentType.isEmpty() ? "application/octet-stream" : contentType;
        this.bytes = bytes == null ? new byte[0] : bytes;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getOriginalFilename() {
        return originalFilename;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return bytes.length == 0;
    }

    @Override
    public long getSize() {
        return bytes.length;
    }

    @Override
    public byte[] getBytes() {
        return bytes;
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public Resource getResource() {
        return new ByteArrayResource(bytes) {

            @Override
            public String getFilename() {
                return originalFilename;
            }
        };
    }

    @Override
    public void transferTo(File dest) throws IOException, IllegalStateException {
        Files.write(dest.toPath(), bytes);
    }

    /**
     * Convenience for callers that already hold a resolved storage path.
     */
    public static InMemoryMultipartFile of(Path path, String originalFilename, String contentType) throws IOException {
        return new InMemoryMultipartFile(originalFilename, contentType, Files.readAllBytes(path));
    }
}
