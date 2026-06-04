package com.deploymate.controller;

import com.deploymate.dto.MergeRequest;
import com.deploymate.dto.MergeResponse;
import com.deploymate.service.GitHubService;
import com.deploymate.service.LogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/merge")
@RequiredArgsConstructor
public class MergeController {

    private final GitHubService gitHubService;
    private final LogService    deploymentLogger;

    @PostMapping
    public ResponseEntity<MergeResponse> mergeBranches(@Valid @RequestBody MergeRequest request) {
        deploymentLogger.info(request.repo(), "merge",
            "Merging " + request.sourceBranch() + " → " + request.targetBranch());

        if (!gitHubService.verifyBranch(request.repo(), request.sourceBranch())) {
            deploymentLogger.warn(request.repo(), "merge",
                "Branch not found: " + request.sourceBranch());
            return ResponseEntity.badRequest()
                .body(new MergeResponse(false, false, null,
                    "Branch \"" + request.sourceBranch() + "\" not found on GitHub"));
        }

        GitHubService.MergeResult mergeResult = gitHubService.mergeBranch(
            request.repo(), request.sourceBranch(), request.targetBranch(), request.ticket());

        if (mergeResult.conflict()) {
            deploymentLogger.error(request.repo(), "merge", "Merge conflict detected");
            return ResponseEntity.status(409)
                .body(new MergeResponse(false, true, null, "Merge conflict detected"));
        }

        String abbreviatedSha = mergeResult.sha() != null && mergeResult.sha().length() >= 7
            ? mergeResult.sha().substring(0, 7) : mergeResult.sha();
        deploymentLogger.info(request.repo(), "merge", "Merge successful (SHA: " + abbreviatedSha + ")");
        return ResponseEntity.ok(new MergeResponse(true, false, mergeResult.sha(), "Merge successful"));
    }
}
