package com.jh.knowledgeos.projection;

/** One authoritative Entity or Relation row used to rebuild a generation-scoped graph. */
public class AuthoritativeGraphSnapshotRow {
    private String graphKey;
    private String graphType;
    private String code;
    private String sourceCode;
    private String targetCode;
    private String semanticType;
    private String name;
    private String description;
    private String status;
    private String permissionTags;
    private String contentHash;
    public String getGraphKey(){return graphKey;} public void setGraphKey(String v){graphKey=v;}
    public String getGraphType(){return graphType;} public void setGraphType(String v){graphType=v;}
    public String getCode(){return code;} public void setCode(String v){code=v;}
    public String getSourceCode(){return sourceCode;} public void setSourceCode(String v){sourceCode=v;}
    public String getTargetCode(){return targetCode;} public void setTargetCode(String v){targetCode=v;}
    public String getSemanticType(){return semanticType;} public void setSemanticType(String v){semanticType=v;}
    public String getName(){return name;} public void setName(String v){name=v;}
    public String getDescription(){return description;} public void setDescription(String v){description=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public String getPermissionTags(){return permissionTags;} public void setPermissionTags(String v){permissionTags=v;}
    public String getContentHash(){return contentHash;} public void setContentHash(String v){contentHash=v;}
}
