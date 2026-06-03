package com.deploymate.controller;

import com.deploymate.service.JenkinsConfigService;
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

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(JenkinsConfigController.class)
@ActiveProfiles("test")
class JenkinsConfigControllerTest {

    @Autowired MockMvc      mvc;
    @Autowired ObjectMapper mapper;

    @MockBean JenkinsConfigService svc;

    @Test
    void getCategories_returnsJsonArray() throws Exception {
        when(svc.getCategories()).thenReturn(List.of("cross-products", "platform"));

        mvc.perform(get("/api/jenkins/categories"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0]").value("cross-products"))
            .andExpect(jsonPath("$[1]").value("platform"));
    }

    @Test
    void saveCategory_returns200WithName() throws Exception {
        when(svc.saveCategory("cross-products")).thenReturn("cross-products");

        mvc.perform(post("/api/jenkins/categories")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("name", "cross-products"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("cross-products"));
    }

    @Test
    void saveCategory_returns400WhenNameBlank() throws Exception {
        mvc.perform(post("/api/jenkins/categories")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("name", ""))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void getServiceNames_returnsNamesForCategory() throws Exception {
        when(svc.getServiceNames("cross-products"))
            .thenReturn(List.of("payment-eligibility-service", "auth-service"));

        mvc.perform(get("/api/jenkins/service-names").param("category", "cross-products"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0]").value("payment-eligibility-service"));
    }

    @Test
    void getServiceNames_returns400WhenCategoryMissing() throws Exception {
        mvc.perform(get("/api/jenkins/service-names"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void saveServiceName_returns200() throws Exception {
        mvc.perform(post("/api/jenkins/service-names")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(
                    Map.of("category", "cross-products", "name", "auth-service"))))
            .andExpect(status().isOk());

        verify(svc).saveServiceName("cross-products", "auth-service");
    }

    @Test
    void saveServiceName_returns400WhenBodyInvalid() throws Exception {
        mvc.perform(post("/api/jenkins/service-names")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("category", "", "name", "x"))))
            .andExpect(status().isBadRequest());
    }
}
