package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpAuthorizationCapabilitiesTest {
    private Map<?, ?> report(McpServerService server) {
        Map<?, ?> hello = server.initialize();
        assertEquals(McpServerService.PROTOCOL_VERSION, hello.get("protocolVersion"));
        Map<?, ?> capabilities = (Map<?, ?>) hello.get("capabilities");
        assertTrue(capabilities.containsKey("tools"));
        assertTrue(capabilities.containsKey("resources"));
        return (Map<?, ?>) ((Map<?, ?>) capabilities.get("experimental")).get("akb.authorization");
    }

    @Test
    void reportsOnlyDeploymentFieldsWithoutInvokingBusinessServices() {
        ExternalAccessService access = mock(ExternalAccessService.class);
        ExternalKnowledgeFacade facade = mock(ExternalKnowledgeFacade.class);
        McpServerService server = new McpServerService(access, facade, new ObjectMapper());
        for (RetrievalAuthorizationService.Mode mode : RetrievalAuthorizationService.Mode.values()) {
            for (boolean enabled : new boolean[] {false, true}) {
                RetrievalAuthorizationService authorization = mock(RetrievalAuthorizationService.class);
                DelegatedIdentityService delegation = mock(DelegatedIdentityService.class);
                when(authorization.mode()).thenReturn(mode);
                when(delegation.isOctopEnabled()).thenReturn(enabled);
                server.configureAuthorizationCapabilities(authorization, delegation);
                Map<?, ?> value = report(server);
                assertEquals(new HashSet<String>(Arrays.asList("schema_version", "workload_v2_user_delegation_supported",
                    "legacy_agent_delegation_configured", "external_content_tag_mode", "workload_content_tag_enforcement",
                    "explicit_external_user_binding_required", "empty_tag_grant_semantics", "capability_is_authorization")), value.keySet());
                assertEquals(1, value.get("schema_version"));
                assertEquals(false, value.get("workload_v2_user_delegation_supported"));
                assertEquals(enabled, value.get("legacy_agent_delegation_configured"));
                assertEquals(mode.name(), value.get("external_content_tag_mode"));
                assertEquals(true, value.get("workload_content_tag_enforcement"));
                assertEquals(true, value.get("explicit_external_user_binding_required"));
                assertEquals("PUBLIC_ONLY", value.get("empty_tag_grant_semantics"));
                assertEquals(false, value.get("capability_is_authorization"));
                verify(authorization).mode(); verify(delegation).isOctopEnabled();
                verifyNoMoreInteractions(authorization, delegation);
            }
        }
        verifyNoInteractions(access, facade);
    }

    @Test
    void compatibilityConstructorReportsUnknownNotAssumedEnforcement() {
        Map<?, ?> value = report(new McpServerService(null, null, new ObjectMapper()));
        assertEquals("UNKNOWN", value.get("external_content_tag_mode"));
        assertEquals(false, value.get("legacy_agent_delegation_configured"));
        assertEquals(false, value.get("capability_is_authorization"));
    }
}
