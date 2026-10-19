package com.jh.knowledgeos.service;

import com.jh.knowledgeos.mapper.AgentMemoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Removes expired session snippets promptly and other expired memories after a short recovery window. */
@Service
public class AgentMemoryRetentionService {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryRetentionService.class);

    private final AgentMemoryMapper mapper;

    public AgentMemoryRetentionService(AgentMemoryMapper mapper) {
        this.mapper = mapper;
    }

    @Scheduled(fixedDelayString = "${knowledge.memory.purge-interval-ms:3600000}")
    public void purgeExpired() {
        int deleted = mapper.purgeExpired();
        if (deleted > 0) {
            log.info("purged {} expired Agent memory records", deleted);
        }
    }
}
