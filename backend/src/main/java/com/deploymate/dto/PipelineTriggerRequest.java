package com.deploymate.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record PipelineTriggerRequest(
    @NotBlank
    @Pattern(regexp = "^[a-zA-Z0-9/_-]{1,200}$", message = "jenkinsJob contains invalid characters")
    String jenkinsJob,

    @NotNull
    ParamType paramType,

    @NotBlank
    String paramValue
) {
    public enum ParamType { BRANCH, TAG }
}
