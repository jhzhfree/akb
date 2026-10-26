package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.domain.IndexOutboxEvent;
import com.jh.knowledgeos.mapper.DocumentMapper;
import com.jh.knowledgeos.mapper.IndexMapper;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndexOutboxWorkerReliabilityTest {
    @Test
    void remoteDeliveryRunsAfterClaimTransactionCommits() {
        IndexMapper index = mock(IndexMapper.class);
        KnowledgeMapper knowledge = mock(KnowledgeMapper.class);
        DocumentMapper documents = mock(DocumentMapper.class);
        IndexOutboxService outbox = mock(IndexOutboxService.class);
        AiClient ai = mock(AiClient.class);
        IndexOutboxMetrics metrics = mock(IndexOutboxMetrics.class);
        TransactionRunner transactions = mock(TransactionRunner.class);
        CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<String>();
        when(transactions.required(any(Supplier.class))).thenAnswer(invocation -> {
            events.add("transaction-start");
            Object result = ((Supplier<?>) invocation.getArgument(0)).get();
            events.add("transaction-commit");
            return result;
        });
        doAnswer(invocation -> {
            Runnable operation = invocation.getArgument(0);
            events.add("transaction-start");
            operation.run();
            events.add("transaction-commit");
            return null;
        }).when(transactions).required(any(Runnable.class));
        IndexOutboxEvent event = event(1, 8);
        when(index.selectDueIndexOutbox(10)).thenReturn(Collections.singletonList(event));
        when(index.claimIndexOutbox(eq(1L), anyString())).thenAnswer(invocation -> {
            events.add("claim");
            return 1;
        });
        when(index.markIndexOutboxSucceeded(eq(1L), anyString(), anyString())).thenReturn(1);
        when(outbox.payload(event)).thenReturn(Collections.<String, Object>singletonMap("idempotencyKey", "idem-1"));
        when(ai.post(eq("/index"), any(Map.class))).thenAnswer(invocation -> {
            events.add("remote-delivery");
            return new LinkedHashMap<String, Object>();
        });
        when(documents.documentsForIndexAggregate(1L, "KB:1:CARD:C1")).thenReturn(Collections.<Long>emptyList());
        IndexOutboxWorker worker = new IndexOutboxWorker(knowledge, outbox, ai, new ObjectMapper(), transactions, metrics,
            true, 10, 300, 2, index, documents);

        worker.poll();

        assertTrue(events.indexOf("transaction-commit") < events.indexOf("remote-delivery"));
        assertTrue(events.lastIndexOf("remote-delivery") < events.lastIndexOf("transaction-start"));
        verify(index).markIndexOutboxSucceeded(eq(1L), anyString(), anyString());
        verify(index).insertIndexDeliveryAttempt(eq("OUT_1"), eq(1), eq("SUCCEEDED"), anyLong(), anyString(), eq(null));
    }

    @Test
    void staleLeaseCannotMarkAnEventSucceededAfterAnotherWorkerReclaimsIt() {
        IndexMapper index = mock(IndexMapper.class);
        KnowledgeMapper knowledge = mock(KnowledgeMapper.class);
        DocumentMapper documents = mock(DocumentMapper.class);
        IndexOutboxService outbox = mock(IndexOutboxService.class);
        AiClient ai = mock(AiClient.class);
        IndexOutboxMetrics metrics = mock(IndexOutboxMetrics.class);
        TransactionRunner transactions = directTransactions();
        IndexOutboxEvent event = event(1, 8);
        when(index.selectDueIndexOutbox(10)).thenReturn(Collections.singletonList(event));
        when(index.claimIndexOutbox(eq(1L), anyString())).thenReturn(1);
        when(index.markIndexOutboxSucceeded(eq(1L), anyString(), anyString())).thenReturn(0);
        when(outbox.payload(event)).thenReturn(Collections.<String, Object>singletonMap("idempotencyKey", "idem-1"));
        when(ai.post(eq("/index"), any(Map.class))).thenReturn(new LinkedHashMap<String, Object>());
        IndexOutboxWorker worker = new IndexOutboxWorker(knowledge, outbox, ai, new ObjectMapper(), transactions, metrics,
            true, 10, 300, 2, index, documents);

        worker.poll();

        verify(index).markIndexOutboxSucceeded(eq(1L), anyString(), anyString());
        verify(documents, org.mockito.Mockito.never()).documentsForIndexAggregate(anyLong(), anyString());
        verify(index, org.mockito.Mockito.never()).insertIndexDeliveryAttempt(anyString(), anyInt(), anyString(), anyLong(), any(), any());
    }

    @Test
    void staleLeaseCannotScheduleRetryAfterAnotherWorkerReclaimsIt() {
        IndexMapper index = mock(IndexMapper.class);
        KnowledgeMapper knowledge = mock(KnowledgeMapper.class);
        DocumentMapper documents = mock(DocumentMapper.class);
        IndexOutboxService outbox = mock(IndexOutboxService.class);
        AiClient ai = mock(AiClient.class);
        IndexOutboxMetrics metrics = mock(IndexOutboxMetrics.class);
        TransactionRunner transactions = directTransactions();
        IndexOutboxEvent event = event(1, 8);
        when(index.selectDueIndexOutbox(10)).thenReturn(Collections.singletonList(event));
        when(index.claimIndexOutbox(eq(1L), anyString())).thenReturn(1);
        when(index.markIndexOutboxRetry(eq(1L), anyString(), any(), anyString())).thenReturn(0);
        when(outbox.payload(event)).thenReturn(Collections.<String, Object>singletonMap("idempotencyKey", "idem-1"));
        when(ai.post(eq("/index"), any(Map.class))).thenThrow(new IllegalStateException("temporary failure"));
        IndexOutboxWorker worker = new IndexOutboxWorker(knowledge, outbox, ai, new ObjectMapper(), transactions, metrics,
            true, 10, 300, 2, index, documents);

        worker.poll();

        verify(index).markIndexOutboxRetry(eq(1L), anyString(), any(), anyString());
        verify(index, org.mockito.Mockito.never()).insertIndexDeliveryAttempt(anyString(), anyInt(), anyString(), anyLong(), any(), any());
        verify(documents, org.mockito.Mockito.never()).documentsForIndexAggregate(anyLong(), anyString());
    }

    @Test
    void nestedApplicationErrorOnHttpSuccessIsRetriedAndEventuallyDeadLettered() {
        for (int attempt : Arrays.asList(1, 2)) {
            IndexMapper index = mock(IndexMapper.class);
            KnowledgeMapper knowledge = mock(KnowledgeMapper.class);
            DocumentMapper documents = mock(DocumentMapper.class);
            IndexOutboxService outbox = mock(IndexOutboxService.class);
            AiClient ai = mock(AiClient.class);
            IndexOutboxMetrics metrics = mock(IndexOutboxMetrics.class);
            TransactionRunner transactions = directTransactions();
            IndexOutboxEvent event = event(attempt, 2);
            when(index.selectDueIndexOutbox(10)).thenReturn(Collections.singletonList(event));
            when(index.claimIndexOutbox(eq(1L), anyString())).thenReturn(1);
            when(index.markIndexOutboxRetry(eq(1L), anyString(), any(), anyString())).thenReturn(1);
            when(index.markIndexOutboxDead(eq(1L), anyString(), anyString())).thenReturn(1);
            when(outbox.payload(event)).thenReturn(Collections.<String, Object>singletonMap("idempotencyKey", "idem-1"));
            when(ai.post(eq("/index"), any(Map.class))).thenReturn(Collections.<String, Object>singletonMap("result",
                Collections.<String, Object>singletonMap("error", "partial indexing failure")));
            when(documents.documentsForIndexAggregate(1L, "KB:1:CARD:C1")).thenReturn(Collections.<Long>emptyList());
            IndexOutboxWorker worker = new IndexOutboxWorker(knowledge, outbox, ai, new ObjectMapper(), transactions, metrics,
                true, 10, 300, 2, index, documents);

            worker.poll();

            if (attempt == 1) {
                verify(index).markIndexOutboxRetry(eq(1L), anyString(), any(), anyString());
                verify(index).insertIndexDeliveryAttempt(eq("OUT_1"), eq(1), eq("RETRY"), anyLong(), eq(null), anyString());
            } else {
                verify(index).markIndexOutboxDead(eq(1L), anyString(), anyString());
                verify(index).insertIndexDeliveryAttempt(eq("OUT_1"), eq(2), eq("DEAD"), anyLong(), eq(null), anyString());
            }
        }
    }

    private TransactionRunner directTransactions() {
        TransactionRunner transactions = mock(TransactionRunner.class);
        when(transactions.required(any(Supplier.class))).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
        doAnswer(invocation -> { ((Runnable) invocation.getArgument(0)).run(); return null; })
            .when(transactions).required(any(Runnable.class));
        return transactions;
    }

    private IndexOutboxEvent event(int attempt, int maxAttempts) {
        IndexOutboxEvent event = new IndexOutboxEvent();
        event.setId(1L);
        event.setEventId("OUT_1");
        event.setIdempotencyKey("idem-1");
        event.setKnowledgeBaseId(1L);
        event.setAggregateType("CARD");
        event.setAggregateKey("KB:1:CARD:C1");
        event.setEventType("UPSERT");
        event.setTargetPath("/index");
        event.setAttemptCount(attempt - 1);
        event.setMaxAttempts(maxAttempts);
        return event;
    }
}
