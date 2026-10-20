package com.jh.knowledgeos.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 菜单项目录：菜单项 key → 所需权限码 / 是否管理员专属。
 *
 * <p>与前端 {@code navigation.ts} 的 {@code NAV_SECTIONS} 一一对应（5 个一级模块 / 38 个功能菜单）。
 * 后端只关心「菜单项需要哪个权限码、是否管理员专属」这两件事——界面文案与图标由前端维护，
 * 避免同一份菜单定义散在两端各自演化。</p>
 *
 * <p>这里是「角色可配置菜单」与「勾选菜单即授予权限码」的唯一映射来源：
 * 角色被授予的权限码 = 其可见菜单项所需权限码的并集（见 {@code RoleMenuService.permissionsFor}）。</p>
 */
public final class MenuCatalog {

    /** 一个菜单项。 */
    public static final class Item {

        private final String key;

        /** 所需权限码；null 表示登录即可见，不参与权限派生。 */
        private final String permission;

        /** 是否管理员专属入口（历史上靠「管理视图」隐藏的入口）。 */
        private final boolean adminOnly;

        private Item(String key, String permission, boolean adminOnly) {
            this.key = key;
            this.permission = permission;
            this.adminOnly = adminOnly;
        }

        public String key() {
            return key;
        }

        public String permission() {
            return permission;
        }

        public boolean adminOnly() {
            return adminOnly;
        }
    }

    private static final List<Item> ITEMS = new ArrayList<Item>();

    private static final Map<String, Item> BY_KEY = new LinkedHashMap<String, Item>();

    private MenuCatalog() {
    }

    private static void add(String key, String permission, boolean adminOnly) {
        Item item = new Item(key, permission, adminOnly);
        ITEMS.add(item);
        BY_KEY.put(key, item);
    }

    static {
        // 知识概览
        add("home", Permissions.WORKBENCH_VIEW, false);
        add("workbench", Permissions.WORKBENCH_VIEW, false);
        add("workbench.assets", Permissions.WORKBENCH_ASSET, false);
        add("workbench.version", Permissions.WORKBENCH_VIEW, false);
        add("workbench.quality", Permissions.WORKBENCH_VIEW, false);

        // 知识生成
        add("create.overview", Permissions.GENERATE_IMPORT, true);
        add("create.generation", Permissions.GENERATE_IMPORT, false);
        add("create.import", Permissions.GENERATE_IMPORT, false);
        add("create.connector", Permissions.GENERATE_CONNECTOR, true);
        add("create.connectorCatalog", Permissions.GENERATE_CONNECTOR, true);
        add("create.ledger", Permissions.GENERATE_IMPORT, true);
        add("create.judgment", Permissions.GENERATE_JUDGE, false);
        add("create.derive", Permissions.GENERATE_DERIVE, false);

        // 知识检索
        add("search.query", Permissions.SEARCH_QUERY, false);
        add("search.test", Permissions.SEARCH_TEST, false);
        add("search.profile", Permissions.SEARCH_POLICY, false);
        add("search.trace", Permissions.SEARCH_TEST, true);
        add("search.graph", Permissions.SEARCH_GRAPH, false);
        add("search.feedback", Permissions.SEARCH_EVALUATE, true);
        add("search.benchmark", Permissions.SEARCH_EVALUATE, true);

        // 接口服务
        add("integration.overview", Permissions.INTEGRATION_VIEW, true);
        add("integration.services", Permissions.INTEGRATION_VIEW, false);
        add("integration.api", Permissions.INTEGRATION_VIEW, true);
        add("integration.mcp", Permissions.INTEGRATION_VIEW, true);
        add("integration.sse", Permissions.INTEGRATION_VIEW, true);
        add("integration.events", Permissions.INTEGRATION_VIEW, true);
        add("integration.credentials", Permissions.INTEGRATION_VIEW, true);
        add("integration.embed", Permissions.INTEGRATION_VIEW, true);
        add("integration.logs", Permissions.INTEGRATION_VIEW, true);

        // 系统配置
        add("system", Permissions.SYSTEM_VIEW, false);
        add("system.model", Permissions.SYSTEM_MODEL, false);
        add("system.users", Permissions.SYSTEM_USER, true);
        add("system.acl", Permissions.SYSTEM_ACL, false);
        add("system.schema", Permissions.SYSTEM_SCHEMA, false);
        add("system.index", Permissions.SYSTEM_INDEX, false);
        add("system.tasks", Permissions.SYSTEM_TASK, false);
        add("system.audit", Permissions.SYSTEM_AUDIT, false);
        add("system.space", Permissions.SYSTEM_SPACE, false);
        // 角色菜单配置本身：管理员在这里裁剪各角色可见的模块功能菜单。
        add("system.menu", Permissions.SYSTEM_ACL, false);
    }

    /** 全部菜单项，顺序即前端展示顺序。 */
    public static List<Item> items() {
        return Collections.unmodifiableList(ITEMS);
    }

    /** 全部菜单项 key；未开启认证时前端按全权限渲染，菜单同样全开。 */
    public static List<String> keys() {
        List<String> out = new ArrayList<String>();
        for (Item item : ITEMS) {
            out.add(item.key());
        }
        return out;
    }

    public static Item of(String key) {
        return key == null ? null : BY_KEY.get(key);
    }

    public static boolean known(String key) {
        return key != null && BY_KEY.containsKey(key);
    }

    /** 菜单项所需权限码；未知或无权限要求的菜单项返回 null。 */
    public static String permissionOf(String key) {
        Item item = of(key);
        return item == null ? null : item.permission();
    }
}
