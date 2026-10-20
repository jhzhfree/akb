package com.jh.knowledgeos.service;

import com.jh.knowledgeos.common.MenuCatalog;
import com.jh.knowledgeos.common.Permissions;
import com.jh.knowledgeos.common.UserRole;
import com.jh.knowledgeos.mapper.RoleMenuMapper;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 角色可见菜单：取代原先「用户视图 / 管理视图」这组二值开关。
 *
 * <p>每个角色一份可见菜单清单。角色没有任何配置行时按内置默认渲染（等价于改造前
 * 该角色实际看到的菜单），管理员配置后按配置渲染，可恢复默认。</p>
 *
 * <p>菜单勾选与接口权限联动：角色被授予的权限码 = 其可见菜单项所需权限码的并集。
 * 因此这里改的不是界面显示，而是这个角色真正能做什么。</p>
 */
@Service
public class RoleMenuService {

    private final RoleMenuMapper mapper;

    public RoleMenuService(RoleMenuMapper mapper) {
        this.mapper = mapper;
    }

    /** 该角色可见的菜单项 key；未配置时回落到内置默认。 */
    public Set<String> visibleKeys(String roleCode) {
        String role = normalize(roleCode);
        if (mapper.countByRole(role) <= 0) {
            return defaultVisibleKeys(role);
        }
        List<String> configured = mapper.listVisibleKeys(role);
        Set<String> out = new LinkedHashSet<String>();
        if (configured != null) {
            for (String key : configured) {
                // 菜单项可能已从代码中移除：忽略遗留配置，避免前端拿到不存在的 key。
                if (MenuCatalog.known(key)) {
                    out.add(key);
                }
            }
        }
        return out;
    }

    /**
     * 内置默认：管理员专属入口只对管理员角色开放，其余按角色既有权限码裁剪——
     * 即改造前各角色实际看到的菜单，把原先散落在视图开关里的规则显式化为数据。
     */
    public Set<String> defaultVisibleKeys(String roleCode) {
        String role = normalize(roleCode);
        UserRole parsed = UserRole.of(role);
        boolean admin = parsed == UserRole.ADMIN || parsed == UserRole.KNOWLEDGE_ADMIN;
        Set<String> granted = parsed.permissions();
        Set<String> out = new LinkedHashSet<String>();
        for (MenuCatalog.Item item : MenuCatalog.items()) {
            if (item.adminOnly() && !admin) {
                continue;
            }
            if (item.permission() != null && !granted.contains(item.permission())) {
                continue;
            }
            out.add(item.key());
        }
        return out;
    }

    /**
     * 角色被授予的权限码 = 可见菜单项所需权限码的并集。
     * 系统管理员恒为全部权限：配置是给管理员改的，不能因为改错把自己锁在系统外。
     */
    public Set<String> permissionsFor(String roleCode) {
        String role = normalize(roleCode);
        if (UserRole.ADMIN.name().equals(role)) {
            return Permissions.all();
        }
        Set<String> out = new LinkedHashSet<String>();
        for (String key : visibleKeys(role)) {
            String permission = MenuCatalog.permissionOf(key);
            if (permission != null) {
                out.add(permission);
            }
        }
        return out;
    }

    /** 保存一个角色的可见菜单：未列出的菜单项写入为不可见。 */
    public void save(String roleCode, Collection<String> visibleKeys) {
        String role = normalize(roleCode);
        Set<String> wanted = visibleKeys == null
            ? Collections.<String>emptySet()
            : new HashSet<String>(visibleKeys);
        for (MenuCatalog.Item item : MenuCatalog.items()) {
            mapper.upsert(role, item.key(), wanted.contains(item.key()) ? 1 : 0);
        }
    }

    /** 恢复内置默认：删掉该角色的配置行即可回到默认规则。 */
    public void reset(String roleCode) {
        mapper.deleteByRole(normalize(roleCode));
    }

    /** 该角色是否已被管理员显式配置过（用于界面上区分「默认」与「已定制」）。 */
    public boolean configured(String roleCode) {
        return mapper.countByRole(normalize(roleCode)) > 0;
    }

    /** 全部菜单项：未开启认证时前端按全权限渲染，菜单同样全开。 */
    public Set<String> allKeys() {
        return new LinkedHashSet<String>(MenuCatalog.keys());
    }

    private static String normalize(String roleCode) {
        return UserRole.of(roleCode).name();
    }
}
