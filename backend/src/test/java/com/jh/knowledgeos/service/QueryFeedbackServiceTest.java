package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.dto.MemoryCaptureRequest;
import com.jh.knowledgeos.dto.QueryFeedbackRequest;
import com.jh.knowledgeos.dto.QueryFeedbackReviewRequest;
import com.jh.knowledgeos.mapper.QueryFeedbackMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QueryFeedbackServiceTest {

    private final QueryFeedbackMapper mapper = mock(QueryFeedbackMapper.class);
    private final KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
    private final AgentMemoryService memories = mock(AgentMemoryService.class);
    private final AclContextResolver aclContext = mock(AclContextResolver.class);
    private final QueryFeedbackService service = new QueryFeedbackService(mapper, access, memories, aclContext);

    @AfterEach
    void clearActor() {
        AuthContext.clear();
    }

    @Test
    void submissionDerivesKnowledgeBaseFromTraceAndUpsertsByActor() {
        AuthContext.set(user(7L));
        Map<String, Object> trace = new HashMap<String, Object>();
        trace.put("knowledgeBaseId", 23L);
        trace.put("actorUserId", 7L);
        when(mapper.getTraceOwner("q-1")).thenReturn(trace);
        Map<String, Object> saved = new HashMap<String, Object>();
        saved.put("queryId", "q-1");
        when(mapper.findByQueryAndActor("q-1", 7L)).thenReturn(saved);
        QueryFeedbackRequest request = request("q-1", "UNHELPFUL", "INCOMPLETE");

        Map<String, Object> result = service.submit(request);

        assertEquals("q-1", result.get("queryId"));
        verify(access).assertRead(23L);
        verify(mapper).upsert(org.mockito.ArgumentMatchers.anyString(), eq("q-1"), eq(23L), eq(7L), eq("UNHELPFUL"),
            eq("INCOMPLETE"), isNull(), isNull());
    }

    @Test
    void suggestedAnswerIsStoredAsTheUsersOwnMemory() {
        AuthContext.set(user(7L));
        when(aclContext.currentSubjectCode()).thenReturn("u1");
        traceOwnedBy(7L);
        QueryFeedbackRequest request = request("q-1", "UNHELPFUL", "INCOMPLETE");
        request.setQuestion("年假怎么算？");
        request.setSuggestedAnswer("年假按入职周年折算，不是自然年");

        service.submit(request);

        ArgumentCaptor<MemoryCaptureRequest> captor = ArgumentCaptor.forClass(MemoryCaptureRequest.class);
        verify(memories).captureForIdentity(eq("user:u1"), eq("u1"), captor.capture());
        MemoryCaptureRequest stored = captor.getValue();
        assertEquals("L1", stored.getMemoryLevel());
        assertEquals("AGENT", stored.getScopeType());
        assertTrue(stored.getContent().contains("年假怎么算？"), "记忆里要带问题原文，否则下次换个措辞提问就召回不到");
        assertTrue(stored.getContent().contains("入职周年折算"));
        // 同一条问答重复提交只留一份，而不是越积越多。
        assertEquals("feedback:q-1", stored.getIdempotencyKey());
    }

    @Test
    void feedbackWithoutSuggestionStoresNoMemory() {
        AuthContext.set(user(7L));
        when(aclContext.currentSubjectCode()).thenReturn("u1");
        traceOwnedBy(7L);

        service.submit(request("q-1", "UNHELPFUL", "INCOMPLETE"));

        // 只点了"没帮助"没有可存的信息：存一条"用户说不好"只会让下次的上下文变脏。
        verify(memories, never()).captureForIdentity(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(MemoryCaptureRequest.class));
    }

    private void traceOwnedBy(Long actorUserId) {
        Map<String, Object> trace = new HashMap<String, Object>();
        trace.put("knowledgeBaseId", 23L);
        trace.put("actorUserId", actorUserId);
        when(mapper.getTraceOwner("q-1")).thenReturn(trace);
        when(mapper.findByQueryAndActor("q-1", 7L)).thenReturn(new HashMap<String, Object>());
    }

    @Test
    void userCannotFeedbackOnAnotherUsersTrace() {
        AuthContext.set(user(7L));
        Map<String, Object> trace = new HashMap<String, Object>();
        trace.put("knowledgeBaseId", 23L);
        trace.put("actorUserId", 8L);
        when(mapper.getTraceOwner("q-1")).thenReturn(trace);

        assertThrows(SecurityException.class, () -> service.submit(request("q-1", "HELPFUL", null)));
        verify(mapper, never()).upsert(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.nullable(String.class), org.mockito.ArgumentMatchers.nullable(String.class),
            org.mockito.ArgumentMatchers.nullable(String.class));
    }

    @Test
    void unownedLegacyOrExternalTraceCannotBeFeedbackTarget() {
        AuthContext.set(user(7L));
        Map<String, Object> trace = new HashMap<String, Object>();
        trace.put("knowledgeBaseId", 23L);
        when(mapper.getTraceOwner("q-1")).thenReturn(trace);

        assertThrows(SecurityException.class, () -> service.submit(request("q-1", "HELPFUL", null)));
        verify(mapper, never()).upsert(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.nullable(String.class), org.mockito.ArgumentMatchers.nullable(String.class),
            org.mockito.ArgumentMatchers.nullable(String.class));
    }

    @Test
    void negativeFeedbackMustHaveReasonBeforeWrite() {
        AuthContext.set(user(7L));
        Map<String, Object> trace = new HashMap<String, Object>();
        trace.put("knowledgeBaseId", 23L);
        // 发起人必须给全：缺了它先撞上"无法确认发起人"，而这条用例要验的是"负反馈必须填原因"。
        trace.put("actorUserId", 7L);
        when(mapper.getTraceOwner("q-1")).thenReturn(trace);

        assertThrows(IllegalArgumentException.class, () -> service.submit(request("q-1", "UNHELPFUL", null)));
        verify(mapper, never()).upsert(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.nullable(String.class), org.mockito.ArgumentMatchers.nullable(String.class),
            org.mockito.ArgumentMatchers.nullable(String.class));
    }

    @Test
    void actionRequiredAndResolvedReviewsRequireAnAuditNote() {
        AuthContext.set(user(7L));
        Map<String, Object> row = new HashMap<String, Object>();
        row.put("knowledgeBaseId", 23L);
        when(mapper.getById(1L)).thenReturn(row);
        QueryFeedbackReviewRequest request = new QueryFeedbackReviewRequest();
        request.setStatus("ACTION_REQUIRED");

        assertThrows(IllegalArgumentException.class, () -> service.review(1L, request));
        verify(access).assertWrite(23L);
        verify(mapper, never()).review(eq(1L), eq("ACTION_REQUIRED"), eq(7L), isNull());
    }

    private QueryFeedbackRequest request(String queryId, String rating, String reason) {
        QueryFeedbackRequest request = new QueryFeedbackRequest();
        request.setQueryId(queryId);
        request.setRating(rating);
        request.setReasonCode(reason);
        return request;
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("tester");
        return user;
    }
}
