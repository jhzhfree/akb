package com.jh.knowledgeos.service;

import com.jh.knowledgeos.domain.ApiCallAudit;
import com.jh.knowledgeos.domain.EmbedTokenRevocation;
import com.jh.knowledgeos.mapper.ExternalApiMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lifecycle of an embed token after it has been minted.
 *
 * <p>Tokens are self-contained and deliberately not stored when issued, so the only state this
 * service keeps is the negative kind: which token ids must no longer be accepted. Everything else
 * it answers — where a token was used, how often, from which site — is read back from the call
 * audit, which already records every request.</p>
 *
 * <p>Revocation is read straight from the table on each call instead of being cached. A revoke is
 * usually an emergency, and a cache would keep honouring a token somebody just killed for as long
 * as the entry lived.</p>
 */
@Service
public class EmbedTokenLifecycleService {

    private final ExternalApiMapper mapper;

    public EmbedTokenLifecycleService(ExternalApiMapper mapper) {
        this.mapper = mapper;
    }

    public boolean isRevoked(String jti) {
        if (jti == null || jti.trim().isEmpty()) {
            return false;
        }
        return mapper.findEmbedTokenRevocation(jti.trim()) != null;
    }

    /**
     * Revokes one token id; calling it twice is harmless (the row is unique on jti).
     */
    public EmbedTokenRevocation revoke(String jti, Long keyId, String keyCode, Long knowledgeBaseId, String origin, String reason, Long operatorId) {
        String id = jti == null ? "" : jti.trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("缺少令牌 ID（jti）：v1 旧令牌没有 ID，请吊销其 API Key");
        }
        EmbedTokenRevocation row = new EmbedTokenRevocation();
        row.setJti(id);
        row.setKeyId(keyId);
        row.setKeyCode(keyCode);
        row.setKnowledgeBaseId(knowledgeBaseId);
        row.setOrigin(origin);
        row.setReason(reason == null || reason.trim().isEmpty() ? null : reason.trim());
        row.setOperatorId(operatorId);
        mapper.insertEmbedTokenRevocation(row);
        EmbedTokenRevocation stored = mapper.findEmbedTokenRevocation(id);
        return stored == null ? row : stored;
    }

    /**
     * Undoes a revoke: the token becomes acceptable again until it expires or its key is revoked.
     */
    public boolean restore(String jti) {
        if (jti == null || jti.trim().isEmpty()) {
            return false;
        }
        return mapper.deleteEmbedTokenRevocation(jti.trim()) > 0;
    }

    public List<EmbedTokenRevocation> revocations(int limit) {
        return mapper.listEmbedTokenRevocations(Math.min(Math.max(limit, 1), 500));
    }

    /**
     * Recently used tokens with their traffic, so the console can revoke one without first
     * hunting its id through raw log lines.
     */
    public List<Map<String, Object>> recentTokens(int windowHours, int limit) {
        LocalDateTime since = LocalDateTime.now().minusHours(Math.min(Math.max(windowHours, 1), 24 * 30));
        List<Map<String, Object>> rows = mapper.recentTokenSummaries(since, Math.min(Math.max(limit, 1), 200));
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<String, Object>(row);
            Object jti = row.get("jti");
            String id = jti == null ? "" : String.valueOf(jti);
            item.put("displayId", displayId(id));
            item.put("revoked", isRevoked(id));
            out.add(item);
        }
        return out;
    }

    public List<ApiCallAudit> calls(Long keyId, String jti, int limit) {
        String id = jti == null || jti.trim().isEmpty() ? null : jti.trim();
        return mapper.listTokenCallAudits(keyId, id, Math.min(Math.max(limit, 1), 500));
    }

    private static String displayId(String jti) {
        if (jti == null || jti.isEmpty()) {
            return "v1（无令牌 ID）";
        }
        return jti.length() <= 8 ? jti : jti.substring(0, 4) + "…" + jti.substring(jti.length() - 4);
    }
}
