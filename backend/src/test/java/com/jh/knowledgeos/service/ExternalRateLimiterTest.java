package com.jh.knowledgeos.service;
import static org.junit.jupiter.api.Assertions.*;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.domain.WorkloadIdentity;
import com.jh.knowledgeos.exception.RateLimitException;
import org.junit.jupiter.api.Test;
class ExternalRateLimiterTest {
    private ApiKey agent(long keyId, long principal) {
        ApiKey key=new ApiKey();key.setId(keyId);key.setWorkloadPolicyId(7L);key.setRateLimitPerMinute(100);
        key.setWorkloadIdentity(new WorkloadIdentity(principal,"session","invocation",AgentProofCrypto.V2,"kid",1L,null,"platform","uuid","ACTIVE"));return key;
    }
    @Test void agentBudgetIsSeparateAndSurvivesServiceKeyRotation() {
        ExternalRateLimiter limiter=new ExternalRateLimiter(); ApiKey first=agent(1L,10L);
        for(int i=0;i<30;i++)limiter.checkAgent(first);
        assertThrows(RateLimitException.class,()->limiter.checkAgent(agent(2L,10L)));
        assertDoesNotThrow(()->limiter.checkAgent(agent(1L,11L)));
        assertDoesNotThrow(()->limiter.check(first));
    }
}
