package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jh.knowledgeos.config.WorkloadRequestFilter;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.domain.WorkloadIdentity;
import com.jh.knowledgeos.exception.UnauthorizedException;
import com.jh.knowledgeos.mapper.WorkloadIdentityRepository;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockHttpServletRequest;

class WorkloadIdentityServiceTest {
    private WorkloadIdentityRepository repository;
    private WorkloadIdentityService service;
    private RSAKey rsa;
    private HttpServer server;
    private ApiKey key;
    private final String body = "{\"question\":\"policy\"}";
    private final String issuer = "https://trusted.example/installation";

    @BeforeEach void start() throws Exception {
        rsa = new RSAKeyGenerator(2048).keyID("test-key").generate();
        byte[] jwks = new JWKSet(rsa.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length); exchange.getResponseBody().write(jwks); exchange.close();
        }); server.start();
        repository = mock(WorkloadIdentityRepository.class); service = new WorkloadIdentityService(repository);
        Map<String,Object> policy = new HashMap<String,Object>();
        policy.put("id", 7L); policy.put("application_principal_id", 1L); policy.put("status", "ACTIVE");
        policy.put("issuer", issuer); policy.put("jwks_uri", "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks");
        policy.put("audience", "akb"); policy.put("installation_id", "installation"); policy.put("workspace_id", "workspace");
        when(repository.policy(7L)).thenReturn(policy);
        when(repository.binding(7L, "agent-a", "build")).thenReturn(Collections.<String,Object>singletonMap("agent_principal_id", 10L));
        when(repository.binding(7L, "agent-b", "review")).thenReturn(Collections.<String,Object>singletonMap("agent_principal_id", 11L));
        key = new ApiKey(); key.setPrincipalId(1L); key.setPrincipalType("APPLICATION");
        key.setIdentityMode("APPLICATION_WORKLOAD"); key.setWorkloadPolicyId(7L);
    }
    @AfterEach void stop() { server.stop(0); }

    private String proof(String external, String runtime, String application, String target, Instant expires) throws Exception {
        Map<String,Object> binding = new HashMap<String,Object>();
        binding.put("method", "POST"); binding.put("target", target);
        binding.put("body_sha256", WorkloadIdentityService.sha256(body.getBytes(StandardCharsets.UTF_8)));
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").type(JOSEObjectType.JWT).build(),
            new JWTClaimsSet.Builder().issuer(issuer).audience("akb").subject("agent:" + external)
                .claim("azp", application).claim("installation_id", "installation").claim("workspace_id", "workspace")
                .claim("runtime_agent", runtime).claim("session_id", "session").claim("invocation_id", UUID.randomUUID().toString())
                .claim("request_binding", binding).issueTime(Date.from(Instant.now())).expirationTime(Date.from(expires))
                .jwtID(UUID.randomUUID().toString()).build());
        jwt.sign(new RSASSASigner(rsa)); return jwt.serialize();
    }
    private MockHttpServletRequest request(String proof) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/external/search");
        request.addHeader(ExternalAccessService.HEADER, "synthetic-test-service-key");
        request.addHeader(WorkloadRequestFilter.HEADER, proof);
        request.setAttribute(WorkloadRequestFilter.BODY, body.getBytes(StandardCharsets.UTF_8)); return request;
    }
    @Test void sharedCredentialResolvesTwoDifferentAgentsWithoutChangingItsOwner() throws Exception {
        WorkloadIdentity a = service.authenticate(request(proof("agent-a", "build", "1", "/api/v1/external/search", Instant.now().plusSeconds(60))), key);
        WorkloadIdentity b = service.authenticate(request(proof("agent-b", "review", "1", "/api/v1/external/search", Instant.now().plusSeconds(60))), key);
        assertEquals(10L, a.getAgentPrincipalId()); assertEquals(11L, b.getAgentPrincipalId());
        assertEquals(1L, key.getPrincipalId());
    }
    @Test void rejectsApplicationMismatchBodyTamperingPathTamperingAndUnknownAgent() throws Exception {
        assertThrows(UnauthorizedException.class, () -> service.authenticate(request(proof("agent-a", "build", "2", "/api/v1/external/search", Instant.now().plusSeconds(60))), key));
        MockHttpServletRequest tampered = request(proof("agent-a", "build", "1", "/api/v1/external/search", Instant.now().plusSeconds(60)));
        tampered.setAttribute(WorkloadRequestFilter.BODY, "{}".getBytes(StandardCharsets.UTF_8));
        assertThrows(UnauthorizedException.class, () -> service.authenticate(tampered, key));
        assertThrows(UnauthorizedException.class, () -> service.authenticate(request(proof("agent-a", "build", "1", "/api/mcp", Instant.now().plusSeconds(60))), key));
        assertThrows(SecurityException.class, () -> service.authenticate(request(proof("agent-a", "review", "1", "/api/v1/external/search", Instant.now().plusSeconds(60))), key));
        verify(repository, never()).consume(anyString(), anyString(), any(Instant.class));
    }
    @Test void rejectsReplayAndExpiredOrOverlongProofs() throws Exception {
        Set<String> seen = new HashSet<String>();
        doAnswer(call -> { if (!seen.add(call.getArgument(1))) throw new DuplicateKeyException("replay"); return null; })
            .when(repository).consume(anyString(), anyString(), any(Instant.class));
        String token = proof("agent-a", "build", "1", "/api/v1/external/search", Instant.now().plusSeconds(60));
        service.authenticate(request(token), key);
        assertThrows(UnauthorizedException.class, () -> service.authenticate(request(token), key));
        assertThrows(UnauthorizedException.class, () -> service.authenticate(request(proof("agent-a", "build", "1", "/api/v1/external/search", Instant.now().minusSeconds(1))), key));
        assertThrows(UnauthorizedException.class, () -> service.authenticate(request(proof("agent-a", "build", "1", "/api/v1/external/search", Instant.now().plusSeconds(300))), key));
    }
    @Test void missingProofAndConflictingAuthenticationFailClosed() throws Exception {
        assertThrows(UnauthorizedException.class, () -> service.authenticate(new MockHttpServletRequest(), key));
        MockHttpServletRequest mixed = request(proof("agent-a", "build", "1", "/api/v1/external/search", Instant.now().plusSeconds(60)));
        mixed.addHeader("Authorization", "Bearer other");
        assertThrows(UnauthorizedException.class, () -> service.authenticate(mixed, key));
    }
}
