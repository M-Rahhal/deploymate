package com.deploymate.service;

import com.deploymate.dto.ServiceRowDto;
import com.deploymate.model.StepExecutionState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrchestratorService {

    private final GitHubService  gitHubService;
    private final JenkinsService jenkinsService;
    private final JiraService    jiraService;
    private final LogService     deploymentLogger;

    public interface StepCallback {
        void onMergeStateChanged(String rowId, StepExecutionState newState, String commitSha,   String errorMessage);
        void onTagStateChanged  (String rowId, StepExecutionState newState, String releaseUrl,  String errorMessage);
        void onPipelineStateChanged(String rowId, StepExecutionState newState, String buildUrl, Integer buildNumber, String errorMessage);
        void onLogMessage(String level, String serviceName, String stageLabel, String message);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 1 — Merge
    // ─────────────────────────────────────────────────────────────────────────

    public boolean executeMergeStep(ServiceRowDto row, String ticket, String stageLabel, StepCallback eventCallback) {
        if (row.sourceBranch().isBlank() || row.targetBranch().equals(row.sourceBranch())) {
            eventCallback.onMergeStateChanged(row.id(), StepExecutionState.SKIPPED, null, null);
            eventCallback.onLogMessage("INFO", row.name(), stageLabel, "Merge skipped (no source branch, assume it is merged)");
            return true;
        }
        if (!row.steps().mergeBranch()) {
            eventCallback.onMergeStateChanged(row.id(), StepExecutionState.SKIPPED, null, null);
            eventCallback.onLogMessage("INFO", row.name(), stageLabel, "Merge skipped (not configured)");
            return true;
        }
        eventCallback.onLogMessage("INFO", row.name(), stageLabel, "Verifying branch " + row.sourceBranch() + "...");
        if (!gitHubService.verifyBranch(row.repo(), row.sourceBranch())) {
            eventCallback.onMergeStateChanged(row.id(), StepExecutionState.FAILED, null,
                "Branch \"" + row.sourceBranch() + "\" not found on GitHub");
            eventCallback.onLogMessage("ERROR", row.name(), stageLabel,
                "Branch " + row.sourceBranch() + " not found on GitHub");
            return false;
        }

        eventCallback.onMergeStateChanged(row.id(), StepExecutionState.RUNNING, null, null);
        eventCallback.onLogMessage("INFO", row.name(), stageLabel,
            "Merging " + row.sourceBranch() + " → " + row.targetBranch());

        GitHubService.MergeResult mergeResult = gitHubService.mergeBranch(
            row.repo(), row.sourceBranch(), row.targetBranch(), ticket);

        if (mergeResult.conflict()) {
            eventCallback.onMergeStateChanged(row.id(), StepExecutionState.CONFLICT, null,
                "Merge conflict. Resolve manually and retry.");
            eventCallback.onLogMessage("ERROR", row.name(), stageLabel,
                "Merge conflict detected. Deployment halted.");
            notifyJiraIfEnabled(row, ticket,
                "⚠️ Merge conflict in `" + row.repo() + "` ("
                + row.sourceBranch() + " → " + row.targetBranch() + "). Manual resolution required.");
            return false;
        }

        eventCallback.onMergeStateChanged(row.id(), StepExecutionState.DONE, mergeResult.sha(), null);
        String abbreviatedSha = abbreviateSha(mergeResult.sha());
        eventCallback.onLogMessage("INFO", row.name(), stageLabel,
            "Merge successful (SHA: " + abbreviatedSha + ")");
        notifyJiraIfEnabled(row, ticket,
            "🔀 `" + row.repo() + "` merged `" + row.sourceBranch()
            + "` → `" + row.targetBranch() + "` (SHA: `" + abbreviatedSha + "`)");
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 2 — Tag
    // ─────────────────────────────────────────────────────────────────────────

    public boolean executeTagCreationStep(ServiceRowDto row, String ticket, String stageLabel, StepCallback eventCallback) {
        if (!row.steps().createTag()) {
            eventCallback.onTagStateChanged(row.id(), StepExecutionState.SKIPPED, null, null);
            return true;
        }

        eventCallback.onTagStateChanged(row.id(), StepExecutionState.RUNNING, null, null);
        eventCallback.onLogMessage("INFO", row.name(), stageLabel,
            "Getting HEAD SHA of " + row.targetBranch() + "...");

        try {
            String commitSha = gitHubService.getBranchSha(row.repo(), row.targetBranch());
            eventCallback.onLogMessage("INFO", row.name(), stageLabel,
                "Creating pre-release tag: " + row.tagName());

            GitHubService.TagResult tagResult = gitHubService.createTagAndPreRelease(
                row.repo(), row.tagName(), commitSha, ticket);
            eventCallback.onTagStateChanged(row.id(), StepExecutionState.DONE, tagResult.releaseUrl(), null);
            eventCallback.onLogMessage("INFO", row.name(), stageLabel,
                "Tag " + row.tagName() + " created — " + tagResult.releaseUrl());
            notifyJiraIfEnabled(row, ticket,
                "🏷️ Pre-release tag `" + row.tagName() + "` created for `" + row.repo()
                + "`. [View release](" + tagResult.releaseUrl() + ")");
            return true;
        } catch (Exception e) {
            eventCallback.onTagStateChanged(row.id(), StepExecutionState.FAILED, null, e.getMessage());
            eventCallback.onLogMessage("ERROR", row.name(), stageLabel,
                "Tag creation failed: " + e.getMessage());
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 3 — Pipeline
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * git_branch parameter strategy (determined by row type):
     *   SERVICE → git_branch = tagName  (deploy from a pre-release tag; tagName must be set)
     *   SDK     → git_branch = "origin/" + targetBranch  (build from branch)
     */
    public boolean executePipelineStep(ServiceRowDto row, String ticket, String stageLabel, StepCallback eventCallback) {
        if (!row.steps().triggerPipeline()) {
            eventCallback.onPipelineStateChanged(row.id(), StepExecutionState.SKIPPED, null, null, null);
            eventCallback.onLogMessage("INFO", row.name(), stageLabel, "Pipeline skipped (not configured)");
            return true;
        }

        String gitBranchValue;
        if (row.type() == ServiceRowDto.ServiceType.SERVICE) {
            if (row.tagName() == null || row.tagName().isBlank()) {
                eventCallback.onPipelineStateChanged(row.id(), StepExecutionState.FAILED, null, null,
                    "SERVICE rows require a tagName before triggering the pipeline");
                eventCallback.onLogMessage("ERROR", row.name(), stageLabel,
                    "Cannot trigger pipeline: tagName is required for SERVICE rows");
                return false;
            }
            gitBranchValue = row.tagName();
        } else {
            gitBranchValue = "origin/" + row.targetBranch();
        }

        eventCallback.onLogMessage("INFO", row.name(), stageLabel,
            "Triggering Jenkins: " + row.jenkinsJob() + " (git_branch=" + gitBranchValue + ")");
        eventCallback.onPipelineStateChanged(row.id(), StepExecutionState.RUNNING, null, null, null);

        String queueItemUrl;
        try {
            queueItemUrl = jenkinsService.triggerBuild(row.jenkinsJob(), gitBranchValue);
            eventCallback.onLogMessage("INFO", row.name(), stageLabel,
                "Build queued — waiting for executor...");
        } catch (Exception e) {
            eventCallback.onPipelineStateChanged(row.id(), StepExecutionState.FAILED, null, null, e.getMessage());
            eventCallback.onLogMessage("ERROR", row.name(), stageLabel,
                "Jenkins build trigger failed: " + e.getMessage());
            return false;
        }

        String buildUrl = null;
        while (buildUrl == null) {
            sleepMilliseconds(3_000);
            buildUrl = jenkinsService.pollQueueItem(queueItemUrl);
        }

        JenkinsService.BuildStatus initialStatus = jenkinsService.pollBuildStatus(buildUrl);
        int buildNumber = initialStatus.number();
        eventCallback.onPipelineStateChanged(row.id(), StepExecutionState.RUNNING, buildUrl, buildNumber, null);
        eventCallback.onLogMessage("INFO", row.name(), stageLabel,
            "Build #" + buildNumber + " running — " + buildUrl);
        notifyJiraIfEnabled(row, ticket,
            "🚀 Jenkins build [#" + buildNumber + "](" + buildUrl + ") started for `" + row.repo() + "`");

        String buildResult = null;
        while (buildResult == null) {
            sleepMilliseconds(5_000);
            buildResult = jenkinsService.pollBuildStatus(buildUrl).result();
        }

        if ("SUCCESS".equals(buildResult)) {
            eventCallback.onPipelineStateChanged(row.id(), StepExecutionState.DONE, buildUrl, buildNumber, null);
            eventCallback.onLogMessage("INFO", row.name(), stageLabel,
                "Build #" + buildNumber + " SUCCESS ✅");
            notifyJiraIfEnabled(row, ticket,
                "✅ Jenkins build [#" + buildNumber + "](" + buildUrl + ") passed for `" + row.repo() + "`");
            return true;
        } else {
            String failureMessage = "Build #" + buildNumber + " result: " + buildResult;
            eventCallback.onPipelineStateChanged(row.id(), StepExecutionState.FAILED, buildUrl, buildNumber, failureMessage);
            eventCallback.onLogMessage("ERROR", row.name(), stageLabel,
                "Build #" + buildNumber + " " + buildResult + " ❌");
            notifyJiraIfEnabled(row, ticket,
                "❌ Jenkins build [#" + buildNumber + "](" + buildUrl + ") **" + buildResult
                + "** for `" + row.repo() + "`");
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // All Steps — idempotent wrapper
    // ─────────────────────────────────────────────────────────────────────────

    public boolean executeAllDeploymentSteps(
        ServiceRowDto row, String ticket, String stageLabel,
        StepExecutionState currentMergeState, StepExecutionState currentTagState,
        StepExecutionState currentPipelineState, StepCallback eventCallback
    ) {
        if (!isStepAlreadyCompleted(currentMergeState)) {
            if (!executeMergeStep(row, ticket, stageLabel, eventCallback)) return false;
        }
        if (!isStepAlreadyCompleted(currentTagState)) {
            if (!executeTagCreationStep(row, ticket, stageLabel, eventCallback)) return false;
        }
        if (!isStepAlreadyCompleted(currentPipelineState)) {
            return executePipelineStep(row, ticket, stageLabel, eventCallback);
        }
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Orchestrate All — unified stage-based deployment
    // ─────────────────────────────────────────────────────────────────────────

    public boolean orchestrateDeployment(List<ServiceRowDto> rows, String ticket, StepCallback eventCallback) {
        deploymentLogger.info("SYSTEM", "—",
            "═══ NEW DEPLOY SESSION ═══ Ticket: " + (ticket != null ? ticket : "(none)"));
        eventCallback.onLogMessage("INFO", "SYSTEM", "—",
            "Deploy All started" + (ticket != null && !ticket.isBlank() ? " for ticket " + ticket : ""));

        List<Integer> stageNumbers = rows.stream()
            .map(ServiceRowDto::stage)
            .distinct()
            .sorted()
            .toList();

        for (int stageNumber : stageNumbers) {
            List<ServiceRowDto> stageRows = rows.stream()
                .filter(row -> row.stage() == stageNumber)
                .toList();
            String stageLabel = "Stage " + stageNumber;
            eventCallback.onLogMessage("INFO", "SYSTEM", stageLabel,
                "Starting " + stageLabel + " — "
                + stageRows.stream().map(ServiceRowDto::name).collect(Collectors.joining(", "))
                + " (parallel)");

            boolean allRowsSucceeded = executeRowsConcurrently(stageRows, ticket, stageLabel, eventCallback);
            if (!allRowsSucceeded) {
                eventCallback.onLogMessage("ERROR", "SYSTEM", stageLabel,
                    stageLabel + " had failures or conflicts. Halting Deploy All.");
                return false;
            }
            eventCallback.onLogMessage("INFO", "SYSTEM", stageLabel, stageLabel + " complete ✅");
        }

        eventCallback.onLogMessage("INFO", "SYSTEM", "—", "All stages complete.");
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private boolean executeRowsConcurrently(
        List<ServiceRowDto> rows, String ticket, String stageLabel, StepCallback eventCallback
    ) {
        if (rows.size() == 1) {
            return executeAllDeploymentSteps(
                rows.get(0), ticket, stageLabel,
                StepExecutionState.IDLE, StepExecutionState.IDLE, StepExecutionState.IDLE,
                eventCallback);
        }
        ExecutorService executor = Executors.newFixedThreadPool(rows.size());
        try {
            List<CompletableFuture<Boolean>> futures = rows.stream()
                .map(row -> CompletableFuture.supplyAsync(
                    () -> executeAllDeploymentSteps(
                        row, ticket, stageLabel,
                        StepExecutionState.IDLE, StepExecutionState.IDLE, StepExecutionState.IDLE,
                        eventCallback),
                    executor))
                .toList();
            return futures.stream()
                .map(CompletableFuture::join)
                .allMatch(Boolean::booleanValue);
        } finally {
            executor.shutdown();
        }
    }

    private boolean isStepAlreadyCompleted(StepExecutionState stepState) {
        return stepState == StepExecutionState.DONE || stepState == StepExecutionState.SKIPPED;
    }

    private void notifyJiraIfEnabled(ServiceRowDto row, String ticket, String commentText) {
        if (row.steps().updateJira() && ticket != null && !ticket.isBlank()) {
            try {
                jiraService.addComment(ticket, commentText);
            } catch (Exception e) {
                log.warn("Failed to post Jira comment for ticket {}: {}", ticket, e.getMessage());
            }
        }
    }

    private String abbreviateSha(String sha) {
        return sha != null && sha.length() >= 7 ? sha.substring(0, 7) : (sha != null ? sha : "n/a");
    }

    private void sleepMilliseconds(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
