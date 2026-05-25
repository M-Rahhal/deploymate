package com.deploymate.dto;

public record PipelineStatusResponse(
    BuildState state,
    String     buildUrl,
    Integer    buildNumber,
    String     logFragment,
    String     message
) {
    public enum BuildState {
        QUEUED, RUNNING, SUCCESS, FAILURE, ABORTED, UNKNOWN
    }
}
