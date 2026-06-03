package com.deploymate.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Jenkins always receives the parameter name "git_branch".
 * For SDKs: gitBranch = "origin/{targetBranch}" (branch to build).
 * For SERVICEs with createTag=true: gitBranch = tagName (pre-release tag to deploy).
 */
public record PipelineTriggerRequest(
    @NotBlank
    @Pattern(regexp = "^[a-zA-Z0-9/_-]{1,200}$",
             message = "Jenkins job path contains invalid characters")
    String jenkinsJob,

    @NotBlank
    String gitBranch
) {}
