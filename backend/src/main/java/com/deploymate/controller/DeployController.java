package com.deploymate.controller;

import com.deploymate.dto.DeployAllRequest;
import com.deploymate.model.StepExecutionState;
import com.deploymate.service.LogService;
import com.deploymate.service.OrchestratorService;
import com.deploymate.service.OrchestratorService.StepCallback;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/deploy")
@RequiredArgsConstructor
public class DeployController {

    private final OrchestratorService orchestratorService;
    private final LogService          deploymentLogger;

    /**
     * Launches Deploy All in a virtual thread (Java 21) so the HTTP response returns
     * immediately with 202 Accepted. The UI polls individual step endpoints for state.
     */
    @PostMapping("/all")
    public ResponseEntity<Map<String, String>> startDeployAll(
        @Valid @RequestBody DeployAllRequest request
    ) {
        deploymentLogger.info("SYSTEM", "—",
            "Deploy All requested for " + request.rows().size() + " service(s)");

        Thread.ofVirtual().name("deploy-all").start(() -> {
            try {
                orchestratorService.orchestrateDeployment(
                    request.rows(), request.ticket(), new LoggingStepCallback(deploymentLogger));
            } catch (Exception e) {
                log.error("Deploy All failed with unexpected error", e);
            }
        });

        return ResponseEntity.accepted().body(Map.of(
            "message",  "Deploy All started",
            "services", String.valueOf(request.rows().size())
        ));
    }

    @RequiredArgsConstructor
    private static final class LoggingStepCallback implements StepCallback {

        private final LogService deploymentLogger;

        @Override
        public void onMergeStateChanged(String rowId, StepExecutionState newState,
                                        String commitSha, String errorMessage) {
            log.debug("Merge state changed to {} for rowId={} sha={} error={}",
                newState, rowId, commitSha, errorMessage);
        }

        @Override
        public void onTagStateChanged(String rowId, StepExecutionState newState,
                                      String releaseUrl, String errorMessage) {
            log.debug("Tag state changed to {} for rowId={} url={} error={}",
                newState, rowId, releaseUrl, errorMessage);
        }

        @Override
        public void onPipelineStateChanged(String rowId, StepExecutionState newState,
                                           String buildUrl, Integer buildNumber, String errorMessage) {
            log.debug("Pipeline state changed to {} for rowId={} build=#{} error={}",
                newState, rowId, buildNumber, errorMessage);
        }

        @Override
        public void onLogMessage(String level, String serviceName, String stageLabel, String message) {
            deploymentLogger.info(serviceName, stageLabel, "[" + level + "] " + message);
        }
    }
}
