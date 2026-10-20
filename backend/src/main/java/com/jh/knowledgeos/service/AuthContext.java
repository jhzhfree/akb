package com.jh.knowledgeos.service;

import com.jh.knowledgeos.domain.User;

import java.util.Set;

/**
 * Request-scoped holder for the signed-in user, populated by the authentication
 * interceptor and cleared when the request completes.
 */
public final class AuthContext {

    public static final String REQUEST_ATTRIBUTE = "knowledgeos.currentUser";

    private static final ThreadLocal<User> CURRENT = new ThreadLocal<User>();

    /**
     * 本次请求实际生效的权限码：由角色菜单配置派生（见 RoleMenuService.permissionsFor）。
     * 为 null 表示尚未解析（未登录或未启用认证），权限判定回落到角色自带的权限码。
     */
    private static final ThreadLocal<Set<String>> GRANTED = new ThreadLocal<Set<String>>();

    private AuthContext() {
    }

    public static void set(User user) {
        CURRENT.set(user);
    }

    public static User get() {
        return CURRENT.get();
    }

    public static void setGranted(Set<String> permissions) {
        GRANTED.set(permissions);
    }

    public static Set<String> granted() {
        return GRANTED.get();
    }

    public static void clear() {
        CURRENT.remove();
        GRANTED.remove();
    }
}
