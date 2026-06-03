package com.deploymate.controller;

import com.deploymate.dto.DeployAllRequest;
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

    private final OrchestratorService orchestrator;
    private final LogService          logSvc;

    /**
     * Launches Deploy All in a virtual thread (Java 21) so the HTTP response returns
     * immediately with 202 Accepted. The UI polls individual step endpoints for state.
     */
    @PostMapping("/all")
    public ResponseEntity<Map<String, String>> deployAll(
        @Valid @RequestBody DeployAllRequest req
    ) {
        logSvc.info("SYSTEM", "—",
            "Deploy All requested for " + req.rows().size() + " service(s)");

        Thread.ofVirtual().name("deploy-all").start(() -> {
            try {
                orchestrator.deployAll(req.rows(), req.ticket(), loggingCallback());
            } catch (Exception e) {
                log.error("Deploy All failed with unexpected error", e);
            }
        });

        return ResponseEntity.accepted().body(Map.of(
            "message",  "Deploy All started",
            "services", String.valueOf(req.rows().size())
        ));
    }

    /**
     * A callback that writes state changes to the structured log.
     * Future improvement: replace with SSE / WebSocket push to the frontend.
     */
    private StepCallback loggingCallback() {
        return new StepCallback() {
            @Override
            public void onMergeState(String id, String state, String sha, String errorMessage) {
                log.debug("Merge state → {} for id={} sha={} error={}", state, id, sha, errorMessage);
            }

            @Override
            public void onTagState(String id, String state, String releaseUrl, String errorMessage) {
                log.debug("Tag state → {} for id={} url={} error={}", state, id, releaseUrl, errorMessage);
            }

            @Override
            public void onPipelineState(String id, String state, String buildUrl,
                                         Integer buildNumber, String errorMessage) {
                log.debug("Pipeline state → {} for id={} build=#{} error={}",
                    state, id, buildNumber, errorMessage);
            }

            @Override
            public void onLog(String level, String service, String stage, String message) {
                logSvc.info(service, stage, "[" + level + "] " + message);
            }
        };
    }
}
