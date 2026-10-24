package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.jh.knowledgeos.domain.PlatformEvent;
import com.jh.knowledgeos.mapper.PlatformEventMapper;
import java.util.Collections;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PlatformEventPublisherIdempotencyTest {

    @Test
    void retryReusesDurableEventInsteadOfInsertingAnotherRow() {
        PlatformEventMapper mapper = mock(PlatformEventMapper.class);
        PlatformEventStore store = new PlatformEventStore(mapper, new com.fasterxml.jackson.databind.ObjectMapper());
        PlatformEventHub hub = mock(PlatformEventHub.class);
        IdGenerator ids = mock(IdGenerator.class);
        when(ids.next("EV_")).thenReturn("EV_EXTRACTION_1");
        PlatformEvent prior = storedEvent();
        when(mapper.getPlatformEventByIdempotencyKey("EXTRACT_POST:run:EVENT_PUBLISHED:COMPLETED")).thenReturn(null, prior);
        when(mapper.insertPlatformEvent(any(PlatformEvent.class))).thenAnswer(invocation -> {
            PlatformEvent event = invocation.getArgument(0);
            event.setId(12L);
            return 1;
        });
        Executor fanout = Runnable::run;
        PlatformEventPublisher publisher = new PlatformEventPublisher(store, hub, ids, fanout);

        publisher.emitIdempotent("EXTRACT_POST:run:EVENT_PUBLISHED:COMPLETED", "EXTRACTION_COMPLETED", 1L, "UI", "DOCUMENT",
            "9", "extraction completed", Collections.<String, Object>emptyMap(), "USER", "7", "operator");
        publisher.emitIdempotent("EXTRACT_POST:run:EVENT_PUBLISHED:COMPLETED", "EXTRACTION_COMPLETED", 1L, "UI", "DOCUMENT",
            "9", "extraction completed", Collections.<String, Object>emptyMap(), "USER", "7", "operator");

        ArgumentCaptor<PlatformEvent> inserted = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(mapper, times(1)).insertPlatformEvent(inserted.capture());
        assertEquals("EXTRACT_POST:run:EVENT_PUBLISHED:COMPLETED", inserted.getValue().getIdempotencyKey());
        ArgumentCaptor<PlatformEvent> published = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(hub, times(2)).publish(published.capture());
        assertSame(inserted.getValue(), published.getAllValues().get(0));
        assertSame(prior, published.getAllValues().get(1));
    }

    private PlatformEvent storedEvent() {
        PlatformEvent event = new PlatformEvent();
        event.setEventId("EV_EXTRACTION_1");
        event.setIdempotencyKey("EXTRACT_POST:run:EVENT_PUBLISHED:COMPLETED");
        event.setEventType("EXTRACTION_COMPLETED");
        return event;
    }
}
