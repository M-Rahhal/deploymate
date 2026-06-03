package com.deploymate.controller;

import com.deploymate.service.GitHubService;
import com.deploymate.service.GitHubService.TagResult;
import com.deploymate.service.JenkinsService;
import com.deploymate.service.LogService;
import com.deploymate.service.TagGeneratorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TagController.class)
@ActiveProfiles("test")
class TagControllerTest {

    @Autowired MockMvc      mvc;
    @Autowired ObjectMapper mapper;

    @MockBean GitHubService      github;
    @MockBean TagGeneratorService tagGen;
    @MockBean JenkinsService      jenkins;
    @MockBean LogService          logSvc;

    @Test
    void createTag_returns200_onSuccess() throws Exception {
        when(github.getBranchSha("my-repo", "env/staging")).thenReturn("deadbeef12345");
        when(github.createTagAndPreRelease("my-repo", "env-stag-20260519-my-repo-001",
                "deadbeef12345", "PROJ-1"))
            .thenReturn(new TagResult("https://github.com/org/repo/releases/tag/v1"));

        mvc.perform(post("/api/tag")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "repo",         "my-repo",
                    "tagName",      "env-stag-20260519-my-repo-001",
                    "targetBranch", "env/staging",
                    "ticket",       "PROJ-1"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.tagName").value("env-stag-20260519-my-repo-001"))
            .andExpect(jsonPath("$.sha").value("deadbeef12345"))
            .andExpect(jsonPath("$.releaseUrl").value("https://github.com/org/repo/releases/tag/v1"));
    }

    @Test
    void createTag_returns400_whenTagNameInvalid() throws Exception {
        mvc.perform(post("/api/tag")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "repo",         "my-repo",
                    "tagName",      "tag with spaces!",
                    "targetBranch", "env/staging"
                ))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createTag_returns400_whenRepoEmpty() throws Exception {
        mvc.perform(post("/api/tag")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "repo",         "",
                    "tagName",      "v1.0.0",
                    "targetBranch", "main"
                ))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void nextTag_returns200WithComputedTag_whenReleaseExists() throws Exception {
        when(github.getLatestTag("my-repo")).thenReturn(Optional.of("v1.0.0rc1"));
        when(tagGen.computeNextTag("v1.0.0rc1")).thenReturn("v1.0.0rc2");

        mvc.perform(get("/api/tag/next").param("repo", "my-repo"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tagName").value("v1.0.0rc2"))
            .andExpect(jsonPath("$.previousTag").value("v1.0.0rc1"))
            .andExpect(jsonPath("$.lastDeployedTag").doesNotExist());
    }

    @Test
    void nextTag_returnsDefault_whenNoReleasesExist() throws Exception {
        when(github.getLatestTag("new-repo")).thenReturn(Optional.empty());

        mvc.perform(get("/api/tag/next").param("repo", "new-repo"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tagName").value("v1.0.0rc1"))
            .andExpect(jsonPath("$.lastDeployedTag").doesNotExist());
    }

    @Test
    void nextTag_includesLastDeployedTag_whenJenkinsJobProvided() throws Exception {
        when(github.getLatestTag("my-repo")).thenReturn(Optional.of("v1.0.0rc2"));
        when(tagGen.computeNextTag("v1.0.0rc2")).thenReturn("v1.0.0rc3");
        when(jenkins.getLastDeployedBranch("team/staging/my-service"))
            .thenReturn(Optional.of("v1.0.0rc1"));

        mvc.perform(get("/api/tag/next")
                .param("repo", "my-repo")
                .param("jenkinsJob", "team/staging/my-service"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tagName").value("v1.0.0rc3"))
            .andExpect(jsonPath("$.previousTag").value("v1.0.0rc2"))
            .andExpect(jsonPath("$.lastDeployedTag").value("v1.0.0rc1"));
    }

    @Test
    void nextTag_lastDeployedTagIsNull_whenJenkinsJobHasNoSuccessfulBuild() throws Exception {
        when(github.getLatestTag("my-repo")).thenReturn(Optional.of("v1.0.0rc2"));
        when(tagGen.computeNextTag("v1.0.0rc2")).thenReturn("v1.0.0rc3");
        when(jenkins.getLastDeployedBranch("team/staging/my-service"))
            .thenReturn(Optional.empty());

        mvc.perform(get("/api/tag/next")
                .param("repo", "my-repo")
                .param("jenkinsJob", "team/staging/my-service"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lastDeployedTag").doesNotExist());
    }

    @Test
    void nextTag_returns400_whenRepoMissing() throws Exception {
        mvc.perform(get("/api/tag/next"))
            .andExpect(status().isBadRequest());
    }
}
