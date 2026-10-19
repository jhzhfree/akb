package com.jh.knowledgeos.domain;

import com.jh.knowledgeos.common.KnowledgeBaseVisibility;

/**
 * Authoritative knowledge-base workspace metadata, including ownership and sharing scope.
 */
public class KnowledgeBase {

    private Long id;

    private String baseCode;

    private String name;

    private String description;

    private String status;

    /**
     * 创建人：私有与用户组可见的知识库以此来判定归属。
     */
    private Long createdBy;

    private String createdByName;

    private String visibility;

    private Long ownerGroupId;

    private String ownerGroupName;

    /**
     * 预置示例问句：作为提问输入框的默认提示，可一键发起，不参与召回。
     *
     * <p>库自己最清楚该被怎么问——运维库想听「这个告警的处置步骤是什么」，合同库想听
     * 「这份合同有哪些交付范围」。留空时各端退回各自的通用提示语。</p>
     */
    private String defaultQuery;

    /**
     * 场景模板编码（GENERAL / OPS / POLICY / BUSINESS / VULN）：决定该库的切分、衍生与检索策略。
     * 由 t_kb_scenario 联表带出，无配置行时为 GENERAL，保证列表与编辑弹窗看到的是同一个值。
     */
    private String scenario;

    private java.time.LocalDateTime createdTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBaseCode() {
        return baseCode;
    }

    public void setBaseCode(String v) {
        baseCode = v;
    }

    public String getName() {
        return name;
    }

    public void setName(String v) {
        name = v;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String v) {
        description = v;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String v) {
        status = v;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long v) {
        createdBy = v;
    }

    public String getCreatedByName() {
        return createdByName;
    }

    public void setCreatedByName(String v) {
        createdByName = v;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String v) {
        visibility = v;
    }

    public Long getOwnerGroupId() {
        return ownerGroupId;
    }

    public void setOwnerGroupId(Long v) {
        ownerGroupId = v;
    }

    public String getOwnerGroupName() {
        return ownerGroupName;
    }

    public void setOwnerGroupName(String v) {
        ownerGroupName = v;
    }

    public String getScenario() {
        return scenario;
    }

    public void setScenario(String v) {
        scenario = v;
    }

    public String getDefaultQuery() {
        return defaultQuery;
    }

    public void setDefaultQuery(String v) {
        defaultQuery = v;
    }

    public java.time.LocalDateTime getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(java.time.LocalDateTime v) {
        createdTime = v;
    }

    public KnowledgeBaseVisibility visibility() {
        return KnowledgeBaseVisibility.of(visibility);
    }
}
