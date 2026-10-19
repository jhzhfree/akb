package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.dto.QueryFeedbackRequest;
import com.jh.knowledgeos.dto.QueryFeedbackReviewRequest;
import com.jh.knowledgeos.mapper.QueryFeedbackMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class QueryFeedbackServiceTest {

    private final QueryFeedbackMapper mapper = mock(QueryFeedbackMapper.class);
    private final KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
    private final QueryFeedbackService service = new QueryFeedbackService(mapper, access);

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
