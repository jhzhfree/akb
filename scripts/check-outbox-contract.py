#!/usr/bin/env python3
from pathlib import Path
import re

root=Path(__file__).resolve().parents[1]
migration=(root/'backend/src/main/resources/db/migration/V4__transactional_outbox.sql').read_text(encoding='utf-8')
mapper=(root/'backend/src/main/resources/mapper/KnowledgeMapper.xml').read_text(encoding='utf-8')
worker=(root/'backend/src/main/java/com/jh/knowledgeos/service/IndexOutboxWorker.java').read_text(encoding='utf-8')
outbox=(root/'backend/src/main/java/com/jh/knowledgeos/service/IndexOutboxService.java').read_text(encoding='utf-8')
required_tables=['t_index_outbox','t_index_aggregate_version','t_index_delivery_attempt']
for name in required_tables:
    assert re.search(r'CREATE TABLE IF NOT EXISTS\s+'+name+r'\b',migration,re.I), name
for token in ['idempotency_key','object_version','next_attempt_at','locked_by','max_attempts']:
    assert token in migration, token
assert 'FOR UPDATE SKIP LOCKED' in mapper
assert "older.status NOT IN ('SUCCEEDED','SKIPPED')" in mapper
assert 'markIndexOutboxDead' in mapper and 'replayIndexOutbox' in mapper and 'skipIndexOutbox' in mapper
assert '@Scheduled' in worker and 'recoverStaleLocks' in worker and 'retryBaseSeconds' in worker
assert 'Propagation.MANDATORY' in outbox
print('transactional outbox contract passed: tables=%d' % len(required_tables))
