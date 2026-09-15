from pathlib import Path
f=Path('backend/src/main/java/com/jh/knowledgeos/service/OpsRbacFilter.java').read_text()
y=Path('backend/src/main/resources/application.yml').read_text()
env=Path('.env.production.example').read_text()
assert 'X-Ops-Token' in f
assert 'startsWith("/api/knowledge/index/")' in f
assert 'return 3' in f and 'return 2' in f and 'return 1' in f
assert 'ops-rbac-enabled' in y
assert 'OPS_RBAC_ENABLED=true' in env
print('ops rbac contract passed')
