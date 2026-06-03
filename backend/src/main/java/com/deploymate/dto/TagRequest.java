package com.deploymate.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record TagRequest(
    @NotBlank
    @Pattern(regexp = "^[a-zA-Z0-9_.-]{1,100}$")
    String repo,

    @NotBlank
    @Pattern(regexp = "^[a-zA-Z0-9._-]{1,128}$", message = "tagName contains invalid characters")
    String tagName,

    @NotBlank
    @Pattern(regexp = "^[a-zA-Z0-9/_.-]{1,255}$")
    String targetBranch,

    @Pattern(regexp = "^([A-Z]{1,20}-[0-9]{1,10})?$")
    String ticket
) {}
