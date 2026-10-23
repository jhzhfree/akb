package com.jh.knowledgeos.mapper;

import com.jh.knowledgeos.domain.*;
import com.jh.knowledgeos.projection.AuthoritativeIndexSnapshotRow;
import com.jh.knowledgeos.projection.AuthoritativeGraphSnapshotRow;
import org.apache.ibatis.annotations.Param;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** Domain persistence mapper for document concerns. */
public interface DocumentMapper {

    int insertSource(KnowledgeSource source);

    int insertSourceVersion(@Param("sourceId") Long sourceId, @Param("versionNo") Integer versionNo, @Param("sourceHash") String hash,
        @Param("objectUri") String uri);

    Long findSourceVersionId(@Param("sourceId") Long sourceId, @Param("versionNo") Integer versionNo);

    int insertDocument(KnowledgeDocument document);

    int updateDocumentParsed(@Param("id") Long id, @Param("rawText") String rawText, @Param("chunkCount") Integer chunkCount, @Param("status") String status);

    int updateDocumentState(@Param("id") Long id, @Param("status") String status, @Param("stage") String stage, @Param("error") String error,
        @Param("retryCount") Integer retryCount);

    Map<String, Object> findDocumentRetryContext(@Param("id") Long id);

    List<KnowledgeSource> listSources(@Param("kb") Long kb, @Param("permissionTags") List<String> permissionTags);

    /**
     * Update the governance columns a ledger row owns.
     *
     * <p>Every parameter may be null, which means "leave it alone": the ledger is filled in one
     * column at a time — nominating an owner must not silently erase the expiry date.</p>
     */
    int updateSourceGovernance(@Param("kb") Long kb, @Param("id") Long id, @Param("ownerUserId") Long ownerUserId,
        @Param("effectiveAt") Date effectiveAt, @Param("expireAt") Date expireAt, @Param("scope") String scope,
        @Param("status") String status);

    Map<String, Object> getSourcePreview(@Param("kb") Long kb, @Param("sourceId") Long sourceId, @Param("permissionTags") List<String> permissionTags);

    /**
     * 重跑抽取所需的上下文：文档正文、所属知识库与来源、来源的权限标签与权威度。
     * 一次取齐，避免调用方拼出一条与真实来源不一致的权限标签——那会让重跑出来的知识
     * 带上错误的可见范围。
     */
    Map<String, Object> findReextractContext(@Param("documentId") Long documentId);

    /**
     * 这篇文档已经入库的标准单元，连同每条单元的证据编码。
     *
     * <p>重跑抽取必须复用这些单元：证据编码是知识指向原文的锚点，重新解析会生成一批新的
     * 编码，旧的引用关系全部作废。</p>
     */
    List<Map<String, Object>> findUnitsWithEvidence(@Param("documentId") Long documentId);

    /** 记录抽取阶段的成败；解析与索引各自记在自己的列上，互不覆盖。 */
    int updateExtractionStatus(@Param("id") Long id, @Param("status") String status);

    /** 记录索引阶段的成败。 */
    int updateIndexStatus(@Param("id") Long id, @Param("status") String status);

    List<Map<String, Object>> listDocuments(@Param("kb") Long kb, @Param("permissionTags") List<String> permissionTags);

    int supersedeDocumentChunks(@Param("documentId") Long documentId);

    int nextChunkRevision(@Param("documentId") Long documentId);

    int batchUpdateContentUnitParents(@Param("rows") List<Map<String, Object>> rows);

    List<Map<String, Object>> listDocumentChunks(@Param("documentId") Long documentId);

    /**
     * Full content of every unit of one document, joined to its evidence code.
     *
     * <p>Re-extraction needs the whole unit text and the evidence code it is anchored to:
     * the browsing query deliberately returns only a 200-character excerpt.</p>
     */
    List<Map<String, Object>> listDocumentUnits(@Param("documentId") Long documentId);

    int insertContentUnit(@Param("documentId") Long documentId, @Param("unitCode") String unitCode, @Param("headingPath") String heading,
        @Param("pageNo") Integer pageNo, @Param("content") String content, @Param("contentHash") String hash);

    Long findContentUnitId(@Param("unitCode") String unitCode);

    int batchInsertContentUnits(@Param("rows") List<Map<String, Object>> rows);

    List<Map<String, Object>> findContentUnitIdsByCodes(@Param("codes") List<String> codes);
}
