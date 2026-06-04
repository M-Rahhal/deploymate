package com.deploymate.service;

import com.deploymate.dto.ServiceRowDto;
import com.deploymate.dto.ServiceRowDto.ServiceType;
import com.deploymate.dto.ServiceRowDto.Steps;
import com.deploymate.model.StepExecutionState;
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

    @Mock GitHubService  gitHubService;
    @Mock JenkinsService jenkinsService;
    @Mock JiraService    jiraService;
    @Mock LogService     deploymentLogger;

    OrchestratorService orchestrator;

    record LogEntry(String level, String service, String stage, String message) {}
    List<LogEntry> capturedLogs = new ArrayList<>();

    OrchestratorService.StepCallback captureCallback() {
        return new OrchestratorService.StepCallback() {
            @Override public void onMergeStateChanged(String rowId, StepExecutionState newState, String sha, String err) {}
            @Override public void onTagStateChanged(String rowId, StepExecutionState newState, String url, String err) {}
            @Override public void onPipelineStateChanged(String rowId, StepExecutionState newState, String url, Integer num, String err) {}
            @Override public void onLogMessage(String level, String serviceName, String stageLabel, String msg) {
                capturedLogs.add(new LogEntry(level, serviceName, stageLabel, msg));
            }
        };
    }

    @BeforeEach
    void setUp() {
        orchestrator = new OrchestratorService(gitHubService, jenkinsService, jiraService, deploymentLogger);
        capturedLogs.clear();
    }

    // ── executeMergeStep ─────────────────────────────────────────────────────

    @Test
    void executeMergeStep_skipsImmediately_whenMergeBranchFalse() {
        var row    = sdkRow("r1", false);
        var result = orchestrator.executeMergeStep(row, "PROJ-1", "Stage 1", captureCallback());

        assertThat(result).isTrue();
        assertThat(capturedLogs).anyMatch(l -> l.message().contains("skipped"));
        verifyNoInteractions(gitHubService);
    }

    @Test
    void executeMergeStep_returnsFalse_whenBranchNotFound() {
        when(gitHubService.verifyBranch("repo", "PROJ-1")).thenReturn(false);

        var result = orchestrator.executeMergeStep(sdkRow("r1", true), "PROJ-1", "S1", captureCallback());

        assertThat(result).isFalse();
        verify(gitHubService).verifyBranch("repo", "PROJ-1");
        verify(gitHubService, never()).mergeBranch(any(), any(), any(), any());
    }

    @Test
    void executeMergeStep_returnsFalse_onConflict() {
        when(gitHubService.verifyBranch("repo", "PROJ-1")).thenReturn(true);
        when(gitHubService.mergeBranch("repo", "PROJ-1", "env/staging", "PROJ-1"))
            .thenReturn(new GitHubService.MergeResult(false, true, null));

        var result = orchestrator.executeMergeStep(sdkRow("r1", true), "PROJ-1", "S1", captureCallback());

        assertThat(result).isFalse();
        assertThat(capturedLogs).anyMatch(l -> l.level().equals("ERROR") && l.message().contains("conflict"));
    }

    @Test
    void executeMergeStep_returnsTrue_onSuccess() {
        when(gitHubService.verifyBranch("repo", "PROJ-1")).thenReturn(true);
        when(gitHubService.mergeBranch("repo", "PROJ-1", "env/staging", "PROJ-1"))
            .thenReturn(new GitHubService.MergeResult(true, false, "abc1234567"));

        var result = orchestrator.executeMergeStep(sdkRow("r1", true), "PROJ-1", "S1", captureCallback());

        assertThat(result).isTrue();
        assertThat(capturedLogs).anyMatch(l -> l.message().contains("abc1234"));
    }

    @Test
    void executeMergeStep_postsJiraComment_onSuccess() {
        when(gitHubService.verifyBranch(any(), any())).thenReturn(true);
        when(gitHubService.mergeBranch(any(), any(), any(), any()))
            .thenReturn(new GitHubService.MergeResult(true, false, "sha1"));

        orchestrator.executeMergeStep(sdkRowWithJira("r1", true, true), "PROJ-5", "S1", captureCallback());

        verify(jiraService).addComment(eq("PROJ-5"), contains("merged"));
    }

    @Test
    void executeMergeStep_doesNotPostJira_whenUpdateJiraFalse() {
        when(gitHubService.verifyBranch(any(), any())).thenReturn(true);
        when(gitHubService.mergeBranch(any(), any(), any(), any()))
            .thenReturn(new GitHubService.MergeResult(true, false, "sha1"));

        orchestrator.executeMergeStep(sdkRow("r1", true), "PROJ-5", "S1", captureCallback());

        verifyNoInteractions(jiraService);
    }

    // ── executeTagCreationStep ────────────────────────────────────────────────

    @Test
    void executeTagCreationStep_skips_whenCreateTagFalse() {
        var row    = sdkRow("r1", true);
        var result = orchestrator.executeTagCreationStep(row, "PROJ-1", "S1", captureCallback());

        assertThat(result).isTrue();
        verifyNoInteractions(gitHubService);
    }

    @Test
    void executeTagCreationStep_skips_whenCreateTagFalse_serviceRow() {
        var row    = serviceRow("r1", false);
        var result = orchestrator.executeTagCreationStep(row, "PROJ-1", "Service", captureCallback());

        assertThat(result).isTrue();
        verifyNoInteractions(gitHubService);
    }

    @Test
    void executeTagCreationStep_returnsTrue_onSuccess() {
        when(gitHubService.getBranchSha("repo", "env/staging")).thenReturn("deadbeef");
        when(gitHubService.createTagAndPreRelease("repo", "env-stag-001", "deadbeef", "PROJ-1"))
            .thenReturn(new GitHubService.TagResult("https://github.com/releases/1"));

        var result = orchestrator.executeTagCreationStep(
            serviceRowWithTag("r1"), "PROJ-1", "Service", captureCallback());

        assertThat(result).isTrue();
        assertThat(capturedLogs).anyMatch(l -> l.message().contains("Tag") && l.message().contains("created"));
    }

    @Test
    void executeTagCreationStep_returnsFalse_onException() {
        when(gitHubService.getBranchSha(any(), any())).thenThrow(new RuntimeException("network down"));

        var result = orchestrator.executeTagCreationStep(
            serviceRowWithTag("r1"), "PROJ-1", "S", captureCallback());

        assertThat(result).isFalse();
        assertThat(capturedLogs).anyMatch(l -> l.level().equals("ERROR"));
    }

    // ── executePipelineStep ───────────────────────────────────────────────────

    @Test
    void executePipelineStep_usesBranchRef_forSdkRow() {
        mockJenkinsSuccess();

        var result = orchestrator.executePipelineStep(sdkRow("r1", true), "PROJ-1", "S1", captureCallback());

        assertThat(result).isTrue();
        verify(jenkinsService).triggerBuild("backend/my-job", "origin/env/staging");
    }

    @Test
    void executePipelineStep_usesTagName_forServiceRowWithTag() {
        mockJenkinsSuccess();

        orchestrator.executePipelineStep(serviceRowWithTag("r1"), "PROJ-1", "Service", captureCallback());

        verify(jenkinsService).triggerBuild("backend/my-job", "env-stag-001");
    }

    @Test
    void executePipelineStep_usesTagName_forServiceRow_evenWhenCreateTagFalse() {
        mockJenkinsSuccess();

        orchestrator.executePipelineStep(serviceRow("r1", true), "PROJ-1", "Service", captureCallback());

        verify(jenkinsService).triggerBuild("backend/my-job", "env-stag-001");
    }

    @Test
    void executePipelineStep_returnsFalse_whenServiceRowTagNameIsEmpty() {
        var row = new ServiceRowDto("id-r1", "r1", "repo", ServiceType.SERVICE, 1,
            "PROJ-1", "env/staging", "backend/my-job", "",
            new Steps(true, false, true, false));

        var result = orchestrator.executePipelineStep(row, "PROJ-1", "Service", captureCallback());

        assertThat(result).isFalse();
        assertThat(capturedLogs).anyMatch(l -> l.level().equals("ERROR") && l.message().contains("tagName"));
        verifyNoInteractions(jenkinsService);
    }

    @Test
    void executePipelineStep_skips_whenTriggerPipelineFalse() {
        var row = new ServiceRowDto("id-r1", "r1", "repo", ServiceType.SDK, 1,
            "PROJ-1", "env/staging", "backend/my-job", "",
            new Steps(true, false, false, false));

        var result = orchestrator.executePipelineStep(row, "PROJ-1", "S1", captureCallback());

        assertThat(result).isTrue();
        verifyNoInteractions(jenkinsService);
        assertThat(capturedLogs).anyMatch(l -> l.message().contains("skipped"));
    }

    @Test
    void executePipelineStep_returnsFalse_whenBuildTriggerFails() {
        when(jenkinsService.triggerBuild(any(), any()))
            .thenThrow(new RuntimeException("Jenkins down"));

        var result = orchestrator.executePipelineStep(sdkRow("r1", true), "P", "S", captureCallback());

        assertThat(result).isFalse();
        assertThat(capturedLogs).anyMatch(l -> l.level().equals("ERROR"));
    }

    @Test
    void executePipelineStep_returnsFalse_whenBuildFails() {
        when(jenkinsService.triggerBuild(any(), any())).thenReturn("http://q/1/");
        when(jenkinsService.pollQueueItem(any())).thenReturn("http://build/1/");
        when(jenkinsService.pollBuildStatus(any()))
            .thenReturn(new JenkinsService.BuildStatus(null, 1))
            .thenReturn(new JenkinsService.BuildStatus("FAILURE", 1));

        var result = orchestrator.executePipelineStep(sdkRow("r1", true), "P", "S", captureCallback());

        assertThat(result).isFalse();
        assertThat(capturedLogs).anyMatch(l -> l.level().equals("ERROR") && l.message().contains("FAILURE"));
    }

    // ── orchestrateDeployment ─────────────────────────────────────────────────

    @Test
    void orchestrateDeployment_haltsOnStageFailure() {
        when(gitHubService.verifyBranch(any(), any())).thenReturn(true);
        when(gitHubService.mergeBranch(any(), any(), any(), any()))
            .thenReturn(new GitHubService.MergeResult(false, true, null));

        var stage1 = sdkRowStage("s1", 1);
        var stage2 = sdkRowStage("s2", 2);

        orchestrator.orchestrateDeployment(List.of(stage1, stage2), "PROJ-1", captureCallback());

        assertThat(capturedLogs).anyMatch(l -> l.message().contains("Halting"));
        assertThat(capturedLogs).noneMatch(l -> l.service().equals("s2") && l.message().contains("Merging"));
    }

    @Test
    void orchestrateDeployment_runsStagesInOrder() {
        mockJenkinsSuccess();
        when(gitHubService.verifyBranch(any(), any())).thenReturn(true);
        when(gitHubService.mergeBranch(any(), any(), any(), any()))
            .thenReturn(new GitHubService.MergeResult(true, false, "sha1"));
        when(gitHubService.getBranchSha(any(), any())).thenReturn("sha1");
        when(gitHubService.createTagAndPreRelease(any(), any(), any(), any()))
            .thenReturn(new GitHubService.TagResult("https://github.com/rel/1"));

        var sdkRow     = sdkRowStage("sdk-a", 1);
        var serviceRow = serviceRowWithTagStage("svc-a", 2);

        orchestrator.orchestrateDeployment(List.of(sdkRow, serviceRow), "PROJ-1", captureCallback());

        var sdkMergeIndex     = indexOfLog(l -> l.service().equals("sdk-a") && l.message().contains("Merging"));
        var serviceMergeIndex = indexOfLog(l -> l.service().equals("svc-a") && l.message().contains("Merging"));
        assertThat(sdkMergeIndex).isLessThan(serviceMergeIndex);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void mockJenkinsSuccess() {
        when(jenkinsService.triggerBuild(any(), any())).thenReturn("http://q/item/1/");
        when(jenkinsService.pollQueueItem(any())).thenReturn("http://build/1/");
        when(jenkinsService.pollBuildStatus(any()))
            .thenReturn(new JenkinsService.BuildStatus(null, 1))
            .thenReturn(new JenkinsService.BuildStatus("SUCCESS", 1));
    }

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

    private ServiceRowDto serviceRow(String name, boolean mergeBranch) {
        return new ServiceRowDto("id-" + name, name, "repo", ServiceType.SERVICE, 1,
            "PROJ-1", "env/staging", "backend/my-job", "env-stag-001",
            new Steps(mergeBranch, false, true, false));
    }

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
        for (int i = 0; i < capturedLogs.size(); i++) {
            if (predicate.test(capturedLogs.get(i))) return i;
        }
        return Integer.MAX_VALUE;
    }
}
