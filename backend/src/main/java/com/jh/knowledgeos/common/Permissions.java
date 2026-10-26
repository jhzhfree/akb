package com.jh.knowledgeos.common;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.jh.knowledgeos.service.AuthContext;

/**
 * 权限码字典与运行时校验。
 *
 * 权限码是前后端唯一的权限契约：后端在写接口入口调用 {@link #require(String)}，
 * 前端在 /api/auth/me 拿到 permissions 后据此裁剪导航、区块与按钮。
 * 认证关闭或匿名访问时 AuthContext 为空，此时不做限制（与既有行为一致）。
 */
public final class Permissions {

    /**
     * 系统配置：配置总览入口。
     */
    public static final String SYSTEM_VIEW = "system:view";

    /**
     * 系统配置：用户与组织。
     */
    public static final String SYSTEM_USER = "system:user";

    /**
     * 系统配置：角色与权限（ACL）。
     */
    public static final String SYSTEM_ACL = "system:acl";

    /**
     * 系统配置：模型配置。
     */
    public static final String SYSTEM_MODEL = "system:model";

    /**
     * 系统配置：知识 Schema。
     */
    public static final String SYSTEM_SCHEMA = "system:schema";

    /**
     * 系统配置：存储与索引（含重建、回滚、Outbox 重放）。
     */
    public static final String SYSTEM_INDEX = "system:index";

    /**
     * 系统配置：任务与告警（含告警规则维护）。
     */
    public static final String SYSTEM_TASK = "system:task";

    /**
     * 系统配置：审计日志。
     */
    public static final String SYSTEM_AUDIT = "system:audit";

    /**
     * 系统配置：知识空间（知识库管理）。
     */
    public static final String SYSTEM_SPACE = "system:space";

    /**
     * 系统配置：系统初始化（把出厂默认配置恢复到配置目录）。
     */
    public static final String SYSTEM_DEFAULTS = "system:defaults";

    /**
     * 知识工作台：总览、版本、质量。
     */
    public static final String WORKBENCH_VIEW = "workbench:view";

    /**
     * 知识工作台：资产浏览。
     */
    public static final String WORKBENCH_ASSET = "workbench:asset";

    /**
     * 知识生成：文档导入、解析与重跑。
     */
    public static final String GENERATE_IMPORT = "generate:import";

    /**
     * 知识生成：连接器与数据源。
     */
    public static final String GENERATE_CONNECTOR = "generate:connector";

    /**
     * 知识生成：知识裁决。
     */
    public static final String GENERATE_JUDGE = "generate:judge";

    /**
     * 知识生成：知识衍生。
     */
    public static final String GENERATE_DERIVE = "generate:derive";

    /**
     * 知识检索：问答检索。
     */
    public static final String SEARCH_QUERY = "search:query";

    /**
     * 知识检索：知识图谱。
     */
    public static final String SEARCH_GRAPH = "search:graph";

    /**
     * 知识检索：检索测试与 Trace。
     */
    public static final String SEARCH_TEST = "search:test";

    /**
     * 知识检索：检索策略配置。
     */
    public static final String SEARCH_POLICY = "search:policy";

    /**
     * 知识检索：检索评测（Benchmark）。
     */
    public static final String SEARCH_EVALUATE = "search:evaluate";

    /**
     * 知识集成：服务、协议、凭证与调用日志。
     */
    public static final String INTEGRATION_VIEW = "integration:view";

    public static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(SYSTEM_VIEW, SYSTEM_USER, SYSTEM_ACL, SYSTEM_MODEL,
        SYSTEM_SCHEMA, SYSTEM_INDEX, SYSTEM_TASK, SYSTEM_AUDIT, SYSTEM_SPACE, SYSTEM_DEFAULTS, WORKBENCH_VIEW, WORKBENCH_ASSET, GENERATE_IMPORT,
        GENERATE_CONNECTOR,
        GENERATE_JUDGE, GENERATE_DERIVE, SEARCH_QUERY, SEARCH_GRAPH, SEARCH_TEST, SEARCH_POLICY, SEARCH_EVALUATE, INTEGRATION_VIEW));

    private static final Map<String, String> LABELS = new LinkedHashMap<String, String>();

    static {
        LABELS.put(SYSTEM_VIEW, "系统配置总览");
        LABELS.put(SYSTEM_USER, "用户与组织");
        LABELS.put(SYSTEM_ACL, "角色与权限");
        LABELS.put(SYSTEM_MODEL, "模型配置");
        LABELS.put(SYSTEM_SCHEMA, "知识 Schema");
        LABELS.put(SYSTEM_INDEX, "存储与索引");
        LABELS.put(SYSTEM_TASK, "任务与告警");
        LABELS.put(SYSTEM_AUDIT, "审计日志");
        LABELS.put(SYSTEM_SPACE, "知识空间");
        LABELS.put(SYSTEM_DEFAULTS, "系统初始化");
        LABELS.put(WORKBENCH_VIEW, "知识工作台");
        LABELS.put(WORKBENCH_ASSET, "资产浏览");
        LABELS.put(GENERATE_IMPORT, "文档导入与生成");
        LABELS.put(GENERATE_CONNECTOR, "连接器与数据源");
        LABELS.put(GENERATE_JUDGE, "知识裁决");
        LABELS.put(GENERATE_DERIVE, "知识衍生");
        LABELS.put(SEARCH_QUERY, "知识检索");
        LABELS.put(SEARCH_GRAPH, "知识图谱");
        LABELS.put(SEARCH_TEST, "检索测试");
        LABELS.put(SEARCH_POLICY, "检索策略配置");
        LABELS.put(SEARCH_EVALUATE, "检索评测");
        LABELS.put(INTEGRATION_VIEW, "知识集成");
    }

    private Permissions() {
    }

    public static Set<String> all() {
        return new java.util.LinkedHashSet<String>(ALL);
    }

    /**
     * 权限码字典：code → 中文名，供前端展示。
     */
    public static Map<String, String> labels() {
        return Collections.unmodifiableMap(LABELS);
    }

    public static String label(String permission) {
        String text = permission == null ? null : LABELS.get(permission);
        return text == null ? String.valueOf(permission) : text;
    }

    /**
     * 当前登录人是否拥有该权限；未开启认证时一律放行。
     */
    public static boolean has(String permission) {
        if (permission == null || permission.isEmpty()) {
            return true;
        }
        // 优先按角色菜单配置派生出的权限判定：菜单可配置化后，角色的实际能力由配置决定。
        Set<String> granted = AuthContext.granted();
        if (granted != null) {
            return granted.contains(permission);
        }
        com.jh.knowledgeos.domain.User user = AuthContext.get();
        if (user == null) {
            return true;
        }
        return UserRole.of(user.getRole()).has(permission);
    }

    /**
     * 当前登录人的权限码集合；未开启认证时返回全部权限。
     * 已解析过角色菜单配置时直接返回配置派生的结果。
     */
    public static Set<String> granted() {
        Set<String> granted = AuthContext.granted();
        if (granted != null) {
            return granted;
        }
        com.jh.knowledgeos.domain.User user = AuthContext.get();
        if (user == null) {
            return all();
        }
        return UserRole.of(user.getRole()).permissions();
    }

    /**
     * 校验失败抛 SecurityException（由全局异常处理映射为 403）。
     */
    public static void require(String permission) {
        if (has(permission)) {
            return;
        }
        throw new SecurityException("当前角色无权执行该操作：" + label(permission));
    }
}
