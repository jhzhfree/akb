package com.jh.knowledgeos.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Persistence for the per-role visible menu configuration.
 *
 * <p>A role without any row means "not configured yet": the service falls back to the built-in
 * defaults, so adding a role or a new menu item never needs a data migration.</p>
 */
public interface RoleMenuMapper {

    /** Visible leaf keys of one role. */
    List<String> listVisibleKeys(@Param("role") String role);

    /** Rows of one role; zero means the role still uses built-in defaults. */
    int countByRole(@Param("role") String role);

    int deleteByRole(@Param("role") String role);

    /** Insert or update the visibility of one menu item. */
    int upsert(@Param("role") String role, @Param("leaf") String leaf, @Param("visible") int visible);
}
