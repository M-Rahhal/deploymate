package com.deploymate.dto;

import jakarta.validation.constraints.*;

public record ServiceRowDto(
    @NotBlank
    String id,

    @NotBlank
    @Size(max = 100)
    String name,

    @NotBlank
    @Pattern(regexp = "^[a-zA-Z0-9_.-]{1,100}$", message = "repo name contains invalid characters")
    String repo,

    @NotNull
    ServiceType type,

    @Min(1) @Max(99)
    int stage,

    @NotBlank
    @Pattern(regexp = "^[a-zA-Z0-9/_.-]{1,255}$")
    String sourceBranch,

    @NotBlank
    @Pattern(regexp = "^[a-zA-Z0-9/_.-]{1,255}$")
    String targetBranch,

    @NotBlank
    @Pattern(regexp = "^[a-zA-Z0-9/_-]{1,200}$")
    String jenkinsJob,

    @Pattern(regexp = "^([a-zA-Z0-9._-]{1,128})?$")
    String tagName,

    boolean updateJira,
    boolean skipMerge
) {
    public enum ServiceType { SDK, SERVICE }
}
