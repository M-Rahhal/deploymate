package com.deploymate.controller;

import com.deploymate.dto.TagRequest;
import com.deploymate.dto.TagResponse;
import com.deploymate.service.GitHubService;
import com.deploymate.service.JenkinsService;
import com.deploymate.service.LogService;
import com.deploymate.service.TagGeneratorService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/tag")
@Validated
@RequiredArgsConstructor
public class TagController {

    private final GitHubService       gitHubService;
    private final TagGeneratorService tagGeneratorService;
    private final JenkinsService      jenkinsService;
    private final LogService          deploymentLogger;

    @PostMapping
    public ResponseEntity<TagResponse> createTag(@Valid @RequestBody TagRequest request) {
        deploymentLogger.info(request.repo(), "tag",
            "Getting HEAD SHA of " + request.targetBranch());
        String commitSha = gitHubService.getBranchSha(request.repo(), request.targetBranch());

        deploymentLogger.info(request.repo(), "tag", "Creating pre-release tag: " + request.tagName());
        GitHubService.TagResult tagResult = gitHubService.createTagAndPreRelease(
            request.repo(), request.tagName(), commitSha, request.ticket());

        deploymentLogger.info(request.repo(), "tag",
            "Tag " + request.tagName() + " created — " + tagResult.releaseUrl());
        return ResponseEntity.ok(
            new TagResponse(true, request.tagName(), commitSha, tagResult.releaseUrl(), "Tag created"));
    }

    @GetMapping("/next")
    public ResponseEntity<Map<String, Object>> getSuggestedNextTag(
        @RequestParam
        @NotBlank
        @Pattern(regexp = "^[a-zA-Z0-9_.-]{1,100}$", message = "Invalid repo name")
        String repo,
        @RequestParam(required = false)
        @Pattern(regexp = "^[a-zA-Z0-9/_-]{1,200}$", message = "Invalid jenkinsJob format")
        String jenkinsJob
    ) {
        Optional<String> latestTag = gitHubService.getLatestTag(repo);
        String suggestedNextTag    = latestTag.map(tagGeneratorService::computeNextTag).orElse("v1.0.0rc1");

        String lastDeployedTag = null;
        if (jenkinsJob != null && !jenkinsJob.isBlank()) {
            lastDeployedTag = jenkinsService.getLastDeployedBranch(jenkinsJob).orElse(null);
        }

        Map<String, Object> responseBody = new LinkedHashMap<>();
        responseBody.put("tagName",         suggestedNextTag);
        responseBody.put("previousTag",     latestTag.orElse(""));
        responseBody.put("lastDeployedTag", lastDeployedTag);
        return ResponseEntity.ok(responseBody);
    }
}
