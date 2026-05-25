package com.deploymate.controller;

import com.deploymate.dto.TagRequest;
import com.deploymate.dto.TagResponse;
import com.deploymate.service.GitHubService;
import com.deploymate.service.LogService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tag")
public class TagController {

    private final GitHubService github;
    private final LogService    logSvc;

    public TagController(GitHubService github, LogService logSvc) {
        this.github = github;
        this.logSvc = logSvc;
    }

    @PostMapping
    public ResponseEntity<TagResponse> createTag(@Valid @RequestBody TagRequest req) {
        logSvc.info(req.repo(), "tag", "Getting HEAD SHA of " + req.targetBranch());
        var sha = github.getBranchSha(req.repo(), req.targetBranch());

        logSvc.info(req.repo(), "tag", "Creating pre-release tag: " + req.tagName());
        var result = github.createTagAndPreRelease(req.repo(), req.tagName(), sha, req.ticket());

        logSvc.info(req.repo(), "tag", "Tag " + req.tagName() + " created — " + result.releaseUrl());
        return ResponseEntity.ok(
            new TagResponse(true, req.tagName(), sha, result.releaseUrl(), "Tag created"));
    }
}
