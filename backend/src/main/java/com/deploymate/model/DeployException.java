package com.deploymate.model;

public class DeployException extends RuntimeException {

    private final ErrorCode code;
    private final String repo;

    public DeployException(String message, ErrorCode code) {
        this(message, code, null);
    }

    public DeployException(String message, ErrorCode code, String repo) {
        super(message);
        this.code = code;
        this.repo = repo;
    }

    public ErrorCode getCode() { return code; }
    public String    getRepo() { return repo; }
}
