package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.common.ClientIpResolver;
import com.jh.knowledgeos.domain.ApiCallAudit;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.domain.WorkloadIdentity;
import com.jh.knowledgeos.mapper.ExternalApiMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import org.mockito.ArgumentCaptor;

/**
 * 调用日志的「调用方」要写清是谁在调：服务凭证名称 * Agent名称 * UUID。
 *
 * <p>一枚凭证下可以挂多个 Agent，只写凭证名的话，日志只能说"这个凭证下的某个 Agent 调了"，
 * 追责时得回去翻关联表。三段少了一段就不好对齐，所以缺失的段保留占位而不是整段省略。</p>
 */
class ExternalAuditCallerNameTest {

    private static final String UUID_TEXT = "3f2b1c9e-5a7d-4b8f-9c0e-1d2a3b4c5d6e";

    private ApiCallAudit record(ApiKey key) {
        ExternalApiMapper mapper = mock(ExternalApiMapper.class);
        ExternalAuditService service = new ExternalAuditService(mapper, new ObjectMapper(),
            mock(ClientIpResolver.class), 10000);
        service.record(key, "REST", "POST /query", 6L, 200, 12, null);
        ArgumentCaptor<ApiCallAudit> written = ArgumentCaptor.forClass(ApiCallAudit.class);
        verify(mapper).insertApiCallAudit(written.capture());
        return written.getValue();
    }

    private static ApiKey credential(String name) {
        ApiKey key = new ApiKey();
        key.setName(name);
        key.setKeyCode("ak_live_test");
        return key;
    }

    private static WorkloadIdentity agent(String agentName, String uuid) {
        WorkloadIdentity identity = new WorkloadIdentity(5L, "session-1", "invocation-1", "DIGEST_HMAC",
            "kid-1", 9L, "message-1", "platform-1", uuid, "ACTIVE");
        return identity.withAgentNames("runtime-agent", agentName);
    }

    @Test void auditRecordsAuthorizationOutcomeWithoutTreatingIdentityReadAsAuthorization() throws Exception {
        ApiKey key = credential("service"); key.setIdentityMode("APPLICATION_WORKLOAD");
        key.setWorkloadIdentity(agent("build", UUID_TEXT));
        try (CallerContext.Scope ignored = CallerContext.open(key)) {
            CallerContext.current();
            CallerContext.decision("SEARCH", 6L, false, "RESOURCE_DENIED");
            Map<?,?> data = new ObjectMapper().readValue(record(key).getResponseData(), Map.class);
            Map<?,?> context = (Map<?,?>) data.get("callerContext");
            org.junit.jupiter.api.Assertions.assertNotNull(context);
            java.util.List<?> decisions = (java.util.List<?>) context.get("authorizationDecisions");
            assertEquals("deny", ((Map<?,?>)decisions.get(0)).get("decision"));
        }
    }

    @Test void workloadCallNamesCredentialThenAgentThenUuid() {
        ApiKey key = credential("结算中心");
        key.setWorkloadIdentity(agent("客服助手", UUID_TEXT));

        assertEquals("结算中心 * 客服助手 * " + UUID_TEXT, record(key).getCallerName());
    }

    @Test void missingSegmentsKeepTheirColumn() {
        ApiKey key = credential("结算中心");
        key.setWorkloadIdentity(agent(null, null));

        // 缺什么补一个占位：整段省略的话，这行跟别行对不齐，扫日志时看不出少了哪一段。
        assertEquals("结算中心 * — * —", record(key).getCallerName());
    }

    @Test void callWithoutAgentStaysJustTheCredential() {
        assertEquals("结算中心", record(credential("结算中心")).getCallerName());
    }
}
