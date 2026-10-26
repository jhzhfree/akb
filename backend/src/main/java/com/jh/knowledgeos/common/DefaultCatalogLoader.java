package com.jh.knowledgeos.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the default catalogs (scenario templates, derivation rule presets) from a
 * configuration directory that can be mounted from outside the image.
 *
 * <p>Lookup order, and why each step exists:
 * <ol>
 *   <li>{@code $KNOWLEDGE_CONFIG_DIR/scenario-templates.json} — the copy an operator can
 *       edit and mount. Read once at startup, so changing it needs a restart, not a rebuild.</li>
 *   <li>{@code classpath:defaults/scenario-templates.json} — the copy baked into the jar.
 *       Used when the volume is empty or the mounted file is broken.</li>
 *   <li>built-in constants — returned as null here; callers fall back. Keeps the system
 *       bootable with a usable catalog no matter what happens to the files.</li>
 * </ol>
 *
 * <p>On startup {@link #prepare()} also seeds the directory: a mounted volume that is
 * empty gets the shipped files written into it, so there is something to edit. Seeding
 * never overwrites an existing file — the operator's edits are the whole point.
 *
 * <p>These files only supply defaults. Whatever a knowledge base already stored stays
 * untouched; seeding stays idempotent through the preset code.
 */
public final class DefaultCatalogLoader {
    private static final Logger log = LoggerFactory.getLogger(DefaultCatalogLoader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> ROWS =
        new TypeReference<List<Map<String, Object>>>() {
        };

    /** Mount point for the editable copy; unset means "only use what is in the jar". */
    static final String CONFIG_DIR_ENV = "KNOWLEDGE_CONFIG_DIR";
    private static final String SCENARIO_FILE = "scenario-templates.json";
    private static final String DERIVATION_FILE = "derivation-rules.json";

    private static final Object LOCK = new Object();
    private static List<Map<String, Object>> scenarioCache;
    private static List<Map<String, Object>> derivationCache;
    private static boolean loaded;

    private DefaultCatalogLoader() {
    }

    /**
     * Seed the configured directory and read the catalogs. Called once at startup.
     *
     * <p>Seeding is best effort: a read-only mount (the index worker shares this image)
     * or a missing directory must not stop the application from starting.
     */
    public static void prepare() {
        synchronized (LOCK) {
            File dir = configDir();
            if (dir == null) {
                log.info("{} is not set, default catalogs come from the jar", CONFIG_DIR_ENV);
            } else {
                seed(dir, SCENARIO_FILE);
                seed(dir, DERIVATION_FILE);
            }
            reloadLocked();
        }
    }

    /** Re-read the catalogs, discarding the cache. */
    public static void reload() {
        synchronized (LOCK) {
            reloadLocked();
        }
    }

    /** Scenario templates, or null when every source failed (callers use built-ins). */
    public static List<Map<String, Object>> scenarioTemplates() {
        synchronized (LOCK) {
            if (!loaded) {
                reloadLocked();
            }
            return copies(scenarioCache);
        }
    }

    /** Derivation rule presets, or null when every source failed (callers use built-ins). */
    public static List<Map<String, Object>> derivationPresets() {
        synchronized (LOCK) {
            if (!loaded) {
                reloadLocked();
            }
            return copies(derivationCache);
        }
    }

    private static void reloadLocked() {
        scenarioCache = read(SCENARIO_FILE, "scenario templates");
        derivationCache = read(DERIVATION_FILE, "derivation rule presets");
        loaded = true;
    }

    private static List<Map<String, Object>> read(String fileName, String what) {
        File dir = configDir();
        if (dir != null) {
            File file = new File(dir, fileName);
            if (file.isFile()) {
                List<Map<String, Object>> rows = parse(readFile(file), what, file.getPath());
                if (rows != null) {
                    return rows;
                }
                log.warn("falling back to the copy in the jar for {}", what);
            }
        }
        ClassPathResource resource = new ClassPathResource("defaults/" + fileName);
        if (!resource.exists()) {
            log.info("no {} default file in the jar, using built-in constants", what);
            return null;
        }
        try {
            InputStream stream = resource.getInputStream();
            try {
                return parse(new String(readAll(stream), StandardCharsets.UTF_8), what,
                    "classpath:defaults/" + fileName);
            } finally {
                stream.close();
            }
        } catch (Exception exc) {
            log.warn("{} default file in the jar is unreadable, using built-in constants: {}",
                what, exc.toString());
            return null;
        }
    }

    private static List<Map<String, Object>> parse(String text, String what, String source) {
        try {
            List<Map<String, Object>> rows = MAPPER.readValue(text, ROWS);
            if (rows == null || rows.isEmpty()) {
                log.warn("{} at {} is empty", what, source);
                return null;
            }
            log.info("loaded {} {} from {}", rows.size(), what, source);
            return rows;
        } catch (Exception exc) {
            log.warn("{} at {} is unreadable: {}", what, source, exc.toString());
            return null;
        }
    }

    /** Write the shipped file into the directory when it is not there yet. */
    private static void seed(File dir, String fileName) {
        File target = new File(dir, fileName);
        if (target.exists()) {
            return;
        }
        ClassPathResource resource = new ClassPathResource("defaults/" + fileName);
        if (!resource.exists()) {
            return;
        }
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                log.warn("could not create {}, skipping seed of {}", dir.getPath(), fileName);
                return;
            }
            byte[] bytes;
            InputStream stream = resource.getInputStream();
            try {
                bytes = readAll(stream);
            } finally {
                stream.close();
            }
            OutputStream out = new FileOutputStream(target);
            try {
                out.write(bytes);
            } finally {
                out.close();
            }
            log.info("seeded {} into {}", fileName, target.getPath());
        } catch (Exception exc) {
            // 只读挂载、权限不足都走这里：文件没写进去不影响启动，jar 里那份照样能用。
            log.warn("could not seed {} into {}: {}", fileName, target.getPath(), exc.toString());
        }
    }

    private static File configDir() {
        String configured = System.getenv(CONFIG_DIR_ENV);
        if (configured == null || configured.trim().isEmpty()) {
            return null;
        }
        return new File(configured.trim());
    }

    private static String readFile(File file) {
        try {
            InputStream stream = new java.io.FileInputStream(file);
            try {
                return new String(readAll(stream), StandardCharsets.UTF_8);
            } finally {
                stream.close();
            }
        } catch (Exception exc) {
            log.warn("could not read {}: {}", file.getPath(), exc.toString());
            return null;
        }
    }

    private static byte[] readAll(InputStream stream) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = stream.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    /** Hand out copies: callers decorate the rows (installed / ruleCode) and must not see each other. */
    private static List<Map<String, Object>> copies(List<Map<String, Object>> rows) {
        if (rows == null) {
            return null;
        }
        List<Map<String, Object>> copies = new ArrayList<Map<String, Object>>(rows.size());
        for (Map<String, Object> row : rows) {
            copies.add(new LinkedHashMap<String, Object>(row));
        }
        return copies;
    }
}
