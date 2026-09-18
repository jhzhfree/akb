from pathlib import Path
R=Path(__file__).resolve().parents[1]
engine=(R/'ai-service/app/services/query_engine.py').read_text()
hybrid=(R/'ai-service/app/services/retrieval/hybrid.py').read_text()
panel=(R/'frontend/src/components/QueryTracePanel.vue').read_text()
pipeline=(R/'frontend/src/components/RetrievalPipeline.vue').read_text()
for name in ['RecallChannelExplorer.vue','LatencyWaterfall.vue','RetrievalPolicyPanel.vue']:
    assert (R/'frontend/src/components'/name).exists(), name
for token in ['search_with_diagnostics','channelLatencyMs','filters']:
    assert token in hybrid
for token in ['fusionRank','rerankRank','rankDelta','channelRanks','channelLatencyMs']:
    assert token in engine
for token in ['RecallChannelExplorer','LatencyWaterfall','RetrievalPolicyPanel','recallChannels']:
    assert token in panel
for token in ['排名变化','rankText','fusionRank','rerankRank']:
    assert token in pipeline
print('query trace deep observability contract passed')
