package com.deploymate.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record MergeRequest(
    @NotBlank
    @Pattern(regexp = "^[a-zA-Z0-9_.-]{1,100}$", message = "org name contains invalid characters")
    String org,

    @NotBlank
    @Pattern(regexp = "^[a-zA-Z0-9_.-]{1,100}$", message = "repo name contains invalid characters")
    String repo,

    @NotBlank
    @Pattern(regexp = "^[a-zA-Z0-9/_.-]{1,255}$", message = "sourceBranch contains invalid characters")
    String sourceBranch,

    @NotBlank
    @Pattern(regexp = "^[a-zA-Z0-9/_.-]{1,255}$", message = "targetBranch contains invalid characters")
    String targetBranch,

    @Pattern(regexp = "^([A-Z]{1,20}-[0-9]{1,10})?$", message = "ticket must be in format PROJ-123 or empty")
    String ticket
) {}
