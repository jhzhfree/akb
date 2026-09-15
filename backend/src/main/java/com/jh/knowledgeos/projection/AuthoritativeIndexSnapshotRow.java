package com.jh.knowledgeos.projection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed projection used by the authoritative index rebuild.
 *
 * <p>This object is intentionally read-only from the service perspective. It replaces
 * unstructured {@code Map<String, Object>} rows at the MyBatis boundary so column-name
 * changes become visible to the compiler and code review.</p>
 */
public class AuthoritativeIndexSnapshotRow {
    private String objectKey;
    private String objectId;
    private String objectType;
    private Long knowledgeBaseId;
    private Long documentId;
    private Long contentUnitId;
    private String title;
    private String content;
    private String sourceName;
    private String sourceUri;
    private Integer pageNo;
    private String headingPath;
    private String status;
    private String contentHash;
    private String permissionTags;

    /** Convert the typed database projection to the stable AI-service contract payload. */
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("objectKey", objectKey);
        payload.put("objectId", objectId);
        payload.put("objectType", objectType);
        payload.put("knowledgeBaseId", knowledgeBaseId);
        payload.put("documentId", documentId);
        payload.put("contentUnitId", contentUnitId);
        payload.put("title", title);
        payload.put("content", content);
        payload.put("sourceName", sourceName);
        payload.put("sourceUri", sourceUri);
        payload.put("pageNo", pageNo);
        payload.put("headingPath", headingPath);
        payload.put("status", status);
        payload.put("contentHash", contentHash);
        payload.put("permissionTags", permissionTags);
        return payload;
    }

    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
    public String getObjectId() { return objectId; }
    public void setObjectId(String objectId) { this.objectId = objectId; }
    public String getObjectType() { return objectType; }
    public void setObjectType(String objectType) { this.objectType = objectType; }
    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(Long knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }
    public Long getContentUnitId() { return contentUnitId; }
    public void setContentUnitId(Long contentUnitId) { this.contentUnitId = contentUnitId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }
    public String getSourceUri() { return sourceUri; }
    public void setSourceUri(String sourceUri) { this.sourceUri = sourceUri; }
    public Integer getPageNo() { return pageNo; }
    public void setPageNo(Integer pageNo) { this.pageNo = pageNo; }
    public String getHeadingPath() { return headingPath; }
    public void setHeadingPath(String headingPath) { this.headingPath = headingPath; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPermissionTags() { return permissionTags; }
    public void setPermissionTags(String permissionTags) { this.permissionTags = permissionTags; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
}
