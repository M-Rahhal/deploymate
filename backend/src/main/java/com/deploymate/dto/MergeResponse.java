package com.deploymate.dto;

public record MergeResponse(
    boolean success,
    boolean conflict,
    String  sha,
    String  message
) {}
