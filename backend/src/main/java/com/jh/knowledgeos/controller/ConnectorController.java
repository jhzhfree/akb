package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.dto.ConnectorRequest;
import com.jh.knowledgeos.service.AuthContext;
import com.jh.knowledgeos.service.ConnectorCatalogService;
import com.jh.knowledgeos.service.ConnectorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.Collections;
import java.util.List;

/**
 * HTTP facade for external data-source connectors (design §6.1, first step: connect and list).
 *
 * <p>{@code /preview} exists because a connector is only right once you have seen what it
 * returns — a JQL that matches nothing and a field mapping that points at the wrong key both
 * save cleanly, so the form has to be able to read before it writes.</p>
 */
@RestController
@RequestMapping("/api/knowledge/connectors")
@CrossOrigin
@Tag(name = "Connector", description = "外部数据源连接器")
public class ConnectorController {

    private final ConnectorService service;

    private final ConnectorCatalogService catalog;

    public ConnectorController(ConnectorService service, ConnectorCatalogService catalog) {
        this.service = service;
        this.catalog = catalog;
    }

    @GetMapping("/{kb}")
    @Operation(summary = "列出知识库下已保存的外部数据源连接器")
    public ApiResponse<Object> list(@PathVariable Long kb) {
        return ApiResponse.ok(service.list(kb));
    }

    @PostMapping
    @Operation(summary = "保存连接器配置（Jira / Confluence / RESTful API）")
    public ApiResponse<Object> create(@RequestBody ConnectorRequest request) {
        return ApiResponse.ok(service.create(request, AuthContext.get()));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新连接器配置；密钥留空表示沿用已保存的凭据")
    public ApiResponse<Object> update(@PathVariable Long id, @RequestBody ConnectorRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除连接器配置")
    public ApiResponse<Object> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok(Collections.singletonMap("deleted", Boolean.TRUE));
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "测试连接：读取一页最小结果并记录连通状态")
    public ApiResponse<Object> test(@PathVariable Long id) {
        return ApiResponse.ok(service.test(id));
    }

    @PostMapping("/preview")
    @Operation(summary = "按未保存的配置试读一页知识列表，用于填写时校验")
    public ApiResponse<Object> preview(@RequestBody ConnectorRequest request) {
        return ApiResponse.ok(service.preview(request));
    }

    @GetMapping("/{id}/items")
    @Operation(summary = "读取连接器当前的知识列表（分页）")
    public ApiResponse<Object> items(@PathVariable Long id, @RequestParam(value = "limit", required = false) Integer limit,
        @RequestParam(value = "offset", required = false) Integer offset) {
        return ApiResponse.ok(service.items(id, limit == null ? 0 : limit, offset == null ? 0 : offset));
    }

    @PostMapping("/{id}/sync")
    @Operation(summary = "把连接器当前的知识列表同步落库，供按类型与属性浏览")
    public ApiResponse<Object> sync(@PathVariable Long id) {
        return ApiResponse.ok(catalog.sync(id));
    }

    @GetMapping("/{id}/catalog")
    @Operation(summary = "浏览已同步的外部知识：按类型、关键词与属性值过滤后分页")
    public ApiResponse<Object> catalog(@PathVariable Long id,
        @RequestParam(value = "type", required = false) String type,
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "attr", required = false) List<String> attr,
        @RequestParam(value = "page", defaultValue = "1") int page,
        @RequestParam(value = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(catalog.page(id, type, keyword, attr, page, size));
    }

    @GetMapping("/{id}/catalog/facets")
    @Operation(summary = "已同步知识的类型与属性分面（左侧筛选树的取值与计数）")
    public ApiResponse<Object> catalogFacets(@PathVariable Long id, @RequestParam(value = "type", required = false) String type) {
        return ApiResponse.ok(catalog.facets(id, type));
    }

    @GetMapping("/catalog/items/{itemId}")
    @Operation(summary = "查看单条已同步外部知识的详情与全部属性")
    public ApiResponse<Object> catalogItem(@PathVariable Long itemId) {
        return ApiResponse.ok(catalog.detail(itemId));
    }
}
