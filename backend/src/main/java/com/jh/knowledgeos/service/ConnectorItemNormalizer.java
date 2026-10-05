package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.domain.ConnectorItem;
import com.jh.knowledgeos.domain.ConnectorSource;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one connector item into the same document model {@code /ai/parse} returns for a file.
 *
 * <p>Everything downstream — chunk persistence, evidence anchoring, card / claim / entity
 * extraction, the index outbox and finally retrieval — is written against that one model. A
 * second, connector-specific shape would mean a second implementation of all of it, so instead
 * an item is rendered as: a heading block (the title), a summary block (its attributes as
 * Chinese "状态：已完成" lines), then the body paragraphs. From there the pipeline is identical
 * to uploading a document, and a connector item is retrievable through the same five channels.</p>
 *
 * <p>The remote systems are not clean: Jira descriptions are HTML or Atlassian Document Format
 * JSON, Confluence bodies are view/storage markup. {@link #htmlToText} and {@link #deepText}
 * collapse all of that to plain text before anything consumes it, because a chunk carrying
 * {@code <p>} tags is unreadable for whoever reviews the source of a generated card.</p>
 */
@Service
public class ConnectorItemNormalizer {

    /** Same defaults as the parser: a manual and a Jira ticket should be cut the same way. */
    private static final int DEFAULT_CHUNK_SIZE = 900;

    private static final int DEFAULT_CHUNK_OVERLAP = 120;

    private static final String DEFAULT_STRATEGY = "SEMANTIC";

    /** A single item is never a book; this only guards against a pathological description. */
    private static final int MAX_BLOCKS = 400;

    private static final String BLOCK_HEADING = "HEADING";

    private static final String BLOCK_PARAGRAPH = "PARAGRAPH";

    private static final Pattern SCRIPT = Pattern.compile("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>");

    private static final Pattern BLOCK_TAG = Pattern.compile("(?i)<\\s*/?\\s*(p|div|br|tr|li|h[1-6]|table|td|section)\\b[^>]*>");

    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    private static final Pattern ENTITY = Pattern.compile("&(nbsp|amp|lt|gt|quot|apos|#39|#34);");

    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");

    private static final Pattern LATIN = Pattern.compile("[A-Za-z]");

    private static final Pattern BLANK_LINE = Pattern.compile("\\n{2,}");

    private final ObjectMapper objectMapper;

    public ConnectorItemNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @return a map with the same keys {@code /ai/parse} returns: text, chunks, standard, sha256…
     */
    public Map<String, Object> normalize(ConnectorSource source, ConnectorItem item, Map<String, Object> policy) {
        String title = title(item);
        String filename = clip((blank(item.getExternalId()) ? "" : item.getExternalId() + " · ") + title, 200);
        List<Map<String, Object>> blocks = blocks(item, title);
        String text = joinText(blocks);
        int chunkSize = chunkSize(policy);
        String strategy = strategy(policy);
        List<Map<String, Object>> units = units(blocks, chunkSize, strategy);
        Map<String, Object> standard = standard(filename, title, text, blocks, units, strategy, policy);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("filename", filename);
        result.put("extension", ".connector");
        result.put("sha256", sha256(text));
        result.put("page_count", 0);
        result.put("chunk_policy", policy == null ? defaultPolicy() : policy);
        result.put("text", text);
        result.put("chunks", legacyChunks(units));
        result.put("standard", standard);
        return result;
    }

    /**
     * Title heading, then attributes, then the body. Attributes are written as prose lines
     * rather than kept as JSON because extraction reads text: "状态：已完成" yields a claim,
     * whereas a bare {@code status: 已完成} inside a JSON blob yields nothing.
     */
    private List<Map<String, Object>> blocks(ConnectorItem item, String title) {
        List<Map<String, Object>> blocks = new ArrayList<Map<String, Object>>();
        int seq = 0;
        seq++;
        blocks.add(block("B" + seq, seq, BLOCK_HEADING, title, title, 1));
        String attributes = attributeLines(item);
        if (!attributes.isEmpty()) {
            seq++;
            blocks.add(block("B" + seq, seq, BLOCK_PARAGRAPH, attributes, title, 0));
        }
        String body = body(item);
        for (String paragraph : paragraphs(body)) {
            if (blocks.size() >= MAX_BLOCKS) {
                break;
            }
            seq++;
            blocks.add(block("B" + seq, seq, BLOCK_PARAGRAPH, paragraph, title, 0));
        }
        return blocks;
    }

    /**
     * The body is whatever the remote system called its content. {@code raw_json} keeps the full
     * fetched object, so the untruncated text is still available even though {@code excerpt} is
     * capped for browsing.
     */
    private String body(ConnectorItem item) {
        Map<String, Object> raw = parse(item.getRawJson());
        String text = first(deepText(raw.get("description")), deepText(raw.get("body")), deepText(raw.get("content")),
            deepText(raw.get("text")));
        if (text.isEmpty()) {
            text = htmlToText(item.getExcerpt());
        }
        return text;
    }

    /**
     * HTML, ADF JSON and nested storage markup all reduce to plain text: the value of a
     * {@code text} key when the payload is structured, tag stripping when it is markup.
     */
    private String deepText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String) {
            return htmlToText((String) value);
        }
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            Object text = map.get("text");
            if (text != null) {
                return deepText(text);
            }
            StringBuilder builder = new StringBuilder();
            for (Object child : map.values()) {
                String nested = deepText(child);
                if (!nested.isEmpty()) {
                    if (builder.length() > 0) {
                        builder.append("\n");
                    }
                    builder.append(nested);
                }
            }
            return builder.toString();
        }
        if (value instanceof List) {
            StringBuilder builder = new StringBuilder();
            for (Object child : (List<?>) value) {
                String nested = deepText(child);
                if (!nested.isEmpty()) {
                    if (builder.length() > 0) {
                        builder.append("\n");
                    }
                    builder.append(nested);
                }
            }
            return builder.toString();
        }
        return "";
    }

    private String htmlToText(String html) {
        if (html == null || html.trim().isEmpty()) {
            return "";
        }
        String text = SCRIPT.matcher(html).replaceAll(" ");
        text = BLOCK_TAG.matcher(text).replaceAll("\n");
        text = TAG.matcher(text).replaceAll("");
        text = ENTITY.matcher(text).replaceAll(" ");
        text = text.replace(' ', ' ');
        return normalize(text);
    }

    private List<String> paragraphs(String body) {
        List<String> out = new ArrayList<String>();
        if (body == null || body.trim().isEmpty()) {
            return out;
        }
        for (String part : BLANK_LINE.split(body.trim())) {
            // A single newline inside one Jira paragraph is a line break, not a new paragraph;
            // only blank lines split, and very short leftovers are merged into the previous one.
            String paragraph = normalize(part);
            if (paragraph.isEmpty()) {
                continue;
            }
            if (!out.isEmpty() && out.get(out.size() - 1).length() + paragraph.length() < 80) {
                out.set(out.size() - 1, out.get(out.size() - 1) + "\n" + paragraph);
                continue;
            }
            out.add(paragraph);
        }
        return out;
    }

    /** Attribute lines in the console's own labels, minus the time fields nobody filters on. */
    private String attributeLines(ConnectorItem item) {
        Map<String, Object> attributes = parse(item.getAttributesJson());
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            String value = String.valueOf(entry.getValue() == null ? "" : entry.getValue()).trim();
            if (value.isEmpty() || ConnectorFetchService.isTimeAttribute(entry.getKey(), value)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("；");
            }
            builder.append(ConnectorCatalogService.labelOf(entry.getKey())).append("：").append(value);
        }
        return builder.toString();
    }

    /**
     * Semantic grouping: accumulate blocks up to the window and cut on a heading, the same rule
     * the parser uses, so a ticket and a manual are cut by the same policy.
     */
    private List<Map<String, Object>> units(List<Map<String, Object>> blocks, int chunkSize, String strategy) {
        Map<String, int[]> spans = spans(blocks);
        List<Map<String, Object>> units = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> buffer = new ArrayList<Map<String, Object>>();
        int size = 0;
        for (Map<String, Object> block : blocks) {
            boolean heading = BLOCK_HEADING.equals(block.get("block_type"));
            if (heading && !buffer.isEmpty()) {
                units.add(unit(units.size() + 1, buffer, spans, strategy));
                buffer.clear();
                size = 0;
            }
            buffer.add(block);
            size += String.valueOf(block.get("text")).length();
            if (size >= chunkSize) {
                units.add(unit(units.size() + 1, buffer, spans, strategy));
                buffer.clear();
                size = 0;
            }
        }
        if (!buffer.isEmpty()) {
            units.add(unit(units.size() + 1, buffer, spans, strategy));
        }
        return units;
    }

    private Map<String, Object> unit(int seq, List<Map<String, Object>> buffer, Map<String, int[]> spans, String strategy) {
        StringBuilder content = new StringBuilder();
        List<String> blockIds = new ArrayList<String>(buffer.size());
        for (Map<String, Object> block : buffer) {
            if (content.length() > 0) {
                content.append("\n\n");
            }
            content.append(String.valueOf(block.get("text")));
            blockIds.add(String.valueOf(block.get("block_id")));
        }
        Map<String, Object> first = buffer.get(0);
        Map<String, Object> last = buffer.get(buffer.size() - 1);
        String text = content.toString();
        int[] start = spans.get(String.valueOf(first.get("block_id")));
        int[] end = spans.get(String.valueOf(last.get("block_id")));
        Map<String, Object> unit = new LinkedHashMap<String, Object>();
        unit.put("unit_id", "U" + seq);
        unit.put("seq", seq);
        unit.put("heading_path", String.valueOf(first.get("heading_path")));
        unit.put("page_no", null);
        unit.put("page_end", null);
        unit.put("block_type", first.get("block_type"));
        unit.put("chunk_type", BLOCK_HEADING.equals(first.get("block_type")) ? "HEADING" : "PARAGRAPH");
        unit.put("block_ids", blockIds);
        unit.put("content", text);
        unit.put("content_hash", sha256(text));
        unit.put("char_start", start == null ? null : start[0]);
        unit.put("char_end", end == null ? null : end[1]);
        unit.put("token_count", estimateTokens(text));
        unit.put("parent_unit_id", null);
        unit.put("overlap_prefix", "");
        unit.put("strategy", strategy);
        return unit;
    }

    /** Historical chunk shape persistence and indexing both read. */
    private List<Map<String, Object>> legacyChunks(List<Map<String, Object>> units) {
        List<Map<String, Object>> chunks = new ArrayList<Map<String, Object>>(units.size());
        for (Map<String, Object> unit : units) {
            Map<String, Object> chunk = new LinkedHashMap<String, Object>();
            chunk.put("seq", unit.get("seq"));
            chunk.put("unit_id", unit.get("unit_id"));
            chunk.put("page_no", unit.get("page_no"));
            chunk.put("page_end", unit.get("page_end"));
            chunk.put("char_start", unit.get("char_start"));
            chunk.put("char_end", unit.get("char_end"));
            chunk.put("token_count", unit.get("token_count"));
            chunk.put("heading_path", unit.get("heading_path"));
            chunk.put("content", unit.get("content"));
            chunk.put("content_hash", unit.get("content_hash"));
            chunk.put("chunk_type", unit.get("chunk_type"));
            chunk.put("block_type", unit.get("block_type"));
            chunk.put("parent_unit_id", unit.get("parent_unit_id"));
            chunk.put("overlap_prefix", unit.get("overlap_prefix"));
            chunk.put("strategy", unit.get("strategy"));
            chunks.add(chunk);
        }
        return chunks;
    }

    /** Character span of every block inside the joined text, so a citation can explain itself. */
    private Map<String, int[]> spans(List<Map<String, Object>> blocks) {
        Map<String, int[]> spans = new LinkedHashMap<String, int[]>();
        int cursor = 0;
        for (Map<String, Object> block : blocks) {
            String text = String.valueOf(block.get("text"));
            spans.put(String.valueOf(block.get("block_id")), new int[] { cursor, cursor + text.length() });
            cursor += text.length() + 2;
        }
        return spans;
    }

    private String joinText(List<Map<String, Object>> blocks) {
        StringBuilder builder = new StringBuilder();
        for (Map<String, Object> block : blocks) {
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(String.valueOf(block.get("text")));
        }
        return builder.toString();
    }

    private Map<String, Object> standard(String filename, String title, String text, List<Map<String, Object>> blocks,
        List<Map<String, Object>> units, String strategy, Map<String, Object> policy) {
        String sha256 = sha256(text);
        int tokens = 0;
        for (Map<String, Object> unit : units) {
            Object count = unit.get("token_count");
            tokens += count instanceof Number ? ((Number) count).intValue() : 0;
        }
        Map<String, Object> stats = new LinkedHashMap<String, Object>();
        stats.put("blocks", blocks.size());
        stats.put("sections", 1);
        stats.put("units", units.size());
        stats.put("tables", 0);
        stats.put("chars", text.length());
        stats.put("tokens", tokens);
        stats.put("chunkStrategy", strategy);
        stats.put("chunkPolicy", policy == null ? defaultPolicy() : policy);
        Map<String, Object> standard = new LinkedHashMap<String, Object>();
        standard.put("doc_id", sha256(filename + ":" + sha256).substring(0, 32));
        standard.put("title", title);
        standard.put("filename", filename);
        standard.put("source_format", "TEXT");
        standard.put("language", language(text));
        standard.put("sha256", sha256);
        standard.put("page_count", 0);
        standard.put("text", text);
        standard.put("blocks", blocks);
        standard.put("sections", sections(title, blocks));
        standard.put("units", units);
        standard.put("stats", stats);
        return standard;
    }

    private List<Map<String, Object>> sections(String title, List<Map<String, Object>> blocks) {
        List<Map<String, Object>> sections = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> block : blocks) {
            if (!BLOCK_HEADING.equals(block.get("block_type"))) {
                continue;
            }
            Map<String, Object> section = new LinkedHashMap<String, Object>();
            section.put("section_id", "S" + (sections.size() + 1));
            section.put("heading", block.get("text"));
            section.put("level", block.get("level"));
            section.put("heading_path", block.get("heading_path"));
            section.put("order", block.get("seq"));
            sections.add(section);
        }
        if (sections.isEmpty()) {
            Map<String, Object> section = new LinkedHashMap<String, Object>();
            section.put("section_id", "S1");
            section.put("heading", title);
            section.put("level", 1);
            section.put("heading_path", title);
            section.put("order", 1);
            sections.add(section);
        }
        return sections;
    }

    private Map<String, Object> block(String id, int seq, String type, String text, String headingPath, int level) {
        Map<String, Object> block = new LinkedHashMap<String, Object>();
        block.put("block_id", id);
        block.put("seq", seq);
        block.put("block_type", type);
        block.put("text", text);
        block.put("heading_path", headingPath);
        block.put("level", level);
        block.put("page_no", null);
        return block;
    }

    private Map<String, Object> defaultPolicy() {
        Map<String, Object> policy = new LinkedHashMap<String, Object>();
        policy.put("strategy", DEFAULT_STRATEGY);
        policy.put("chunkSize", DEFAULT_CHUNK_SIZE);
        policy.put("chunkOverlap", DEFAULT_CHUNK_OVERLAP);
        policy.put("minChunkSize", 120);
        policy.put("measure", "CHAR");
        policy.put("keepStructure", Boolean.TRUE);
        policy.put("respectSentence", Boolean.TRUE);
        policy.put("parentChild", Boolean.FALSE);
        return policy;
    }

    private int chunkSize(Map<String, Object> policy) {
        Object value = policy == null ? null : policy.get("chunkSize");
        int size = value instanceof Number ? ((Number) value).intValue() : DEFAULT_CHUNK_SIZE;
        return size < 200 ? DEFAULT_CHUNK_SIZE : size;
    }

    private String strategy(Map<String, Object> policy) {
        Object value = policy == null ? null : policy.get("strategy");
        return value == null || String.valueOf(value).trim().isEmpty() ? DEFAULT_STRATEGY : String.valueOf(value).trim();
    }

    private String language(String text) {
        Matcher cjk = CJK.matcher(text == null ? "" : text);
        int chinese = 0;
        while (cjk.find()) {
            chinese++;
        }
        Matcher latin = LATIN.matcher(text == null ? "" : text);
        int english = 0;
        while (latin.find()) {
            english++;
        }
        return chinese >= english ? "zh" : "en";
    }

    private int estimateTokens(String content) {
        return content == null || content.isEmpty() ? 0 : (content.length() + 3) / 4;
    }

    private String title(ConnectorItem item) {
        String title = item.getTitle() == null ? "" : item.getTitle().trim();
        return title.isEmpty() ? (blank(item.getExternalId()) ? "未命名条目" : item.getExternalId()) : title;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            return parsed == null ? new LinkedHashMap<String, Object>() : parsed;
        } catch (IOException ex) {
            return new LinkedHashMap<String, Object>();
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
    }

    private String first(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String clip(String value, int limit) {
        String text = value == null ? "" : value.trim();
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new UncheckedIOException(new IOException("SHA-256 unavailable", ex));
        }
    }
}
