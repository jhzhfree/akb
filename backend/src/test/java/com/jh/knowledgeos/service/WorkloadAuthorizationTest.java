package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.jh.knowledgeos.config.KnowledgeProperties;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.domain.WorkloadIdentity;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class WorkloadAuthorizationTest {
    @Test void resourceIntersectionCannotFallBackToPublicBasesWhenEmpty() {
        ApiKeyService keys = mock(ApiKeyService.class);
        KnowledgeBaseAccessService bases = mock(KnowledgeBaseAccessService.class);
        ExternalAccessService access = new ExternalAccessService(keys, mock(ExternalRateLimiter.class), bases,
            mock(EmbedTokenService.class), mock(EmbedTokenLifecycleService.class), new KnowledgeProperties());
        ApiKey key = new ApiKey(); key.setPrincipalId(1L); key.setIdentityMode("APPLICATION_WORKLOAD");
        key.setWorkloadIdentity(new WorkloadIdentity(10L, "session", "call"));
        when(keys.grantedKnowledgeBases(key)).thenReturn(Arrays.asList(1L, 2L));
        when(keys.grantedKnowledgeBases(10L)).thenReturn(Collections.singletonList(2L));
        assertEquals(Collections.singletonList(2L), access.grantedKnowledgeBases(key));
        assertThrows(SecurityException.class, () -> access.resolveKnowledgeBase(key, 1L));
        assertEquals(2L, access.resolveKnowledgeBase(key, 2L));
        when(keys.grantedKnowledgeBases(10L)).thenReturn(Collections.emptyList());
        assertThrows(SecurityException.class, () -> access.resolveKnowledgeBase(key, 1L));
        verifyNoInteractions(bases);
    }
    @Test void workloadCredentialsCannotUseLegacyWriteOrMemoryAliases() {
        ApiKey key = new ApiKey(); key.setIdentityMode("APPLICATION_WORKLOAD"); key.setScopes("READ,WRITE,MEMORY_MANAGE");
        assertTrue(key.hasScope("SEARCH")); assertFalse(key.hasScope("INGEST")); assertFalse(key.hasScope("MEMORY_MANAGE"));
    }

    @Test void strictWorkloadTagsAreIntersectedEvenWhenLegacyAclModeIsClient() {
        com.jh.knowledgeos.mapper.ExternalApiMapper mapper = mock(com.jh.knowledgeos.mapper.ExternalApiMapper.class);
        when(mapper.getPrincipalKnowledgeBaseGrantPermissionTags(1L, 2L)).thenReturn("[\"engineering\",\"finance\"]");
        when(mapper.getPrincipalKnowledgeBaseGrantPermissionTags(10L, 2L)).thenReturn("[\"engineering\"]");
        ApiKey key = new ApiKey(); key.setPrincipalId(1L); key.setIdentityMode("APPLICATION_WORKLOAD");
        key.setWorkloadIdentity(new WorkloadIdentity(10L, "session", "call"));
        RetrievalAuthorizationService service = new RetrievalAuthorizationService(new com.fasterxml.jackson.databind.ObjectMapper(), "CLIENT", mapper);
        assertEquals(Collections.singletonList("engineering"), service.effectiveTags(key, 2L, Arrays.asList("engineering", "finance"), "REST"));
    }
}
