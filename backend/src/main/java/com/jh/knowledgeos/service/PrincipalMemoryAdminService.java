package com.jh.knowledgeos.service;

import com.jh.knowledgeos.domain.ExternalPrincipal;
import com.jh.knowledgeos.dto.AgentMemoryScopeGrantRequest;
import com.jh.knowledgeos.mapper.AgentMemoryMapper;
import com.jh.knowledgeos.mapper.ExternalApiMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 主体级 Memory 身份与授权管理，对应设计 7.4 的 memory-profile / memory-grants。
 *
 * <p>Memory 的 namespace 与 Agent identity 挂在主体上而不是 Key 上——Key 轮换、新增、吊销都不该
 * 改变记忆归属。管理员在这里设置主体的记忆身份，并限定 MEMORY_MANAGE 实际能操作哪些作用域。
 *
 * <p>与 key 级入口（{@link AgentMemoryScopeAccessService}）的区别：那边额外校验「授权不能超出
 * 凭据自身的能力」，因为入参是一把具体的 Key；这里入参是主体，凭据能力要在每次调用时按当次
 * 使用的 Key 判定，不是配置期能定死的事。
 */
@Service
public class PrincipalMemoryAdminService {
    private static final Set<String> SCOPE_TYPES = new LinkedHashSet<String>(Arrays.asList(
        "AGENT", "TEAM", "PROJECT", "TASK", "WORKFLOW", "SESSION"));
    private static final int MAX_LENGTH = 64;
    private static final String IDENTITY_PATTERN = "[A-Za-z0-9_.:-]+";

    private final ExternalApiMapper externalMapper;
    private final AgentMemoryMapper memoryMapper;

    public PrincipalMemoryAdminService(ExternalApiMapper externalMapper, AgentMemoryMapper memoryMapper) {
        this.externalMapper = externalMapper;
        this.memoryMapper = memoryMapper;
    }

    public Map<String, Object> profile(Long principalId) {
        ExternalPrincipal principal = requirePrincipal(principalId);
        Map<String, Object> stored = externalMapper.getPrincipalMemoryProfile(principalId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("principalId", principal.getId());
        result.put("principalCode", principal.getPrincipalCode());
        result.put("principalType", principal.getPrincipalType());
        result.put("configured", Boolean.valueOf(stored != null));
        result.put("namespace", stored == null ? null : stored.get("namespace"));
        result.put("agentCode", stored == null ? null : stored.get("agentCode"));
        result.put("status", stored == null ? null : stored.get("status"));
        return result;
    }

    /**
     * 设置主体的记忆身份，并播种它对自身私有空间的读写授权。
     *
     * <p>没有自身授权这一条，主体即使拿到 MEMORY_MANAGE 也什么都做不了——设计 12 章要求
     * 「Principal 级 Memory grant 缺失 → MEMORY_MANAGE 调用拒绝」，播种正是为了让默认可用、
     * 撤销即可禁用。改动 agent code 只播种新的，旧 namespace 下的历史记忆不受影响。
     */
    @Transactional
    public Map<String, Object> saveProfile(Long principalId, String namespace, String agentCode, Long operatorId) {
        requirePrincipal(principalId);
        String cleanNamespace = requireIdentity(namespace, "namespace");
        String cleanAgent = requireIdentity(agentCode, "agentCode");
        externalMapper.upsertPrincipalMemoryProfile(principalId, cleanNamespace, cleanAgent, operatorId);
        externalMapper.insertDefaultAgentMemoryGrant(principalId, cleanNamespace, cleanAgent, operatorId);
        return profile(principalId);
    }

    public List<Map<String, Object>> grants(Long principalId) {
        Map<String, Object> stored = requireProfile(principalId);
        return memoryMapper.listPrincipalScopeAccess(principalId, String.valueOf(stored.get("namespace")));
    }

    /** 原子替换主体的记忆作用域授权；空数组表示只保留自身的私有空间。 */
    @Transactional
    public List<Map<String, Object>> replaceGrants(Long principalId, List<AgentMemoryScopeGrantRequest> requests,
        Long operatorId) {
        Map<String, Object> stored = requireProfile(principalId);
        String namespace = String.valueOf(stored.get("namespace"));
        String agentCode = String.valueOf(stored.get("agentCode"));
        List<Grant> grants = normalize(requests);
        memoryMapper.deletePrincipalScopeAccess(principalId, agentCode);
        for (Grant grant : grants) {
            memoryMapper.insertPrincipalScopeAccess(principalId, namespace, grant.type, grant.code, grant.read,
                grant.write, operatorId);
        }
        return memoryMapper.listPrincipalScopeAccess(principalId, namespace);
    }

    private ExternalPrincipal requirePrincipal(Long principalId) {
        if (principalId == null) throw new IllegalArgumentException("主体 ID 不能为空");
        ExternalPrincipal principal = externalMapper.getPrincipal(principalId);
        if (principal == null) throw new IllegalArgumentException("主体不存在");
        return principal;
    }

    /** 授权按 namespace 维度存储，主体还没配记忆身份时没有可落的 namespace。 */
    private Map<String, Object> requireProfile(Long principalId) {
        requirePrincipal(principalId);
        Map<String, Object> stored = externalMapper.getPrincipalMemoryProfile(principalId);
        if (stored == null) throw new IllegalArgumentException("该主体尚未配置记忆身份，请先设置 memory-profile");
        return stored;
    }

    private String requireIdentity(String raw, String field) {
        if (raw == null || raw.trim().isEmpty()) throw new IllegalArgumentException(field + " 不能为空");
        String value = raw.trim();
        if (value.length() > MAX_LENGTH || !value.matches(IDENTITY_PATTERN)) {
            throw new IllegalArgumentException(field + " 仅允许 1-" + MAX_LENGTH + " 位字母、数字、点、下划线、冒号或连字符");
        }
        return value;
    }

    private List<Grant> normalize(List<AgentMemoryScopeGrantRequest> requests) {
        List<Grant> grants = new ArrayList<Grant>();
        Set<String> seen = new LinkedHashSet<String>();
        if (requests == null) return grants;
        if (requests.size() > 200) throw new IllegalArgumentException("单个主体最多配置 200 条记忆作用域授权");
        for (AgentMemoryScopeGrantRequest request : requests) {
            if (request == null) throw new IllegalArgumentException("授权项不能为空");
            String type = request.getScopeType() == null ? "" : request.getScopeType().trim().toUpperCase(Locale.ROOT);
            String code = request.getScopeCode() == null ? "" : request.getScopeCode().trim();
            boolean read = Boolean.TRUE.equals(request.getCanRead());
            boolean write = Boolean.TRUE.equals(request.getCanWrite());
            if (!SCOPE_TYPES.contains(type)) throw new IllegalArgumentException("记忆作用域类型不合法");
            if (code.isEmpty() || code.length() > 128 || !code.matches(IDENTITY_PATTERN)) {
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
            this.type = type;
            this.code = code;
            this.read = read;
            this.write = write;
        }
    }
}
