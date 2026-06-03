package com.deploymate.service;

import com.deploymate.dto.ServiceRowDto;
import com.deploymate.dto.ServiceRowDto.ServiceType;
import com.deploymate.dto.ServiceRowDto.Steps;
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
    void mergeOnly_skipsImmediately_whenMergeBranchFalse() {
        var row = sdkRow("r1", false);
        var cb  = captureCallback();
        var result = orchestrator.mergeOnly(row, "PROJ-1", "Stage 1", cb);

        assertThat(result).isTrue();
        assertThat(logs).anyMatch(l -> l.message().contains("skipped"));
        verifyNoInteractions(github);
    }

    @Test
    void mergeOnly_returnsFalse_whenBranchNotFound() {
        when(github.verifyBranch("repo", "PROJ-1")).thenReturn(false);

        var result = orchestrator.mergeOnly(sdkRow("r1", true), "PROJ-1", "S1", captureCallback());

        assertThat(result).isFalse();
        verify(github).verifyBranch("repo", "PROJ-1");
        verify(github, never()).mergeBranch(any(), any(), any(), any());
    }

    @Test
    void mergeOnly_returnsFalse_onConflict() {
        when(github.verifyBranch("repo", "PROJ-1")).thenReturn(true);
        when(github.mergeBranch("repo", "PROJ-1", "env/staging", "PROJ-1"))
            .thenReturn(new GitHubService.MergeResult(false, true, null));

        var result = orchestrator.mergeOnly(sdkRow("r1", true), "PROJ-1", "S1", captureCallback());

        assertThat(result).isFalse();
        assertThat(logs).anyMatch(l -> l.level().equals("ERROR") && l.message().contains("conflict"));
    }

    @Test
    void mergeOnly_returnsTrue_onSuccess() {
        when(github.verifyBranch("repo", "PROJ-1")).thenReturn(true);
        when(github.mergeBranch("repo", "PROJ-1", "env/staging", "PROJ-1"))
            .thenReturn(new GitHubService.MergeResult(true, false, "abc1234567"));

        var result = orchestrator.mergeOnly(sdkRow("r1", true), "PROJ-1", "S1", captureCallback());

        assertThat(result).isTrue();
        assertThat(logs).anyMatch(l -> l.message().contains("abc1234"));
    }

    @Test
    void mergeOnly_postsJiraComment_onSuccess() {
        when(github.verifyBranch(any(), any())).thenReturn(true);
        when(github.mergeBranch(any(), any(), any(), any()))
            .thenReturn(new GitHubService.MergeResult(true, false, "sha1"));

        var row = sdkRowWithJira("r1", true, true);
        orchestrator.mergeOnly(row, "PROJ-5", "S1", captureCallback());

        verify(jira).addComment(eq("PROJ-5"), contains("merged"));
    }

    @Test
    void mergeOnly_doesNotPostJira_whenUpdateJiraFalse() {
        when(github.verifyBranch(any(), any())).thenReturn(true);
        when(github.mergeBranch(any(), any(), any(), any()))
            .thenReturn(new GitHubService.MergeResult(true, false, "sha1"));

        orchestrator.mergeOnly(sdkRow("r1", true), "PROJ-5", "S1", captureCallback());

        verifyNoInteractions(jira);
    }

    // ── tagOnly ───────────────────────────────────────────────────────────────

    @Test
    void tagOnly_skips_whenCreateTagFalse() {
        var row = sdkRow("r1", true); // SDK has createTag=false
        var result = orchestrator.tagOnly(row, "PROJ-1", "S1", captureCallback());

        assertThat(result).isTrue();
        verifyNoInteractions(github);
    }

    @Test
    void tagOnly_skips_whenCreateTagFalse_service() {
        var row = serviceRow("r1", false); // mergeBranch=false → createTag=false
        var result = orchestrator.tagOnly(row, "PROJ-1", "Service", captureCallback());

        assertThat(result).isTrue();
        verifyNoInteractions(github);
    }

    @Test
    void tagOnly_returnsTrue_onSuccess() {
        when(github.getBranchSha("repo", "env/staging")).thenReturn("deadbeef");
        when(github.createTagAndPreRelease("repo", "env-stag-001", "deadbeef", "PROJ-1"))
            .thenReturn(new GitHubService.TagResult("https://github.com/releases/1"));

        var result = orchestrator.tagOnly(serviceRowWithTag("r1"), "PROJ-1", "Service", captureCallback());

        assertThat(result).isTrue();
        assertThat(logs).anyMatch(l -> l.message().contains("Tag") && l.message().contains("created"));
    }

    @Test
    void tagOnly_returnsFalse_onException() {
        when(github.getBranchSha(any(), any())).thenThrow(new RuntimeException("network down"));

        var result = orchestrator.tagOnly(serviceRowWithTag("r1"), "PROJ-1", "S", captureCallback());

        assertThat(result).isFalse();
        assertThat(logs).anyMatch(l -> l.level().equals("ERROR"));
    }

    // ── pipelineOnly ──────────────────────────────────────────────────────────

    @Test
    void pipelineOnly_usesBranch_whenCreateTagFalse() {
        mockJenkinsSuccess();

        var result = orchestrator.pipelineOnly(sdkRow("r1", true), "PROJ-1", "S1", captureCallback());

        assertThat(result).isTrue();
        verify(jenkins).triggerBuild("backend/my-job", "origin/env/staging");
    }

    @Test
    void pipelineOnly_usesTag_whenCreateTagTrue() {
        mockJenkinsSuccess();

        orchestrator.pipelineOnly(serviceRowWithTag("r1"), "PROJ-1", "Service", captureCallback());

        verify(jenkins).triggerBuild("backend/my-job", "env-stag-001");
    }

    @Test
    void pipelineOnly_usesTag_forService_evenWhenCreateTagFalse() {
        mockJenkinsSuccess();

        // SERVICE always uses tagName regardless of the createTag step toggle
        orchestrator.pipelineOnly(serviceRow("r1", true), "PROJ-1", "Service", captureCallback());

        verify(jenkins).triggerBuild("backend/my-job", "env-stag-001");
    }

    @Test
    void pipelineOnly_returnsFalse_whenServiceTagNameIsEmpty() {
        var row = new ServiceRowDto("id-r1", "r1", "repo", ServiceType.SERVICE, 1,
            "PROJ-1", "env/staging", "backend/my-job", "",
            new Steps(true, false, true, false));

        var result = orchestrator.pipelineOnly(row, "PROJ-1", "Service", captureCallback());

        assertThat(result).isFalse();
        assertThat(logs).anyMatch(l -> l.level().equals("ERROR") && l.message().contains("tagName"));
        verifyNoInteractions(jenkins);
    }

    @Test
    void pipelineOnly_skips_whenTriggerPipelineFalse() {
        var row = new ServiceRowDto("id-r1", "r1", "repo", ServiceType.SDK, 1,
            "PROJ-1", "env/staging", "backend/my-job", "",
            new Steps(true, false, false, false));

        var result = orchestrator.pipelineOnly(row, "PROJ-1", "S1", captureCallback());

        assertThat(result).isTrue();
        verifyNoInteractions(jenkins);
        assertThat(logs).anyMatch(l -> l.message().contains("skipped"));
    }

    @Test
    void pipelineOnly_returnsFalse_whenTriggerFails() {
        when(jenkins.triggerBuild(any(), any()))
            .thenThrow(new RuntimeException("Jenkins down"));

        var result = orchestrator.pipelineOnly(sdkRow("r1", true), "P", "S", captureCallback());

        assertThat(result).isFalse();
        assertThat(logs).anyMatch(l -> l.level().equals("ERROR"));
    }

    @Test
    void pipelineOnly_returnsFalse_whenBuildFails() {
        when(jenkins.triggerBuild(any(), any())).thenReturn("http://q/1/");
        when(jenkins.pollQueueItem(any())).thenReturn("http://build/1/");
        when(jenkins.pollBuildStatus(any()))
            .thenReturn(new JenkinsService.BuildStatus(null, 1))
            .thenReturn(new JenkinsService.BuildStatus("FAILURE", 1));

        var result = orchestrator.pipelineOnly(sdkRow("r1", true), "P", "S", captureCallback());

        assertThat(result).isFalse();
        assertThat(logs).anyMatch(l -> l.level().equals("ERROR") && l.message().contains("FAILURE"));
    }

    // ── deployAll ─────────────────────────────────────────────────────────────

    @Test
    void deployAll_haltsOnStageFailure() {
        when(github.verifyBranch(any(), any())).thenReturn(true);
        when(github.mergeBranch(any(), any(), any(), any()))
            .thenReturn(new GitHubService.MergeResult(false, true, null));

        var stage1 = sdkRowStage("s1", 1);
        var stage2 = sdkRowStage("s2", 2);

        orchestrator.deployAll(List.of(stage1, stage2), "PROJ-1", captureCallback());

        assertThat(logs).anyMatch(l -> l.message().contains("Halting"));
        assertThat(logs).noneMatch(l -> l.service().equals("s2") && l.message().contains("Merging"));
    }

    @Test
    void deployAll_runsStagesInOrder() {
        mockJenkinsSuccess();
        when(github.verifyBranch(any(), any())).thenReturn(true);
        when(github.mergeBranch(any(), any(), any(), any()))
            .thenReturn(new GitHubService.MergeResult(true, false, "sha1"));
        when(github.getBranchSha(any(), any())).thenReturn("sha1");
        when(github.createTagAndPreRelease(any(), any(), any(), any()))
            .thenReturn(new GitHubService.TagResult("https://github.com/rel/1"));

        var sdk     = sdkRowStage("sdk-a", 1);
        var service = serviceRowWithTagStage("svc-a", 2); // stage 2 runs after stage 1

        orchestrator.deployAll(List.of(sdk, service), "PROJ-1", captureCallback());

        var sdkMergeIdx = indexOfLog(l -> l.service().equals("sdk-a") && l.message().contains("Merging"));
        var svcMergeIdx = indexOfLog(l -> l.service().equals("svc-a") && l.message().contains("Merging"));
        assertThat(sdkMergeIdx).isLessThan(svcMergeIdx);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void mockJenkinsSuccess() {
        when(jenkins.triggerBuild(any(), any())).thenReturn("http://q/item/1/");
        when(jenkins.pollQueueItem(any())).thenReturn("http://build/1/");
        when(jenkins.pollBuildStatus(any()))
            .thenReturn(new JenkinsService.BuildStatus(null, 1))
            .thenReturn(new JenkinsService.BuildStatus("SUCCESS", 1));
    }

    /** SDK row — mergeBranch controlled by param, createTag=false, triggerPipeline=true */
    private ServiceRowDto sdkRow(String name, boolean mergeBranch) {
        return new ServiceRowDto("id-" + name, name, "repo", ServiceType.SDK, 1,
            "PROJ-1", "env/staging", "backend/my-job", "",
            new Steps(mergeBranch, false, true, false));
    }

    private ServiceRowDto sdkRowWithJira(String name, boolean mergeBranch, boolean updateJira) {
        return new ServiceRowDto("id-" + name, name, "repo", ServiceType.SDK, 1,
            "PROJ-1", "env/staging", "backend/my-job", "",
            new Steps(mergeBranch, false, true, updateJira));
    }

    private ServiceRowDto sdkRowStage(String name, int stage) {
        return new ServiceRowDto("id-" + name, name, "repo", ServiceType.SDK, stage,
            "PROJ-1", "env/staging", "backend/my-job", "",
            new Steps(true, false, true, false));
    }

    /** Service row without tag (mergeBranch flag controls merge, createTag=false) */
    private ServiceRowDto serviceRow(String name, boolean mergeBranch) {
        return new ServiceRowDto("id-" + name, name, "repo", ServiceType.SERVICE, 1,
            "PROJ-1", "env/staging", "backend/my-job", "env-stag-001",
            new Steps(mergeBranch, false, true, false));
    }

    /** Service row with tag enabled */
    private ServiceRowDto serviceRowWithTag(String name) {
        return new ServiceRowDto("id-" + name, name, "repo", ServiceType.SERVICE, 1,
            "PROJ-1", "env/staging", "backend/my-job", "env-stag-001",
            new Steps(true, true, true, false));
    }

    private ServiceRowDto serviceRowWithTagStage(String name, int stage) {
        return new ServiceRowDto("id-" + name, name, "repo", ServiceType.SERVICE, stage,
            "PROJ-1", "env/staging", "backend/my-job", "env-stag-001",
            new Steps(true, true, true, false));
    }

    private int indexOfLog(java.util.function.Predicate<LogEntry> predicate) {
        for (int i = 0; i < logs.size(); i++) {
            if (predicate.test(logs.get(i))) return i;
        }
        return Integer.MAX_VALUE;
    }
}
