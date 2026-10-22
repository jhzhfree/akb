package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jh.knowledgeos.config.KnowledgeProperties;
import com.jh.knowledgeos.domain.ApiKey;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ExternalAccessServiceTest {

    private final ApiKeyService keys = mock(ApiKeyService.class);
    private final KnowledgeBaseAccessService kbAccess = mock(KnowledgeBaseAccessService.class);
    private final ExternalAccessService service = new ExternalAccessService(keys, mock(ExternalRateLimiter.class), kbAccess,
        mock(EmbedTokenService.class), mock(EmbedTokenLifecycleService.class), new KnowledgeProperties());

    @Test
    void principalGrantAllowsMultipleBasesAndRejectsAnUnlistedBase() {
        ApiKey key = key(19L, 10L);
        when(keys.grantedKnowledgeBases(key)).thenReturn(Arrays.asList(10L, 11L));

        assertEquals(11L, service.resolveKnowledgeBase(key, 11L));
        assertThrows(SecurityException.class, () -> service.resolveKnowledgeBase(key, 12L));
        verify(kbAccess, never()).assertExternalAccess(12L, false);
    }

    @Test
    void revokingLegacyBoundGrantDoesNotFallBackToTheOldBoundColumn() {
        ApiKey key = key(19L, 10L);
        when(keys.grantedKnowledgeBases(key)).thenReturn(Collections.<Long>emptyList());

        assertThrows(SecurityException.class, () -> service.resolveKnowledgeBase(key, 10L));
        verify(kbAccess, never()).assertExternalAccess(10L, false);
    }

    @Test
    void unboundPrincipalWithExplicitGrantsCannotFallBackToOtherPublicBases() {
        ApiKey key = key(19L, null);
        when(keys.grantedKnowledgeBases(key)).thenReturn(Collections.singletonList(10L));

        assertThrows(SecurityException.class, () -> service.resolveKnowledgeBase(key, 11L));
        verify(kbAccess, never()).assertExternalAccess(11L, false);
    }

    @Test
    void delegatedEmployeeAccessIsTheIntersectionOfAgentAndEmployeeGrants() {
        ApiKey key = key(19L, null);
        key.setDelegatedPrincipalId(27L);
        when(keys.grantedKnowledgeBases(key)).thenReturn(Arrays.asList(10L, 11L));
        when(keys.grantedKnowledgeBases(27L)).thenReturn(Collections.singletonList(11L));

        assertEquals(Collections.singletonList(11L), service.grantedKnowledgeBases(key));
        assertEquals(11L, service.resolveKnowledgeBase(key, 11L));
        assertThrows(SecurityException.class, () -> service.resolveKnowledgeBase(key, 10L));
        assertThrows(SecurityException.class, () -> service.resolveKnowledgeBase(key, 12L));
        verify(kbAccess, never()).assertExternalAccess(12L, false);
    }

    @Test
    void delegatedAuthenticationRequiresSeparateAgentKeyAndOidcBearer() {
        DelegatedIdentityService identity = mock(DelegatedIdentityService.class);
        when(identity.isEnabled()).thenReturn(true);
        when(identity.authenticateBearer("Bearer signed-jwt")).thenReturn(27L);
        ApiKey key = key(19L, null);
        key.setPrincipalType("APPLICATION");
        when(keys.authenticate("agent-secret")).thenReturn(key);
        ExternalAccessService delegated = new ExternalAccessService(keys, mock(ExternalRateLimiter.class), kbAccess,
            mock(EmbedTokenService.class), mock(EmbedTokenLifecycleService.class), new KnowledgeProperties(), identity);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ExternalAccessService.HEADER, "agent-secret");
        request.addHeader("Authorization", "Bearer signed-jwt");
        assertEquals(27L, delegated.authenticate(request).getDelegatedPrincipalId());

        MockHttpServletRequest missingAgentKey = new MockHttpServletRequest();
        missingAgentKey.addHeader("Authorization", "Bearer signed-jwt");
        assertThrows(com.jh.knowledgeos.exception.UnauthorizedException.class, () -> delegated.authenticate(missingAgentKey));
    }

    private ApiKey key(Long principalId, Long knowledgeBaseId) {
        ApiKey key = new ApiKey();
        key.setPrincipalId(principalId);
        key.setKnowledgeBaseId(knowledgeBaseId);
        return key;
    }
}
