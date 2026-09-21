package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class /**
 * Persists security access decisions without changing the protected response path.
 */
AclAuditService {

    private static final Logger log = LoggerFactory.getLogger(AclAuditService.class);

    private final KnowledgeMapper mapper;

    private final ObjectMapper objectMapper;

    private final AclContextResolver resolver;

    public AclAuditService(KnowledgeMapper mapper, ObjectMapper objectMapper, AclContextResolver resolver) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.resolver = resolver;
    }

    public void record(Long kb, String type, String code, String action, String decision, List<String> tags, String reason) {
        try {
            String tagsJson = objectMapper.writeValueAsString(tags == null ? Collections.emptyList() : tags);
            mapper.insertAclAudit(resolver.currentSubjectCode(), kb, type, code, action, decision, tagsJson, reason, MDC.get("traceId"), requestUri());
        } catch (Exception ex) {
            log.error("ACL audit persistence failed resourceType={} resourceCode={} decision={}", type, code, decision, ex);
        }
    }

    public List<Map<String, Object>> list(Long kb, Integer limit) {
        int normalized = Math.min(500, Math.max(1, limit == null ? 100 : limit));
        return mapper.listAclAudits(kb, normalized);
    }

    private String requestUri() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes)) {
            return null;
        }
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        return request.getRequestURI();
    }
}
