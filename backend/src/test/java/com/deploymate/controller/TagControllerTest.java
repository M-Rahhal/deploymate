package com.deploymate.controller;

import com.deploymate.service.GitHubService;
import com.deploymate.service.GitHubService.TagResult;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TagController.class)
@ActiveProfiles("test")
class TagControllerTest {

    @Autowired MockMvc      mvc;
    @Autowired ObjectMapper mapper;

    @MockBean GitHubService github;
    @MockBean LogService    logSvc;

    @Test
    void createTag_returns200_onSuccess() throws Exception {
        when(github.getBranchSha("my-repo", "env/staging")).thenReturn("deadbeef12345");
        when(github.createTagAndPreRelease("my-repo", "env-stag-20260519-my-repo-001",
                "deadbeef12345", "PROJ-1"))
            .thenReturn(new TagResult("https://github.com/org/repo/releases/tag/v1"));

        mvc.perform(post("/api/tag")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "org",          "my-org",
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
                    "org",          "my-org",
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
                    "org",          "my-org",
                    "repo",         "",
                    "tagName",      "v1.0.0",
                    "targetBranch", "main"
                ))))
            .andExpect(status().isBadRequest());
    }
}
