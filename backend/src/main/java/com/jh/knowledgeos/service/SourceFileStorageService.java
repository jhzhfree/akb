package com.jh.knowledgeos.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Keeps the original bytes of an ingested source so the console can show the file itself.
 *
 * <p>Parsed text and content units describe what the system understood; they cannot answer
 * "what did we actually import". Storing the upload under a deterministic
 * {@code <root>/<knowledgeBaseId>/<sourceCode>/<filename>} layout makes that answer available
 * without a blob column, and keeps the storage root movable through configuration.</p>
 *
 * <p>Storage failures never fail ingestion: preview is a console convenience, so a write or
 * read problem degrades to the parsed-text preview instead of rejecting a document.</p>
 */
@Service
public class SourceFileStorageService {

    private static final Logger log = LoggerFactory.getLogger(SourceFileStorageService.class);

    private static final int TEXT_SNIFF_BYTES = 8192;

    private static final int TEXT_SNIFF_BUFFER = 4096;

    private static final Set<String> TEXT_EXTENSIONS = new HashSet<String>(Arrays.asList("md", "markdown", "txt", "text", "csv", "tsv", "json",
        "yaml", "yml", "xml", "html", "htm", "log", "ini", "properties", "sql", "rst", "tex"));

    private static final Set<String> BINARY_EXTENSIONS = new HashSet<String>(Arrays.asList("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt",
        "rtf", "png", "jpg", "jpeg", "gif", "bmp", "svg", "webp", "zip", "gz", "tar", "tgz", "7z", "rar", "jar", "class"));

    private final Path root;

    public SourceFileStorageService(@Value("${knowledge.source-storage-dir:data/source-files}") String directory) {
        this.root = Paths.get(directory).toAbsolutePath().normalize();
    }

    /**
     * Persist an upload and return its path relative to the storage root.
     *
     * @return relative path, or {@code null} when the file cannot be stored
     */
    public String store(Long knowledgeBaseId, String sourceCode, String filename, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            Path directory = root.resolve(String.valueOf(knowledgeBaseId)).resolve(sanitize(sourceCode));
            Files.createDirectories(directory);
            Path target = directory.resolve(sanitize(filename));
            Files.write(target, bytes);
            return root.relativize(target).toString();
        } catch (IOException ex) {
            log.warn("source file could not be stored sourceCode={} reason={}", sourceCode, ex.getMessage());
            return null;
        }
    }

    /**
     * Resolve a stored path back to a file, refusing anything outside the storage root.
     *
     * @return the file, or {@code null} when it is absent or the path escapes the root
     */
    public Path resolve(String relativePath) {
        if (relativePath == null || relativePath.trim().isEmpty()) {
            return null;
        }
        Path candidate;
        try {
            candidate = root.resolve(relativePath).normalize();
        } catch (RuntimeException ex) {
            return null;
        }
        if (!candidate.startsWith(root)) {
            return null;
        }
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    /**
     * Read the first {@code limit} characters of a stored file as UTF-8 text.
     */
    public String readText(Path file, int limit) throws IOException {
        StringBuilder text = new StringBuilder();
        try (Reader reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            char[] buffer = new char[TEXT_SNIFF_BUFFER];
            int read;
            while (text.length() < limit && (read = reader.read(buffer)) > 0) {
                text.append(buffer, 0, Math.min(read, limit - text.length()));
            }
        }
        return text.toString();
    }

    /**
     * Read a bounded prefix used to decide whether a file can be rendered as text.
     */
    public byte[] head(Path file) {
        try (InputStream stream = Files.newInputStream(file)) {
            byte[] buffer = new byte[TEXT_SNIFF_BYTES];
            int total = 0;
            int read;
            while (total < buffer.length && (read = stream.read(buffer, total, buffer.length - total)) > 0) {
                total += read;
            }
            byte[] head = new byte[total];
            System.arraycopy(buffer, 0, head, 0, total);
            return head;
        } catch (IOException ex) {
            log.warn("source file head could not be read file={} reason={}", file, ex.getMessage());
            return new byte[0];
        }
    }

    /**
     * Decide whether a stored file can be rendered as text.
     *
     * <p>Known extensions decide first; anything else is sniffed, so an unknown text format
     * still previews while a PDF without a recognizable name is not dumped as binary noise.</p>
     */
    public boolean isText(String filename, byte[] head) {
        String extension = extension(filename);
        if (TEXT_EXTENSIONS.contains(extension)) {
            return true;
        }
        if (BINARY_EXTENSIONS.contains(extension)) {
            return false;
        }
        if (head == null || head.length == 0) {
            return false;
        }
        for (byte value : head) {
            if (value == 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Lower-cased extension without the dot; empty when the name has none.
     */
    public String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase();
    }

    /**
     * Reduce a caller-supplied name to a single safe path segment.
     *
     * <p>Upload names are attacker-controlled, so separators and parent references are dropped
     * before the name is joined onto the storage root.</p>
     */
    private String sanitize(String value) {
        String name = value == null ? "" : value.trim();
        name = name.replace("\\", "_").replace("/", "_").replace("..", "_");
        name = name.replace(":", "_").replace("\n", "_").replace("\r", "_");
        return name.isEmpty() ? "source" : name.substring(0, Math.min(name.length(), 120));
    }
}
