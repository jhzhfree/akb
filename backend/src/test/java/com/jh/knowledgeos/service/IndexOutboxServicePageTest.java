package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.domain.IndexOutboxEvent;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the console paging contract: window size bounds, page clamping, and the
 * total/totalPages values the UI renders.
 */
class IndexOutboxServicePageTest {

    private final KnowledgeMapper mapper = mock(KnowledgeMapper.class);

    private final IndexOutboxService service = new IndexOutboxService(mapper, new ObjectMapper(), 8);

    @Test
    void pageWindowsOffsetFromPageAndSize() {
        when(mapper.countIndexOutbox(eq(1L), isNull())).thenReturn(42L);
        when(mapper.listIndexOutbox(eq(1L), isNull(), anyInt(), anyInt())).thenReturn(new ArrayList<IndexOutboxEvent>());
        Map<String, Object> page = service.page(1L, null, 3, 10);
        assertEquals(42L, page.get("total"));
        assertEquals(3, page.get("page"));
        assertEquals(10, page.get("size"));
        assertEquals(5, page.get("totalPages"));
        ArgumentCaptor<Integer> offset = ArgumentCaptor.forClass(Integer.class);
        verify(mapper, times(1)).listIndexOutbox(eq(1L), isNull(), eq(10), offset.capture());
        assertEquals(Integer.valueOf(20), offset.getValue());
    }

    @Test
    void pageOutOfRangeFallsBackToLastPage() {
        when(mapper.countIndexOutbox(eq(1L), eq("DEAD"))).thenReturn(5L);
        when(mapper.listIndexOutbox(eq(1L), eq("DEAD"), anyInt(), anyInt())).thenReturn(new ArrayList<IndexOutboxEvent>());
        Map<String, Object> page = service.page(1L, "DEAD", 99, 2);
        assertEquals(3, page.get("page"));
        assertEquals(3, page.get("totalPages"));
        verify(mapper).listIndexOutbox(1L, "DEAD", 2, 4);
    }

    @Test
    void pageAppliesDefaultsAndSizeCeiling() {
        when(mapper.countIndexOutbox(eq(1L), isNull())).thenReturn(0L);
        when(mapper.listIndexOutbox(eq(1L), isNull(), anyInt(), anyInt())).thenReturn(new ArrayList<IndexOutboxEvent>());
        Map<String, Object> defaults = service.page(1L, " ", 0, 0);
        assertEquals(1, defaults.get("page"));
        assertEquals(20, defaults.get("size"));
        Map<String, Object> oversized = service.page(1L, null, 1, 10000);
        assertEquals(500, oversized.get("size"));
    }

    @Test
    void emptyResultKeepsSinglePage() {
        when(mapper.countIndexOutbox(eq(1L), isNull())).thenReturn(0L);
        when(mapper.listIndexOutbox(eq(1L), isNull(), anyInt(), anyInt())).thenReturn(new ArrayList<IndexOutboxEvent>());
        Map<String, Object> page = service.page(1L, null, null, null);
        assertEquals(0L, page.get("total"));
        assertEquals(1, page.get("page"));
        assertEquals(1, page.get("totalPages"));
        verify(mapper).listIndexOutbox(1L, null, 20, 0);
    }

    @Test
    void listReadsFirstWindowOnly() {
        List<IndexOutboxEvent> events = new ArrayList<IndexOutboxEvent>();
        when(mapper.listIndexOutbox(eq(1L), anyString(), anyInt(), anyInt())).thenReturn(events);
        assertTrue(service.list(1L, "RETRY", 50).isEmpty());
        verify(mapper).listIndexOutbox(1L, "RETRY", 50, 0);
    }
}
