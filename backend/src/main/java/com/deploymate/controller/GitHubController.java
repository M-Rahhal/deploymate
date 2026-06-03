package com.deploymate.controller;

import com.deploymate.service.GitHubService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/github")
@Validated
@RequiredArgsConstructor
public class GitHubController {

    private final GitHubService github;

    /** GET /api/github/branch-info?repo={repo}&branch={branch} */
    @GetMapping("/branch-info")
    public ResponseEntity<Map<String, String>> branchInfo(
        @RequestParam
        @NotBlank
        @Pattern(regexp = "^[a-zA-Z0-9_.-]{1,100}$", message = "Invalid repo name")
        String repo,
        @RequestParam
        @NotBlank
        @Pattern(regexp = "^[a-zA-Z0-9/_.-]{1,255}$", message = "Invalid branch name")
        String branch
    ) {
        GitHubService.CommitInfo info = github.getBranchCommitInfo(repo, branch);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("sha",         info.sha());
        body.put("shortSha",    info.shortSha());
        body.put("authorLogin", info.authorLogin() != null ? info.authorLogin() : "");
        body.put("authorName",  info.authorName());
        body.put("message",     info.message());
        body.put("committedAt", info.committedAt());
        return ResponseEntity.ok(body);
    }
}
