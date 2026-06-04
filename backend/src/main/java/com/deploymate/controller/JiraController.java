package com.deploymate.controller;

import com.deploymate.dto.JiraCommentRequest;
import com.deploymate.service.JiraService;
import com.deploymate.service.LogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/jira")
@RequiredArgsConstructor
public class JiraController {

    private final JiraService jiraService;
    private final LogService  deploymentLogger;

    @PostMapping("/comment")
    public ResponseEntity<Map<String, Object>> postIssueComment(
        @Valid @RequestBody JiraCommentRequest request
    ) {
        jiraService.addComment(request.issueKey(), request.text());
        deploymentLogger.info("SYSTEM", "jira", "Comment posted to " + request.issueKey());
        return ResponseEntity.ok(Map.of("success", true, "message", "Comment posted"));
    }
}
