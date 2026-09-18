from pathlib import Path
R=Path(__file__).resolve().parents[1]
controller=(R/'backend/src/main/java/com/jh/knowledgeos/controller/KnowledgeController.java').read_text()
mapper=(R/'backend/src/main/java/com/jh/knowledgeos/mapper/KnowledgeMapper.java').read_text()
xml=(R/'backend/src/main/resources/mapper/KnowledgeMapper.xml').read_text()
app=(R/'frontend/src/App.vue').read_text()
detail=(R/'frontend/src/components/KnowledgeDetailWorkspace.vue').read_text()
api=(R/'frontend/src/api/knowledge.ts').read_text()
for token in ['/objects/{kb}/{type}/{code}','knowledgeDetail']:
    assert token in controller or token in (R/'backend/src/main/java/com/jh/knowledgeos/service/KnowledgeService.java').read_text()
for token in ['getKnowledgeObject','listReviewsForObject','listAssessmentsForObject','listEvidenceForObject']:
    assert token in mapper and token in xml
for token in ['EVIDENCE CHAIN','REVIEW HISTORY','GOVERNANCE','CONFLICT']:
    assert token in detail
assert 'getKnowledgeObjectDetail' in api
assert 'KnowledgeDetailWorkspace' in app and '@open-detail="openDetail"' in app
print('knowledge detail workspace contract passed')
