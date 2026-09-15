#!/usr/bin/env python3
"""Static release qualification gate for Elasticsearch + Milvus."""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
mapper=(ROOT/'backend/src/main/resources/mapper/KnowledgeMapper.xml').read_text()
assert "status IN ('APPROVED','EFFECTIVE')" in mapper
assert 'elasticsearch_count=#{elasticsearchCount}' in mapper
assert 'milvus_count=#{milvusCount}' in mapper
assert 'permissionTags' in mapper
indexer=(ROOT/'ai-service/app/services/indexer.py').read_text()
assert '/v2/vectordb/collections/drop' in indexer
for legacy in ['MILVUS_URL=http://milvus:6333','/collections/%s']:
    assert legacy not in '\n'.join((ROOT/p).read_text() for p in ['.env.example','.env.production.example','ai-service/app/services/indexer.py'])
print('release qualification static gate passed')
