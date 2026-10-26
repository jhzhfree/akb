package com.jh.knowledgeos.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Loads the shipped default catalogs (scenario templates, derivation rule presets)
 * from files under {@code classpath:defaults/}.
 *
 * <p>The built-in constants stay as the fallback. Two reasons: a deployment that
 * somehow ships without the files must still boot with a usable catalog, and a
 * file that fails to parse is a warning rather than a startup failure — the system
 * answers with the same defaults it always did instead of refusing to start.
 *
 * <p>The files are only a source for defaults; whatever a knowledge base already
 * stored stays untouched (seeding remains idempotent through the preset code).
 */
public final class DefaultCatalogLoader {
    private static final Logger log = LoggerFactory.getLogger(DefaultCatalogLoader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> ROWS =
        new TypeReference<List<Map<String, Object>>>() {
        };

    private DefaultCatalogLoader() {
    }

    /** Scenario templates from {@code defaults/scenario-templates.json}, or null to use built-ins. */
    public static List<Map<String, Object>> scenarioTemplates() {
        return read("defaults/scenario-templates.json", "scenario templates");
    }

    /** Derivation rule presets from {@code defaults/derivation-rules.json}, or null to use built-ins. */
    public static List<Map<String, Object>> derivationPresets() {
        return read("defaults/derivation-rules.json", "derivation rule presets");
    }

    private static List<Map<String, Object>> read(String location, String what) {
        ClassPathResource resource = new ClassPathResource(location);
        if (!resource.exists()) {
            log.info("no {} file at {}, using built-in defaults", what, location);
            return null;
        }
        try {
            InputStream stream = resource.getInputStream();
            try {
                List<Map<String, Object>> rows = MAPPER.readValue(
                    new String(readAll(stream), StandardCharsets.UTF_8), ROWS);
                if (rows == null || rows.isEmpty()) {
                    log.warn("{} file at {} is empty, using built-in defaults", what, location);
                    return null;
                }
                log.info("loaded {} {} from {}", rows.size(), what, location);
                return rows;
            } finally {
                stream.close();
            }
        } catch (Exception exc) {
            log.warn("{} file at {} is unreadable, using built-in defaults: {}", what, location, exc.toString());
            return null;
        }
    }

    private static byte[] readAll(InputStream stream) throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = stream.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
