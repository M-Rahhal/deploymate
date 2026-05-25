package com.deploymate.service;

import com.deploymate.config.AppProperties;
import com.deploymate.model.DeployException;
import com.deploymate.model.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class GitHubService {

    private static final Logger log = LoggerFactory.getLogger(GitHubService.class);
    private static final String BASE = "https://api.github.com";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final ObjectMapper mapper;
    private final AppProperties props;

    public GitHubService(AppProperties props) {
        this.props  = props;
        this.mapper = new ObjectMapper();
        this.client = new OkHttpClient.Builder().build();
    }

    // Package-private constructor for tests (allows injecting a MockWebServer-backed client)
    GitHubService(AppProperties props, OkHttpClient client) {
        this.props  = props;
        this.mapper = new ObjectMapper();
        this.client = client;
    }

    private Request.Builder authed() {
        return new Request.Builder()
            .header("Authorization",        "Bearer " + props.github().token())
            .header("Accept",               "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28");
    }

    private String repoUrl(String repo) {
        return BASE + "/repos/" + props.github().org() + "/" + repo;
    }

    /**
     * Returns true if the branch exists, false if 404, throws DeployException on other errors.
     */
    public boolean verifyBranch(String repo, String branch) {
        var req = authed()
            .url(repoUrl(repo) + "/git/refs/heads/" + branch)
            .build();
        try (var resp = client.newCall(req).execute()) {
            if (resp.code() == 404) return false;
            if (!resp.isSuccessful()) {
                throw new DeployException(
                    "GitHub branch check failed: HTTP " + resp.code(), ErrorCode.NETWORK, repo);
            }
            return true;
        } catch (DeployException e) {
            throw e;
        } catch (IOException e) {
            throw new DeployException("GitHub network error: " + e.getMessage(), ErrorCode.NETWORK, repo);
        }
    }

    public record MergeResult(boolean success, boolean conflict, String sha) {}

    /**
     * Merges sourceBranch into targetBranch.
     * Returns MergeResult — never throws on conflict (conflict=true is a normal result).
     * Throws DeployException on network or unexpected errors.
     */
    public MergeResult mergeBranch(String repo, String sourceBranch, String targetBranch, String ticket) {
        ObjectNode body = mapper.createObjectNode()
            .put("base",           targetBranch)
            .put("head",           sourceBranch)
            .put("commit_message", "chore: merge " + sourceBranch + " into " + targetBranch
                                   + (ticket != null && !ticket.isBlank() ? " for " + ticket : ""));

        var req = authed()
            .url(repoUrl(repo) + "/merges")
            .post(RequestBody.create(body.toString(), JSON))
            .build();

        try (var resp = client.newCall(req).execute()) {
            if (resp.code() == 409) return new MergeResult(false, true, null);
            if (resp.code() == 204) return new MergeResult(true, false, null); // already up to date
            if (resp.code() == 201) {
                JsonNode node = mapper.readTree(resp.body().string());
                return new MergeResult(true, false, node.path("sha").asText(null));
            }
            var bodyStr = resp.body() != null ? resp.body().string() : "";
            throw new DeployException(
                "Merge failed: HTTP " + resp.code() + " — " + bodyStr, ErrorCode.NETWORK, repo);
        } catch (DeployException e) {
            throw e;
        } catch (IOException e) {
            throw new DeployException("GitHub network error during merge: " + e.getMessage(), ErrorCode.NETWORK, repo);
        }
    }

    /**
     * Returns the HEAD SHA of the given branch.
     */
    public String getBranchSha(String repo, String branch) {
        var req = authed()
            .url(repoUrl(repo) + "/git/refs/heads/" + branch)
            .build();
        try (var resp = client.newCall(req).execute()) {
            if (resp.code() == 404) {
                throw new DeployException("Branch not found: " + branch, ErrorCode.NOT_FOUND, repo);
            }
            if (!resp.isSuccessful()) {
                throw new DeployException(
                    "Could not get branch SHA: HTTP " + resp.code(), ErrorCode.NETWORK, repo);
            }
            JsonNode node = mapper.readTree(resp.body().string());
            return node.path("object").path("sha").asText();
        } catch (DeployException e) {
            throw e;
        } catch (IOException e) {
            throw new DeployException("GitHub error getting branch SHA: " + e.getMessage(), ErrorCode.NETWORK, repo);
        }
    }

    public record TagResult(String releaseUrl) {}

    /**
     * Creates a lightweight tag ref and a GitHub pre-release for it.
     */
    public TagResult createTagAndPreRelease(String repo, String tagName, String sha, String ticket) {
        // Step 1: create the tag ref
        ObjectNode refBody = mapper.createObjectNode()
            .put("ref", "refs/tags/" + tagName)
            .put("sha", sha);

        var refReq = authed()
            .url(repoUrl(repo) + "/git/refs")
            .post(RequestBody.create(refBody.toString(), JSON))
            .build();

        try (var resp = client.newCall(refReq).execute()) {
            if (!resp.isSuccessful()) {
                throw new DeployException(
                    "Tag ref creation failed: HTTP " + resp.code(), ErrorCode.NETWORK, repo);
            }
        } catch (DeployException e) {
            throw e;
        } catch (IOException e) {
            throw new DeployException("Error creating tag ref: " + e.getMessage(), ErrorCode.NETWORK, repo);
        }

        // Step 2: create a pre-release
        ObjectNode relBody = mapper.createObjectNode()
            .put("tag_name",   tagName)
            .put("name",       tagName + " (staging pre-release)")
            .put("body",       "Pre-release for staging deployment."
                               + (ticket != null && !ticket.isBlank() ? " Ticket: " + ticket : ""))
            .put("prerelease", true)
            .put("draft",      false);

        var relReq = authed()
            .url(repoUrl(repo) + "/releases")
            .post(RequestBody.create(relBody.toString(), JSON))
            .build();

        try (var resp = client.newCall(relReq).execute()) {
            if (!resp.isSuccessful()) {
                throw new DeployException(
                    "Release creation failed: HTTP " + resp.code(), ErrorCode.NETWORK, repo);
            }
            JsonNode node = mapper.readTree(resp.body().string());
            return new TagResult(node.path("html_url").asText());
        } catch (DeployException e) {
            throw e;
        } catch (IOException e) {
            throw new DeployException("Error creating release: " + e.getMessage(), ErrorCode.NETWORK, repo);
        }
    }
}
