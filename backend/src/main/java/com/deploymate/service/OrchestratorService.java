package com.deploymate.service;

import com.deploymate.dto.ServiceRowDto;
import com.deploymate.dto.ServiceRowDto.ServiceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
public class OrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorService.class);

    private final GitHubService  github;
    private final JenkinsService jenkins;
    private final JiraService    jira;
    private final LogService     logSvc;

    /**
     * Callback interface so the orchestrator can push step state changes
     * to whatever layer is listening (controller, SSE stream, etc.).
     */
    public interface StepCallback {
        void onMergeState(String id, String state, String sha,        String errorMessage);
        void onTagState  (String id, String state, String releaseUrl, String errorMessage);
        void onPipelineState(String id, String state, String buildUrl, Integer buildNumber, String errorMessage);
        void onLog(String level, String service, String stage, String message);
    }

    public OrchestratorService(GitHubService github, JenkinsService jenkins,
                                JiraService jira, LogService logSvc) {
        this.github  = github;
        this.jenkins = jenkins;
        this.jira    = jira;
        this.logSvc  = logSvc;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 1 — Merge
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Merges sourceBranch into targetBranch.
     * When skipMerge=true, marks the step as SKIPPED immediately.
     * Returns true on success/skip, false on conflict or failure.
     */
    public boolean mergeOnly(ServiceRowDto row, String ticket, String stageLabel, StepCallback cb) {
        if (row.skipMerge()) {
            cb.onMergeState(row.id(), "SKIPPED", null, null);
            cb.onLog("INFO", row.name(), stageLabel, "Merge skipped (skipMerge=true)");
            return true;
        }

        cb.onLog("INFO", row.name(), stageLabel, "Verifying branch " + row.sourceBranch() + "...");
        if (!github.verifyBranch(row.repo(), row.sourceBranch())) {
            cb.onMergeState(row.id(), "FAILED", null,
                "Branch \"" + row.sourceBranch() + "\" not found on GitHub");
            cb.onLog("ERROR", row.name(), stageLabel,
                "Branch " + row.sourceBranch() + " not found on GitHub");
            return false;
        }

        cb.onMergeState(row.id(), "RUNNING", null, null);
        cb.onLog("INFO", row.name(), stageLabel,
            "Merging " + row.sourceBranch() + " → " + row.targetBranch());

        var result = github.mergeBranch(row.repo(), row.sourceBranch(), row.targetBranch(), ticket);

        if (result.conflict()) {
            cb.onMergeState(row.id(), "CONFLICT", null, "Merge conflict. Resolve manually and retry.");
            cb.onLog("ERROR", row.name(), stageLabel, "Merge conflict detected. Deployment halted.");
            postJiraComment(row, ticket,
                "⚠️ Merge conflict in `" + row.repo() + "` ("
                + row.sourceBranch() + " → " + row.targetBranch() + "). Manual resolution required.");
            return false;
        }

        cb.onMergeState(row.id(), "DONE", result.sha(), null);
        var sha = shortSha(result.sha());
        cb.onLog("INFO", row.name(), stageLabel, "Merge successful (SHA: " + sha + ")");
        postJiraComment(row, ticket,
            "🔀 `" + row.repo() + "` merged `" + row.sourceBranch()
            + "` → `" + row.targetBranch() + "` (SHA: `" + sha + "`)");
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 2 — Tag (SERVICE type, skipMerge=false only)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a pre-release tag from the HEAD of targetBranch.
     * Skipped automatically when type != SERVICE or skipMerge=true.
     */
    public boolean tagOnly(ServiceRowDto row, String ticket, String stageLabel, StepCallback cb) {
        if (row.type() != ServiceType.SERVICE || row.skipMerge()) {
            cb.onTagState(row.id(), "SKIPPED", null, null);
            if (row.type() == ServiceType.SERVICE && row.skipMerge()) {
                cb.onLog("INFO", row.name(), stageLabel, "Tag skipped (skipMerge=true)");
            }
            return true;
        }

        cb.onTagState(row.id(), "RUNNING", null, null);
        cb.onLog("INFO", row.name(), stageLabel,
            "Getting HEAD SHA of " + row.targetBranch() + "...");

        try {
            var sha = github.getBranchSha(row.repo(), row.targetBranch());
            cb.onLog("INFO", row.name(), stageLabel, "Creating pre-release tag: " + row.tagName());

            var result = github.createTagAndPreRelease(row.repo(), row.tagName(), sha, ticket);
            cb.onTagState(row.id(), "DONE", result.releaseUrl(), null);
            cb.onLog("INFO", row.name(), stageLabel,
                "Tag " + row.tagName() + " created — " + result.releaseUrl());
            postJiraComment(row, ticket,
                "🏷️ Pre-release tag `" + row.tagName() + "` created for `" + row.repo()
                + "`. [View release](" + result.releaseUrl() + ")");
            return true;
        } catch (Exception e) {
            cb.onTagState(row.id(), "FAILED", null, e.getMessage());
            cb.onLog("ERROR", row.name(), stageLabel, "Tag creation failed: " + e.getMessage());
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 3 — Pipeline
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Triggers Jenkins and polls until the build completes.
     *
     * Parameter strategy:
     *   SDK  (any skipMerge)     → BRANCH=targetBranch
     *   SERVICE skipMerge=false  → TAG=tagName
     *   SERVICE skipMerge=true   → BRANCH=targetBranch
     */
    public boolean pipelineOnly(ServiceRowDto row, String ticket, String stageLabel, StepCallback cb) {
        var useTag    = row.type() == ServiceType.SERVICE && !row.skipMerge();
        var paramType = useTag ? "TAG" : "BRANCH";
        var paramVal  = useTag ? row.tagName() : row.targetBranch();

        if (paramVal == null || paramVal.isBlank()) {
            cb.onPipelineState(row.id(), "FAILED", null, null, "Missing " + paramType + " value");
            cb.onLog("ERROR", row.name(), stageLabel, "Cannot trigger pipeline: " + paramType + " value is empty");
            return false;
        }

        cb.onLog("INFO", row.name(), stageLabel,
            "Triggering Jenkins: " + row.jenkinsJob() + " (" + paramType + "=" + paramVal + ")");
        cb.onPipelineState(row.id(), "RUNNING", null, null, null);

        String queueItemUrl;
        try {
            queueItemUrl = jenkins.triggerBuild(row.jenkinsJob(), paramType, paramVal);
            cb.onLog("INFO", row.name(), stageLabel, "Build queued — waiting for executor...");
        } catch (Exception e) {
            cb.onPipelineState(row.id(), "FAILED", null, null, e.getMessage());
            cb.onLog("ERROR", row.name(), stageLabel, "Jenkins trigger failed: " + e.getMessage());
            return false;
        }

        // Poll until an executor picks up the build
        String buildUrl = null;
        while (buildUrl == null) {
            sleep(3_000);
            buildUrl = jenkins.pollQueueItem(queueItemUrl);
        }

        var status   = jenkins.pollBuildStatus(buildUrl);
        var buildNum = status.number();
        cb.onPipelineState(row.id(), "RUNNING", buildUrl, buildNum, null);
        cb.onLog("INFO", row.name(), stageLabel,
            "Build #" + buildNum + " running — " + buildUrl);
        postJiraComment(row, ticket,
            "🚀 Jenkins build [#" + buildNum + "](" + buildUrl + ") started for `" + row.repo() + "`");

        // Poll until the build finishes
        String result = null;
        while (result == null) {
            sleep(5_000);
            result = jenkins.pollBuildStatus(buildUrl).result();
        }

        if ("SUCCESS".equals(result)) {
            cb.onPipelineState(row.id(), "DONE", buildUrl, buildNum, null);
            cb.onLog("INFO", row.name(), stageLabel, "Build #" + buildNum + " SUCCESS ✅");
            postJiraComment(row, ticket,
                "✅ Jenkins build [#" + buildNum + "](" + buildUrl + ") passed for `" + row.repo() + "`");
            return true;
        } else {
            var msg = "Build #" + buildNum + " result: " + result;
            cb.onPipelineState(row.id(), "FAILED", buildUrl, buildNum, msg);
            cb.onLog("ERROR", row.name(), stageLabel, "Build #" + buildNum + " " + result + " ❌");
            postJiraComment(row, ticket,
                "❌ Jenkins build [#" + buildNum + "](" + buildUrl + ") **" + result
                + "** for `" + row.repo() + "`");
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // All Steps convenience wrapper
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Runs merge → tag → pipeline in sequence, skipping already DONE/SKIPPED steps.
     * currentXxxState should be the current state of each step from the frontend.
     */
    public boolean deployAllSteps(ServiceRowDto row, String ticket, String stageLabel,
                                   String currentMergeState, String currentTagState,
                                   String currentPipelineState, StepCallback cb) {
        if (!isDoneOrSkipped(currentMergeState)) {
            if (!mergeOnly(row, ticket, stageLabel, cb)) return false;
        }
        if (!isDoneOrSkipped(currentTagState)) {
            if (!tagOnly(row, ticket, stageLabel, cb)) return false;
        }
        if (!isDoneOrSkipped(currentPipelineState)) {
            return pipelineOnly(row, ticket, stageLabel, cb);
        }
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Deploy All — stage-aware
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Deploys all rows in stage order.
     * SDKs: stage-by-stage, parallel within each stage.
     * Services: all in parallel after all SDK stages complete.
     */
    public void deployAll(List<ServiceRowDto> rows, String ticket, StepCallback cb) {
        logSvc.info("SYSTEM", "—",
            "═══ NEW DEPLOY SESSION ═══ Ticket: " + (ticket != null ? ticket : "(none)"));
        cb.onLog("INFO", "SYSTEM", "—",
            "Deploy All started" + (ticket != null && !ticket.isBlank() ? " for ticket " + ticket : ""));

        var sdks     = rows.stream()
            .filter(r -> r.type() == ServiceType.SDK)
            .sorted(Comparator.comparingInt(ServiceRowDto::stage))
            .toList();
        var services = rows.stream()
            .filter(r -> r.type() == ServiceType.SERVICE)
            .toList();

        var stages = sdks.stream()
            .map(ServiceRowDto::stage)
            .distinct()
            .sorted()
            .toList();

        for (var stageNum : stages) {
            var stageRows  = sdks.stream().filter(r -> r.stage() == stageNum).toList();
            var stageLabel = "Stage " + stageNum;
            cb.onLog("INFO", "SYSTEM", stageLabel,
                "Starting " + stageLabel + " — "
                + stageRows.stream().map(ServiceRowDto::name).collect(Collectors.joining(", "))
                + " (parallel)");

            var allOk = runParallel(stageRows, ticket, stageLabel, cb);
            if (!allOk) {
                cb.onLog("ERROR", "SYSTEM", stageLabel,
                    stageLabel + " had failures or conflicts. Halting Deploy All.");
                return;
            }
            cb.onLog("INFO", "SYSTEM", stageLabel, stageLabel + " complete ✅");
        }

        if (!services.isEmpty()) {
            cb.onLog("INFO", "SYSTEM", "Services",
                "All SDK stages done. Starting " + services.size() + " service(s) in parallel...");
            runParallel(services, ticket, "Service", cb);
            cb.onLog("INFO", "SYSTEM", "Services", "All services deployment complete.");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private boolean runParallel(List<ServiceRowDto> rows, String ticket,
                                 String stageLabel, StepCallback cb) {
        if (rows.size() == 1) {
            return deployAllSteps(rows.get(0), ticket, stageLabel, "IDLE", "IDLE", "IDLE", cb);
        }
        ExecutorService executor = Executors.newFixedThreadPool(rows.size());
        try {
            var futures = rows.stream()
                .map(row -> CompletableFuture.supplyAsync(
                    () -> deployAllSteps(row, ticket, stageLabel, "IDLE", "IDLE", "IDLE", cb),
                    executor))
                .toList();
            return futures.stream()
                .map(CompletableFuture::join)
                .allMatch(Boolean::booleanValue);
        } finally {
            executor.shutdown();
        }
    }

    private boolean isDoneOrSkipped(String state) {
        return "DONE".equals(state) || "SKIPPED".equals(state);
    }

    private void postJiraComment(ServiceRowDto row, String ticket, String text) {
        if (row.updateJira() && ticket != null && !ticket.isBlank()) {
            try {
                jira.addComment(ticket, text);
            } catch (Exception e) {
                log.warn("Failed to post Jira comment for {}: {}", ticket, e.getMessage());
            }
        }
    }

    private String shortSha(String sha) {
        return sha != null && sha.length() >= 7 ? sha.substring(0, 7) : (sha != null ? sha : "n/a");
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
