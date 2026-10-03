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

    Map<String, Object> getSourcePreview(@Param("kb") Long kb, @Param("sourceId") Long sourceId, @Param("permissionTags") List<String> permissionTags);

    List<Map<String, Object>> listDocuments(@Param("kb") Long kb, @Param("permissionTags") List<String> permissionTags);

    int supersedeDocumentChunks(@Param("documentId") Long documentId);

    int nextChunkRevision(@Param("documentId") Long documentId);

    int batchUpdateContentUnitParents(@Param("rows") List<Map<String, Object>> rows);

    List<Map<String, Object>> listDocumentChunks(@Param("documentId") Long documentId);

    int insertContentUnit(@Param("documentId") Long documentId, @Param("unitCode") String unitCode, @Param("headingPath") String heading,
        @Param("pageNo") Integer pageNo, @Param("content") String content, @Param("contentHash") String hash);

    Long findContentUnitId(@Param("unitCode") String unitCode);

    int batchInsertContentUnits(@Param("rows") List<Map<String, Object>> rows);

    List<Map<String, Object>> findContentUnitIdsByCodes(@Param("codes") List<String> codes);
}
