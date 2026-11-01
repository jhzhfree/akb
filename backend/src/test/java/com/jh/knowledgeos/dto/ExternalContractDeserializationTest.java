package com.jh.knowledgeos.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * 对外契约的反序列化：知识库一律收编码。
 *
 * <p>这组用例的存在理由是一次线上 500：前端已经改传 {@code KB_xxxx}，而建 Key 的 DTO 还是
 * {@code Long knowledgeBaseId}，Jackson 当场抛 Cannot deserialize value of type Long。
 * 那种错只在真的发一次请求时才看得见，编译期和常规用例都抓不到，所以在这里钉住。</p>
 */
class ExternalContractDeserializationTest {

    private final ObjectMapper json = new ObjectMapper();

    /** 报错现场：建 Key 时传知识库编码。 */
    @Test void keyRequestAcceptsKnowledgeBaseCode() throws Exception {
        ExternalKeyRequest request = json.readValue(
            "{\"name\":\"octop\",\"knowledgeBaseId\":\"KB_a59ae5dd679944aaa7e2826cc548aeec\",\"scopes\":[\"READ\"]}",
            ExternalKeyRequest.class);
        assertEquals("KB_a59ae5dd679944aaa7e2826cc548aeec", request.getKnowledgeBaseId());
    }

    /** 过渡期：老脚本传数字也要收得下，落进来是字符串，解析在 Service 里做。 */
    @Test void keyRequestStillAcceptsNumericId() throws Exception {
        assertEquals("6", json.readValue("{\"knowledgeBaseId\":6}", ExternalKeyRequest.class).getKnowledgeBaseId());
    }

    @Test void queryRequestAcceptsKnowledgeBaseCode() throws Exception {
        ExternalQueryRequest request = json.readValue(
            "{\"knowledgeBaseId\":\"KB_OPS\",\"question\":\"报销流程是什么？\"}", ExternalQueryRequest.class);
        assertEquals("KB_OPS", request.getKnowledgeBaseId());
    }

    @Test void ingestRequestAcceptsKnowledgeBaseCode() throws Exception {
        assertEquals("KB_OPS", json.readValue("{\"knowledgeBaseId\":\"KB_OPS\",\"text\":\"...\"}",
            ExternalIngestRequest.class).getKnowledgeBaseId());
    }

    @Test void embedTokenRevokeRequestAcceptsKnowledgeBaseCode() throws Exception {
        assertEquals("KB_OPS", json.readValue("{\"knowledgeBaseId\":\"KB_OPS\",\"jti\":\"jti-1\"}",
            EmbedTokenRevokeRequest.class).getKnowledgeBaseId());
    }

    @Test void grantRequestAcceptsKnowledgeBaseCode() throws Exception {
        assertEquals("KB_OPS", json.readValue("{\"knowledgeBaseId\":\"KB_OPS\",\"expiresAt\":null}",
            ExternalKnowledgeBaseGrantRequest.class).getKnowledgeBaseId());
    }
}
