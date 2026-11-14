package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.service.*;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthoritativeReadBoundaryTest {
    private final AclContextResolver resolver = mock(AclContextResolver.class);
    private final KnowledgeService knowledge = mock(KnowledgeService.class);
    private final EntityGovernanceService entities = mock(EntityGovernanceService.class);
    private final KnowledgeGraphController graphs = new KnowledgeGraphController(knowledge, resolver, mock(AclAuditService.class));
    private final EntityGovernanceController controller = new EntityGovernanceController(entities, resolver);

    @BeforeEach void admin() { setUser("ADMIN", "ACTIVE"); }
    @AfterEach void clear() { AuthContext.clear(); }
    private void setUser(String role, String status) {
        User user = new User(); user.setId(1L); user.setRole(role); user.setStatus(status); user.setUserCode("user"); AuthContext.set(user);
    }
    private void deniedBoth() {
        assertThrows(SecurityException.class, () -> graphs.graphOverview(7L, 24, null));
        assertThrows(SecurityException.class, () -> controller.entityDetail(7L, "entity", null));
        verifyNoInteractions(knowledge, entities);
    }
    @Test void unidentifiedAdminIsDenied() { AuthContext.get().setId(null); deniedBoth(); }
    @Test void anonymousNullAclIsNotAdministrator() { AuthContext.clear(); deniedBoth(); }
    @Test void ordinaryNullAclIsNotAdministrator() { setUser("KNOWLEDGE_VIEWER", "ACTIVE"); deniedBoth(); }
    @Test void knowledgeAdminNullAclIsNotPlatformAdmin() { setUser("KNOWLEDGE_ADMIN", "ACTIVE"); deniedBoth(); }
    @Test void inactiveAdminIsDenied() { setUser("ADMIN", "DISABLED"); deniedBoth(); }
    @Test void missingStatusAdminIsDenied() { setUser("ADMIN", null); deniedBoth(); }
    @Test void identityDbDisabledCannotAuthorizeOrdinaryCaller() {
        setUser("KNOWLEDGE_VIEWER", "ACTIVE");
        AclContextResolver disabled = new AclContextResolver(mock(KnowledgeMapper.class), false, false);
        assertNull(disabled.resolve(null));
        KnowledgeGraphController graph = new KnowledgeGraphController(knowledge, disabled, mock(AclAuditService.class));
        EntityGovernanceController entity = new EntityGovernanceController(entities, disabled);
        assertThrows(SecurityException.class, () -> graph.graphOverview(7L, 24, null));
        assertThrows(SecurityException.class, () -> entity.entityDetail(7L, "entity", null));
        verifyNoInteractions(knowledge, entities);
    }
    @Test void publicOnlyOverviewNeverReadsAuthoritativeProjection() {
        when(resolver.resolve(null)).thenReturn(Collections.emptyList());
        assertThrows(SecurityException.class, () -> graphs.graphOverview(7L, 24, null));
        verifyNoInteractions(knowledge);
    }
    @Test void scopedOverviewNeverReadsAuthoritativeProjection() {
        when(resolver.resolve("ignored-header")).thenReturn(Collections.singletonList("team-a"));
        SecurityException ex = assertThrows(SecurityException.class, () -> graphs.graphOverview(7L, 24, "ignored-header"));
        assertTrue(ex.getMessage().contains("ACL_GRAPH_OVERVIEW_UNAVAILABLE"));
        verifyNoInteractions(knowledge);
    }
    @Test void unconstrainedOverviewRetainsCompatibility() {
        when(resolver.resolve(null)).thenReturn(null);
        graphs.graphOverview(7L, 24, null);
        verify(knowledge).graphOverview(7L, 24);
    }
    @Test void publicOnlyEntityDetailNeverReadsUnfilteredAssociations() {
        when(resolver.resolve(null)).thenReturn(Collections.emptyList());
        assertThrows(SecurityException.class, () -> controller.entityDetail(7L, "entity", null));
        verifyNoInteractions(entities);
    }
    @Test void scopedEntityDetailNeverReadsUnfilteredAssociations() {
        when(resolver.resolve("ignored-header")).thenReturn(Collections.singletonList("team-a"));
        SecurityException ex = assertThrows(SecurityException.class, () -> controller.entityDetail(7L, "entity", "ignored-header"));
        assertTrue(ex.getMessage().contains("ACL_ENTITY_DETAIL_UNAVAILABLE"));
        verifyNoInteractions(entities);
    }
    @Test void unconstrainedEntityDetailRetainsCompatibility() {
        when(resolver.resolve(null)).thenReturn(null);
        controller.entityDetail(7L, "entity", null);
        verify(entities).detail(7L, "entity");
    }
}
