package com.jh.knowledgeos.exception;

public class WorkloadProofException extends UnauthorizedException {
    public WorkloadProofException() { super("无效、缺失或重放的 Agent 身份证明"); }
}
