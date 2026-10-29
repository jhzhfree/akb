package com.jh.knowledgeos.exception;

public class WorkloadUnavailableException extends RuntimeException {
    public WorkloadUnavailableException() { super("身份认证存储暂不可用"); }
}
