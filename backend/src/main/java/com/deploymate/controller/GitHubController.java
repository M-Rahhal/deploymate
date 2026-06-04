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

    private final GitHubService gitHubService;

    @GetMapping("/branch-info")
    public ResponseEntity<Map<String, String>> getBranchCommitInfo(
        @RequestParam
        @NotBlank
        @Pattern(regexp = "^[a-zA-Z0-9_.-]{1,100}$", message = "Invalid repo name")
        String repo,
        @RequestParam
        @NotBlank
        @Pattern(regexp = "^[a-zA-Z0-9/_.-]{1,255}$", message = "Invalid branch name")
        String branch
    ) {
        GitHubService.CommitInfo commitInfo = gitHubService.getBranchCommitInfo(repo, branch);
        Map<String, String> responseBody = new LinkedHashMap<>();
        responseBody.put("sha",         commitInfo.sha());
        responseBody.put("shortSha",    commitInfo.shortSha());
        responseBody.put("authorLogin", commitInfo.authorLogin() != null ? commitInfo.authorLogin() : "");
        responseBody.put("authorName",  commitInfo.authorName());
        responseBody.put("message",     commitInfo.message());
        responseBody.put("committedAt", commitInfo.committedAt());
        return ResponseEntity.ok(responseBody);
    }
}
