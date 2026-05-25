package com.deploymate.controller;

import com.deploymate.service.JenkinsService;
import com.deploymate.service.JenkinsService.BuildStatus;
import com.deploymate.service.JenkinsService.LogFragment;
import com.deploymate.service.LogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PipelineController.class)
@ActiveProfiles("test")
class PipelineControllerTest {

    @Autowired MockMvc      mvc;
    @Autowired ObjectMapper mapper;

    @MockBean JenkinsService jenkins;
    @MockBean LogService     logSvc;

    @Test
    void trigger_returns200_onSuccess() throws Exception {
        when(jenkins.triggerBuild("backend/my-job", "BRANCH", "env/staging"))
            .thenReturn("http://jenkins/queue/item/42/");

        mvc.perform(post("/api/pipeline/trigger")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "jenkinsJob", "backend/my-job",
                    "paramType",  "BRANCH",
                    "paramValue", "env/staging"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.queueItemUrl").value("http://jenkins/queue/item/42/"));
    }

    @Test
    void trigger_returns400_whenJobEmpty() throws Exception {
        mvc.perform(post("/api/pipeline/trigger")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "jenkinsJob", "",
                    "paramType",  "BRANCH",
                    "paramValue", "main"
                ))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void trigger_returns400_whenParamTypeNull() throws Exception {
        mvc.perform(post("/api/pipeline/trigger")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "jenkinsJob", "backend/job",
                    "paramValue", "main"
                ))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void trigger_returns400_whenJobHasInvalidChars() throws Exception {
        mvc.perform(post("/api/pipeline/trigger")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "jenkinsJob", "backend/../../../etc/passwd",
                    "paramType",  "BRANCH",
                    "paramValue", "main"
                ))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void status_returnsQueued_whenQueueItemStillWaiting() throws Exception {
        when(jenkins.pollQueueItem("http://q/1/")).thenReturn(null);

        mvc.perform(get("/api/pipeline/status")
                .param("queueItemUrl", "http://q/1/"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("QUEUED"));
    }

    @Test
    void status_returnsRunning_whenBuildNotFinished() throws Exception {
        when(jenkins.pollBuildStatus("http://build/42/"))
            .thenReturn(new BuildStatus(null, 42));
        when(jenkins.getBuildLog(any(), anyInt()))
            .thenReturn(new LogFragment("build output...", 100, false));

        mvc.perform(get("/api/pipeline/status")
                .param("buildUrl", "http://build/42/"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("RUNNING"))
            .andExpect(jsonPath("$.buildNumber").value(42));
    }

    @Test
    void status_returnsSuccess_whenBuildSucceeds() throws Exception {
        when(jenkins.pollBuildStatus("http://build/1/"))
            .thenReturn(new BuildStatus("SUCCESS", 1));
        when(jenkins.getBuildLog(any(), anyInt()))
            .thenReturn(new LogFragment("", 0, false));

        mvc.perform(get("/api/pipeline/status")
                .param("buildUrl", "http://build/1/"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("SUCCESS"));
    }

    @Test
    void status_returns400_whenNoParamProvided() throws Exception {
        mvc.perform(get("/api/pipeline/status"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void status_resolvesBuildUrl_fromQueueItem() throws Exception {
        when(jenkins.pollQueueItem("http://q/1/")).thenReturn("http://build/1/");
        when(jenkins.pollBuildStatus("http://build/1/"))
            .thenReturn(new BuildStatus("SUCCESS", 1));
        when(jenkins.getBuildLog(any(), anyInt()))
            .thenReturn(new LogFragment("", 0, false));

        mvc.perform(get("/api/pipeline/status")
                .param("queueItemUrl", "http://q/1/"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("SUCCESS"))
            .andExpect(jsonPath("$.buildUrl").value("http://build/1/"));
    }
}
