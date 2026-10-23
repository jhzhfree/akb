package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.mapper.DocumentMapper;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DocumentExtractionAccessTest {
    @Test
    void privateBaseAndSourceLabelsAreCheckedBeforeExtraction() {
        DocumentMapper documents = mock(DocumentMapper.class);
        KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
        AclContextResolver acl = mock(AclContextResolver.class);
        Map<String, Object> row = new HashMap<String, Object>();
        row.put("knowledgeBaseId", 42L);
        row.put("permissionTags", "[\"payroll\"]");
        when(documents.findReextractContext(7L)).thenReturn(row);
        DocumentExtractionAccess service = new DocumentExtractionAccess(documents, access, acl, new ObjectMapper());
        doThrow(new SecurityException("private")).when(access).assertWrite(42L);
        assertThrows(SecurityException.class, () -> service.requireWrite(7L));
        verifyNoInteractions(acl);
        doNothing().when(access).assertWrite(42L);
        when(acl.resolve(null)).thenReturn(Collections.emptyList());
        assertThrows(SecurityException.class, () -> service.requireWrite(7L));
        when(acl.resolve(null)).thenReturn(Arrays.asList("payroll"));
        assertSame(row, service.requireWrite(7L));
    }
}
