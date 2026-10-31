package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jh.knowledgeos.domain.KnowledgeBase;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.junit.jupiter.api.Test;

/** 对外接口里的知识库标识：数字是内部主键，其余按知识库编码。 */
class KnowledgeBaseIdentifierResolutionTest {

    private KnowledgeBaseService service(KnowledgeMapper mapper) {
        return new KnowledgeBaseService(mapper, mock(IdGenerator.class), mock(PlatformEventPublisher.class),
            mock(KnowledgeDerivationService.class), mock(KbScenarioService.class));
    }

    private KnowledgeBase base(long id, String code) {
        KnowledgeBase base = new KnowledgeBase();
        base.setId(id);
        base.setBaseCode(code);
        return base;
    }

    /** 没传就是没传：不能拿 0 之类的默认值冒充"调用方指定了库"。 */
    @Test void absentIdentifierStaysNull() {
        KnowledgeBaseService service = service(mock(KnowledgeMapper.class));
        assertNull(service.resolveId(null));
        assertNull(service.resolveId(""));
        assertNull(service.resolveId("   "));
    }

    /** 数字按主键走，与改造前完全一致——已经发出去的脚本不该因为这次改动失效。 */
    @Test void numericIdentifierIsTreatedAsPrimaryKey() {
        assertEquals(6L, service(mock(KnowledgeMapper.class)).resolveId(6L));
        assertEquals(6L, service(mock(KnowledgeMapper.class)).resolveId("6"));
        assertEquals(6L, service(mock(KnowledgeMapper.class)).resolveId(" 6 "));
    }

    @Test void codeResolvesThroughBaseCode() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        when(mapper.getExternalKnowledgeBaseByCode("KB_OPS")).thenReturn(base(23L, "KB_OPS"));
        assertEquals(23L, service(mapper).resolveId("KB_OPS"));
    }

    /**
     * 认不出来的编码要报错，不能返回 null 让调用方静默落到默认库上。
     * 静默回退会让对方拿到"能查通但查的是另一个库"的结果，比报错难排查得多。
     */
    @Test void unknownCodeIsRejected() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        when(mapper.getExternalKnowledgeBaseByCode("KB_GONE")).thenReturn(null);
        KnowledgeBaseService service = service(mapper);
        assertThrows(IllegalArgumentException.class, () -> service.resolveId("KB_GONE"));
    }
}
