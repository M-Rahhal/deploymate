package com.deploymate.controller;

import com.deploymate.service.GitHubService;
import com.deploymate.service.GitHubService.MergeResult;
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

@WebMvcTest(MergeController.class)
@ActiveProfiles("test")
class MergeControllerTest {

    @Autowired MockMvc        mvc;
    @Autowired ObjectMapper   mapper;

    @MockBean GitHubService github;
    @MockBean LogService    logSvc;

    private String json(Object obj) throws Exception { return mapper.writeValueAsString(obj); }

    private Map<String, String> validBody() {
        return Map.of(
            "repo",         "my-repo",
            "sourceBranch", "PROJ-123",
            "targetBranch", "env/staging",
            "ticket",       "PROJ-123"
        );
    }

    @Test
    void merge_returns200_onSuccess() throws Exception {
        when(github.verifyBranch("my-repo", "PROJ-123")).thenReturn(true);
        when(github.mergeBranch("my-repo", "PROJ-123", "env/staging", "PROJ-123"))
            .thenReturn(new MergeResult(true, false, "abc1234567890"));

        mvc.perform(post("/api/merge")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(validBody())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.conflict").value(false))
            .andExpect(jsonPath("$.sha").value("abc1234567890"));
    }

    @Test
    void merge_returns409_onConflict() throws Exception {
        when(github.verifyBranch(any(), any())).thenReturn(true);
        when(github.mergeBranch(any(), any(), any(), any()))
            .thenReturn(new MergeResult(false, true, null));

        mvc.perform(post("/api/merge")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(validBody())))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.conflict").value(true));
    }

    @Test
    void merge_returns400_whenBranchNotFound() throws Exception {
        when(github.verifyBranch(any(), any())).thenReturn(false);

        mvc.perform(post("/api/merge")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(validBody())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void merge_returns400_whenRepoIsEmpty() throws Exception {
        var body = Map.of(
            "repo",         "",
            "sourceBranch", "PROJ-1",
            "targetBranch", "main"
        );

        mvc.perform(post("/api/merge")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_INPUT"));
    }

    @Test
    void merge_returns400_whenRepoHasPathTraversal() throws Exception {
        var body = Map.of(
            "repo",         "../etc/passwd",
            "sourceBranch", "PROJ-1",
            "targetBranch", "main"
        );

        mvc.perform(post("/api/merge")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void merge_returns400_whenBodyIsMissing() throws Exception {
        mvc.perform(post("/api/merge")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest());
    }

    @Test
    void merge_returns400_whenTicketFormatInvalid() throws Exception {
        var body = Map.of(
            "repo",         "repo",
            "sourceBranch", "branch",
            "targetBranch", "main",
            "ticket",       "not-a-ticket"
        );

        mvc.perform(post("/api/merge")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body)))
            .andExpect(status().isBadRequest());
    }
}
