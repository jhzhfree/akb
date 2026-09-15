package com.jh.knowledgeos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.common.ApiErrorCode;
import com.jh.knowledgeos.common.KnowledgeStatus;
import java.io.File;
import org.junit.jupiter.api.Test;

public class ApiContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sharedAiContractIsVersionedAndContainsCoreEndpoints() throws Exception {
        File contract = new File("../contracts/ai-api-v1.1.json");
        assertTrue(contract.isFile(), "shared AI contract file missing");
        JsonNode root = mapper.readTree(contract);
        assertEquals("1.1", root.path("version").asText());
        assertEquals("X-Trace-Id", root.path("traceHeader").asText());
        assertNotNull(root.path("endpoints").get("query"));
        assertNotNull(root.path("endpoints").get("extract"));
        assertNotNull(root.path("endpoints").get("adjudicate"));
        assertTrue(root.path("endpoints").path("query").path("responseRequired").toString().contains("trace"));
    }

    @Test
    void statusAndErrorCodesAreStable() {
        assertTrue(KnowledgeStatus.isRetrievable("APPROVED"));
        assertTrue(KnowledgeStatus.isRetrievable("EFFECTIVE"));
        assertTrue(!KnowledgeStatus.isRetrievable("SUPERSEDED"));
        assertEquals("KB-502-001", ApiErrorCode.AI_SERVICE_ERROR.getCode());
    }
}
