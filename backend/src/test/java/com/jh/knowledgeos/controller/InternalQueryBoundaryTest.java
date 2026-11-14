package com.jh.knowledgeos.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.dto.QueryRequest;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.jh.knowledgeos.service.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

/** Offline controller boundary tests; no real database or AI service. */
class InternalQueryBoundaryTest {
    KnowledgeService service;
    KnowledgeBaseAccessService access;
    AclContextResolver resolver;
    KnowledgeQueryController controller;

    @BeforeEach void setup() {
        AuthContext.clear();
        service = mock(KnowledgeService.class);
        access = mock(KnowledgeBaseAccessService.class);
        resolver = mock(AclContextResolver.class);
        when(resolver.resolve(null)).thenReturn(Collections.emptyList());
        when(resolver.currentSubjectCode()).thenReturn("viewer");
        user("KNOWLEDGE_VIEWER");
        controller = new KnowledgeQueryController(service, new ObjectMapper(), resolver, access);
    }
    @AfterEach void cleanup() { AuthContext.clear(); RequestContextHolder.resetRequestAttributes(); }
    Map<String, Object> request(Object kb) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("knowledgeBaseId", kb);
        request.put("query", "example");
        request.put("permissionTags", Arrays.asList("secret"));
        return request;
    }
    void user(String role) {
        User user = new User(); user.setId(1L); user.setRole(role); user.setStatus("ACTIVE"); user.setUserCode("viewer");
        AuthContext.set(user);
    }
    @Test void retrieveReplacesForgedTagsAndCanonicalizesKnowledgeBase() throws Exception {
        when(resolver.resolve(null)).thenReturn(Collections.emptyList());
        Map<String, Object> input = request("42");
        input.put("knowledge_base_id", 999L); input.put("permission_tags", Arrays.asList("secret"));
        controller.retrieve(input);
        ArgumentCaptor<Map> payload = ArgumentCaptor.forClass(Map.class);
        verify(access).assertRead(42L); verify(service).retrieve(payload.capture());
        assertEquals(42L, payload.getValue().get("knowledgeBaseId"));
        assertEquals(Collections.emptyList(), payload.getValue().get("permissionTags"));
        assertFalse(payload.getValue().containsKey("knowledge_base_id"));
        assertFalse(payload.getValue().containsKey("permission_tags"));
        assertEquals(Arrays.asList("secret"), input.get("permissionTags"));
    }
    @Test void answerUsesOnlyServerTags() throws Exception {
        when(resolver.resolve(null)).thenReturn(Arrays.asList("team-a"));
        controller.answer(request(42));
        ArgumentCaptor<Map> payload = ArgumentCaptor.forClass(Map.class);
        verify(access).assertRead(42L); verify(service).answer(payload.capture());
        assertEquals(Arrays.asList("team-a"), payload.getValue().get("permissionTags"));
    }
    @Test void unrestrictedResolverRemovesClientTags() throws Exception {
        user("ADMIN"); when(resolver.resolve(null)).thenReturn(null);
        controller.retrieve(request(42L));
        ArgumentCaptor<Map> payload = ArgumentCaptor.forClass(Map.class);
        verify(service).retrieve(payload.capture());
        assertFalse(payload.getValue().containsKey("permissionTags"));
    }
    @Test void inaccessibleKnowledgeBaseNeverReachesEitherService() {
        doThrow(new SecurityException("denied")).when(access).assertRead(42L);
        assertThrows(SecurityException.class, () -> controller.retrieve(request(42L)));
        assertThrows(SecurityException.class, () -> controller.answer(request(42L)));
        verifyNoInteractions(service, resolver);
    }
    @Test void invalidKnowledgeBasesNeverReachAuthorizationOrService() {
        for (Object value : Arrays.asList(null, "", 0, -1, 1.5, 1.0, true, "9223372036854775808", Collections.emptyMap(), Arrays.asList(1))) {
            assertThrows(IllegalArgumentException.class, () -> controller.retrieve(request(value)));
            assertThrows(IllegalArgumentException.class, () -> controller.answer(request(value)));
        }
        assertThrows(IllegalArgumentException.class, () -> controller.retrieve(null));
        verifyNoInteractions(access, resolver, service);
    }
    @Test void unresolvedOrSpoofedContentIdentityIsDenied() {
        when(resolver.resolve(null)).thenReturn(null);
        assertThrows(SecurityException.class, () -> controller.retrieve(request(42)));
        when(resolver.resolve(null)).thenReturn(Arrays.asList("secret"));
        when(resolver.currentSubjectCode()).thenReturn("someone-else");
        assertThrows(SecurityException.class, () -> controller.answer(request(42)));
        AuthContext.clear();
        assertThrows(SecurityException.class, () -> controller.retrieve(request(42)));
        verifyNoInteractions(service);
    }
    QueryRequest typedQuery() {
        QueryRequest request = new QueryRequest();
        request.setKnowledgeBaseId(42L); request.setQuery("example");
        request.setPermissionTags(Arrays.asList("secret"));
        return request;
    }
    @Test void queryUsesSameServerGuardAndIgnoresTagsHeader() throws Exception {
        controller.query(typedQuery(), "secret");
        ArgumentCaptor<Map> payload = ArgumentCaptor.forClass(Map.class);
        verify(service).query(payload.capture()); verify(access).assertRead(42L);
        assertEquals(Collections.emptyList(), payload.getValue().get("permissionTags"));
        verify(resolver).resolve(null);
    }
    @Test void queryRejectsSpoofedAuthenticatedUserHeaderUsingRealResolver() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        AclContextResolver realResolver = new AclContextResolver(mapper, true, false);
        controller = new KnowledgeQueryController(service, new ObjectMapper(), realResolver, access);
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.addHeader("X-Authenticated-User-Id", "another-user");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(http));
        assertThrows(SecurityException.class, () -> controller.query(typedQuery(), "secret"));
        verifyNoInteractions(mapper, service);
    }
    @Test void queryRejectsDisabledIdentityResolutionEvenWithTrustedTagsHeader() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        AclContextResolver realResolver = new AclContextResolver(mapper, false, true);
        controller = new KnowledgeQueryController(service, new ObjectMapper(), realResolver, access);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        assertThrows(SecurityException.class, () -> controller.query(typedQuery(), "secret"));
        verifyNoInteractions(mapper, service);
    }
    @Test void queryRejectsMissingSubjectAndUnauthorizedKnowledgeBase() {
        AuthContext.get().setUserCode(" ");
        assertThrows(SecurityException.class, () -> controller.query(typedQuery(), null));
        user("KNOWLEDGE_VIEWER");
        doThrow(new SecurityException("denied")).when(access).assertRead(42L);
        assertThrows(SecurityException.class, () -> controller.query(typedQuery(), null));
        verifyNoInteractions(service);
    }
    @Test void privateMemoryRejectsSpoofedSubjectForEveryRoleIncludingAdmins() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        AclContextResolver realResolver = new AclContextResolver(mapper, true, false);
        controller = new KnowledgeQueryController(service, new ObjectMapper(), realResolver, access);
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.addHeader("X-Authenticated-User-Id", "another-user");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(http));
        for (String role : Arrays.asList("ADMIN", "KNOWLEDGE_ADMIN", "KNOWLEDGE_VIEWER")) {
            user(role);
            QueryRequest request = typedQuery(); request.setIncludeMemory(true);
            assertThrows(SecurityException.class, () -> controller.query(request, null));
            Map<String, Object> map = request(42); map.put("includeMemory", "true");
            assertThrows(SecurityException.class, () -> controller.answer(map));
            assertThrows(SecurityException.class, () -> controller.retrieve(map));
        }
        verifyNoInteractions(mapper, service);
    }
    @Test void adminPrivateMemoryRequiresNonblankLoginSubject() {
        for (String role : Arrays.asList("ADMIN", "KNOWLEDGE_ADMIN")) {
            user(role); AuthContext.get().setUserCode(" ");
            QueryRequest request = typedQuery(); request.setIncludeMemory(true);
            assertThrows(SecurityException.class, () -> controller.query(request, null));
        }
        verifyNoInteractions(service);
    }
    @Test void adminOwnPrivateMemoryRemainsAvailable() throws Exception {
        user("ADMIN"); when(resolver.resolve(null)).thenReturn(null);
        QueryRequest request = typedQuery(); request.setIncludeMemory(true);
        controller.query(request, null);
        ArgumentCaptor<Map> payload = ArgumentCaptor.forClass(Map.class);
        verify(service).query(payload.capture());
        assertEquals(Boolean.TRUE, payload.getValue().get("includeMemory"));
    }
    @Test void anonymousTraceAndQualificationDenied() {
        AuthContext.clear();
        assertThrows(SecurityException.class, () -> controller.trace("q"));
        assertThrows(SecurityException.class, () -> controller.answerQualification("q"));
        verifyNoInteractions(service);
    }
    @Test void restrictedRolesCannotReadRawBundlesEvenWithNoResolvedTags() {
        for (String role : Arrays.asList("KNOWLEDGE_VIEWER", "KNOWLEDGE_ADMIN", "KNOWLEDGE_OPERATOR")) {
            user(role);
            assertThrows(SecurityException.class, () -> controller.trace("q"));
            assertThrows(SecurityException.class, () -> controller.answerQualification("q"));
        }
        verifyNoInteractions(service);
    }
    @Test void constrainedAdminDenied() {
        user("ADMIN"); when(resolver.resolve(null)).thenReturn(Collections.emptyList());
        assertThrows(SecurityException.class, () -> controller.trace("q"));
        assertThrows(SecurityException.class, () -> controller.answerQualification("q"));
        verifyNoInteractions(service);
    }
    @Test void inactiveAdminDenied() {
        user("ADMIN"); AuthContext.get().setStatus("DISABLED");
        assertThrows(SecurityException.class, () -> controller.trace("q"));
        assertThrows(SecurityException.class, () -> controller.answerQualification("q"));
        verifyNoInteractions(service);
    }
    @Test void activeUnrestrictedPlatformAdminRetainsDiagnosticAccess() {
        user("ADMIN"); when(resolver.resolve(null)).thenReturn(null);
        controller.trace("q"); controller.answerQualification("q");
        verify(service).trace("q"); verify(service).answerQualification("q");
    }
}
