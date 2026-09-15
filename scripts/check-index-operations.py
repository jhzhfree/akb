#!/usr/bin/env python3
from pathlib import Path
import re, yaml
root=Path(__file__).resolve().parents[1]
v5=(root/'backend/src/main/resources/db/migration/V5__index_operations_and_schema_release.sql').read_text()
worker=(root/'backend/src/main/java/com/jh/knowledgeos/service/IndexOutboxWorker.java').read_text()
archive=(root/'backend/src/main/java/com/jh/knowledgeos/service/IndexOutboxArchiveService.java').read_text()
metrics=(root/'backend/src/main/java/com/jh/knowledgeos/service/IndexOutboxMetrics.java').read_text()
schema=(root/'backend/src/main/java/com/jh/knowledgeos/service/IndexSchemaService.java').read_text()
ext=(root/'ai-service/app/services/retrieval/elasticsearch_provider.py').read_text() + '\n' + (root/'ai-service/app/services/retrieval/milvus_provider.py').read_text()
for table in ['t_index_outbox_archive','t_index_delivery_attempt_archive','t_index_schema_release']:
    assert re.search(r'CREATE TABLE IF NOT EXISTS\s+'+table+r'\b',v5,re.I),table
for token in ['llmkb.index.outbox.delivery','llmkb.index.outbox.pending','llmkb.index.outbox.oldest.lag.seconds']:
    assert token in metrics,token
assert '@Scheduled' in archive and 'retentionDays' in archive
assert 'VALIDATION_FAILED' in schema and 'expected' in schema
assert 'schema_target' in ext and 'activate_target' in ext and 'read_alias' in ext
assert 'micrometer-registry-prometheus' in (root/'backend/pom.xml').read_text()
yaml.safe_load((root/'monitoring/prometheus.yml').read_text())
yaml.safe_load((root/'monitoring/alerts.yml').read_text())
print('index operations contract passed')
