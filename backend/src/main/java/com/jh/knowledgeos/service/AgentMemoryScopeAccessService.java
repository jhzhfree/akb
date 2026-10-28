package com.jh.knowledgeos.service;

import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.dto.AgentMemoryScopeGrantRequest;
import com.jh.knowledgeos.mapper.AgentMemoryMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Console-managed, principal-level access to shared memory scopes. */
@Service
public class AgentMemoryScopeAccessService {
    private static final Set<String> SHARED_SCOPE_TYPES = new LinkedHashSet<String>(Arrays.asList("AGENT", "TEAM", "PROJECT", "TASK", "WORKFLOW", "SESSION"));
    private final AgentMemoryMapper mapper;
    private final ApiKeyService apiKeyService;
    private final AgentMemoryService memoryService;

    public AgentMemoryScopeAccessService(AgentMemoryMapper mapper, ApiKeyService apiKeyService, AgentMemoryService memoryService) {
        this.mapper = mapper;
        this.apiKeyService = apiKeyService;
        this.memoryService = memoryService;
    }

    public List<Map<String, Object>> list(Long keyId) {
        ApiKey key = requireKey(keyId);
        if (key.getPrincipalId() == null) throw new IllegalArgumentException("API Key 未绑定主体");
        return mapper.listPrincipalScopeAccess(key.getPrincipalId(), memoryService.namespace(key));
    }

    @Transactional
    public List<Map<String, Object>> replace(Long keyId, List<AgentMemoryScopeGrantRequest> requests, Long operatorId) {
        ApiKey key = requireKey(keyId);
        List<Grant> grants = normalize(requests);
        if (key.getPrincipalId() == null) throw new IllegalArgumentException("API Key 未绑定主体");
        // 授权不能超出凭据自身的能力：MEMORY_MANAGE 是覆盖读写的组合权限，未持有时仍退回
        // 旧的细粒度判断。少了这道判断，控制台就能配出主体实际用不了的授权。
        boolean memoryManage = key.hasScope(ApiKey.SCOPE_MEMORY_MANAGE);
        for (Grant grant : grants) {
            if (grant.read && !memoryManage && !key.hasScope(ApiKey.SCOPE_MEMORY_READ)) {
                throw new IllegalArgumentException("该凭据未授予 MEMORY_MANAGE 或 MEMORY_READ，不能配置读取授权");
            }
            if (grant.write && !memoryManage && !key.hasScope(ApiKey.SCOPE_MEMORY_WRITE)) {
                throw new IllegalArgumentException("该凭据未授予 MEMORY_MANAGE 或 MEMORY_WRITE，不能配置写入授权");
            }
        }
        String namespace = memoryService.namespace(key);
        mapper.deletePrincipalScopeAccess(key.getPrincipalId(), memoryService.agentCode(key));
        for (Grant grant : grants) {
            mapper.insertPrincipalScopeAccess(key.getPrincipalId(), namespace, grant.type, grant.code, grant.read, grant.write, operatorId);
        }
        return mapper.listPrincipalScopeAccess(key.getPrincipalId(), namespace);
    }

    private ApiKey requireKey(Long id) {
        ApiKey key = id == null ? null : apiKeyService.get(id);
        if (key == null) throw new IllegalArgumentException("API Key 不存在");
        return key;
    }

    private List<Grant> normalize(List<AgentMemoryScopeGrantRequest> requests) {
        List<Grant> grants = new ArrayList<Grant>();
        Set<String> seen = new LinkedHashSet<String>();
        if (requests == null) return grants;
        if (requests.size() > 200) throw new IllegalArgumentException("单个 API Key 最多配置 200 条记忆作用域授权");
        for (AgentMemoryScopeGrantRequest request : requests) {
            if (request == null) throw new IllegalArgumentException("授权项不能为空");
            String type = request.getScopeType() == null ? "" : request.getScopeType().trim().toUpperCase(Locale.ROOT);
            String code = request.getScopeCode() == null ? "" : request.getScopeCode().trim();
            boolean read = Boolean.TRUE.equals(request.getCanRead());
            boolean write = Boolean.TRUE.equals(request.getCanWrite());
            if (!SHARED_SCOPE_TYPES.contains(type)) throw new IllegalArgumentException("共享作用域类型不合法");
            if (code.isEmpty() || code.length() > 128 || !code.matches("[A-Za-z0-9_.:-]+")) {
                throw new IllegalArgumentException("scopeCode 仅允许 1-128 位字母、数字、点、下划线、冒号或连字符");
            }
            if (!read && !write) throw new IllegalArgumentException("每条授权至少需要开启读取或写入");
            if (!seen.add(type + ":" + code)) throw new IllegalArgumentException("存在重复的记忆作用域授权");
            grants.add(new Grant(type, code, read, write));
        }
        return grants;
    }

    private static final class Grant {
        final String type;
        final String code;
        final boolean read;
        final boolean write;
        Grant(String type, String code, boolean read, boolean write) {
            this.type = type; this.code = code; this.read = read; this.write = write;
        }
    }
}
