from pathlib import Path
R=Path(__file__).resolve().parents[1]
controller=(R/'backend/src/main/java/com/jh/knowledgeos/controller/KnowledgeController.java').read_text()
mapper=(R/'backend/src/main/resources/mapper/KnowledgeMapper.xml').read_text()
app=(R/'frontend/src/App.vue').read_text()
detail=(R/'frontend/src/components/KnowledgeDetailWorkspace.vue').read_text()
query=(R/'frontend/src/components/QueryPanel.vue').read_text()
engine=(R/'ai-service/app/services/query_engine.py').read_text()
for token in ['/evidence/{kb}/{evidenceCode}','/content-unit/{kb}/{contentUnitId}']: assert token in controller
for token in ['id="getEvidenceDetail"','id="getContentUnitDetail"']: assert token in mapper
for token in ['EvidenceViewer','openEvidence','openCitation','getContentUnitDetail']: assert token in app
assert "'open-evidence'" in detail and '查看原文' in detail
assert "'open-citation'" in query
assert 'objectType' in engine and 'contentUnitId' in engine
assert (R/'frontend/src/components/EvidenceViewer.vue').exists()
print('evidence viewer + cross navigation contract passed')
