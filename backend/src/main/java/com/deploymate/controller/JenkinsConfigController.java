package com.deploymate.controller;

import com.deploymate.service.JenkinsConfigService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/jenkins")
@Validated
@RequiredArgsConstructor
public class JenkinsConfigController {

    private final JenkinsConfigService jenkinsConfigService;

    @GetMapping("/categories")
    public List<String> getCategories() {
        return jenkinsConfigService.getCategories();
    }

    @PostMapping("/categories")
    public ResponseEntity<Map<String, String>> saveCategory(
        @RequestBody @Validated SaveCategoryRequest requestBody
    ) {
        String savedName = jenkinsConfigService.saveCategory(requestBody.name());
        return ResponseEntity.ok(Map.of("name", savedName));
    }

    @GetMapping("/service-names")
    public List<String> getServiceNames(
        @RequestParam @NotBlank @Size(max = 100) String category
    ) {
        return jenkinsConfigService.getServiceNames(category);
    }

    @PostMapping("/service-names")
    public ResponseEntity<Void> saveServiceName(
        @RequestBody @Validated SaveJenkinsServiceNameRequest requestBody
    ) {
        jenkinsConfigService.saveServiceName(requestBody.category(), requestBody.name());
        return ResponseEntity.ok().build();
    }

    // ─── Request body records ──────────────────────────────────────────────────

    record SaveCategoryRequest(
        @NotBlank
        @Size(max = 100)
        @Pattern(regexp = "^[\\w][\\w ./-]{0,98}[\\w]$|^[\\w]$")
        String name
    ) {}

    record SaveJenkinsServiceNameRequest(
        @NotBlank @Size(max = 100) String category,
        @NotBlank @Size(max = 200)
        @Pattern(regexp = "^[a-zA-Z0-9][a-zA-Z0-9._-]{0,198}[a-zA-Z0-9]$|^[a-zA-Z0-9]$")
        String name
    ) {}
}
