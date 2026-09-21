package com.jh.knowledgeos.service;

import org.springframework.stereotype.Component;
import java.util.UUID;

/**
 * Generates stable human-readable business identifiers for persisted knowledge objects.
 */
@Component
public class IdGenerator {

    public String next(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
