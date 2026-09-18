from pathlib import Path
root=Path(__file__).resolve().parents[1]/'frontend'
required=['src/main.ts','src/types.ts','src/api/knowledge.ts','vite.config.ts','tsconfig.json']
for rel in required: assert (root/rel).exists(), 'missing '+rel
assert not (root/'src/main.js').exists()
assert not (root/'src/api/knowledge.js').exists()
for vue in (root/'src').rglob('*.vue'):
    text=vue.read_text()
    if '<script' in text: assert '<script setup lang="ts">' in text, str(vue)+' must use TypeScript'
app=(root/'src/App.vue').read_text()
assert 'KnowledgeBaseDialog' in app and 'IngestionLogPanel' not in app
api=(root/'src/api/knowledge.ts').read_text()
assert 'getIngestionRuns' in api and 'getKnowledgeBases' in api
print('frontend TypeScript contract passed')
