package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.common.PlatformEventTypes;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class /**
 * Coordinates authoritative ACL mutations and transactional index propagation events.
 */
AclAdministrationService {

    private final KnowledgeMapper mapper;

    private final ObjectMapper objectMapper;

    private final IndexOutboxService outbox;

    private final AclContextResolver resolver;

    private final PlatformEventPublisher publisher;

    public AclAdministrationService(KnowledgeMapper mapper, ObjectMapper objectMapper, IndexOutboxService outbox, AclContextResolver resolver, PlatformEventPublisher publisher) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.outbox = outbox;
        this.resolver = resolver;
        this.publisher = publisher;
    }

    public List<Map<String, Object>> subjects() {
        return mapper.listAclSubjects();
    }

    public List<Map<String, Object>> roles() {
        return mapper.listAclRoles();
    }

    public List<Map<String, Object>> changes(Long kb, Integer limit) {
        int normalized = Math.min(500, Math.max(1, limit == null ? 100 : limit));
        return mapper.listAclChanges(kb, normalized);
    }

    @Transactional
    public Map<String, Object> changeResourceAcl(Long kb, String type, String code, List<String> tags, String reason) {
        String normalizedType = type.toUpperCase();
        if (!Arrays.asList("SOURCE", "CARD", "CLAIM").contains(normalizedType)) {
            throw new IllegalArgumentException("Unsupported ACL resource type: " + type);
        }
        List<String> clean = normalize(tags);
        try {
            String oldTags = mapper.getResourcePermissionTags(kb, normalizedType, code);
            if (oldTags == null) {
                oldTags = "[]";
            }
            String newTags = objectMapper.writeValueAsString(clean);
            if (mapper.updateResourcePermissionTags(kb, normalizedType, code, newTags) != 1) {
                throw new IllegalArgumentException("ACL resource not found");
            }
            String changeCode = "ACL-" + UUID.randomUUID().toString();
            Map<String, Object> impact = new LinkedHashMap<String, Object>();
            impact.put("requiresRetrievalPropagation", true);
            impact.put("sourceCascade", "SOURCE".equals(normalizedType));
            impact.put("reason", reason);
            mapper.insertAclChange(changeCode, kb, normalizedType, code, oldTags, newTags, resolver.currentSubjectCode(), objectMapper.writeValueAsString(impact));
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("knowledgeBaseId", kb);
            payload.put("objectType", normalizedType);
            payload.put("objectId", code);
            payload.put("permissionTags", clean);
            payload.put("sourceCascade", "SOURCE".equals(normalizedType));
            Map<String, Object> event = outbox.enqueue(kb, normalizedType, "KB:" + kb + ":" + normalizedType + ":" + code, "ACL_CHANGED", AiApiPaths.INDEX_OBJECT_ACL, payload, changeCode);
            Map<String, Object> detail = new LinkedHashMap<String, Object>();
            detail.put("changeCode", changeCode);
            detail.put("previousTags", oldTags);
            detail.put("permissionTags", clean);
            detail.put("sourceCascade", "SOURCE".equals(normalizedType));
            detail.put("requiresRetrievalPropagation", true);
            detail.put("reason", reason);
            publisher.emitUser(PlatformEventTypes.ACL_CHANGED, kb, PlatformEventPublisher.SOURCE_UI, normalizedType, code, "权限标签变更：" + normalizedType + " " + code, detail);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("changeCode", changeCode);
            result.put("permissionTags", clean);
            result.put("outbox", event);
            result.put("impact", impact);
            return result;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("ACL change failed", ex);
        }
    }

    private List<String> normalize(List<String> tags) {
        if (tags == null) {
            return Collections.emptyList();
        }
        List<String> clean = new ArrayList<String>();
        for (String raw : tags) {
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            String tag = raw.trim();
            if (!clean.contains(tag)) {
                clean.add(tag);
            }
        }
        return clean;
    }
}
