package com.deploymate.controller;

import com.deploymate.service.JiraService;
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

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(JiraController.class)
@ActiveProfiles("test")
class JiraControllerTest {

    @Autowired MockMvc      mvc;
    @Autowired ObjectMapper mapper;

    @MockBean JiraService jira;
    @MockBean LogService  logSvc;

    @Test
    void comment_returns200_onSuccess() throws Exception {
        doNothing().when(jira).addComment("PROJ-123", "Deployment done!");

        mvc.perform(post("/api/jira/comment")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "issueKey", "PROJ-123",
                    "text",     "Deployment done!"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        verify(jira).addComment("PROJ-123", "Deployment done!");
    }

    @Test
    void comment_returns400_whenIssueKeyInvalid() throws Exception {
        mvc.perform(post("/api/jira/comment")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "issueKey", "not-valid",
                    "text",     "Hello"
                ))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void comment_returns400_whenIssueKeyEmpty() throws Exception {
        mvc.perform(post("/api/jira/comment")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "issueKey", "",
                    "text",     "Hello"
                ))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void comment_returns400_whenTextEmpty() throws Exception {
        mvc.perform(post("/api/jira/comment")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "issueKey", "PROJ-1",
                    "text",     ""
                ))))
            .andExpect(status().isBadRequest());
    }
}
