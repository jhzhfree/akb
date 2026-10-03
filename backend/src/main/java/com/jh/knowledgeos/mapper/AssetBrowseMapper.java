package com.jh.knowledgeos.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 资产浏览（工作台 KPI 的下钻页）：按对象类型做服务端分页与关键字检索。
 *
 * <p>SQL 与各自清单接口取同一批表、同一套 ACL 语义，因此「列表第 N 页能看到多少条」
 * 与工作台 KPI 是同一个数字。type 取值由 AssetBrowseService 归一化后传入。</p>
 */
public interface AssetBrowseMapper {

    /**
     * 命中总数（含 ACL 与关键字过滤）。
     */
    long countAssets(@Param("kb") Long kb, @Param("type") String type, @Param("keyword") String keyword,
        @Param("permissionTags") List<String> permissionTags);

    /**
     * 当前页数据。offset 由服务层按页码算好，避免在 SQL 里做算术。
     */
    List<Map<String, Object>> pageAssets(@Param("kb") Long kb, @Param("type") String type, @Param("keyword") String keyword,
        @Param("permissionTags") List<String> permissionTags, @Param("offset") int offset, @Param("size") int size);
}
