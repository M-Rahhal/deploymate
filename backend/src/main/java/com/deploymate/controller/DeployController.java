package com.deploymate.controller;

import com.deploymate.dto.DeployAllRequest;
import com.deploymate.model.StepExecutionState;
import com.deploymate.service.DeploymentEventPublisher;
import com.deploymate.service.LogService;
import com.deploymate.service.OrchestratorService;
import com.deploymate.service.OrchestratorService.StepCallback;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/deploy")
@RequiredArgsConstructor
public class DeployController {

    private final OrchestratorService      orchestratorService;
    private final DeploymentEventPublisher eventPublisher;
    private final LogService               deploymentLogger;

    /**
     * Opens an SSE stream the frontend subscribes to before submitting Deploy All.
     * Events:
     *   "log"         — { level, serviceName, stageLabel, message }
     *   "step-update" — { rowId, step, state, commitSha?, releaseUrl?, buildUrl?, buildNumber?, errorMessage? }
     *   "done"        — { success: boolean }
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamDeploymentEvents() {
        return eventPublisher.openStream();
    }

    /**
     * Launches Deploy All in a virtual thread so the HTTP response returns
     * immediately with 202 Accepted. Progress events are pushed via SSE.
     */
    @PostMapping("/all")
    public ResponseEntity<Map<String, String>> startDeployAll(
        @Valid @RequestBody DeployAllRequest request
    ) {
        deploymentLogger.info("SYSTEM", "—",
            "Deploy All requested for " + request.rows().size() + " service(s)");

        SseStepCallback callback = new SseStepCallback(eventPublisher, deploymentLogger);

        Thread.ofVirtual().name("deploy-all").start(() -> {
            boolean succeeded = false;
            try {
                succeeded = orchestratorService.orchestrateDeployment(
                    request.rows(), request.ticket(), callback);
            } catch (Exception e) {
                log.error("Deploy All failed with unexpected error", e);
            } finally {
                eventPublisher.publishDeploymentCompleted(succeeded);
            }
        });

        return ResponseEntity.accepted().body(Map.of(
            "message",  "Deploy All started",
            "services", String.valueOf(request.rows().size())
        ));
    }

    // ─── SSE step callback ────────────────────────────────────────────────────

    @RequiredArgsConstructor
    private static final class SseStepCallback implements StepCallback {

        private final DeploymentEventPublisher eventPublisher;
        private final LogService               deploymentLogger;

        @Override
        public void onMergeStateChanged(String rowId, StepExecutionState newState,
                                        String commitSha, String errorMessage) {
            log.debug("Merge state → {} rowId={} sha={}", newState, rowId, commitSha);
            eventPublisher.publishStepUpdate(
                rowId, "merge", newState.name(),
                commitSha, null, null, null, errorMessage);
        }

        @Override
        public void onTagStateChanged(String rowId, StepExecutionState newState,
                                      String releaseUrl, String errorMessage) {
            log.debug("Tag state → {} rowId={} url={}", newState, rowId, releaseUrl);
            eventPublisher.publishStepUpdate(
                rowId, "tag", newState.name(),
                null, releaseUrl, null, null, errorMessage);
        }

        @Override
        public void onPipelineStateChanged(String rowId, StepExecutionState newState,
                                           String buildUrl, Integer buildNumber, String errorMessage) {
            log.debug("Pipeline state → {} rowId={} build=#{}", newState, rowId, buildNumber);
            eventPublisher.publishStepUpdate(
                rowId, "pipeline", newState.name(),
                null, null, buildUrl, buildNumber, errorMessage);
        }

        @Override
        public void onLogMessage(String level, String serviceName, String stageLabel, String message) {
            deploymentLogger.info(serviceName, stageLabel, "[" + level + "] " + message);
            eventPublisher.publishLogEvent(level, serviceName, stageLabel, message);
        }
    }
}
