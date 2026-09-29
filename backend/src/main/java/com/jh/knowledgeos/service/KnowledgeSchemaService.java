package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The knowledge schema: the vocabulary a knowledge base is allowed to speak.
 *
 * <p>Entity and relation types used to be whatever the extraction model emitted that day, because
 * the only vocabulary lived inside a Python tuple. Moving the dictionary into the platform makes
 * the contract explicit and editable: knowledge types, entity types and relation types with their
 * properties, validated before publication, published as a versioned snapshot, and checked against
 * the data that already exists so a rename cannot quietly orphan thousands of relations.</p>
 *
 * <p>{@code knowledge_base_id = 0} holds the platform defaults every knowledge base inherits; a
 * knowledge base may add its own types or override a default label, but the built-ins themselves
 * stay put.</p>
 */
@Service
/** Application service for knowledge schema lifecycle and type governance. */
public class KnowledgeSchemaService {

    public static final String KIND_KNOWLEDGE = "KNOWLEDGE_TYPE";

    public static final String KIND_ENTITY = "ENTITY_TYPE";

    public static final String KIND_RELATION = "RELATION_TYPE";

    public static final String ACTIVE = "ACTIVE";

    public static final String DEPRECATED = "DEPRECATED";

    public static final String STATE_DRAFT = "DRAFT";

    public static final String STATE_PUBLISHED = "PUBLISHED";

    public static final String STATE_ARCHIVED = "ARCHIVED";

    /**
     * Property types a schema may declare; anything else is a typo waiting to happen.
     */
    public static final List<String> PROPERTY_TYPES = Collections.unmodifiableList(Arrays.asList("STRING", "TEXT", "NUMBER", "BOOLEAN", "DATE", "ENUM", "REF"));

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    private static final Pattern PROPERTY_NAME = Pattern.compile("^[a-z][a-zA-Z0-9_]{0,63}$");

    private static final List<String> KINDS = Collections.unmodifiableList(Arrays.asList(KIND_KNOWLEDGE, KIND_ENTITY, KIND_RELATION));

    private final KnowledgeMapper mapper;

    private final IdGenerator ids;

    private final ObjectMapper objectMapper;

    private final AiClient aiClient;

    public KnowledgeSchemaService(KnowledgeMapper mapper, IdGenerator ids, ObjectMapper objectMapper, AiClient aiClient) {
        this.mapper = mapper;
        this.ids = ids;
        this.objectMapper = objectMapper;
        this.aiClient = aiClient;
    }

    // ------------------------------------------------------------------ types
    public List<Map<String, Object>> types(Long kb, String kind) {
        return mapper.listSchemaTypes(kb == null ? 0 : kb, kind == null || kind.trim().isEmpty() ? null : kind.trim().toUpperCase(Locale.ROOT));
    }

    public Map<String, Object> upsertType(Long kb, Map<String, Object> body) {
        String kind = kindOf(body.get("kind"));
        String code = text(body.get("typeCode")).trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("类型标识需为大写字母开头、仅含大写字母数字与下划线：" + code);
        }
        long scope = kb == null ? 0 : kb;
        Map<String, Object> existing = mapper.getSchemaType(scope, kind, code);
        if (existing != null && toInt(existing.get("builtIn")) == 1 && scope == 0) {
            throw new IllegalArgumentException("平台内置类型不可修改，可在知识库内覆盖：" + code);
        }
        Map<String, Object> properties = normalizeProperties(body.get("properties"), body.get("constraints"));
        List<String> errors = validateProperties(properties, kind);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(errors.get(0));
        }
        mapper.upsertSchemaType(scope, kind, code, text(body.get("label")).trim().isEmpty() ? code : text(body.get("label")).trim(),
            text(body.get("description")), write(properties), DEPRECATED.equals(text(body.get("status")).toUpperCase(Locale.ROOT)) ? DEPRECATED :
            ACTIVE, currentUser());
        return mapper.getSchemaType(scope, kind, code);
    }

    /**
     * Deprecate instead of delete: existing data keeps its type, new data should not use it.
     */
    public Map<String, Object> deprecate(Long kb, String kind, String typeCode) {
        long scope = kb == null ? 0 : kb;
        Map<String, Object> existing = mapper.getSchemaType(scope, kind, typeCode);
        if (existing == null) {
            throw new IllegalArgumentException("类型不存在：" + typeCode);
        }
        mapper.upsertSchemaType(scope, kindOf(kind), typeCode, text(existing.get("label")), text(existing.get("description")),
            text(existing.get("propertiesJson")), DEPRECATED, currentUser());
        return mapper.getSchemaType(scope, kindOf(kind), typeCode);
    }

    public void delete(Long kb, String kind, String typeCode) {
        mapper.deleteSchemaType(kb == null ? 0 : kb, kindOf(kind), typeCode);
    }

    // --------------------------------------------------------------- validation
    /**
     * Validate the dictionary and the data against it.
     *
     * <p>Two different questions: is the schema itself well formed (errors, blocks publication),
     * and does the stored knowledge conform to it (violations, reported and alertable but never
     * blocking, because a knowledge base with history cannot be made conform overnight).</p>
     */
    public Map<String, Object> validate(Long kb) {
        long scope = kb == null ? 0 : kb;
        List<String> errors = new ArrayList<String>();
        List<String> warnings = new ArrayList<String>();
        List<Map<String, Object>> violations = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> types = mapper.listSchemaTypes(scope, null);
        Map<String, List<String>> known = new LinkedHashMap<String, List<String>>();
        for (String kind : KINDS) {
            known.put(kind, new ArrayList<String>());
        }
        for (Map<String, Object> type : types) {
            String kind = text(type.get("kind"));
            String code = text(type.get("typeCode"));
            if (!CODE.matcher(code).matches()) {
                errors.add("类型标识不合法：" + code);
            }
            if (known.containsKey(kind) && !known.get(kind).contains(code)) {
                known.get(kind).add(code);
            }
            errors.addAll(validateProperties(readProperties(type.get("propertiesJson")), kind));
            if (KIND_RELATION.equals(kind)) {
                warnings.addAll(validateConstraints(readProperties(type.get("propertiesJson")), known.get(KIND_ENTITY)));
            }
        }
        if (!known.get(KIND_KNOWLEDGE).contains("FACT")) {
            warnings.add("缺少知识类型 FACT，抽取结果将难以归类");
        }
        violations.addAll(conformance(kb, known));
        Map<String, Object> report = new LinkedHashMap<String, Object>();
        report.put("valid", errors.isEmpty());
        report.put("errors", errors);
        report.put("warnings", warnings);
        report.put("violations", violations);
        report.put("violationCount", violationCountOf(violations));
        report.put("typeCount", types.size());
        report.put("validatedAt", java.time.LocalDateTime.now().toString());
        return report;
    }

    /**
     * Data that uses a type the dictionary does not know about.
     */
    private List<Map<String, Object>> conformance(Long kb, Map<String, List<String>> known) {
        List<Map<String, Object>> violations = new ArrayList<Map<String, Object>>();
        if (kb == null) {
            return violations;
        }
        for (Map<String, Object> usage : mapper.listEntityTypeUsage(kb)) {
            addViolation(violations, KIND_ENTITY, usage, known.get(KIND_ENTITY));
        }
        for (Map<String, Object> usage : mapper.listRelationTypeUsage(kb)) {
            addViolation(violations, KIND_RELATION, usage, known.get(KIND_RELATION));
        }
        for (Map<String, Object> usage : mapper.listKnowledgeTypeUsage(kb)) {
            addViolation(violations, KIND_KNOWLEDGE, usage, known.get(KIND_KNOWLEDGE));
        }
        return violations;
    }

    private void addViolation(List<Map<String, Object>> violations, String kind, Map<String, Object> usage, List<String> knownTypes) {
        String code = text(usage.get("typeCode"));
        if (code.isEmpty() || knownTypes.contains(code)) {
            return;
        }
        Map<String, Object> violation = new LinkedHashMap<String, Object>();
        violation.put("kind", kind);
        violation.put("typeCode", code);
        violation.put("useCount", toInt(usage.get("useCount")));
        violations.add(violation);
    }

    private int violationCountOf(List<Map<String, Object>> violations) {
        int total = 0;
        for (Map<String, Object> violation : violations) {
            total += toInt(violation.get("useCount"));
        }
        return total;
    }

    public int violationCount(Long kb) {
        if (kb == null) {
            return 0;
        }
        Map<String, List<String>> known = new LinkedHashMap<String, List<String>>();
        for (String kind : KINDS) {
            known.put(kind, new ArrayList<String>());
        }
        for (Map<String, Object> type : mapper.listSchemaTypes(kb, null)) {
            List<String> codes = known.get(text(type.get("kind")));
            if (codes != null) {
                codes.add(text(type.get("typeCode")));
            }
        }
        return violationCountOf(conformance(kb, known));
    }

    private List<String> validateProperties(Map<String, Object> properties, String kind) {
        List<String> errors = new ArrayList<String>();
        Object raw = properties == null ? null : properties.get("properties");
        if (!(raw instanceof List)) {
            return errors;
        }
        List<String> seen = new ArrayList<String>();
        for (Object item : (List<?>) raw) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> property = (Map<?, ?>) item;
            String name = text(property.get("name")).trim();
            String type = text(property.get("type")).trim().toUpperCase(Locale.ROOT);
            if (!PROPERTY_NAME.matcher(name).matches()) {
                errors.add("属性名需为小写字母开头：" + name);
            }
            if (!PROPERTY_TYPES.contains(type)) {
                errors.add("属性类型不支持：" + type + "（可选 " + String.join("/", PROPERTY_TYPES) + "）");
            }
            if (seen.contains(name)) {
                errors.add("属性名重复：" + name);
            }
            seen.add(name);
            if ("ENUM".equals(type)) {
                Object values = property.get("values");
                if (!(values instanceof List) || ((List<?>) values).isEmpty()) {
                    errors.add("枚举属性需要填写可选值：" + name);
                }
            }
        }
        return errors;
    }

    /**
     * Relation endpoints must point at entity types that exist, or the graph cannot be checked.
     */
    private List<String> validateConstraints(Map<String, Object> properties, List<String> entityTypes) {
        List<String> warnings = new ArrayList<String>();
        if (properties == null || !(properties.get("constraints") instanceof Map)) {
            return warnings;
        }
        Map<?, ?> constraints = (Map<?, ?>) properties.get("constraints");
        for (String field : Arrays.asList("fromKinds", "toKinds")) {
            Object raw = constraints.get(field);
            if (!(raw instanceof List)) {
                continue;
            }
            for (Object value : (List<?>) raw) {
                String code = text(value);
                if (!code.isEmpty() && !entityTypes.contains(code)) {
                    warnings.add("关系端点引用了未登记的实体类型：" + code);
                }
            }
        }
        return warnings;
    }

    // ---------------------------------------------------------------- releases
    public Map<String, Object> publish(Long kb, String operator) {
        long scope = kb == null ? 0 : kb;
        Map<String, Object> report = validate(kb);
        Object valid = report.get("valid");
        if (!Boolean.TRUE.equals(valid)) {
            throw new IllegalArgumentException("Schema 校验未通过，无法发布：" + String.join("；", strings(report.get("errors"))));
        }
        List<Map<String, Object>> types = mapper.listSchemaTypes(scope, null);
        Map<String, Object> definition = new LinkedHashMap<String, Object>();
        definition.put("knowledgeBaseId", scope);
        definition.put("types", types);
        String definitionJson = write(definition);
        String version = "v" + (mapper.listSchemaReleases(scope).size() + 1);
        String releaseCode = ids.next("SR");
        mapper.retireSchemaReleases(scope);
        String publishedBy = operator == null || operator.trim().isEmpty() ? currentUser() : operator;
        mapper.insertSchemaRelease(releaseCode, scope, version, STATE_PUBLISHED, definitionJson, write(report), sha256(definitionJson), publishedBy);
        Map<String, Object> release = mapper.getPublishedSchemaRelease(scope);
        if (release != null) {
            Map<String, Object> delivery = sync(scope, definition);
            release.put("delivery", delivery);
        }
        return release == null ? mapper.listSchemaReleases(scope).get(0) : release;
    }

    public List<Map<String, Object>> releases(Long kb) {
        return mapper.listSchemaReleases(kb == null ? 0 : kb);
    }

    public Map<String, Object> published(Long kb) {
        Map<String, Object> release = mapper.getPublishedSchemaRelease(kb == null ? 0 : kb);
        if (release == null) {
            Map<String, Object> empty = new LinkedHashMap<String, Object>();
            empty.put("state", STATE_DRAFT);
            empty.put("published", false);
            return empty;
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>(release);
        row.put("published", true);
        return row;
    }

    /**
     * Push the published vocabulary to the extraction side so prompts and parsing agree.
     */
    public Map<String, Object> sync(Long kb, Map<String, Object> definition) {
        Map<String, Object> delivery = new LinkedHashMap<String, Object>();
        try {
            Map<String, Object> payload = definition == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(definition);
            payload.put("knowledgeBaseId", kb);
            Map<String, Object> response = aiClient.post(AiApiPaths.SCHEMA_APPLY, payload);
            // An unreachable AI service answers with an empty body rather than an error, and an
            // empty body is not a delivery: saying "synced" when it was not would be worse than
            // saying nothing.
            delivery.put("delivered", response != null && !response.isEmpty());
            delivery.put("response", response);
        } catch (Exception e) {
            // A schema is authoritative on the platform side even when the AI service is down:
            // the sync is retried on the next publication, it must not fail this one.
            delivery.put("delivered", false);
            delivery.put("error", e.getMessage());
        }
        return delivery;
    }

    public Map<String, Object> schema() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("kinds", KINDS);
        payload.put("propertyTypes", PROPERTY_TYPES);
        payload.put("statuses", Arrays.asList(ACTIVE, DEPRECATED));
        payload.put("codePattern", "^[A-Z][A-Z0-9_]{0,63}$");
        payload.put("states", Arrays.asList(STATE_DRAFT, STATE_PUBLISHED, STATE_ARCHIVED));
        return payload;
    }

    // ----------------------------------------------------------------- helpers
    private String kindOf(Object value) {
        String kind = text(value).trim().toUpperCase(Locale.ROOT);
        return KINDS.contains(kind) ? kind : KIND_ENTITY;
    }

    private Map<String, Object> normalizeProperties(Object properties, Object constraints) {
        Map<String, Object> normalized = new LinkedHashMap<String, Object>();
        normalized.put("properties", properties instanceof List ? properties : new ArrayList<Object>());
        if (constraints instanceof Map) {
            normalized.put("constraints", constraints);
        }
        return normalized;
    }

    private Map<String, Object> readProperties(Object raw) {
        if (raw == null) {
            return new LinkedHashMap<String, Object>();
        }
        if (raw instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = (Map<String, Object>) raw;
            return new LinkedHashMap<String, Object>(parsed);
        }
        String value = text(raw).trim();
        if (value.isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            return objectMapper.readValue(value, Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<String, Object>();
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> strings(Object raw) {
        List<String> values = new ArrayList<String>();
        if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                values.add(text(item));
            }
        }
        return values;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String currentUser() {
        User user = AuthContext.get();
        return user == null ? "system" : user.getUsername();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder builder = new StringBuilder();
            for (byte b : digest.digest(value.getBytes(StandardCharsets.UTF_8))) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            return String.valueOf(value.hashCode());
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int toInt(Object value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
