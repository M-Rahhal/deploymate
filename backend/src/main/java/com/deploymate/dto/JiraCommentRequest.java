package com.deploymate.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record JiraCommentRequest(
    @NotBlank
    @Pattern(regexp = "^[A-Z]{1,20}-[0-9]{1,10}$", message = "issueKey must be in format PROJ-123")
    String issueKey,

    @NotBlank
    @Size(max = 10000)
    String text
) {}
