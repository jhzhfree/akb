package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.service.KnowledgeSchemaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Knowledge schema console (design §7: 知识 Schema = 知识类型 / Entity / Relation / Ontology).
 *
 * <p>The vocabulary used to live inside the extractor, so "which entity types exist" was answered
 * by reading Python. Here it is platform data: editable per knowledge base, validated before
 * publication, published as a versioned snapshot, and continuously checked against what is already
 * stored so an unregistered type shows up as a violation instead of a silent inconsistency.</p>
 */
@RestController
@RequestMapping("/api/knowledge/schema")
@CrossOrigin
@Tag(name = "KnowledgeSchema", description = "知识 Schema：类型字典、校验与发布")
public class KnowledgeSchemaController {
    private final KnowledgeSchemaService schema;

    public KnowledgeSchemaController(KnowledgeSchemaService schema) {
        this.schema = schema;
    }

    @GetMapping
    @Operation(summary = "知识 Schema 总览：类型字典、已发布版本、发布历史与校验报告")
    public ApiResponse<Object> overview(
            @RequestParam(value = "kb", required = false) Long kb,
            @RequestParam(value = "kind", required = false) String kind) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("types", schema.types(kb, kind));
        payload.put("published", schema.published(kb));
        payload.put("releases", schema.releases(kb));
        payload.put("validation", schema.validate(kb));
        payload.put("meta", schema.schema());
        return ApiResponse.ok(payload);
    }

    @GetMapping("/types")
    @Operation(summary = "类型字典：知识类型、实体类型与关系类型")
    public ApiResponse<Object> types(
            @RequestParam(value = "kb", required = false) Long kb,
            @RequestParam(value = "kind", required = false) String kind) {
        return ApiResponse.ok(schema.types(kb, kind));
    }

    @PostMapping("/types")
    @Operation(summary = "新增或修改类型定义（含属性定义与关系端点约束）")
    public ApiResponse<Object> upsert(@RequestParam(value = "kb", required = false) Long kb,
            @RequestBody(required = false) Map<String, Object> payload) {
        return ApiResponse.ok(schema.upsertType(kb, payload));
    }

    @PostMapping("/types/deprecate")
    @Operation(summary = "弃用类型：存量数据保留，新数据不再使用")
    public ApiResponse<Object> deprecate(@RequestParam(value = "kb", required = false) Long kb,
            @RequestBody(required = false) Map<String, Object> payload) {
        String kind = payload == null || payload.get("kind") == null ? null
                : String.valueOf(payload.get("kind"));
        String typeCode = payload == null || payload.get("typeCode") == null ? null
                : String.valueOf(payload.get("typeCode"));
        return ApiResponse.ok(schema.deprecate(kb, kind, typeCode));
    }

    @DeleteMapping("/types")
    @Operation(summary = "删除知识库自定义类型（内置类型不可删除）")
    public ApiResponse<Object> delete(@RequestParam(value = "kb", required = false) Long kb,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "typeCode", required = false) String typeCode) {
        schema.delete(kb, kind, typeCode);
        return ApiResponse.ok("deleted");
    }

    @GetMapping("/validate")
    @Operation(summary = "校验 Schema 与存量数据的一致性")
    public ApiResponse<Object> validate(@RequestParam(value = "kb", required = false) Long kb) {
        return ApiResponse.ok(schema.validate(kb));
    }

    @PostMapping("/publish")
    @Operation(summary = "发布 Schema：生成版本快照并同步给抽取侧")
    public ApiResponse<Object> publish(@RequestParam(value = "kb", required = false) Long kb) {
        return ApiResponse.ok(schema.publish(kb, null));
    }

    @GetMapping("/releases")
    @Operation(summary = "Schema 发布历史")
    public ApiResponse<Object> releases(@RequestParam(value = "kb", required = false) Long kb) {
        return ApiResponse.ok(schema.releases(kb));
    }

    @GetMapping("/meta")
    @Operation(summary = "Schema 元数据：类型种类、属性类型与标识规则")
    public ApiResponse<Object> meta() {
        return ApiResponse.ok(schema.schema());
    }
}
