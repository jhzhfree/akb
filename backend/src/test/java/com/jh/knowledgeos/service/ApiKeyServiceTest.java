package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.domain.ExternalPrincipal;
import com.jh.knowledgeos.mapper.ExternalApiMapper;
import org.mockito.Mockito;
import static org.mockito.Mockito.doAnswer;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

class ApiKeyServiceTest {

    private final ExternalApiMapper mapper = mock(ExternalApiMapper.class);
    private final IdGenerator ids = mock(IdGenerator.class);
    private final ApiKeyService service = new ApiKeyService(mapper, ids);

    /**
     * 授权前会校验主体存在且 ACTIVE——给一个不存在或已停用的主体授权没有意义，所以
     * 凡是要走授权写入的用例都得先把这个主体 mock 出来，否则只会拿到"主体不存在"。
     */
    private static ExternalPrincipal principal(String status) {
        ExternalPrincipal principal = new ExternalPrincipal();
        principal.setStatus(status);
        return principal;
    }

    @Test
    void memoryPolicyCanDisableAndRestoreOnlyPreviouslyGrantedScopes() {
        ApiKey before = key("READ,MEMORY_READ,MEMORY_WRITE");
        ApiKey after = key("READ,MEMORY_READ,MEMORY_WRITE");
        after.setMemoryReadEnabled(Boolean.FALSE);
        after.setMemoryWriteEnabled(Boolean.TRUE);
        when(mapper.getApiKey(9L)).thenReturn(before, after);

        Map<String, Object> result = service.updateMemoryPolicy(9L, false, true);

        verify(mapper).updateApiKeyMemoryPolicy(9L, Boolean.FALSE, Boolean.TRUE);
        assertEquals(Boolean.FALSE, result.get("memoryReadEnabled"));
        assertEquals(Boolean.TRUE, result.get("memoryWriteEnabled"));
    }

    @Test
    void memoryPolicyCannotGrantScopesThatWereNotIssued() {
        when(mapper.getApiKey(9L)).thenReturn(key("READ"));

        assertThrows(IllegalArgumentException.class, () -> service.updateMemoryPolicy(9L, true, false));
        verify(mapper, never()).updateApiKeyMemoryPolicy(9L, Boolean.TRUE, Boolean.FALSE);
    }

    @Test
    void disabledMemoryScopeIsDeniedWithoutAffectingOtherGrantedCapabilities() {
        ApiKey key = key("READ,MEMORY_READ,MEMORY_WRITE");
        key.setMemoryReadEnabled(Boolean.FALSE);
        key.setMemoryWriteEnabled(Boolean.TRUE);

        assertFalse(key.hasScope(ApiKey.SCOPE_MEMORY_READ));
        assertTrue(key.hasScope(ApiKey.SCOPE_MEMORY_WRITE));
        assertTrue(key.hasScope(ApiKey.SCOPE_READ));
    }

    @Test
    void fineGrainedReadScopesDoNotImplyOtherReadActions() {
        ApiKey key = key("QUERY");

        assertTrue(key.hasScope(ApiKey.SCOPE_QUERY));
        assertFalse(key.hasScope(ApiKey.SCOPE_SEARCH));
        assertFalse(key.hasScope(ApiKey.SCOPE_LIST));
        assertFalse(key.hasScope(ApiKey.SCOPE_READ_OBJECT));
        assertFalse(key.hasScope(ApiKey.SCOPE_READ));
    }

    @Test
    void legacyReadAndWriteScopesRemainCompatibleWithFineGrainedActions() {
        ApiKey read = key("READ");
        assertTrue(read.hasScope(ApiKey.SCOPE_LIST));
        assertTrue(read.hasScope(ApiKey.SCOPE_QUERY));
        assertTrue(read.hasScope(ApiKey.SCOPE_SEARCH));
        assertTrue(read.hasScope(ApiKey.SCOPE_READ_OBJECT));
        assertFalse(read.hasScope(ApiKey.SCOPE_INGEST));

        ApiKey write = key("WRITE");
        assertTrue(write.hasScope(ApiKey.SCOPE_QUERY));
        assertTrue(write.hasScope(ApiKey.SCOPE_READ_OBJECT));
        assertTrue(write.hasScope(ApiKey.SCOPE_INGEST));
    }

    @Test
    void createsFineGrainedScopeAndRejectsUnknownScopeInsteadOfBroadeningIt() {
        assertThrows(IllegalArgumentException.class,
            () -> service.create("invalid", null, Arrays.asList("QUERY_ALL"), null, null, null));
        verify(mapper, never()).insertApiKey(any(ApiKey.class));

        service.create("query-only", null, Arrays.asList(ApiKey.SCOPE_QUERY), null, null, null);
        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(mapper).insertApiKey(captor.capture());
        assertEquals(ApiKey.SCOPE_QUERY, captor.getValue().getScopes());
    }

    @Test
    void replacesPrincipalKnowledgeBaseGrantsAndAuditsBothRevocationAndGrant() {
        ApiKey key = key("QUERY");
        key.setPrincipalId(71L);
        when(mapper.getApiKey(9L)).thenReturn(key);
        when(mapper.getPrincipal(71L)).thenReturn(principal("ACTIVE"));
        when(mapper.countKnowledgeBase(7L)).thenReturn(1);
        when(mapper.countKnowledgeBase(8L)).thenReturn(1);
        when(mapper.listPrincipalKnowledgeBaseGrants(71L)).thenReturn(Arrays.asList(7L, 8L));
        when(mapper.listPrincipalKnowledgeBaseGrantDetails(71L)).thenReturn(Arrays.asList(
            grantRow(3L, "[\"team-a\"]"), grantRow(4L, null)));

        Map<String, Object> result = service.replaceKnowledgeBaseGrants(9L, Arrays.asList(7L, 7L, 8L), 2L).get(0);

        verify(mapper).deletePrincipalKnowledgeBaseGrants(71L);
        verify(mapper).insertPrincipalKnowledgeBaseGrantWithExpiry(71L, 7L, 2L, "控制台配置授权", null, null);
        verify(mapper).insertPrincipalKnowledgeBaseGrantWithExpiry(71L, 8L, 2L, "控制台配置授权", null, null);
        verify(mapper).insertPrincipalKnowledgeBaseGrantAudit(71L, 3L, "REVOKE", 2L, "控制台替换授权配置", "[\"team-a\"]");
        verify(mapper).insertPrincipalKnowledgeBaseGrantAudit(71L, 4L, "REVOKE", 2L, "控制台替换授权配置", null);
        verify(mapper).insertPrincipalKnowledgeBaseGrantAudit(71L, 7L, "GRANT", 2L, "控制台配置授权", null);
        assertEquals(7L, result.get("knowledgeBaseId"));
    }

    @Test
    void grantConfigurationPersistsPerKnowledgeBaseExpiryAndReason() {
        ApiKey key = key("QUERY");
        key.setPrincipalId(71L);
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(10).withNano(0);
        when(mapper.getApiKey(9L)).thenReturn(key);
        when(mapper.getPrincipal(71L)).thenReturn(principal("ACTIVE"));
        when(mapper.countKnowledgeBase(7L)).thenReturn(1);
        when(mapper.listPrincipalKnowledgeBaseGrants(71L)).thenReturn(Arrays.asList(7L));
        when(mapper.listPrincipalKnowledgeBaseGrantDetails(71L)).thenReturn(Collections.emptyList());

        com.jh.knowledgeos.dto.ExternalKnowledgeBaseGrantRequest grant = new com.jh.knowledgeos.dto.ExternalKnowledgeBaseGrantRequest();
        grant.setKnowledgeBaseId(7L);
        grant.setExpiresAt(expiresAt.toString());
        grant.setReason("项目验收期临时授权");
        service.replaceKnowledgeBaseGrantDetails(9L, Arrays.asList(grant), 2L);

        verify(mapper).insertPrincipalKnowledgeBaseGrantWithExpiry(71L, 7L, 2L, "项目验收期临时授权", expiresAt, null);
        verify(mapper).insertPrincipalKnowledgeBaseGrantAudit(71L, 7L, "GRANT", 2L, "项目验收期临时授权", null);
    }

    @Test
    void grantConfigurationPersistsAndAuditsPerKnowledgeBaseTags() {
        ApiKey key = key("QUERY");
        key.setPrincipalId(71L);
        when(mapper.getApiKey(9L)).thenReturn(key);
        when(mapper.getPrincipal(71L)).thenReturn(principal("ACTIVE"));
        when(mapper.countKnowledgeBase(7L)).thenReturn(1);
        when(mapper.listPrincipalKnowledgeBaseGrantDetails(71L)).thenReturn(Collections.emptyList());
        com.jh.knowledgeos.dto.ExternalKnowledgeBaseGrantRequest grant = new com.jh.knowledgeos.dto.ExternalKnowledgeBaseGrantRequest();
        grant.setKnowledgeBaseId(7L);
        grant.setPermissionTags(Arrays.asList("team-a", " team-b ", "team-a"));

        service.replaceKnowledgeBaseGrantDetails(9L, Arrays.asList(grant), 2L);

        verify(mapper).insertPrincipalKnowledgeBaseGrantWithExpiry(71L, 7L, 2L, "控制台配置授权", null, "[\"team-a\",\"team-b\"]");
        verify(mapper).insertPrincipalKnowledgeBaseGrantAudit(71L, 7L, "GRANT", 2L, "控制台配置授权", "[\"team-a\",\"team-b\"]");
    }

    @Test
    void grantDetailsExposeTagPolicyAsAListAndPreserveInheritanceNull() {
        ApiKey key = key("QUERY");
        key.setPrincipalId(71L);
        when(mapper.listPrincipalKnowledgeBaseGrantDetails(71L)).thenReturn(Arrays.asList(
            grantRow(7L, "[\"team-a\"]"), grantRow(8L, null)));

        List<Map<String, Object>> details = service.knowledgeBaseGrantDetails(key);

        assertEquals(Arrays.asList("team-a"), details.get(0).get("permissionTags"));
        assertNull(details.get(1).get("permissionTags"));
        assertFalse(details.get(0).containsKey("permissionTagsJson"));
    }

    @Test
    void expiredGrantConfigurationIsRejectedBeforeReplacingPermissions() {
        ApiKey key = key("QUERY");
        key.setPrincipalId(71L);
        when(mapper.getApiKey(9L)).thenReturn(key);
        when(mapper.countKnowledgeBase(7L)).thenReturn(1);
        com.jh.knowledgeos.dto.ExternalKnowledgeBaseGrantRequest grant = new com.jh.knowledgeos.dto.ExternalKnowledgeBaseGrantRequest();
        grant.setKnowledgeBaseId(7L);
        grant.setExpiresAt(LocalDateTime.now().minusMinutes(1).toString());

        assertThrows(IllegalArgumentException.class,
            () -> service.replaceKnowledgeBaseGrantDetails(9L, Arrays.asList(grant), 2L));

        verify(mapper, never()).deletePrincipalKnowledgeBaseGrants(71L);
    }

    @Test
    void assigningKeyToExistingPrincipalAuditsAndClearsLegacyBinding() {
        ApiKey key = key("QUERY");
        key.setPrincipalId(71L);
        key.setKnowledgeBaseId(7L);
        ExternalPrincipal target = new ExternalPrincipal();
        target.setId(88L);
        target.setName("Octop Agent 集群");
        target.setStatus("ACTIVE");
        when(mapper.getApiKey(9L)).thenReturn(key, key);
        when(mapper.getPrincipal(88L)).thenReturn(target);

        service.assignPrincipal(9L, 88L, null, 2L, "共享给 Octop 的受控 Agent Key");

        verify(mapper).updateApiKeyPrincipal(9L, 88L);
        verify(mapper).insertApiKeyPrincipalAudit(9L, "AK_TEST", 71L, 88L, 2L, "共享给 Octop 的受控 Agent Key");
    }

    @Test
    void creatingNewPrincipalCopiesCurrentGrantsBeforeReassignment() {
        ApiKey key = key("QUERY");
        key.setPrincipalId(71L);
        doAnswer(invocation -> {
            ExternalPrincipal principal = invocation.getArgument(0);
            principal.setId(99L);
            return 1;
        }).when(mapper).insertPrincipal(Mockito.any(ExternalPrincipal.class));
        when(ids.next("PR_")).thenReturn("PR_NEW");
        when(mapper.getApiKey(9L)).thenReturn(key, key);
        when(mapper.listPrincipalKnowledgeBaseGrantDetails(99L)).thenReturn(Arrays.asList(grantRow(4L, "[\"team-a\"]")));

        service.assignPrincipal(9L, null, "隔离后的 Octop 应用", 2L, "拆分主体，保留原授权");

        verify(mapper).copyActivePrincipalKnowledgeBaseGrants(71L, 99L, 2L);
        verify(mapper).insertPrincipalKnowledgeBaseGrantAudit(99L, 4L, "GRANT", 2L, "主体拆分复制授权", "[\"team-a\"]");
        verify(mapper).updateApiKeyPrincipal(9L, 99L);
    }

    @Test
    void rejectsInvalidGrantInputBeforeChangingExistingPermissions() {
        ApiKey key = key("QUERY");
        key.setPrincipalId(71L);
        when(mapper.getApiKey(9L)).thenReturn(key);

        assertThrows(IllegalArgumentException.class, () -> service.replaceKnowledgeBaseGrants(9L, Arrays.asList(7L, 0L), 2L));

        verify(mapper, never()).deletePrincipalKnowledgeBaseGrants(71L);
    }

    /**
     * 签发页选的是「到期时间」而非整天，这里钉住它在接口调用时真的生效：认证每次查库，
     * 到点的凭据直接判为不可用，不需要额外吊销；没到点或长期凭据不受影响。
     */
    @Test
    void expiredCredentialIsRejectedWhenCallingTheApi() {
        ApiKey expired = key("READ");
        expired.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(mapper.getApiKeyByHash(any())).thenReturn(expired);

        assertNull(service.authenticate("kos_live_expired"));
    }

    @Test
    void credentialExpiringInTheFutureIsStillAccepted() {
        ApiKey valid = key("READ");
        valid.setExpiresAt(LocalDateTime.now().plusMinutes(1));
        when(mapper.getApiKeyByHash(any())).thenReturn(valid);

        assertEquals(valid, service.authenticate("kos_live_valid"));
    }

    @Test
    void credentialWithoutExpiryIsNotTreatedAsExpired() {
        when(mapper.getApiKeyByHash(any())).thenReturn(key("READ"));

        assertTrue(service.authenticate("kos_live_permanent") != null);
    }

    private ApiKey key(String scopes) {
        ApiKey key = new ApiKey();
        key.setId(9L);
        key.setKeyCode("AK_TEST");
        key.setName("test");
        key.setScopes(scopes);
        key.setStatus(ApiKey.STATUS_ACTIVE);
        return key;
    }

    private static Map<String, Object> grantRow(Long knowledgeBaseId, String permissionTagsJson) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("knowledgeBaseId", knowledgeBaseId);
        row.put("permissionTagsJson", permissionTagsJson);
        return row;
    }
}
