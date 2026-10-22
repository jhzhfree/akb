package com.jh.knowledgeos.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jh.knowledgeos.config.KnowledgeProperties;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.service.AgentMemoryService;
import com.jh.knowledgeos.service.ExternalAccessService;
import com.jh.knowledgeos.service.ExternalAuditService;
import com.jh.knowledgeos.service.ExternalKnowledgeFacade;
import com.jh.knowledgeos.service.KbRetrievalService;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** HTTP contract shape for the third-party retrieval endpoint. */
class ExternalApiRetrievalTest {

    private ExternalAccessService access;

    private KbRetrievalService retrievals;

    private ExternalApiController controller;

    private ApiKey key;

    @BeforeEach
    void setUp() {
        access = mock(ExternalAccessService.class);
        retrievals = mock(KbRetrievalService.class);
        KnowledgeProperties properties = new KnowledgeProperties();
        KnowledgeProperties.External external = new KnowledgeProperties.External();
        external.setEnabled(true);
        properties.setExternal(external);
        controller = new ExternalApiController(access, mock(ExternalKnowledgeFacade.class),
            mock(ExternalAuditService.class), mock(AgentMemoryService.class), retrievals, properties);
        key = new ApiKey();
    }

    @Test
    void successReturnsProtocolChunksAndUsesAuthorizedKnowledgeBase() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(access.authenticateEmbed(request)).thenReturn(key);
        when(retrievals.resolveKnowledgeBase(eq(key), isNull(), isNull())).thenReturn(41L);
        when(access.resolveKnowledgeBase(key, 41L)).thenReturn(41L);
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("chunks", Collections.singletonList(Collections.singletonMap("id", "EVD_1")));
        when(retrievals.retrieve(key, 41L, "question", 3, false, false)).thenReturn(data);

        Map<String, Object> body = new HashMap<String, Object>();
        body.put("question", "question");
        body.put("topK", 3);
        ResponseEntity<Object> response = controller.retrievals(request, body);

        verify(access).assertScope(key, ApiKey.SCOPE_SEARCH);
        assertEquals(200, response.getStatusCodeValue());
        assertEquals(data, response.getBody());
    }

    @Test
    void includeAnswerRequiresQueryInAdditionToSearch() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(access.authenticateEmbed(request)).thenReturn(key);
        when(retrievals.resolveKnowledgeBase(eq(key), isNull(), isNull())).thenReturn(41L);
        when(access.resolveKnowledgeBase(key, 41L)).thenReturn(41L);
        doThrow(new SecurityException("missing QUERY scope")).when(access).assertScope(key, ApiKey.SCOPE_QUERY);
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("question", "question");
        body.put("includeAnswer", true);

        ResponseEntity<Object> response = controller.retrievals(request, body);

        verify(access).assertScope(key, ApiKey.SCOPE_SEARCH);
        verify(access).assertScope(key, ApiKey.SCOPE_QUERY);
        verify(retrievals, never()).retrieve(key, 41L, "question", null, true, false);
        assertEquals(403, response.getStatusCodeValue());
        assertError(response.getBody(), "FORBIDDEN");
    }

    @Test
    void invalidTopKReturnsStandardErrorShape() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(access.authenticateEmbed(request)).thenReturn(key);
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("question", "question");
        body.put("topK", 0);

        ResponseEntity<Object> response = controller.retrievals(request, body);

        assertEquals(400, response.getStatusCodeValue());
        assertError(response.getBody(), "INVALID_ARGUMENT");
    }

    @Test
    void unknownKnowledgeBaseReturnsStandardNotFoundError() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Knowledge-Base-Id", "999");
        when(access.authenticateEmbed(request)).thenReturn(key);
        when(retrievals.resolveKnowledgeBase(eq(key), eq("999"), isNull()))
            .thenThrow(new com.jh.knowledgeos.common.KbRetrievalException(404, "KB_NOT_FOUND", "missing"));
        Map<String, Object> body = Collections.<String, Object>singletonMap("question", "question");

        ResponseEntity<Object> response = controller.retrievals(request, body);

        assertEquals(404, response.getStatusCodeValue());
        assertError(response.getBody(), "KB_NOT_FOUND");
    }

    @SuppressWarnings("unchecked")
    private static void assertError(Object body, String expectedCode) {
        assertTrue(body instanceof Map);
        assertEquals(expectedCode, ((Map<String, Object>) body).get("code"));
        assertTrue(((Map<String, Object>) body).containsKey("message"));
    }
}
