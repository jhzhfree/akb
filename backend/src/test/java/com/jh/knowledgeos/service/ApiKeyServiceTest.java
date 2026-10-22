package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.mapper.ExternalApiMapper;
import java.util.Arrays;
import java.util.Map;
import java.time.LocalDateTime;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

class ApiKeyServiceTest {

    private final ExternalApiMapper mapper = mock(ExternalApiMapper.class);
    private final ApiKeyService service = new ApiKeyService(mapper, mock(IdGenerator.class));

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
        when(mapper.countKnowledgeBase(7L)).thenReturn(1);
        when(mapper.countKnowledgeBase(8L)).thenReturn(1);
        when(mapper.listPrincipalKnowledgeBaseGrants(71L)).thenReturn(Arrays.asList(3L, 4L), Arrays.asList(7L, 8L));

        Map<String, Object> result = service.replaceKnowledgeBaseGrants(9L, Arrays.asList(7L, 7L, 8L), 2L).get(0);

        verify(mapper).deletePrincipalKnowledgeBaseGrants(71L);
        verify(mapper).insertPrincipalKnowledgeBaseGrantWithExpiry(71L, 7L, 2L, "控制台配置授权", null);
        verify(mapper).insertPrincipalKnowledgeBaseGrantWithExpiry(71L, 8L, 2L, "控制台配置授权", null);
        verify(mapper).insertPrincipalKnowledgeBaseGrantAudit(71L, 3L, "REVOKE", 2L, "控制台替换授权配置");
        verify(mapper).insertPrincipalKnowledgeBaseGrantAudit(71L, 4L, "REVOKE", 2L, "控制台替换授权配置");
        verify(mapper).insertPrincipalKnowledgeBaseGrantAudit(71L, 7L, "GRANT", 2L, "控制台授权");
        assertEquals(7L, result.get("knowledgeBaseId"));
    }

    @Test
    void grantConfigurationPersistsPerKnowledgeBaseExpiryAndReason() {
        ApiKey key = key("QUERY");
        key.setPrincipalId(71L);
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(10).withNano(0);
        when(mapper.getApiKey(9L)).thenReturn(key);
        when(mapper.countKnowledgeBase(7L)).thenReturn(1);
        when(mapper.listPrincipalKnowledgeBaseGrants(71L)).thenReturn(Arrays.asList(7L), Arrays.asList(7L));

        com.jh.knowledgeos.dto.ExternalKnowledgeBaseGrantRequest grant = new com.jh.knowledgeos.dto.ExternalKnowledgeBaseGrantRequest();
        grant.setKnowledgeBaseId(7L);
        grant.setExpiresAt(expiresAt.toString());
        grant.setReason("项目验收期临时授权");
        service.replaceKnowledgeBaseGrantDetails(9L, Arrays.asList(grant), 2L);

        verify(mapper).insertPrincipalKnowledgeBaseGrantWithExpiry(71L, 7L, 2L, "项目验收期临时授权", expiresAt);
        verify(mapper).insertPrincipalKnowledgeBaseGrantAudit(71L, 7L, "GRANT", 2L, "项目验收期临时授权");
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
    void rejectsInvalidGrantInputBeforeChangingExistingPermissions() {
        ApiKey key = key("QUERY");
        key.setPrincipalId(71L);
        when(mapper.getApiKey(9L)).thenReturn(key);

        assertThrows(IllegalArgumentException.class, () -> service.replaceKnowledgeBaseGrants(9L, Arrays.asList(7L, 0L), 2L));

        verify(mapper, never()).deletePrincipalKnowledgeBaseGrants(71L);
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
}
