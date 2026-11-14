package com.jh.knowledgeos.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.jh.knowledgeos.config.KnowledgeProperties;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Offline boundary tests: no database, HTTP client or AI provider is started. */
class ExternalTraceBoundaryTest {
    private ExternalAccessService access;
    private ExternalKnowledgeFacade facade;
    private ExternalApiController controller;

    @BeforeEach
    void setUp() {
        access = mock(ExternalAccessService.class);
        facade = mock(ExternalKnowledgeFacade.class);
        KnowledgeProperties properties = new KnowledgeProperties();
        KnowledgeProperties.External external = new KnowledgeProperties.External();
        external.setEnabled(true);
        properties.setExternal(external);
        controller = new ExternalApiController(access, facade, mock(ExternalAuditService.class),
            mock(AgentMemoryService.class), mock(KbRetrievalService.class), properties);
    }

    private void assertBlocked(ApiKey key) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(access.authenticateEmbed(request)).thenReturn(key);
        when(access.resolveKnowledgeBase(key, (Object) null)).thenReturn(42L);
        SecurityException failure = assertThrows(SecurityException.class,
            () -> controller.trace(request, "another-users-query"));
        assertTrue(failure.getMessage().contains("暂不可用"));
        verify(access).authenticateEmbed(request);
        verify(access).assertScope(key, ApiKey.SCOPE_READ_OBJECT);
        verifyNoInteractions(facade);
    }

    @Test void ordinaryKeyCannotReadTraceById() throws Exception {
        assertBlocked(new ApiKey());
    }

    @Test void delegatedKeyCannotReadTraceById() throws Exception {
        ApiKey key = new ApiKey();
        key.setDelegatedPrincipalId(7L);
        assertBlocked(key);
    }

    @Test void workloadKeyCannotReadTraceEvenAfterKnowledgeBaseResolution() throws Exception {
        ApiKey key = new ApiKey();
        key.setIdentityMode("APPLICATION_WORKLOAD");
        assertBlocked(key);
        verify(access).resolveKnowledgeBase(key, (Object) null);
    }

    @Test void workloadMissingKnowledgeBaseRetainsEarlierValidationFailure() throws Exception {
        ApiKey key = new ApiKey();
        key.setIdentityMode("APPLICATION_WORKLOAD");
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(access.authenticateEmbed(request)).thenReturn(key);
        when(access.resolveKnowledgeBase(key, (Object) null))
            .thenThrow(new IllegalArgumentException("缺少 knowledgeBaseId"));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> controller.trace(request, "another-users-query"));
        assertEquals("缺少 knowledgeBaseId", failure.getMessage());
        verifyNoInteractions(facade);
    }

    @Test void missingCredentialsRetainAuthenticationFailure() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(access.authenticateEmbed(request)).thenThrow(
            new com.jh.knowledgeos.exception.UnauthorizedException("missing API key"));
        assertThrows(com.jh.knowledgeos.exception.UnauthorizedException.class,
            () -> controller.trace(request, "another-users-query"));
        verifyNoInteractions(facade);
    }
}
