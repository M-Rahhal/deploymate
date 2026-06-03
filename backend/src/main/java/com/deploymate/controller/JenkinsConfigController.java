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

    private final JenkinsConfigService svc;

    /** GET /api/jenkins/categories → ["cross-products", "platform", ...] */
    @GetMapping("/categories")
    public List<String> getCategories() {
        return svc.getCategories();
    }

    /** POST /api/jenkins/categories  body: {"name":"cross-products"} */
    @PostMapping("/categories")
    public ResponseEntity<Map<String, String>> saveCategory(
        @RequestBody @Validated CategoryBody body
    ) {
        String saved = svc.saveCategory(body.name());
        return ResponseEntity.ok(Map.of("name", saved));
    }

    /** GET /api/jenkins/service-names?category=cross-products → ["payment-service", ...] */
    @GetMapping("/service-names")
    public List<String> getServiceNames(
        @RequestParam @NotBlank @Size(max = 100) String category
    ) {
        return svc.getServiceNames(category);
    }

    /** POST /api/jenkins/service-names  body: {"category":"cross-products","name":"payment-service"} */
    @PostMapping("/service-names")
    public ResponseEntity<Void> saveServiceName(@RequestBody @Validated ServiceNameBody body) {
        svc.saveServiceName(body.category(), body.name());
        return ResponseEntity.ok().build();
    }

    // ─── Request body records ──────────────────────────────────────────────────

    record CategoryBody(
        @NotBlank
        @Size(max = 100)
        @Pattern(regexp = "^[\\w][\\w ./-]{0,98}[\\w]$|^[\\w]$")
        String name
    ) {}

    record ServiceNameBody(
        @NotBlank @Size(max = 100) String category,
        @NotBlank @Size(max = 200)
        @Pattern(regexp = "^[a-zA-Z0-9][a-zA-Z0-9._-]{0,198}[a-zA-Z0-9]$|^[a-zA-Z0-9]$")
        String name
    ) {}
}
