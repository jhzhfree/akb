package com.jh.knowledgeos.service;

import com.jh.knowledgeos.common.UserRole;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.domain.KnowledgeBase;
import com.jh.knowledgeos.domain.PlatformEvent;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.springframework.stereotype.Service;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Decides what each SSE subscriber is allowed to see.
 *
 * <p>Publishers always attach the richest payload they have; this class decides per subscription
 * whether the event may be delivered at all and how much detail survives. Keeping it out of the
 * publishers is what stops an accidental payload change from widening external exposure.</p>
 */
@Service
public class PlatformEventVisibilityService {

    private static final Set<String> EXTERNAL_DENYLIST = new HashSet<String>(Arrays.asList("permissionTags", "aclTags", "requirePermissionTags", "effectiveTags", "tagScope", "tagScopes", "userId", "subjectCode"));

    private static final int EXTERNAL_TEXT_LIMIT = 240;

    private final KnowledgeMapper mapper;

    private final KnowledgeBaseAccessService accessService;

    public PlatformEventVisibilityService(KnowledgeMapper mapper, KnowledgeBaseAccessService accessService) {
        this.mapper = mapper;
        this.accessService = accessService;
    }

    /**
     * Console session: everything the account can read, platform-wide events reserved to admins.
     */
    public PlatformEventHub.Visibility forSession(User user, Long requestedKb) {
        return new PlatformEventHub.Visibility() {

            @Override
            public boolean visible(PlatformEvent event) {
                Long kb = event.getKnowledgeBaseId();
                if (kb == null) {
                    return user != null && UserRole.of(user.getRole()) == UserRole.ADMIN;
                }
                if (requestedKb != null && !requestedKb.equals(kb)) {
                    return false;
                }
                KnowledgeBase base = mapper.getKnowledgeBase(kb);
                return base != null && accessService.canRead(user, base);
            }

            @Override
            public Map<String, Object> project(PlatformEvent event, Map<String, Object> detail) {
                return detail;
            }
        };
    }

    /**
     * External credential: no platform-scope events (a key never learns that another workspace
     * exists) and security-relevant fields stripped from every payload.
     */
    public PlatformEventHub.Visibility forApiKey(ApiKey key, Long kb) {
        return new PlatformEventHub.Visibility() {

            @Override
            public boolean visible(PlatformEvent event) {
                if (event.getKnowledgeBaseId() == null) {
                    return false;
                }
                return kb == null || kb.equals(event.getKnowledgeBaseId());
            }

            @Override
            public Map<String, Object> project(PlatformEvent event, Map<String, Object> detail) {
                return redact(detail);
            }
        };
    }

    private Map<String, Object> redact(Map<String, Object> detail) {
        Map<String, Object> safe = new LinkedHashMap<String, Object>();
        if (detail == null) {
            return safe;
        }
        for (Map.Entry<String, Object> entry : detail.entrySet()) {
            String key = entry.getKey();
            if (key == null || EXTERNAL_DENYLIST.contains(key)) {
                continue;
            }
            safe.put(key, truncate(entry.getValue()));
        }
        return safe;
    }

    /**
     * Bridges can be metered by payload size, so long free-text fields leave the process trimmed.
     */
    private Object truncate(Object value) {
        if (value instanceof String) {
            String text = (String) value;
            return text.length() > EXTERNAL_TEXT_LIMIT ? text.substring(0, EXTERNAL_TEXT_LIMIT) + "…" : text;
        }
        return value;
    }
}
