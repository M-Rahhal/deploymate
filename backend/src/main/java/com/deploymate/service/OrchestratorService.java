package com.deploymate.service;

import com.deploymate.dto.ServiceRowDto;
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

    private final GitHubService  github;
    private final JenkinsService jenkins;
    private final JiraService    jira;
    private final LogService     logSvc;

    public interface StepCallback {
        void onMergeState(String id, String state, String sha,        String errorMessage);
        void onTagState  (String id, String state, String releaseUrl, String errorMessage);
        void onPipelineState(String id, String state, String buildUrl, Integer buildNumber, String errorMessage);
        void onLog(String level, String service, String stage, String message);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 1 — Merge
    // ─────────────────────────────────────────────────────────────────────────

    public boolean mergeOnly(ServiceRowDto row, String ticket, String stageLabel, StepCallback cb) {
        if (!row.steps().mergeBranch()) {
            cb.onMergeState(row.id(), "SKIPPED", null, null);
            cb.onLog("INFO", row.name(), stageLabel, "Merge skipped (not configured)");
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

        GitHubService.MergeResult result = github.mergeBranch(row.repo(), row.sourceBranch(), row.targetBranch(), ticket);

        if (result.conflict()) {
            cb.onMergeState(row.id(), "CONFLICT", null, "Merge conflict. Resolve manually and retry.");
            cb.onLog("ERROR", row.name(), stageLabel, "Merge conflict detected. Deployment halted.");
            postJiraComment(row, ticket,
                "⚠️ Merge conflict in `" + row.repo() + "` ("
                + row.sourceBranch() + " → " + row.targetBranch() + "). Manual resolution required.");
            return false;
        }

        cb.onMergeState(row.id(), "DONE", result.sha(), null);
        String sha = shortSha(result.sha());
        cb.onLog("INFO", row.name(), stageLabel, "Merge successful (SHA: " + sha + ")");
        postJiraComment(row, ticket,
            "🔀 `" + row.repo() + "` merged `" + row.sourceBranch()
            + "` → `" + row.targetBranch() + "` (SHA: `" + sha + "`)");
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 2 — Tag
    // ─────────────────────────────────────────────────────────────────────────

    public boolean tagOnly(ServiceRowDto row, String ticket, String stageLabel, StepCallback cb) {
        if (!row.steps().createTag()) {
            cb.onTagState(row.id(), "SKIPPED", null, null);
            return true;
        }

        cb.onTagState(row.id(), "RUNNING", null, null);
        cb.onLog("INFO", row.name(), stageLabel,
            "Getting HEAD SHA of " + row.targetBranch() + "...");

        try {
            String sha = github.getBranchSha(row.repo(), row.targetBranch());
            cb.onLog("INFO", row.name(), stageLabel, "Creating pre-release tag: " + row.tagName());

            GitHubService.TagResult result = github.createTagAndPreRelease(row.repo(), row.tagName(), sha, ticket);
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
     * git_branch parameter strategy (determined by row type):
     *   SERVICE → git_branch = tagName  (deploy from a pre-release tag; tagName must be set)
     *   SDK     → git_branch = "origin/" + targetBranch  (build from branch)
     */
    public boolean pipelineOnly(ServiceRowDto row, String ticket, String stageLabel, StepCallback cb) {
        if (!row.steps().triggerPipeline()) {
            cb.onPipelineState(row.id(), "SKIPPED", null, null, null);
            cb.onLog("INFO", row.name(), stageLabel, "Pipeline skipped (not configured)");
            return true;
        }

        // SERVICE always deploys from a tag; SDK always builds from a branch
        String gitBranch;
        if (row.type() == ServiceRowDto.ServiceType.SERVICE) {
            if (row.tagName() == null || row.tagName().isBlank()) {
                cb.onPipelineState(row.id(), "FAILED", null, null,
                    "SERVICE rows require a tagName before triggering the pipeline");
                cb.onLog("ERROR", row.name(), stageLabel,
                    "Cannot trigger pipeline: tagName is required for SERVICE rows");
                return false;
            }
            gitBranch = row.tagName();
        } else {
            gitBranch = "origin/" + row.targetBranch();
        }

        cb.onLog("INFO", row.name(), stageLabel,
            "Triggering Jenkins: " + row.jenkinsJob() + " (git_branch=" + gitBranch + ")");
        cb.onPipelineState(row.id(), "RUNNING", null, null, null);

        String queueItemUrl;
        try {
            queueItemUrl = jenkins.triggerBuild(row.jenkinsJob(), gitBranch);
            cb.onLog("INFO", row.name(), stageLabel, "Build queued — waiting for executor...");
        } catch (Exception e) {
            cb.onPipelineState(row.id(), "FAILED", null, null, e.getMessage());
            cb.onLog("ERROR", row.name(), stageLabel, "Jenkins trigger failed: " + e.getMessage());
            return false;
        }

        String buildUrl = null;
        while (buildUrl == null) {
            sleep(3_000);
            buildUrl = jenkins.pollQueueItem(queueItemUrl);
        }

        JenkinsService.BuildStatus status = jenkins.pollBuildStatus(buildUrl);
        int buildNum = status.number();
        cb.onPipelineState(row.id(), "RUNNING", buildUrl, buildNum, null);
        cb.onLog("INFO", row.name(), stageLabel,
            "Build #" + buildNum + " running — " + buildUrl);
        postJiraComment(row, ticket,
            "🚀 Jenkins build [#" + buildNum + "](" + buildUrl + ") started for `" + row.repo() + "`");

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
            String msg = "Build #" + buildNum + " result: " + result;
            cb.onPipelineState(row.id(), "FAILED", buildUrl, buildNum, msg);
            cb.onLog("ERROR", row.name(), stageLabel, "Build #" + buildNum + " " + result + " ❌");
            postJiraComment(row, ticket,
                "❌ Jenkins build [#" + buildNum + "](" + buildUrl + ") **" + result
                + "** for `" + row.repo() + "`");
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // All Steps — idempotent wrapper
    // ─────────────────────────────────────────────────────────────────────────

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
    // Deploy All — unified stage-based orchestration
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Deploys all rows in stage order.
     * Rows with the same stage number run in parallel.
     * A failure in any stage halts execution before the next stage starts.
     */
    public void deployAll(List<ServiceRowDto> rows, String ticket, StepCallback cb) {
        logSvc.info("SYSTEM", "—",
            "═══ NEW DEPLOY SESSION ═══ Ticket: " + (ticket != null ? ticket : "(none)"));
        cb.onLog("INFO", "SYSTEM", "—",
            "Deploy All started" + (ticket != null && !ticket.isBlank() ? " for ticket " + ticket : ""));

        List<Integer> stages = rows.stream()
            .map(ServiceRowDto::stage)
            .distinct()
            .sorted()
            .toList();

        for (int stageNum : stages) {
            List<ServiceRowDto> stageRows = rows.stream().filter(r -> r.stage() == stageNum).toList();
            String stageLabel = "Stage " + stageNum;
            cb.onLog("INFO", "SYSTEM", stageLabel,
                "Starting " + stageLabel + " — "
                + stageRows.stream().map(ServiceRowDto::name).collect(Collectors.joining(", "))
                + " (parallel)");

            boolean allOk = runParallel(stageRows, ticket, stageLabel, cb);
            if (!allOk) {
                cb.onLog("ERROR", "SYSTEM", stageLabel,
                    stageLabel + " had failures or conflicts. Halting Deploy All.");
                return;
            }
            cb.onLog("INFO", "SYSTEM", stageLabel, stageLabel + " complete ✅");
        }

        cb.onLog("INFO", "SYSTEM", "—", "All stages complete.");
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
            List<CompletableFuture<Boolean>> futures = rows.stream()
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
        if (row.steps().updateJira() && ticket != null && !ticket.isBlank()) {
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
