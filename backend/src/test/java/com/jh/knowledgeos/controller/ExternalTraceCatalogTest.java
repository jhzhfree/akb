package com.jh.knowledgeos.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.jh.knowledgeos.config.KnowledgeProperties;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ExternalTraceCatalogTest {
    @Test void catalogMarksTraceDisabledWithoutChangingEndpointSchema() {
        User admin = new User();
        admin.setRole("ADMIN");
        AuthContext.set(admin);
        try {
            McpServerService mcp = mock(McpServerService.class);
            when(mcp.toolsList()).thenReturn(Collections.emptyMap());
            ExternalServiceAdminController controller = new ExternalServiceAdminController(
                mock(ApiKeyService.class), null, mcp, null, null, null,
                new KnowledgeProperties(), null, null, null);
            Map<?, ?> payload = (Map<?, ?>) controller.services().getData();
            Map<?, ?> rest = (Map<?, ?>) payload.get("rest");
            Map<?, ?> trace = ((List<?>) rest.get("endpoints")).stream()
                .map(row -> (Map<?, ?>) row)
                .filter(row -> "/queries/{queryId}".equals(row.get("path")))
                .findFirst().orElseThrow(AssertionError::new);
            assertEquals(new HashSet<String>(Arrays.asList("method", "path", "summary")), trace.keySet());
            assertEquals("GET", trace.get("method"));
            assertTrue(String.valueOf(trace.get("summary")).startsWith("已停用"));
        } finally {
            AuthContext.clear();
        }
    }
}
