package com.jh.knowledgeos.service;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.jh.knowledgeos.mapper.WorkloadIdentityRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
class WorkloadReplayRetentionServiceTest {
    @Test void cleanupDrainsBoundedBatchesAndReportsRemainingBacklog() {
        WorkloadIdentityRepository repo=mock(WorkloadIdentityRepository.class);
        SimpleMeterRegistry metrics=new SimpleMeterRegistry();
        when(repo.cleanV2()).thenReturn(1000);when(repo.expiredV2Count()).thenReturn(12000L);
        new WorkloadReplayRetentionService(repo,metrics).clean();
        verify(repo,times(10)).cleanV2();
        assertEquals(12000,metrics.get("akb_workload_replay_expired_backlog").gauge().value());
    }
    @Test void cleanupFailureDoesNotEscapeSchedulerAndHasAMetric() {
        WorkloadIdentityRepository repo=mock(WorkloadIdentityRepository.class);
        SimpleMeterRegistry metrics=new SimpleMeterRegistry();
        when(repo.cleanV2()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic"));
        assertDoesNotThrow(() -> new WorkloadReplayRetentionService(repo,metrics).clean());
        assertEquals(1,metrics.get("akb_workload_replay_cleanup_failures").counter().count());
    }
}
