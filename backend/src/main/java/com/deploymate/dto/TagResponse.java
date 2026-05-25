package com.deploymate.dto;

public record TagResponse(
    boolean success,
    String  tagName,
    String  sha,
    String  releaseUrl,
    String  message
) {}
