from app.services.retrieval.elasticsearch_provider import _permission_filter
from app.services.retrieval.milvus_provider import MilvusProvider

anon=_permission_filter([])
assert anon['bool']['minimum_should_match']==1
assert len(anon['bool']['should'])==1
team=_permission_filter(['team-a'])
assert {'terms': {'permission_tags': ['team-a']}} in team['bool']['should']

p=MilvusProvider()
expr=p._filter_expr(1, [], 'CARD')
assert 'json_contains_any(permission_tags, ["__public__"])' in expr
expr=p._filter_expr(1, ['team-a'], 'CARD')
assert '"__public__"' in expr and '"team-a"' in expr
print('ACL provider contract passed')
