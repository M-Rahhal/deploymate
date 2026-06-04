package com.deploymate.controller;

import com.deploymate.dto.PipelineStatusResponse;
import com.deploymate.dto.PipelineStatusResponse.BuildState;
import com.deploymate.dto.PipelineTriggerRequest;
import com.deploymate.dto.PipelineTriggerResponse;
import com.deploymate.service.JenkinsService;
import com.deploymate.service.LogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pipeline")
@RequiredArgsConstructor
public class PipelineController {

    private final JenkinsService jenkinsService;
    private final LogService     deploymentLogger;

    @PostMapping("/trigger")
    public ResponseEntity<PipelineTriggerResponse> triggerPipelineBuild(
        @Valid @RequestBody PipelineTriggerRequest request
    ) {
        deploymentLogger.info(request.jenkinsJob(), "pipeline",
            "Triggering with git_branch=" + request.gitBranch());

        String queueItemUrl = jenkinsService.triggerBuild(request.jenkinsJob(), request.gitBranch());

        deploymentLogger.info(request.jenkinsJob(), "pipeline", "Queued at: " + queueItemUrl);
        return ResponseEntity.ok(new PipelineTriggerResponse(true, queueItemUrl, "Build queued"));
    }

    @GetMapping("/status")
    public ResponseEntity<PipelineStatusResponse> getPipelineBuildStatus(
        @RequestParam(required = false) String queueItemUrl,
        @RequestParam(required = false) String buildUrl
    ) {
        if (queueItemUrl != null && !queueItemUrl.isBlank()) {
            String resolvedBuildUrl = jenkinsService.pollQueueItem(queueItemUrl);
            if (resolvedBuildUrl == null) {
                return ResponseEntity.ok(
                    new PipelineStatusResponse(BuildState.QUEUED, null, null, null, "Still queued"));
            }
            buildUrl = resolvedBuildUrl;
        }

        if (buildUrl == null || buildUrl.isBlank()) {
            return ResponseEntity.badRequest().body(
                new PipelineStatusResponse(BuildState.UNKNOWN, null, null, null,
                    "Provide queueItemUrl or buildUrl"));
        }

        JenkinsService.BuildStatus  buildStatus  = jenkinsService.pollBuildStatus(buildUrl);
        JenkinsService.LogFragment  logFragment  = jenkinsService.getBuildLog(buildUrl, 0);
        String                      logTail      = extractLogTailFragment(logFragment.text(), 4000);

        BuildState resolvedState = resolveBuildStateFromJenkinsResult(buildStatus.result());
        return ResponseEntity.ok(
            new PipelineStatusResponse(resolvedState, buildUrl, buildStatus.number(), logTail, resolvedState.name()));
    }

    private BuildState resolveBuildStateFromJenkinsResult(String jenkinsResult) {
        if (jenkinsResult == null) return BuildState.RUNNING;
        return switch (jenkinsResult) {
            case "SUCCESS" -> BuildState.SUCCESS;
            case "FAILURE" -> BuildState.FAILURE;
            case "ABORTED" -> BuildState.ABORTED;
            default        -> BuildState.UNKNOWN;
        };
    }

    private String extractLogTailFragment(String fullLogText, int maxCharacters) {
        if (fullLogText == null) return null;
        return fullLogText.length() > maxCharacters
            ? fullLogText.substring(fullLogText.length() - maxCharacters)
            : fullLogText;
    }
}
