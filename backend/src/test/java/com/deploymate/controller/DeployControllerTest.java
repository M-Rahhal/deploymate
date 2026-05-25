package com.deploymate.controller;

import com.deploymate.dto.ServiceRowDto;
import com.deploymate.dto.ServiceRowDto.ServiceType;
import com.deploymate.service.LogService;
import com.deploymate.service.OrchestratorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DeployController.class)
@ActiveProfiles("test")
class DeployControllerTest {

    @Autowired MockMvc      mvc;
    @Autowired ObjectMapper mapper;

    @MockBean OrchestratorService orchestrator;
    @MockBean LogService          logSvc;

    private ServiceRowDto validRow() {
        return new ServiceRowDto(
            "row-1", "My SDK", "my-sdk", ServiceType.SDK, 1,
            "PROJ-1", "env/staging", "backend/sdk-deploy", "", false, false
        );
    }

    @Test
    void deployAll_returns202_immediately() throws Exception {
        mvc.perform(post("/api/deploy/all")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "ticket", "PROJ-123",
                    "rows",   List.of(validRow())
                ))))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.message").value("Deploy All started"));
    }

    @Test
    void deployAll_returns400_whenRowsEmpty() throws Exception {
        mvc.perform(post("/api/deploy/all")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "ticket", "PROJ-1",
                    "rows",   List.of()
                ))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void deployAll_returns400_whenRowRepoIsInvalid() throws Exception {
        var badRow = new ServiceRowDto(
            "r1", "name", "../../etc/passwd", ServiceType.SDK, 1,
            "PROJ-1", "env/staging", "job", "", false, false
        );

        mvc.perform(post("/api/deploy/all")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "ticket", "PROJ-1",
                    "rows",   List.of(badRow)
                ))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void deployAll_returns400_whenTicketFormatInvalid() throws Exception {
        mvc.perform(post("/api/deploy/all")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "ticket", "invalid-ticket-format",
                    "rows",   List.of(validRow())
                ))))
            .andExpect(status().isBadRequest());
    }
}
