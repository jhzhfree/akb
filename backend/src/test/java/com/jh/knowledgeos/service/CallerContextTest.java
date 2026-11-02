package com.jh.knowledgeos.service;

import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.domain.WorkloadIdentity;
import com.jh.knowledgeos.config.CallerContextFilter;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class CallerContextTest {
    private ApiKey agent(String id) {
        ApiKey key = new ApiKey(); key.setId(1L); key.setPrincipalId(123L); key.setIdentityMode("APPLICATION_WORKLOAD");
        key.setWorkloadIdentity(new WorkloadIdentity(4L, "session", "invocation", AgentProofCrypto.V2,
            "kid", 3L, null, "opencode", id, "ACTIVE").withAgentNames("build", "build"));
        return key;
    }
    @Test void concurrentRequestsAndExplicitPropagationKeepTheirOwnAgent() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<String> a, b;
            try (CallerContext.Scope ignored = CallerContext.open(agent("agent-a"))) {
                a = CallerContext.wrap(() -> CallerContext.requireAgent().getExternalAgentId());
            }
            try (CallerContext.Scope ignored = CallerContext.open(agent("agent-b"))) {
                b = CallerContext.wrap(() -> CallerContext.requireAgent().getExternalAgentId());
            }
            java.util.concurrent.Future<String> first = pool.submit(a), second = pool.submit(b);
            assertEquals("agent-a", first.get()); assertEquals("agent-b", second.get());
            assertTrue(pool.submit(() -> CallerContext.audit().isEmpty()).get());
            assertThrows(SecurityException.class, CallerContext::current);
        } finally { pool.shutdownNow(); }
    }
    @Test void runnablePropagationCleansWorkerAfterFailure() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Runnable work;
            try (CallerContext.Scope ignored = CallerContext.open(agent("agent-a"))) {
                work = CallerContext.wrap((Runnable) () -> {
                    assertEquals("agent-a", CallerContext.requireAgent().getExternalAgentId());
                    throw new IllegalStateException("failure");
                });
            }
            assertThrows(java.util.concurrent.ExecutionException.class, () -> pool.submit(work).get());
            assertTrue(pool.submit(() -> CallerContext.audit().isEmpty()).get());
        } finally { pool.shutdownNow(); }
    }
    @Test void serviceAndDiscoveryNeverInventAnAgentAndDecisionsAreSeparateFromReads() {
        ApiKey key = new ApiKey(); key.setPrincipalId(123L);
        try (CallerContext.Scope ignored = CallerContext.open(key)) {
            assertEquals("service", CallerContext.current().getActorType());
            assertThrows(SecurityException.class, CallerContext::requireAgent);
            CallerContext.decision("SEARCH", 6L, false, "RESOURCE_DENIED");
            assertEquals(1, ((java.util.List<?>) CallerContext.audit().get("authorizationDecisions")).size());
        }
        key.setIdentityMode("APPLICATION_WORKLOAD");
        try (CallerContext.Scope ignored = CallerContext.open(key)) {
            assertEquals("service", CallerContext.current().getActorType());
            assertThrows(SecurityException.class, CallerContext::requireAgent);
        }
    }
    @Test void filterCleansContextAfterBusinessFailure() {
        CallerContextFilter filter = new CallerContextFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mcp");
        assertThrows(javax.servlet.ServletException.class, () -> filter.doFilter(request, new MockHttpServletResponse(), (req,res) -> {
            CallerContext.authenticated(agent("agent-a"));
            assertEquals("agent-a", CallerContext.requireAgent().getExternalAgentId());
            throw new javax.servlet.ServletException("business failure");
        }));
        assertThrows(SecurityException.class, CallerContext::current);
        assertTrue(CallerContext.audit().isEmpty());
    }
    @Test void nestedScopesRestoreCallerAndSnapshotsContainNoSecrets() {
        ApiKey key = agent("agent-a");
        try (CallerContext.Scope ignored = CallerContext.open(key)) {
            Map<String,Object> snapshot = CallerContext.current().describe();
            assertThrows(UnsupportedOperationException.class, () -> snapshot.put("agentId", "forged"));
            try (CallerContext.Scope nested = CallerContext.open(agent("agent-b"))) {
                assertEquals("agent-b", CallerContext.requireAgent().getExternalAgentId());
            }
            assertEquals("agent-a", CallerContext.requireAgent().getExternalAgentId());
            assertFalse(snapshot.containsKey("serviceKey")); assertFalse(snapshot.containsKey("privateKey"));
        }
    }
}
