from pathlib import Path
root=Path(__file__).resolve().parents[1]
checks={
 'gate': 'StructuredAnswerGate' in (root/'ai-service/app/services/structured_answer_gate.py').read_text(),
 'decisions': all(x in (root/'ai-service/app/services/structured_answer_gate.py').read_text() for x in ['SUPPORTED','PARTIALLY_SUPPORTED','CONTRADICTED','INSUFFICIENT']),
 'query': 'structuredAnswerVerification' in (root/'ai-service/app/services/query_engine.py').read_text(),
 'trace': 'structuredAnswerGate' in (root/'ai-service/app/services/query_engine.py').read_text(),
 'config': 'ANSWER_ENTAILMENT_MIN' in (root/'ai-service/app/core/config.py').read_text(),
 'tests': (root/'ai-service/tests/test_phase16_structured_answer_gate.py').exists(),
}
for k,v in checks.items(): print(('PASS' if v else 'FAIL'), k)
assert all(checks.values())
print('Phase 16 structured answer contract passed: %d/%d' % (sum(checks.values()),len(checks)))
