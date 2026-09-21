package com.jh.knowledgeos.domain;

import org.springframework.core.io.Resource;

/**
 * A stored original upload, ready to be streamed back to a caller.
 *
 * <p>The resource is handed to Spring for streaming instead of being loaded into
 * memory: source files can reach the configured upload size limit.</p>
 */
public class SourceFile {

    private final String name;

    private final String contentType;

    private final Resource resource;

    public SourceFile(String name, String contentType, Resource resource) {
        this.name = name;
        this.contentType = contentType;
        this.resource = resource;
    }

    public String getName() {
        return name;
    }

    public String getContentType() {
        return contentType;
    }

    public Resource getResource() {
        return resource;
    }
}
