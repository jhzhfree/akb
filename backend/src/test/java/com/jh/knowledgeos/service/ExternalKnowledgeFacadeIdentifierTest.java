package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.common.ResponseMode;
import com.jh.knowledgeos.domain.KnowledgeBase;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 对内请求用主键，对外响应用编码——这条边界以前被踩过一次。
 *
 * <p>知识库编码是对外契约，AI 服务那个 {@code knowledgeBaseId} 字段的契约是整数。把编码
 * 带进对内请求，AI 服务直接 422，落到调用方是一个没有上下文的 502。这类错编译期抓不到，
 * 所以在这里钉住方向。</p>
 */
class ExternalKnowledgeFacadeIdentifierTest {

    private KnowledgeBase base(long id, String code) {
        KnowledgeBase base = new KnowledgeBase();
        base.setId(id);
        base.setBaseCode(code);
        return base;
    }

    @Test void aiServiceRequestCarriesPrimaryKeyNotCode() throws Exception {
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        KnowledgeBaseService bases = mock(KnowledgeBaseService.class);
        RetrievalAuthorizationService authorization = mock(RetrievalAuthorizationService.class);
        when(bases.get(6L)).thenReturn(base(6L, "KB_OPS"));
        when(authorization.effectiveTags(any(), anyLong(), any(), anyString())).thenReturn(Collections.emptyList());
        when(knowledgeService.query(any(Map.class))).thenReturn(new LinkedHashMap<String, Object>());
        ExternalKnowledgeFacade facade = new ExternalKnowledgeFacade(knowledgeService, bases, new ObjectMapper(), authorization,
            mock(AgentMemoryService.class));

        facade.query(null, 6L, "年假怎么申请", 10, null, "REST", null, null, ResponseMode.FULL, false);

        ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
        verify(knowledgeService).query(sent.capture());
        assertEquals(6L, sent.getValue().get("knowledgeBaseId"));
    }
}
