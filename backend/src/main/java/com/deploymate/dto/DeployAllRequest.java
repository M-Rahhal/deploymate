package com.deploymate.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

import java.util.List;

public record DeployAllRequest(
    @Pattern(regexp = "^([A-Z]{1,20}-[0-9]{1,10})?$", message = "ticket must be in format PROJ-123 or empty")
    String ticket,

    @NotEmpty
    @Valid
    List<ServiceRowDto> rows
) {}
