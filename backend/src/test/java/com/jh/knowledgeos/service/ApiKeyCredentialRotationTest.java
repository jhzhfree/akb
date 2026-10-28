package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.mapper.ExternalApiMapper;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 凭据轮换：换掉的应当只是密钥本身。
 *
 * <p>设计验收要求「轮换 Service Key 后新 Key 的 principal 与 Memory identity 不变，旧 Key 按策略
 * 失效」，这两条正是轮换区别于「重新建一把」的地方——主体、授权、记忆身份都必须原样带走。
 */
class ApiKeyCredentialRotationTest {

    private final ExternalApiMapper mapper = mock(ExternalApiMapper.class);
    private final IdGenerator ids = mock(IdGenerator.class);
    private final ApiKeyService service = new ApiKeyService(mapper, ids);

    @Test
    void serviceKeyRotationKeepsPrincipalScopesAndMemoryIdentity() {
        ApiKey existing = new ApiKey();
        existing.setId(41L);
        existing.setStatus(ApiKey.STATUS_ACTIVE);
        existing.setCredentialType(ApiKey.CREDENTIAL_SERVICE_KEY);
        existing.setPrincipalId(901L);
        existing.setScopes("SEARCH,QUERY,MEMORY_MANAGE");
        existing.setMemoryNamespace("octop-prod");
        existing.setMemoryAgentCode("agent-ops");
        existing.setRateLimitPerMinute(120);
        existing.setExpiresAt(LocalDateTime.now().plusDays(30));
        when(mapper.getApiKey(41L)).thenReturn(existing);
        when(ids.next(anyString())).thenReturn("AK_ROTATED");
        ApiKey[] created = captureInsertedKey(42L);

        Map<String, Object> result = service.rotateServiceKey(41L, null, 7L);

        assertEquals(Long.valueOf(901L), created[0].getPrincipalId(), "轮换不能改变主体");
        assertEquals("SEARCH,QUERY,MEMORY_MANAGE", created[0].getScopes(), "轮换不能改变授权范围");
        assertEquals("octop-prod", created[0].getMemoryNamespace(), "轮换不能改变记忆 namespace");
        assertEquals("agent-ops", created[0].getMemoryAgentCode(), "轮换不能改变记忆 Agent 身份");
        assertEquals(Integer.valueOf(120), created[0].getRateLimitPerMinute());
        assertEquals(ApiKey.STATUS_ACTIVE, created[0].getStatus());
        // 没给新的到期时间就沿用旧的：轮换不该顺手把短期 Key 变成长期 Key。
        assertEquals(existing.getExpiresAt(), created[0].getExpiresAt());
        assertNotNull(result.get("secret"), "轮换响应必须一次性返回新明文");
        assertEquals(Long.valueOf(41L), result.get("rotatedFrom"));
        verify(mapper).updateApiKeyStatus(41L, ApiKey.STATUS_REVOKED);
    }

    @Test
    void personalTokenRotationKeepsPrincipalAndRevokesTheOldOne() {
        ApiKey existing = new ApiKey();
        existing.setId(41L);
        existing.setStatus(ApiKey.STATUS_ACTIVE);
        existing.setCredentialType(ApiKey.CREDENTIAL_USER_PAT);
        existing.setOwnerUserId(5L);
        existing.setPrincipalId(901L);
        existing.setScopes("SEARCH,QUERY");
        when(mapper.getApiKey(41L)).thenReturn(existing);
        when(ids.next(anyString())).thenReturn("PAT_ROTATED");
        ApiKey[] created = captureInsertedKey(42L);

        LocalDateTime expiry = LocalDateTime.now().plusDays(90);
        Map<String, Object> result = service.rotatePersonalAccessToken(5L, 41L, expiry);

        assertEquals(Long.valueOf(901L), created[0].getPrincipalId());
        assertEquals("SEARCH,QUERY", created[0].getScopes());
        assertEquals(expiry, created[0].getExpiresAt());
        assertNotNull(result.get("secret"));
        verify(mapper).updateApiKeyStatus(41L, ApiKey.STATUS_REVOKED);
    }

    @Test
    void personalTokenRotationRejectsExpiryBeyondPolicy() {
        ApiKey existing = new ApiKey();
        existing.setId(41L);
        existing.setStatus(ApiKey.STATUS_ACTIVE);
        existing.setCredentialType(ApiKey.CREDENTIAL_USER_PAT);
        existing.setOwnerUserId(5L);
        when(mapper.getApiKey(41L)).thenReturn(existing);

        assertThrows(IllegalArgumentException.class,
            () -> service.rotatePersonalAccessToken(5L, 41L, LocalDateTime.now().plusDays(400)));
        assertThrows(IllegalArgumentException.class,
            () -> service.rotatePersonalAccessToken(5L, 41L, null), "轮换同样强制到期");
    }

    @Test
    void cannotRotateAnotherUsersToken() {
        ApiKey existing = new ApiKey();
        existing.setId(41L);
        existing.setStatus(ApiKey.STATUS_ACTIVE);
        existing.setCredentialType(ApiKey.CREDENTIAL_USER_PAT);
        existing.setOwnerUserId(5L);
        when(mapper.getApiKey(41L)).thenReturn(existing);

        assertThrows(IllegalArgumentException.class,
            () -> service.rotatePersonalAccessToken(9L, 41L, LocalDateTime.now().plusDays(30)));
    }

    @Test
    void revokedCredentialCannotBeRotated() {
        ApiKey existing = new ApiKey();
        existing.setId(41L);
        existing.setStatus(ApiKey.STATUS_REVOKED);
        existing.setCredentialType(ApiKey.CREDENTIAL_SERVICE_KEY);
        existing.setPrincipalId(901L);
        when(mapper.getApiKey(41L)).thenReturn(existing);

        assertThrows(IllegalArgumentException.class, () -> service.rotateServiceKey(41L, null, 7L));
    }

    /**
     * MyBatis 会把生成的主键写回传入的对象，Mockito 不会。这里模拟这一步，并检查真正被插入的
     * 那一行——断言落在插入对象上，比断言返回值更能证明轮换带走了什么。
     */
    private ApiKey[] captureInsertedKey(long newId) {
        final ApiKey[] created = new ApiKey[1];
        doAnswer(invocation -> {
            ApiKey key = invocation.getArgument(0);
            key.setId(newId);
            created[0] = key;
            return 1;
        }).when(mapper).insertApiKey(any(ApiKey.class));
        when(mapper.getApiKey(newId)).thenAnswer(invocation -> created[0]);
        return created;
    }
}
