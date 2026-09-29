package com.jh.knowledgeos.common;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 平台角色与角色自带的权限码。
 *
 * 五个角色按职责边界划分，权限以「权限码」表达（见 {@link Permissions}），
 * 后端接口用 {@code Permissions.require(code)} 校验，前端用 {@code /api/auth/me}
 * 返回的 permissions 数组裁剪菜单与操作入口，两端共用同一套权限码。
 *
 * <pre>
 *   系统管理员      平台配置：用户与组织、ACL、模型、索引、任务告警、审计、Schema、知识空间
 *   知识系统管理员  知识域全功能：工作台 + 知识生成（含裁决、衍生）+ 知识检索（含策略、评测）
 *   知识运营员      简化知识生成（上传、生成、查看对象；不含裁决与衍生）+ 知识检索（不含策略、评测）
 *   知识审核员      知识运营员 + 知识裁决 + 知识测评
 *   知识浏览员      可查看知识工作台（总览 / 资产浏览 / 版本 / 质量）与知识检索，不能改动任何知识
 * </pre>
 */
public enum UserRole {

    /**
     * 系统管理员：平台级配置与账号治理。
     */
    ADMIN("系统管理员", "system"),
    /**
     * 知识系统管理员：知识工作台、知识生成、知识检索的全部功能。
     */
    KNOWLEDGE_ADMIN("知识系统管理员", "workbench"),
    /**
     * 知识运营员：定制简化的知识生成与知识检索。
     */
    KNOWLEDGE_OPERATOR("知识运营员", "create.overview"),
    /**
     * 知识审核员：在知识运营员之上增加知识裁决与知识测评。
     */
    KNOWLEDGE_AUDITOR("知识审核员", "create.judgment"),
    /**
     * 知识浏览员：可查看知识工作台与知识检索，不能改动任何知识。
     */
    KNOWLEDGE_VIEWER("知识浏览员", "workbench");

    /**
     * 旧角色名到新角色的兼容映射，保证历史账号升级后语义不丢。
     */
    private static final String LEGACY_EDITOR = "EDITOR";

    private static final String LEGACY_VIEWER = "VIEWER";

    private final String label;

    private final String homeView;

    UserRole(String label, String homeView) {
        this.label = label;
        this.homeView = homeView;
    }

    public String label() {
        return label;
    }

    /**
     * 该角色登录后默认进入的视图 key（前端 IA key）。
     */
    public String homeView() {
        return homeView;
    }

    /**
     * 角色拥有的全部权限码。
     */
    public Set<String> permissions() {
        Set<String> granted = new LinkedHashSet<String>();
        switch(this) {
            case ADMIN:
                granted.addAll(Permissions.ALL);
                break;
            case KNOWLEDGE_ADMIN:
                granted.addAll(Arrays.asList(Permissions.WORKBENCH_VIEW, Permissions.WORKBENCH_ASSET, Permissions.GENERATE_IMPORT,
                    Permissions.GENERATE_CONNECTOR, Permissions.GENERATE_JUDGE, Permissions.GENERATE_DERIVE, Permissions.SEARCH_QUERY,
                    Permissions.SEARCH_GRAPH, Permissions.SEARCH_TEST, Permissions.SEARCH_POLICY, Permissions.SEARCH_EVALUATE,
                    Permissions.INTEGRATION_VIEW, Permissions.SYSTEM_SPACE, Permissions.SYSTEM_SCHEMA));
                break;
            case KNOWLEDGE_AUDITOR:
                granted.addAll(knowledgeOperatorPermissions());
                granted.add(Permissions.GENERATE_JUDGE);
                granted.add(Permissions.SEARCH_EVALUATE);
                granted.add(Permissions.SEARCH_TEST);
                break;
            case KNOWLEDGE_OPERATOR:
                granted.addAll(knowledgeOperatorPermissions());
                break;
            case KNOWLEDGE_VIEWER:
                // 工作台是「看知识」的主入口：总览、资产浏览、版本、质量只读开放给浏览员，
                // 其余（知识生成、裁决、衍生、策略、集成）一律不放。
                granted.addAll(Arrays.asList(Permissions.WORKBENCH_VIEW, Permissions.WORKBENCH_ASSET, Permissions.SEARCH_QUERY, Permissions.SEARCH_GRAPH));
                break;
            default:
                break;
        }
        return granted;
    }

    /**
     * 知识运营员的基线权限：简化知识生成 + 只读知识检索。
     */
    private static Set<String> knowledgeOperatorPermissions() {
        return new LinkedHashSet<String>(Arrays.asList(Permissions.WORKBENCH_VIEW, Permissions.WORKBENCH_ASSET, Permissions.GENERATE_IMPORT,
            Permissions.SEARCH_QUERY, Permissions.SEARCH_GRAPH));
    }

    public boolean has(String permission) {
        return permission != null && permissions().contains(permission);
    }

    /**
     * 解析角色名：未知取值一律降级为知识浏览员（最小权限），
     * 旧角色 EDITOR / VIEWER 分别映射到知识运营员与知识浏览员。
     */
    public static UserRole of(String value) {
        if (value == null) {
            return KNOWLEDGE_VIEWER;
        }
        String raw = value.trim();
        if (raw.isEmpty()) {
            return KNOWLEDGE_VIEWER;
        }
        for (UserRole role : values()) {
            if (role.name().equalsIgnoreCase(raw)) {
                return role;
            }
        }
        if (LEGACY_EDITOR.equalsIgnoreCase(raw)) {
            return KNOWLEDGE_OPERATOR;
        }
        if (LEGACY_VIEWER.equalsIgnoreCase(raw)) {
            return KNOWLEDGE_VIEWER;
        }
        return KNOWLEDGE_VIEWER;
    }

    /**
     * 可被分配的角色列表，供前端用户管理的角色下拉使用。
     */
    public static Set<String> assignable() {
        Set<String> names = new LinkedHashSet<String>();
        for (UserRole role : values()) {
            names.add(role.name());
        }
        return Collections.unmodifiableSet(names);
    }

    public boolean canManageUsers() {
        return this == ADMIN;
    }

    public boolean canManageKnowledgeBases() {
        return this == ADMIN || this == KNOWLEDGE_ADMIN;
    }

    public boolean canWriteKnowledge() {
        return this == ADMIN || this == KNOWLEDGE_ADMIN || this == KNOWLEDGE_OPERATOR || this == KNOWLEDGE_AUDITOR;
    }
}
