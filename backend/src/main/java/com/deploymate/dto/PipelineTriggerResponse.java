package com.deploymate.dto;

public record PipelineTriggerResponse(
    boolean success,
    String  queueItemUrl,
    String  message
) {}
