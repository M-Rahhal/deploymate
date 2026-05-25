package com.deploymate.service;

import com.deploymate.dto.ServiceRowDto;
import com.deploymate.dto.ServiceRowDto.ServiceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrchestratorServiceTest {

    @Mock GitHubService  github;
    @Mock JenkinsService jenkins;
    @Mock JiraService    jira;
    @Mock LogService     logSvc;

    OrchestratorService orchestrator;

    /** Captures log calls for assertion */
    record LogEntry(String level, String service, String stage, String message) {}
    List<LogEntry> logs = new ArrayList<>();

    OrchestratorService.StepCallback captureCallback() {
        return new OrchestratorService.StepCallback() {
            @Override public void onMergeState(String id, String state, String sha, String err) {}
            @Override public void onTagState(String id, String state, String url, String err) {}
            @Override public void onPipelineState(String id, String state, String url, Integer num, String err) {}
            @Override public void onLog(String level, String service, String stage, String msg) {
                logs.add(new LogEntry(level, service, stage, msg));
            }
        };
    }

    @BeforeEach
    void setUp() {
        orchestrator = new OrchestratorService(github, jenkins, jira, logSvc);
        logs.clear();
    }

    // ── mergeOnly ────────────────────────────────────────────────────────────

    @Test
    void mergeOnly_skipsImmediately_whenSkipMergeTrue() {
        var row = sdkRow("r1", true);
        var cb  = captureCallback();
        var result = orchestrator.mergeOnly(row, "PROJ-1", "Stage 1", cb);

        assertThat(result).isTrue();
        assertThat(logs).anyMatch(l -> l.message().contains("skipped"));
        verifyNoInteractions(github);
    }

    @Test
    void mergeOnly_returnsFalse_whenBranchNotFound() {
        when(github.verifyBranch("repo", "PROJ-1")).thenReturn(false);

        var result = orchestrator.mergeOnly(sdkRow("r1", false), "PROJ-1", "S1", captureCallback());

        assertThat(result).isFalse();
        verify(github).verifyBranch("repo", "PROJ-1");
        verify(github, never()).mergeBranch(any(), any(), any(), any());
    }

    @Test
    void mergeOnly_returnsFalse_onConflict() {
        when(github.verifyBranch("repo", "PROJ-1")).thenReturn(true);
        when(github.mergeBranch("repo", "PROJ-1", "env/staging", "PROJ-1"))
            .thenReturn(new GitHubService.MergeResult(false, true, null));

        var result = orchestrator.mergeOnly(sdkRow("r1", false), "PROJ-1", "S1", captureCallback());

        assertThat(result).isFalse();
        assertThat(logs).anyMatch(l -> l.level().equals("ERROR") && l.message().contains("conflict"));
    }

    @Test
    void mergeOnly_returnsTrue_onSuccess() {
        when(github.verifyBranch("repo", "PROJ-1")).thenReturn(true);
        when(github.mergeBranch("repo", "PROJ-1", "env/staging", "PROJ-1"))
            .thenReturn(new GitHubService.MergeResult(true, false, "abc1234567"));

        var result = orchestrator.mergeOnly(sdkRow("r1", false), "PROJ-1", "S1", captureCallback());

        assertThat(result).isTrue();
        assertThat(logs).anyMatch(l -> l.message().contains("abc1234"));
    }

    @Test
    void mergeOnly_postsJiraComment_onSuccess() {
        when(github.verifyBranch(any(), any())).thenReturn(true);
        when(github.mergeBranch(any(), any(), any(), any()))
            .thenReturn(new GitHubService.MergeResult(true, false, "sha1"));

        var row = sdkRowWithJira("r1", false, true);
        orchestrator.mergeOnly(row, "PROJ-5", "S1", captureCallback());

        verify(jira).addComment(eq("PROJ-5"), contains("merged"));
    }

    @Test
    void mergeOnly_doesNotPostJira_whenUpdateJiraFalse() {
        when(github.verifyBranch(any(), any())).thenReturn(true);
        when(github.mergeBranch(any(), any(), any(), any()))
            .thenReturn(new GitHubService.MergeResult(true, false, "sha1"));

        orchestrator.mergeOnly(sdkRow("r1", false), "PROJ-5", "S1", captureCallback());

        verifyNoInteractions(jira);
    }

    // ── tagOnly ───────────────────────────────────────────────────────────────

    @Test
    void tagOnly_skips_forSdkType() {
        var row = sdkRow("r1", false);
        var result = orchestrator.tagOnly(row, "PROJ-1", "S1", captureCallback());

        assertThat(result).isTrue();
        verifyNoInteractions(github);
    }

    @Test
    void tagOnly_skips_forServiceWithSkipMerge() {
        var row = serviceRow("r1", true);
        var result = orchestrator.tagOnly(row, "PROJ-1", "Service", captureCallback());

        assertThat(result).isTrue();
        assertThat(logs).anyMatch(l -> l.message().contains("skipped"));
        verifyNoInteractions(github);
    }

    @Test
    void tagOnly_returnsTrue_onSuccess() {
        when(github.getBranchSha("repo", "env/staging")).thenReturn("deadbeef");
        when(github.createTagAndPreRelease("repo", "env-stag-001", "deadbeef", "PROJ-1"))
            .thenReturn(new GitHubService.TagResult("https://github.com/releases/1"));

        var result = orchestrator.tagOnly(serviceRow("r1", false), "PROJ-1", "Service", captureCallback());

        assertThat(result).isTrue();
        assertThat(logs).anyMatch(l -> l.message().contains("Tag") && l.message().contains("created"));
    }

    @Test
    void tagOnly_returnsFalse_onException() {
        when(github.getBranchSha(any(), any())).thenThrow(new RuntimeException("network down"));

        var result = orchestrator.tagOnly(serviceRow("r1", false), "PROJ-1", "S", captureCallback());

        assertThat(result).isFalse();
        assertThat(logs).anyMatch(l -> l.level().equals("ERROR"));
    }

    // ── pipelineOnly ──────────────────────────────────────────────────────────

    @Test
    void pipelineOnly_usesBranch_forSdk() throws InterruptedException {
        mockJenkinsSuccess();

        var result = orchestrator.pipelineOnly(sdkRow("r1", false), "PROJ-1", "S1", captureCallback());

        assertThat(result).isTrue();
        verify(jenkins).triggerBuild("backend/my-job", "BRANCH", "env/staging");
    }

    @Test
    void pipelineOnly_usesTag_forServiceWithoutSkipMerge() {
        mockJenkinsSuccess();

        orchestrator.pipelineOnly(serviceRow("r1", false), "PROJ-1", "Service", captureCallback());

        verify(jenkins).triggerBuild("backend/my-job", "TAG", "env-stag-001");
    }

    @Test
    void pipelineOnly_usesBranch_forServiceWithSkipMerge() {
        mockJenkinsSuccess();

        orchestrator.pipelineOnly(serviceRow("r1", true), "PROJ-1", "Service", captureCallback());

        verify(jenkins).triggerBuild("backend/my-job", "BRANCH", "env/staging");
    }

    @Test
    void pipelineOnly_returnsFalse_whenTriggerFails() {
        when(jenkins.triggerBuild(any(), any(), any()))
            .thenThrow(new RuntimeException("Jenkins down"));

        var result = orchestrator.pipelineOnly(sdkRow("r1", false), "P", "S", captureCallback());

        assertThat(result).isFalse();
        assertThat(logs).anyMatch(l -> l.level().equals("ERROR"));
    }

    @Test
    void pipelineOnly_returnsFalse_whenBuildFails() {
        when(jenkins.triggerBuild(any(), any(), any())).thenReturn("http://q/1/");
        when(jenkins.pollQueueItem(any())).thenReturn("http://build/1/");
        when(jenkins.pollBuildStatus(any()))
            .thenReturn(new JenkinsService.BuildStatus(null, 1))  // running
            .thenReturn(new JenkinsService.BuildStatus("FAILURE", 1)); // failed

        var result = orchestrator.pipelineOnly(sdkRow("r1", false), "P", "S", captureCallback());

        assertThat(result).isFalse();
        assertThat(logs).anyMatch(l -> l.level().equals("ERROR") && l.message().contains("FAILURE"));
    }

    // ── deployAll ─────────────────────────────────────────────────────────────

    @Test
    void deployAll_haltsOnStageFailure() {
        when(github.verifyBranch(any(), any())).thenReturn(true);
        when(github.mergeBranch(any(), any(), any(), any()))
            .thenReturn(new GitHubService.MergeResult(false, true, null)); // conflict

        var stage1 = sdkRowStage("s1", 1);
        var stage2 = sdkRowStage("s2", 2);

        orchestrator.deployAll(List.of(stage1, stage2), "PROJ-1", captureCallback());

        // Stage 2 should never start
        assertThat(logs).anyMatch(l -> l.message().contains("Halting"));
        assertThat(logs).noneMatch(l -> l.service().equals("s2") && l.message().contains("Merging"));
    }

    @Test
    void deployAll_runsServicesAfterAllSdks() {
        mockJenkinsSuccess();
        when(github.verifyBranch(any(), any())).thenReturn(true);
        when(github.mergeBranch(any(), any(), any(), any()))
            .thenReturn(new GitHubService.MergeResult(true, false, "sha1"));
        when(github.getBranchSha(any(), any())).thenReturn("sha1");
        when(github.createTagAndPreRelease(any(), any(), any(), any()))
            .thenReturn(new GitHubService.TagResult("https://github.com/rel/1"));

        var sdk     = sdkRowStage("sdk-a", 1);
        var service = serviceRow("svc-a", false);

        orchestrator.deployAll(List.of(sdk, service), "PROJ-1", captureCallback());

        // Verify SDK deploys before service
        var sdkMergeIdx = indexOfLog(l -> l.service().equals("sdk-a") && l.message().contains("Merging"));
        var svcMergeIdx = indexOfLog(l -> l.service().equals("svc-a") && l.message().contains("Merging"));
        assertThat(sdkMergeIdx).isLessThan(svcMergeIdx);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void mockJenkinsSuccess() {
        when(jenkins.triggerBuild(any(), any(), any())).thenReturn("http://q/item/1/");
        when(jenkins.pollQueueItem(any())).thenReturn("http://build/1/");
        when(jenkins.pollBuildStatus(any()))
            .thenReturn(new JenkinsService.BuildStatus(null, 1))
            .thenReturn(new JenkinsService.BuildStatus("SUCCESS", 1));
    }

    private ServiceRowDto sdkRow(String name, boolean skipMerge) {
        return new ServiceRowDto("id-" + name, name, "repo", ServiceType.SDK, 1,
            "PROJ-1", "env/staging", "backend/my-job", "", false, skipMerge);
    }

    private ServiceRowDto sdkRowWithJira(String name, boolean skipMerge, boolean updateJira) {
        return new ServiceRowDto("id-" + name, name, "repo", ServiceType.SDK, 1,
            "PROJ-1", "env/staging", "backend/my-job", "", updateJira, skipMerge);
    }

    private ServiceRowDto sdkRowStage(String name, int stage) {
        return new ServiceRowDto("id-" + name, name, "repo", ServiceType.SDK, stage,
            "PROJ-1", "env/staging", "backend/my-job", "", false, false);
    }

    private ServiceRowDto serviceRow(String name, boolean skipMerge) {
        return new ServiceRowDto("id-" + name, name, "repo", ServiceType.SERVICE, 1,
            "PROJ-1", "env/staging", "backend/my-job", "env-stag-001", false, skipMerge);
    }

    private int indexOfLog(java.util.function.Predicate<LogEntry> predicate) {
        for (int i = 0; i < logs.size(); i++) {
            if (predicate.test(logs.get(i))) return i;
        }
        return Integer.MAX_VALUE;
    }
}
