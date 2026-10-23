package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.mapper.DocumentMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Authorizes document-based extraction by its stored knowledge base and source labels, never by a caller-supplied scope. */
@Service
public class DocumentExtractionAccess {
    private final DocumentMapper documents;
    private final KnowledgeBaseAccessService access;
    private final AclContextResolver acl;
    private final ObjectMapper json;

    public DocumentExtractionAccess(DocumentMapper documents, KnowledgeBaseAccessService access, AclContextResolver acl, ObjectMapper json) {
        this.documents = documents;
        this.access = access;
        this.acl = acl;
        this.json = json;
    }

    /** Role-level import permission is checked at transport; this method enforces resource-level write access. */
    public Map<String, Object> requireWrite(Long documentId) {
        Map<String, Object> context = documents.findReextractContext(documentId);
        if (context == null) { throw new IllegalArgumentException("Document not found"); }
        Long kb = Long.valueOf(String.valueOf(context.get("knowledgeBaseId")));
        access.assertWrite(kb);
        List<String> visible = acl.resolve(null);
        if (visible != null) {
            try {
                String labels = ExtractionCanonical.labels(context.get("permissionTags") == null ? null : String.valueOf(context.get("permissionTags")));
                List<?> required = json.readValue(labels, List.class);
                if (!required.isEmpty() && required.stream().noneMatch(visible::contains)) {
                    throw new SecurityException("No access to this document's source labels");
                }
            } catch (SecurityException ex) { throw ex; }
            catch (Exception ex) { throw new SecurityException("Cannot resolve source authorization", ex); }
        }
        return context;
    }
}
