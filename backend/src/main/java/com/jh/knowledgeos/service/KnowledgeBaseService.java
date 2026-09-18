package com.jh.knowledgeos.service;
import com.jh.knowledgeos.domain.KnowledgeBase;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.springframework.stereotype.Service;
import java.util.List;
/** Workspace lifecycle service; archives instead of deleting authoritative knowledge bases. */
@Service
public class KnowledgeBaseService {
    private final KnowledgeMapper mapper; private final IdGenerator ids;
    public KnowledgeBaseService(KnowledgeMapper mapper,IdGenerator ids){this.mapper=mapper;this.ids=ids;}
    public List<KnowledgeBase> list(){return mapper.listKnowledgeBases();}
    public KnowledgeBase create(String name,String description){
        KnowledgeBase base=new KnowledgeBase();base.setBaseCode(ids.next("KB_"));base.setName(name.trim());
        base.setDescription(description);base.setStatus("ACTIVE");mapper.insertKnowledgeBase(base);return base;
    }
    public KnowledgeBase archive(Long id){
        mapper.updateKnowledgeBaseStatus(id,"ARCHIVED");return mapper.getKnowledgeBase(id);
    }
}
