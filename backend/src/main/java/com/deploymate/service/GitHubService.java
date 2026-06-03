package com.deploymate.service;

import com.deploymate.config.AppProperties;
import com.deploymate.model.DeployException;
import com.deploymate.model.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubService {

    private static final String BASE = "https://api.github.com";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final ObjectMapper mapper;
    private final AppProperties props;


    private Request.Builder authed() {
        return new Request.Builder()
            .header("Authorization",        "Bearer " + props.getGithub().token())
            .header("Accept",               "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28");
    }

    private String repoUrl(String repo) {
        return BASE + "/repos/" + props.getGithub().org() + "/" + repo;
    }

    /**
     * Returns true if the branch exists, false if 404, throws DeployException on other errors.
     */
    public boolean verifyBranch(String repo, String branch) {
        Request req = authed()
            .url(repoUrl(repo) + "/git/refs/heads/" + branch)
            .build();
        try (Response resp = client.newCall(req).execute()) {
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

        Request req = authed()
            .url(repoUrl(repo) + "/merges")
            .post(RequestBody.create(body.toString(), JSON))
            .build();

        try (Response resp = client.newCall(req).execute()) {
            if (resp.code() == 409) return new MergeResult(false, true, null);
            if (resp.code() == 204) return new MergeResult(true, false, null); // already up to date
            if (resp.code() == 201) {
                JsonNode node = mapper.readTree(resp.body().string());
                return new MergeResult(true, false, node.path("sha").asText(null));
            }
            String bodyStr = resp.body() != null ? resp.body().string() : "";
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
        Request req = authed()
            .url(repoUrl(repo) + "/git/refs/heads/" + branch)
            .build();
        try (Response resp = client.newCall(req).execute()) {
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

    /**
     * Returns the tag_name of the release with the highest numeric id (most recently created),
     * or empty if no releases exist. Uses the releases endpoint; the release with the max id
     * is the authoritative "latest" regardless of page ordering.
     */
    public Optional<String> getLatestTag(String repo) {
        Request req = authed()
            .url(repoUrl(repo) + "/releases?per_page=30")
            .build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) return Optional.empty();
            String body = resp.body() != null ? resp.body().string() : "[]";
            JsonNode arr = mapper.readTree(body);
            if (!arr.isArray() || arr.isEmpty()) return Optional.empty();

            JsonNode latest = null;
            long maxId = -1;
            for (JsonNode release : arr) {
                long id = release.path("id").asLong(-1);
                if (id > maxId) {
                    maxId = id;
                    latest = release;
                }
            }
            if (latest == null) return Optional.empty();
            String tagName = latest.path("tag_name").asText(null);
            return Optional.ofNullable(tagName).filter(t -> !t.isBlank());
        } catch (IOException e) {
            log.warn("Could not fetch latest tag for {}: {}", repo, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Returns true if a tag ref already exists for the given tag name.
     */
    public boolean tagExists(String repo, String tagName) {
        Request req = authed()
            .url(repoUrl(repo) + "/git/refs/tags/" + tagName)
            .build();
        try (Response resp = client.newCall(req).execute()) {
            return resp.code() == 200;
        } catch (IOException e) {
            log.warn("Could not check tag existence for {}/{}: {}", repo, tagName, e.getMessage());
            return false;
        }
    }

    public record TagResult(String releaseUrl) {}

    public record CommitInfo(
        String sha,
        String shortSha,
        String authorLogin,
        String authorName,
        String message,
        String committedAt
    ) {}

    /**
     * Returns the latest commit info for a branch (author, SHA, message, date).
     */
    public CommitInfo getBranchCommitInfo(String repo, String branch) {
        Request req = authed()
            .url(repoUrl(repo) + "/commits/" + branch)
            .build();
        try (Response resp = client.newCall(req).execute()) {
            if (resp.code() == 404) {
                throw new DeployException("Branch not found: " + branch, ErrorCode.NOT_FOUND, repo);
            }
            if (!resp.isSuccessful()) {
                throw new DeployException(
                    "Could not fetch commit info: HTTP " + resp.code(), ErrorCode.NETWORK, repo);
            }
            JsonNode node = mapper.readTree(resp.body().string());
            String sha = node.path("sha").asText("");
            String shortSha = sha.length() >= 7 ? sha.substring(0, 7) : sha;
            String authorLogin = node.path("author").path("login").asText(null);
            String authorName = node.path("commit").path("author").path("name").asText("");
            String rawMessage = node.path("commit").path("message").asText("");
            String message = rawMessage.contains("\n")
                ? rawMessage.substring(0, rawMessage.indexOf('\n'))
                : rawMessage;
            String committedAt = node.path("commit").path("author").path("date").asText("");
            return new CommitInfo(sha, shortSha, authorLogin, authorName, message, committedAt);
        } catch (DeployException e) {
            throw e;
        } catch (IOException e) {
            throw new DeployException(
                "GitHub error fetching commit info: " + e.getMessage(), ErrorCode.NETWORK, repo);
        }
    }

    /**
     * Creates a lightweight tag ref and a GitHub pre-release for it.
     * Throws INVALID_INPUT if the tag already exists.
     */
    public TagResult createTagAndPreRelease(String repo, String tagName, String sha, String ticket) {
        if (tagExists(repo, tagName)) {
            throw new DeployException(
                "Tag \"" + tagName + "\" already exists in " + repo + ". Choose a different tag name.",
                ErrorCode.INVALID_INPUT, repo);
        }
        // Step 1: create the tag ref
        ObjectNode refBody = mapper.createObjectNode()
            .put("ref", "refs/tags/" + tagName)
            .put("sha", sha);

        Request refReq = authed()
            .url(repoUrl(repo) + "/git/refs")
            .post(RequestBody.create(refBody.toString(), JSON))
            .build();

        try (Response resp = client.newCall(refReq).execute()) {
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

        Request relReq = authed()
            .url(repoUrl(repo) + "/releases")
            .post(RequestBody.create(relBody.toString(), JSON))
            .build();

        try (Response resp = client.newCall(relReq).execute()) {
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
