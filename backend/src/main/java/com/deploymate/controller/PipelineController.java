package com.deploymate.controller;

import com.deploymate.dto.PipelineStatusResponse;
import com.deploymate.dto.PipelineStatusResponse.BuildState;
import com.deploymate.dto.PipelineTriggerRequest;
import com.deploymate.dto.PipelineTriggerResponse;
import com.deploymate.service.JenkinsService;
import com.deploymate.service.LogService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pipeline")
public class PipelineController {

    private final JenkinsService jenkins;
    private final LogService     logSvc;

    public PipelineController(JenkinsService jenkins, LogService logSvc) {
        this.jenkins = jenkins;
        this.logSvc  = logSvc;
    }

    @PostMapping("/trigger")
    public ResponseEntity<PipelineTriggerResponse> trigger(
        @Valid @RequestBody PipelineTriggerRequest req
    ) {
        logSvc.info(req.jenkinsJob(), "pipeline",
            "Triggering with " + req.paramType() + "=" + req.paramValue());

        var queueUrl = jenkins.triggerBuild(
            req.jenkinsJob(), req.paramType().name(), req.paramValue());

        logSvc.info(req.jenkinsJob(), "pipeline", "Queued at: " + queueUrl);
        return ResponseEntity.ok(new PipelineTriggerResponse(true, queueUrl, "Build queued"));
    }

    @GetMapping("/status")
    public ResponseEntity<PipelineStatusResponse> status(
        @RequestParam(required = false) String queueItemUrl,
        @RequestParam(required = false) String buildUrl
    ) {
        if (queueItemUrl != null && !queueItemUrl.isBlank()) {
            var polledBuildUrl = jenkins.pollQueueItem(queueItemUrl);
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

        var status = jenkins.pollBuildStatus(buildUrl);
        var log    = jenkins.getBuildLog(buildUrl, 0);
        var frag   = truncateTail(log.text(), 4000);

        var state = mapResult(status.result());
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
