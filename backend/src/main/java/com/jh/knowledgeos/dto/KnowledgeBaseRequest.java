package com.jh.knowledgeos.dto;
import javax.validation.constraints.NotBlank;
/** Request for creating a knowledge-base workspace. */
public class KnowledgeBaseRequest {
    @NotBlank private String name; private String description;
    public String getName(){return name;} public void setName(String v){name=v;}
    public String getDescription(){return description;} public void setDescription(String v){description=v;}
}
