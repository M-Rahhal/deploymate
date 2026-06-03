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

    private final GitHubService      github;
    private final TagGeneratorService tagGen;
    private final JenkinsService      jenkins;
    private final LogService          logSvc;

    /** POST /api/tag — create a pre-release tag from the HEAD of target branch. */
    @PostMapping
    public ResponseEntity<TagResponse> createTag(@Valid @RequestBody TagRequest req) {
        logSvc.info(req.repo(), "tag", "Getting HEAD SHA of " + req.targetBranch());
        String sha = github.getBranchSha(req.repo(), req.targetBranch());

        logSvc.info(req.repo(), "tag", "Creating pre-release tag: " + req.tagName());
        GitHubService.TagResult result = github.createTagAndPreRelease(req.repo(), req.tagName(), sha, req.ticket());

        logSvc.info(req.repo(), "tag", "Tag " + req.tagName() + " created — " + result.releaseUrl());
        return ResponseEntity.ok(
            new TagResponse(true, req.tagName(), sha, result.releaseUrl(), "Tag created"));
    }

    /**
     * GET /api/tag/next?repo={repo}[&jenkinsJob={path}]
     * Returns the suggested next tag (from GitHub releases) and, when jenkinsJob is provided,
     * the git_branch value from the job's last successful Jenkins build.
     */
    @GetMapping("/next")
    public ResponseEntity<Map<String, Object>> nextTag(
        @RequestParam
        @NotBlank
        @Pattern(regexp = "^[a-zA-Z0-9_.-]{1,100}$", message = "Invalid repo name")
        String repo,
        @RequestParam(required = false)
        @Pattern(regexp = "^[a-zA-Z0-9/_-]{1,200}$", message = "Invalid jenkinsJob format")
        String jenkinsJob
    ) {
        Optional<String> latestTag = github.getLatestTag(repo);
        String nextTag = latestTag.map(tagGen::computeNextTag).orElse("v1.0.0rc1");

        String lastDeployedTag = null;
        if (jenkinsJob != null && !jenkinsJob.isBlank()) {
            lastDeployedTag = jenkins.getLastDeployedBranch(jenkinsJob).orElse(null);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tagName",        nextTag);
        result.put("previousTag",    latestTag.orElse(""));
        result.put("lastDeployedTag", lastDeployedTag);
        return ResponseEntity.ok(result);
    }
}
