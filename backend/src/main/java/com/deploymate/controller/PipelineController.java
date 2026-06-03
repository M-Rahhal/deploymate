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

    private final JenkinsService jenkins;
    private final LogService     logSvc;

    @PostMapping("/trigger")
    public ResponseEntity<PipelineTriggerResponse> trigger(
        @Valid @RequestBody PipelineTriggerRequest req
    ) {
        logSvc.info(req.jenkinsJob(), "pipeline",
            "Triggering with git_branch=" + req.gitBranch());

        String queueUrl = jenkins.triggerBuild(req.jenkinsJob(), req.gitBranch());

        logSvc.info(req.jenkinsJob(), "pipeline", "Queued at: " + queueUrl);
        return ResponseEntity.ok(new PipelineTriggerResponse(true, queueUrl, "Build queued"));
    }

    @GetMapping("/status")
    public ResponseEntity<PipelineStatusResponse> status(
        @RequestParam(required = false) String queueItemUrl,
        @RequestParam(required = false) String buildUrl
    ) {
        if (queueItemUrl != null && !queueItemUrl.isBlank()) {
            String polledBuildUrl = jenkins.pollQueueItem(queueItemUrl);
            if (polledBuildUrl == null) {
                return ResponseEntity.ok(
                    new PipelineStatusResponse(BuildState.QUEUED, null, null, null, "Still queued"));
            }
            buildUrl = polledBuildUrl;
        }

        if (buildUrl == null || buildUrl.isBlank()) {
            return ResponseEntity.badRequest().body(
                new PipelineStatusResponse(BuildState.UNKNOWN, null, null, null,
                    "Provide queueItemUrl or buildUrl"));
        }

        JenkinsService.BuildStatus status     = jenkins.pollBuildStatus(buildUrl);
        JenkinsService.LogFragment logFragment = jenkins.getBuildLog(buildUrl, 0);
        String frag = truncateTail(logFragment.text(), 4000);

        BuildState state = mapResult(status.result());
        return ResponseEntity.ok(
            new PipelineStatusResponse(state, buildUrl, status.number(), frag, state.name()));
    }

    private BuildState mapResult(String result) {
        if (result == null) return BuildState.RUNNING;
        return switch (result) {
            case "SUCCESS" -> BuildState.SUCCESS;
            case "FAILURE" -> BuildState.FAILURE;
            case "ABORTED" -> BuildState.ABORTED;
            default        -> BuildState.UNKNOWN;
        };
    }

    private String truncateTail(String text, int maxLen) {
        if (text == null) return null;
        return text.length() > maxLen ? text.substring(text.length() - maxLen) : text;
    }
}
