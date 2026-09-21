package com.jh.knowledgeos.config;

import java.util.Arrays;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.jh.knowledgeos.common.Permissions;

/**
 * 按路径前缀做角色权限校验，未授权抛 SecurityException（全局异常处理映射为 403）。
 *
 * <p>权限码与角色归属集中在 {@link com.jh.knowledgeos.common.UserRole} 与
 * {@link Permissions}，前端用同一套权限码裁剪界面，因此前后端不会出现
 * 「界面隐藏了但接口还能调」的错位。规则按从具体到宽泛排列，命中第一条即生效。</p>
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    /** 只拦截写操作：列表/详情仍可被其他页面复用，写入口由界面隐藏 + 此处兜底。 */
    private static final boolean WRITE_ONLY = true;
    /** 读写都拦截：整块能力只对特定角色开放。 */
    private static final boolean ALL_METHODS = false;

    private static final List<Rule> RULES = Arrays.asList(
            new Rule("/api/knowledge/derivations", Permissions.GENERATE_DERIVE, ALL_METHODS),
            new Rule("/api/knowledge/schema", Permissions.SYSTEM_SCHEMA, ALL_METHODS),
            new Rule("/api/knowledge/settings/query-policy", Permissions.SEARCH_POLICY, ALL_METHODS),
            new Rule("/api/knowledge/settings/model", Permissions.SYSTEM_MODEL, WRITE_ONLY),
            new Rule("/api/knowledge/evaluations", Permissions.SEARCH_EVALUATE, WRITE_ONLY),
            // 裁决结果（assessments）是工作台与资产浏览的只读投影，所有角色都要能看；
            // 只有「跑一轮裁决」这类写操作才要求裁决权限。
            new Rule("/api/knowledge/adjudication", Permissions.GENERATE_JUDGE, WRITE_ONLY),
            new Rule("/api/knowledge/conflicts", Permissions.GENERATE_JUDGE, WRITE_ONLY),
            new Rule("/api/knowledge/documents", Permissions.GENERATE_IMPORT, WRITE_ONLY),
            new Rule("/api/knowledge/extract", Permissions.GENERATE_IMPORT, WRITE_ONLY),
            new Rule("/api/knowledge/entities", Permissions.GENERATE_IMPORT, WRITE_ONLY),
            new Rule("/api/knowledge/index/", Permissions.SYSTEM_INDEX, WRITE_ONLY),
            new Rule("/api/knowledge/acl", Permissions.SYSTEM_ACL, WRITE_ONLY),
            // 任务与告警属于平台运维：只有系统管理员能看告警、改规则。
            new Rule("/api/knowledge/tasks/alerts", Permissions.SYSTEM_TASK, ALL_METHODS));

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        if (method == null || "OPTIONS".equalsIgnoreCase(method)) return true;
        String path = request.getRequestURI();
        if (path == null || path.isEmpty()) return true;
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        boolean read = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
        for (Rule rule : RULES) {
            if (!path.startsWith(rule.prefix)) continue;
            if (rule.writeOnly && read) continue;
            Permissions.require(rule.permission);
            return true;
        }
        return true;
    }

    private static final class Rule {
        private final String prefix;
        private final String permission;
        private final boolean writeOnly;

        private Rule(String prefix, String permission, boolean writeOnly) {
            this.prefix = prefix;
            this.permission = permission;
            this.writeOnly = writeOnly;
        }
    }
}
