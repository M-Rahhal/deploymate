package com.deploymate.controller;

import com.deploymate.dto.MergeRequest;
import com.deploymate.dto.MergeResponse;
import com.deploymate.service.GitHubService;
import com.deploymate.service.JiraService;
import com.deploymate.service.LogService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/merge")
public class MergeController {

    private final GitHubService github;
    private final JiraService   jira;
    private final LogService    logSvc;

    public MergeController(GitHubService github, JiraService jira, LogService logSvc) {
        this.github = github;
        this.jira   = jira;
        this.logSvc = logSvc;
    }

    @PostMapping
    public ResponseEntity<MergeResponse> merge(@Valid @RequestBody MergeRequest req) {
        logSvc.info(req.repo(), "merge",
            "Merging " + req.sourceBranch() + " → " + req.targetBranch());

        if (!github.verifyBranch(req.repo(), req.sourceBranch())) {
            logSvc.warn(req.repo(), "merge", "Branch not found: " + req.sourceBranch());
            return ResponseEntity.badRequest()
                .body(new MergeResponse(false, false, null,
                    "Branch \"" + req.sourceBranch() + "\" not found on GitHub"));
        }

        var result = github.mergeBranch(
            req.repo(), req.sourceBranch(), req.targetBranch(), req.ticket());

        if (result.conflict()) {
            logSvc.error(req.repo(), "merge", "Merge conflict detected");
            return ResponseEntity.status(409)
                .body(new MergeResponse(false, true, null, "Merge conflict detected"));
        }

        var sha = result.sha() != null && result.sha().length() >= 7
            ? result.sha().substring(0, 7) : result.sha();
        logSvc.info(req.repo(), "merge", "Merge successful (SHA: " + sha + ")");
        return ResponseEntity.ok(new MergeResponse(true, false, result.sha(), "Merge successful"));
    }
}
