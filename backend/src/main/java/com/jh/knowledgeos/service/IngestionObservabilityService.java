package com.jh.knowledgeos.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.domain.IngestionRun;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;import java.util.List;import java.util.Map;
/** Persists lifecycle events so ingestion is observable and auditable from the UI. */
@Service
public class IngestionObservabilityService {
    private final KnowledgeMapper mapper; private final IdGenerator ids; private final ObjectMapper json;
    public IngestionObservabilityService(KnowledgeMapper mapper,IdGenerator ids,ObjectMapper json){this.mapper=mapper;this.ids=ids;this.json=json;}
    public IngestionRun start(Long kb,String sourceName){
        IngestionRun run=new IngestionRun();run.setRunCode(ids.next("ING_"));run.setKnowledgeBaseId(kb);
        run.setSourceName(sourceName);run.setStatus("RUNNING");run.setCurrentStage("SOURCE_RECEIVED");
        run.setChunkCount(0);run.setCardCount(0);run.setClaimCount(0);mapper.insertIngestionRun(run);
        event(run.getId(),"SOURCE_RECEIVED","SOURCE","SUCCESS","已接收原始资料",null);return run;
    }
    public void event(Long runId,String type,String stage,String status,String message,Object detail){
        String detailJson = null;
        try {
            if (detail != null) detailJson = json.writeValueAsString(detail);
        } catch (Exception ignored) {
            detailJson = "{\"serializationError\":true}";
        }
        mapper.insertIngestionEvent(runId, type, stage, status, message, detailJson);
        mapper.updateIngestionRunStage(runId, stage);
    }
    public void complete(Long runId,int chunks,int cards,int claims){
        mapper.completeIngestionRun(runId,"SUCCEEDED","COMPLETED",chunks,cards,claims,null);
        event(runId,"INGESTION_COMPLETED","COMPLETED","SUCCESS","知识摄入流程完成",null);
    }
    public void fail(Long runId,String stage,Exception error){
        mapper.completeIngestionRun(runId,"FAILED",stage,0,0,0,error.getMessage());
        event(runId,"INGESTION_FAILED",stage,"FAILED",error.getMessage(),null);
    }
    public List<IngestionRun> list(Long kb,Integer limit){return mapper.listIngestionRuns(kb,limit==null?20:Math.min(limit,100));}
    public Map<String,Object> detail(Long runId){
        IngestionRun run=mapper.getIngestionRun(runId);Map<String,Object> result=new LinkedHashMap<String,Object>();
        result.put("id",run.getId());result.put("runCode",run.getRunCode());result.put("knowledgeBaseId",run.getKnowledgeBaseId());
        result.put("sourceName",run.getSourceName());result.put("status",run.getStatus());result.put("currentStage",run.getCurrentStage());
        result.put("chunkCount",run.getChunkCount());result.put("cardCount",run.getCardCount());result.put("claimCount",run.getClaimCount());
        result.put("errorMessage",run.getErrorMessage());result.put("startedTime",run.getStartedTime());result.put("finishedTime",run.getFinishedTime());
        result.put("events",mapper.listIngestionEvents(runId));return result;
    }
}
