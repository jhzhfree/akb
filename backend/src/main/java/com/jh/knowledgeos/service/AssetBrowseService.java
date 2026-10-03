package com.jh.knowledgeos.service;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.jh.knowledgeos.mapper.AssetBrowseMapper;

/**
 * 资产浏览：来源 / 文档 / 知识卡片 / 断言 / 实体 / 关系 的服务端分页与关键字检索。
 *
 * <p>统一返回 {@code {type,keyword,items,total,page,size,pageCount}}：前端直接拿 total 渲染
 * 分页条，不必把整张表拉到浏览器里做本地过滤——运维知识库这类单库几百到上千条对象的场景，
 * 全量拉取会把列表卡住，也拿不到真实命中数。</p>
 *
 * <p>计数与取页共用同一条 WHERE（含对象级 ACL），所以「共 N 条」与工作台 KPI 是同一个数。</p>
 */
@Service
public class AssetBrowseService {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 200;

    private static final Set<String> TYPES = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
        "SOURCE", "DOCUMENT", "CARD", "CLAIM", "ENTITY", "RELATION")));

    private final AssetBrowseMapper mapper;

    public AssetBrowseService(AssetBrowseMapper mapper) {
        this.mapper = mapper;
    }

    public Map<String, Object> browse(Long kb, String type, String keyword, Integer page, Integer size,
        List<String> permissionTags) {
        String normalized = normalizeType(type);
        int pageSize = size == null || size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        String trimmed = keyword == null ? "" : keyword.trim();
        long total = mapper.countAssets(kb, normalized, trimmed, permissionTags);
        int pageCount = (int) Math.ceil((double) total / (double) pageSize);
        int pageNo = page == null || page <= 0 ? 1 : page;
        // 检索后命中变少时把页码收回最后一页，避免停在空白页。
        if (pageCount > 0 && pageNo > pageCount) {
            pageNo = pageCount;
        }
        List<Map<String, Object>> items = total == 0 ? Collections.<Map<String, Object>>emptyList()
            : mapper.pageAssets(kb, normalized, trimmed, permissionTags, (pageNo - 1) * pageSize, pageSize);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("type", normalized);
        result.put("keyword", trimmed);
        result.put("items", items);
        result.put("total", total);
        result.put("page", pageNo);
        result.put("size", pageSize);
        result.put("pageCount", pageCount);
        return result;
    }

    /** 前端把断言叫 assertion、关系叫 relationship；未知类型直接拒绝，避免拼出半截 SQL。 */
    private String normalizeType(String type) {
        String raw = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        if ("ASSERTION".equals(raw)) {
            raw = "CLAIM";
        }
        if ("RELATIONSHIP".equals(raw)) {
            raw = "RELATION";
        }
        if (!TYPES.contains(raw)) {
            throw new IllegalArgumentException("不支持的资产类型：" + type + "（可选 SOURCE/DOCUMENT/CARD/CLAIM/ENTITY/RELATION）");
        }
        return raw;
    }
}
