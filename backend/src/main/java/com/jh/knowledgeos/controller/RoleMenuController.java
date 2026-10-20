package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.common.MenuCatalog;
import com.jh.knowledgeos.common.Permissions;
import com.jh.knowledgeos.common.UserRole;
import com.jh.knowledgeos.service.RoleMenuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 角色可见菜单配置：取代原先「用户视图 / 管理视图」的二值开关。
 *
 * <p>每个角色一份可见菜单；未配置时按内置默认渲染（等价于改造前该角色实际看到的菜单）。
 * 勾选的菜单项同时决定该角色获得的接口权限码，因此保存配置即改变该角色的可访问范围。</p>
 */
@RestController
@RequestMapping("/api/system/role-menu")
@CrossOrigin
@Tag(name = "RoleMenu", description = "角色可见菜单配置")
public class RoleMenuController {

    private final RoleMenuService roleMenuService;

    public RoleMenuController(RoleMenuService roleMenuService) {
        this.roleMenuService = roleMenuService;
    }

    @GetMapping
    @Operation(summary = "角色可见菜单配置：角色清单 + 菜单项目录 + 权限码字典")
    public ApiResponse<Object> read() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> roles = new ArrayList<Map<String, Object>>();
        for (String code : UserRole.assignable()) {
            UserRole role = UserRole.of(code);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("code", code);
            item.put("label", role.label());
            item.put("visible", new ArrayList<String>(roleMenuService.visibleKeys(code)));
            item.put("configured", Boolean.valueOf(roleMenuService.configured(code)));
            roles.add(item);
        }
        List<Map<String, Object>> catalog = new ArrayList<Map<String, Object>>();
        for (MenuCatalog.Item menu : MenuCatalog.items()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("key", menu.key());
            item.put("permission", menu.permission());
            item.put("adminOnly", Boolean.valueOf(menu.adminOnly()));
            catalog.add(item);
        }
        out.put("roles", roles);
        out.put("menu", catalog);
        out.put("permissions", Permissions.labels());
        return ApiResponse.ok(out);
    }

    @PutMapping
    @Operation(summary = "保存一个角色的可见菜单；未列出的菜单项置为不可见")
    public ApiResponse<Object> save(@RequestParam("role") String role,
        @RequestBody(required = false) Map<String, Object> payload) {
        roleMenuService.save(role, readVisible(payload));
        return ApiResponse.ok(result(role));
    }

    @PostMapping("/reset")
    @Operation(summary = "恢复该角色的内置默认菜单")
    public ApiResponse<Object> reset(@RequestParam("role") String role) {
        roleMenuService.reset(role);
        return ApiResponse.ok(result(role));
    }

    private Map<String, Object> result(String role) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("role", UserRole.of(role).name());
        out.put("visible", new ArrayList<String>(roleMenuService.visibleKeys(role)));
        out.put("configured", Boolean.valueOf(roleMenuService.configured(role)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Collection<String> readVisible(Map<String, Object> payload) {
        Set<String> visible = new LinkedHashSet<String>();
        if (payload == null) {
            return visible;
        }
        Object raw = payload.get("visible");
        if (raw instanceof Collection<?>) {
            for (Object key : (Collection<Object>) raw) {
                if (key != null) {
                    visible.add(String.valueOf(key));
                }
            }
        }
        return visible;
    }
}
