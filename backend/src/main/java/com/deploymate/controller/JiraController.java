package com.deploymate.controller;

import com.deploymate.dto.JiraCommentRequest;
import com.deploymate.service.JiraService;
import com.deploymate.service.LogService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/jira")
public class JiraController {

    private final JiraService jira;
    private final LogService  logSvc;

    public JiraController(JiraService jira, LogService logSvc) {
        this.jira   = jira;
        this.logSvc = logSvc;
    }

    @PostMapping("/comment")
    public ResponseEntity<Map<String, Object>> comment(
        @Valid @RequestBody JiraCommentRequest req
    ) {
        jira.addComment(req.issueKey(), req.text());
        logSvc.info("SYSTEM", "jira", "Comment posted to " + req.issueKey());
        return ResponseEntity.ok(Map.of("success", true, "message", "Comment posted"));
    }
}
