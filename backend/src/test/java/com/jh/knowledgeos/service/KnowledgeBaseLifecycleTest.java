package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.jh.knowledgeos.common.PlatformEventTypes;
import com.jh.knowledgeos.domain.KnowledgeBase;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.mapper.IndexMapper;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.*;

/**
 * 知识库的停用与彻底删除。
 *
 * <p>这一组钉住的是顺序约束而不是实现细节：停用必须可逆、必须挡住检索、彻底删除只能
 * 作用在已停用的库上，且删得掉那些容易漏的从属表。</p>
 */
class KnowledgeBaseLifecycleTest {

    KnowledgeMapper mapper;

    PlatformEventPublisher publisher;

    KnowledgeBaseService service;

    KnowledgeBasePurgeService purge;

    @BeforeEach
    void setup() {
        mapper = mock(KnowledgeMapper.class);
        publisher = mock(PlatformEventPublisher.class);
        service = new KnowledgeBaseService(mapper, mock(IdGenerator.class), publisher,
            mock(KnowledgeDerivationService.class), mock(KbScenarioService.class));
        purge = new KnowledgeBasePurgeService(mapper, mock(SourceFileStorageService.class), mock(AiClient.class), publisher,
            mock(IndexMapper.class));
        when(mapper.countPurgeable(anyLong())).thenReturn(new LinkedHashMap<String, Object>());
        when(mapper.listSourceStoragePaths(anyLong())).thenReturn(new ArrayList<String>());
        when(mapper.deleteKnowledgeBase(anyLong())).thenReturn(1);
        // 删除要校验"创建人或管理员"，没有当前用户时这条会先拦住，测不到状态约束。
        User operator = new User();
        operator.setId(1L);
        operator.setRole("KNOWLEDGE_ADMIN");
        operator.setStatus("ACTIVE");
        AuthContext.set(operator);
    }

    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    private KnowledgeBase base(String status) {
        KnowledgeBase base = new KnowledgeBase();
        base.setId(7L);
        base.setBaseCode("KB_test");
        base.setName("运维知识库");
        base.setStatus(status);
        return base;
    }

    @Test
    void disableStopsTheBaseButKeepsItsData() {
        when(mapper.getKnowledgeBase(7L)).thenReturn(base("ACTIVE"), base("DISABLED"));
        KnowledgeBase result = service.disable(7L);
        assertEquals("DISABLED", result.getStatus());
        verify(mapper).updateKnowledgeBaseStatus(7L, "DISABLED");
        // 只改状态：数据一律不动，否则"可逆"就是假的
        verify(mapper, never()).deleteKnowledgeBase(anyLong());
        verify(mapper, never()).purgeKnowledgeBaseTable(anyString(), anyLong());
        verify(publisher).emitUser(eq(PlatformEventTypes.KNOWLEDGE_BASE_DISABLED), eq(7L),
            eq(PlatformEventPublisher.SOURCE_UI), eq("KNOWLEDGE_BASE"), eq("KB_test"), contains("禁用知识库"), anyMap());
    }

    @Test
    void disablingAnAlreadyDisabledBaseIsIdempotent() {
        when(mapper.getKnowledgeBase(7L)).thenReturn(base("DISABLED"));
        assertEquals("DISABLED", service.disable(7L).getStatus());
        verify(mapper, never()).updateKnowledgeBaseStatus(anyLong(), anyString());
        // 重复点击不该在事件流里刷出第二条"禁用"
        verify(publisher, never()).emitUser(anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void enableBringsItBack() {
        when(mapper.getKnowledgeBase(7L)).thenReturn(base("DISABLED"), base("ACTIVE"));
        assertEquals("ACTIVE", service.enable(7L).getStatus());
        verify(mapper).updateKnowledgeBaseStatus(7L, "ACTIVE");
        verify(publisher).emitUser(eq(PlatformEventTypes.KNOWLEDGE_BASE_ENABLED), eq(7L),
            eq(PlatformEventPublisher.SOURCE_UI), eq("KNOWLEDGE_BASE"), eq("KB_test"), contains("启用知识库"), anyMap());
    }

    @Test
    void archivedBasesCannotBeToggled() {
        when(mapper.getKnowledgeBase(7L)).thenReturn(base("ARCHIVED"));
        assertThrows(IllegalStateException.class, () -> service.disable(7L));
        assertThrows(IllegalStateException.class, () -> service.enable(7L));
    }

    @Test
    void purgingAnActiveBaseIsRefusedUntilItIsDisabled() {
        when(mapper.getKnowledgeBase(7L)).thenReturn(base("ACTIVE"));
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> purge.deleteWorkspace(7L, "7"));
        assertTrue(error.getMessage().contains("请先禁用"), error.getMessage());
        // 拒绝不能是"先删一半再报错"
        verify(mapper, never()).deleteKnowledgeBase(anyLong());
        verify(mapper, never()).purgeKnowledgeBaseTable(anyString(), anyLong());
    }

    @Test
    void purgingADisabledBaseAlsoClearsWorkspaceScopedTables() {
        when(mapper.getKnowledgeBase(7L)).thenReturn(base("DISABLED"));
        Map<String, Object> result = purge.deleteWorkspace(7L, "7");
        assertEquals(Boolean.TRUE, result.get("workspaceRemoved"));
        // 配置类表不是知识，清空知识时不该动，但彻底删除必须一起清掉，否则留下孤儿行
        verify(mapper).purgeKnowledgeBaseTable("t_chunk_policy", 7L);
        verify(mapper).purgeKnowledgeBaseTable("t_kb_scenario", 7L);
        verify(mapper).purgeKnowledgeBaseTable("t_evaluation_dataset", 7L);
        verify(mapper).purgeKnowledgeBaseTable("t_schema_type", 7L);
        // 单库绑定的 Key 要解开，别留下指向已删库的脏绑定
        verify(mapper).detachKnowledgeBaseFromApiKeys(7L);
        verify(mapper).deleteKnowledgeBase(7L);
    }

    @Test
    void confirmStillGuardsTheIrreversibleStep() {
        when(mapper.getKnowledgeBase(7L)).thenReturn(base("DISABLED"));
        assertThrows(IllegalArgumentException.class, () -> purge.deleteWorkspace(7L, "别的名字"));
        verify(mapper, never()).deleteKnowledgeBase(anyLong());
    }
}
