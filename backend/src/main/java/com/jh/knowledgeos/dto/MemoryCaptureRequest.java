package com.jh.knowledgeos.dto;

import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.util.List;

/** A confirmed, bounded memory item submitted by an authenticated Agent integration. */
public class MemoryCaptureRequest {

    @NotBlank
    @Pattern(regexp = "L0|L1|L2|L3")
    private String memoryLevel;

    @NotBlank
    @Pattern(regexp = "AGENT|TEAM|PROJECT|TASK|WORKFLOW|SESSION")
    private String scopeType;

    @Size(max = 128)
    private String scopeCode;

    @NotBlank
    @Size(max = 4000)
    private String content;

    @Size(max = 20)
    private List<@Size(max = 64) String> tags;

    @Pattern(regexp = "TASK_RESULT|USER_CONFIRMED|WORKFLOW|MANUAL|OTHER")
    private String sourceType;

    @Size(max = 128)
    private String sourceRef;

    @Size(max = 128)
    private String idempotencyKey;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private Double confidence;

    @Min(1)
    @Max(5)
    private Integer importance;

    @Min(1)
    @Max(3650)
    private Integer expiresInDays;

    public String getMemoryLevel() { return memoryLevel; }
    public void setMemoryLevel(String value) { memoryLevel = value; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String value) { scopeType = value; }
    public String getScopeCode() { return scopeCode; }
    public void setScopeCode(String value) { scopeCode = value; }
    public String getContent() { return content; }
    public void setContent(String value) { content = value; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> value) { tags = value; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String value) { sourceType = value; }
    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String value) { sourceRef = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { idempotencyKey = value; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double value) { confidence = value; }
    public Integer getImportance() { return importance; }
    public void setImportance(Integer value) { importance = value; }
    public Integer getExpiresInDays() { return expiresInDays; }
    public void setExpiresInDays(Integer value) { expiresInDays = value; }
}
