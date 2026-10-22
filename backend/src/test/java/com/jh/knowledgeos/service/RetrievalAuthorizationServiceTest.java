package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.common.RetrievalAuthorizationContext;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.mapper.ExternalApiMapper;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 外部检索标签授权（对应《AKB 知识库检索权限设计》验收用例 3、4 与 §4.1 默认拒绝）。
 */
class RetrievalAuthorizationServiceTest {

    private static final String SOURCE = "REST";

    private static ApiKey keyWith(String tagsJson) {
        ApiKey key = new ApiKey();
        key.setKeyCode("KEY_TEST");
        key.setPermissionTags(tagsJson);
        return key;
    }

    private static RetrievalAuthorizationService service(String mode) {
        return new RetrievalAuthorizationService(new ObjectMapper(), mode);
    }

    private static RetrievalAuthorizationService service(String mode, ExternalApiMapper mapper) {
        return new RetrievalAuthorizationService(new ObjectMapper(), mode, mapper);
    }

    /** 用例 3：调用方伪造受限标签，不能召回对应资料——超出授权的部分被丢弃。 */
    @Test
    void narrowsRequestedTagsToGrantedScope() {
        List<String> effective = service("ENFORCE")
            .effectiveTags(keyWith("[\"team-a\"]"), Arrays.asList("team-a", "team-b"), SOURCE);
        assertEquals(Collections.singletonList("team-a"), effective);
    }

    /** 用例 4：不传标签时不收窄，用授权全集——请求只能缩小范围。 */
    @Test
    void emptyRequestUsesFullGrant() {
        List<String> effective = service("ENFORCE").effectiveTags(keyWith("[\"team-a\",\"team-b\"]"), null, SOURCE);
        assertEquals(Arrays.asList("team-a", "team-b"), effective);
    }

    /** 已配置授权但范围为空：只能检索公开资料。 */
    @Test
    void emptyGrantDeniesTaggedResources() {
        List<String> effective = service("ENFORCE")
            .effectiveTags(keyWith("[]"), Collections.singletonList("team-a"), SOURCE);
        assertTrue(effective.isEmpty(), "空授权下任何标签都不可用");
    }

    /** §4.1 默认拒绝：未配置标签授权的凭证，在强制模式下按最严格处理。 */
    @Test
    void unconfiguredKeyDeniesByDefaultUnderEnforce() {
        List<String> effective = service("ENFORCE")
            .effectiveTags(keyWith(null), Collections.singletonList("team-a"), SOURCE);
        assertTrue(effective.isEmpty(), "未配置授权时请求标签不得生效");
    }

    /** 影子模式：仍按旧行为执行，避免一刀切打断现有调用方。 */
    @Test
    void shadowModeKeepsLegacyBehaviour() {
        List<String> requested = Arrays.asList("team-a", "team-b");
        assertEquals(requested, service("SHADOW").effectiveTags(keyWith("[\"team-a\"]"), requested, SOURCE));
    }

    /** 应急回滚模式：完全沿用旧行为。 */
    @Test
    void clientModeKeepsLegacyBehaviour() {
        List<String> requested = Collections.singletonList("team-b");
        assertEquals(requested, service("CLIENT").effectiveTags(keyWith("[\"team-a\"]"), requested, SOURCE));
    }

    /** 无凭证的内部调用（控制台链路）不参与收窄。 */
    @Test
    void internalCallWithoutCredentialIsUntouched() {
        List<String> requested = Collections.singletonList("team-a");
        assertEquals(requested, service("ENFORCE").effectiveTags(null, requested, SOURCE));
    }

    /** 授权配置损坏时按最严格处理，不能因为解析失败就退化成无约束放行。 */
    @Test
    void brokenGrantConfigFallsBackToDeny() {
        assertTrue(service("ENFORCE")
            .effectiveTags(keyWith("{not-json"), Collections.singletonList("team-a"), SOURCE).isEmpty());
    }

    /** 被丢弃的标签要能报出来：影子模式靠它评估影响面，强制执行时靠它审计试探行为。 */
    @Test
    void removedTagsReportsRejectedTags() {
        RetrievalAuthorizationContext context = service("ENFORCE").forCredential(keyWith("[\"team-a\"]"));
        assertEquals(Collections.singletonList("team-b"), context.removedTags(Arrays.asList("team-a", "team-b")));
        assertTrue(context.removedTags(Arrays.asList("team-a")).isEmpty());
    }

    /** 未知模式回落到 SHADOW，不因为配置写错就把系统置于不可预期状态。 */
    @Test
    void unknownModeFallsBackToShadow() {
        assertEquals(RetrievalAuthorizationService.Mode.SHADOW, service("typo").mode());
    }

    /** 未配置与空授权必须能区分：前者是「还没配」，后者是「配了但什么都不给」。 */
    @Test
    void unconfiguredDiffersFromEmptyGrant() {
        RetrievalAuthorizationService shadow = service("SHADOW");
        RetrievalAuthorizationContext unconfigured = shadow.forCredential(keyWith(null));
        assertEquals("<未配置>", unconfigured.grantedTagsForLog());
        assertFalse(unconfigured.configured());
        assertTrue(shadow.forCredential(keyWith("[]")).configured());
    }

    @Test
    void knowledgeBaseGrantCanOnlyNarrowGlobalKeyTags() {
        ExternalApiMapper mapper = mock(ExternalApiMapper.class);
        when(mapper.getPrincipalKnowledgeBaseGrantPermissionTags(71L, 12L)).thenReturn("[\"team-a\",\"finance\"]");
        when(mapper.getPrincipalKnowledgeBaseGrantPermissionTags(71L, 13L)).thenReturn("[\"engineering\"]");
        ApiKey key = keyWith("[\"team-a\",\"engineering\"]");
        key.setPrincipalId(71L);

        List<String> effective = service("ENFORCE", mapper).effectiveTags(key, 12L,
            Arrays.asList("team-a", "engineering", "finance"), SOURCE);

        assertEquals(Collections.singletonList("team-a"), effective);
        assertEquals(Collections.singletonList("engineering"), service("ENFORCE", mapper).effectiveTags(key, 13L,
            Arrays.asList("team-a", "engineering", "finance"), SOURCE));
    }

    @Test
    void knowledgeBaseEmptyTagGrantMeansPublicResourcesOnly() {
        ExternalApiMapper mapper = mock(ExternalApiMapper.class);
        when(mapper.getPrincipalKnowledgeBaseGrantPermissionTags(71L, 12L)).thenReturn("[]");
        ApiKey key = keyWith(null);
        key.setPrincipalId(71L);

        assertTrue(service("ENFORCE", mapper).effectiveTags(key, 12L, Collections.singletonList("team-a"), SOURCE).isEmpty());
    }
}
