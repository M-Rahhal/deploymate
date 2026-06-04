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

    private static final String GITHUB_API_BASE_URL = "https://api.github.com";
    private static final MediaType APPLICATION_JSON  = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;


    private Request.Builder buildAuthenticatedRequestBuilder() {
        return new Request.Builder()
            .header("Authorization",        "Bearer " + appProperties.getGithub().token())
            .header("Accept",               "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28");
    }

    private String buildRepoApiUrl(String repositoryName) {
        return GITHUB_API_BASE_URL + "/repos/" + appProperties.getGithub().org() + "/" + repositoryName;
    }

    public boolean verifyBranch(String repositoryName, String branchName) {
        Request request = buildAuthenticatedRequestBuilder()
            .url(buildRepoApiUrl(repositoryName) + "/git/refs/heads/" + branchName)
            .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.code() == 404) return false;
            if (!response.isSuccessful()) {
                throw new DeployException(
                    "GitHub branch check failed: HTTP " + response.code(), ErrorCode.NETWORK, repositoryName);
            }
            return true;
        } catch (DeployException e) {
            throw e;
        } catch (IOException e) {
            throw new DeployException("GitHub network error: " + e.getMessage(), ErrorCode.NETWORK, repositoryName);
        }
    }

    public record MergeResult(boolean success, boolean conflict, String sha) {}

    public MergeResult mergeBranch(String repositoryName, String sourceBranch, String targetBranch, String ticket) {
        ObjectNode requestBody = objectMapper.createObjectNode()
            .put("base",           targetBranch)
            .put("head",           sourceBranch)
            .put("commit_message", "chore: merge " + sourceBranch + " into " + targetBranch
                                   + (ticket != null && !ticket.isBlank() ? " for " + ticket : ""));

        Request request = buildAuthenticatedRequestBuilder()
            .url(buildRepoApiUrl(repositoryName) + "/merges")
            .post(RequestBody.create(requestBody.toString(), APPLICATION_JSON))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.code() == 409) return new MergeResult(false, true, null);
            if (response.code() == 204) return new MergeResult(true, false, null);
            if (response.code() == 201) {
                JsonNode responseNode = objectMapper.readTree(response.body().string());
                return new MergeResult(true, false, responseNode.path("sha").asText(null));
            }
            String responseBody = response.body() != null ? response.body().string() : "";
            throw new DeployException(
                "Merge failed: HTTP " + response.code() + " — " + responseBody, ErrorCode.NETWORK, repositoryName);
        } catch (DeployException e) {
            throw e;
        } catch (IOException e) {
            throw new DeployException("GitHub network error during merge: " + e.getMessage(), ErrorCode.NETWORK, repositoryName);
        }
    }

    public String getBranchSha(String repositoryName, String branchName) {
        Request request = buildAuthenticatedRequestBuilder()
            .url(buildRepoApiUrl(repositoryName) + "/git/refs/heads/" + branchName)
            .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.code() == 404) {
                throw new DeployException("Branch not found: " + branchName, ErrorCode.NOT_FOUND, repositoryName);
            }
            if (!response.isSuccessful()) {
                throw new DeployException(
                    "Could not get branch SHA: HTTP " + response.code(), ErrorCode.NETWORK, repositoryName);
            }
            JsonNode responseNode = objectMapper.readTree(response.body().string());
            return responseNode.path("object").path("sha").asText();
        } catch (DeployException e) {
            throw e;
        } catch (IOException e) {
            throw new DeployException("GitHub error getting branch SHA: " + e.getMessage(), ErrorCode.NETWORK, repositoryName);
        }
    }

    /**
     * Returns the tag_name of the release with the highest numeric id (most recently created),
     * or empty if no releases exist. Uses the releases endpoint; the release with the max id
     * is the authoritative "latest" regardless of page ordering.
     */
    public Optional<String> getLatestTag(String repositoryName) {
        Request request = buildAuthenticatedRequestBuilder()
            .url(buildRepoApiUrl(repositoryName) + "/releases?per_page=30")
            .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) return Optional.empty();
            String responseBody = response.body() != null ? response.body().string() : "[]";
            JsonNode releasesArray = objectMapper.readTree(responseBody);
            if (!releasesArray.isArray() || releasesArray.isEmpty()) return Optional.empty();

            JsonNode releaseWithHighestId = null;
            long maxId = -1;
            for (JsonNode release : releasesArray) {
                long releaseId = release.path("id").asLong(-1);
                if (releaseId > maxId) {
                    maxId = releaseId;
                    releaseWithHighestId = release;
                }
            }
            if (releaseWithHighestId == null) return Optional.empty();
            String tagName = releaseWithHighestId.path("tag_name").asText(null);
            return Optional.ofNullable(tagName).filter(t -> !t.isBlank());
        } catch (IOException e) {
            log.warn("Could not fetch latest tag for {}: {}", repositoryName, e.getMessage());
            return Optional.empty();
        }
    }

    public boolean tagExists(String repositoryName, String tagName) {
        Request request = buildAuthenticatedRequestBuilder()
            .url(buildRepoApiUrl(repositoryName) + "/git/refs/tags/" + tagName)
            .build();
        try (Response response = httpClient.newCall(request).execute()) {
            return response.code() == 200;
        } catch (IOException e) {
            log.warn("Could not check tag existence for {}/{}: {}", repositoryName, tagName, e.getMessage());
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

    public CommitInfo getBranchCommitInfo(String repositoryName, String branchName) {
        Request request = buildAuthenticatedRequestBuilder()
            .url(buildRepoApiUrl(repositoryName) + "/commits/" + branchName)
            .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.code() == 404) {
                throw new DeployException("Branch not found: " + branchName, ErrorCode.NOT_FOUND, repositoryName);
            }
            if (!response.isSuccessful()) {
                throw new DeployException(
                    "Could not fetch commit info: HTTP " + response.code(), ErrorCode.NETWORK, repositoryName);
            }
            JsonNode commitNode = objectMapper.readTree(response.body().string());
            String sha         = commitNode.path("sha").asText("");
            String shortSha    = sha.length() >= 7 ? sha.substring(0, 7) : sha;
            String authorLogin = commitNode.path("author").path("login").asText(null);
            String authorName  = commitNode.path("commit").path("author").path("name").asText("");
            String rawMessage  = commitNode.path("commit").path("message").asText("");
            String firstLine   = rawMessage.contains("\n")
                ? rawMessage.substring(0, rawMessage.indexOf('\n'))
                : rawMessage;
            String committedAt = commitNode.path("commit").path("author").path("date").asText("");
            return new CommitInfo(sha, shortSha, authorLogin, authorName, firstLine, committedAt);
        } catch (DeployException e) {
            throw e;
        } catch (IOException e) {
            throw new DeployException(
                "GitHub error fetching commit info: " + e.getMessage(), ErrorCode.NETWORK, repositoryName);
        }
    }

    /**
     * Creates a lightweight tag ref and a GitHub pre-release for it.
     * Throws INVALID_INPUT if the tag already exists.
     */
    public TagResult createTagAndPreRelease(String repositoryName, String tagName, String commitSha, String ticket) {
        if (tagExists(repositoryName, tagName)) {
            throw new DeployException(
                "Tag \"" + tagName + "\" already exists in " + repositoryName + ". Choose a different tag name.",
                ErrorCode.INVALID_INPUT, repositoryName);
        }

        ObjectNode tagRefBody = objectMapper.createObjectNode()
            .put("ref", "refs/tags/" + tagName)
            .put("sha", commitSha);

        Request tagRefRequest = buildAuthenticatedRequestBuilder()
            .url(buildRepoApiUrl(repositoryName) + "/git/refs")
            .post(RequestBody.create(tagRefBody.toString(), APPLICATION_JSON))
            .build();

        try (Response response = httpClient.newCall(tagRefRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new DeployException(
                    "Tag ref creation failed: HTTP " + response.code(), ErrorCode.NETWORK, repositoryName);
            }
        } catch (DeployException e) {
            throw e;
        } catch (IOException e) {
            throw new DeployException("Error creating tag ref: " + e.getMessage(), ErrorCode.NETWORK, repositoryName);
        }

        ObjectNode releaseBody = objectMapper.createObjectNode()
            .put("tag_name",   tagName)
            .put("name",       tagName + " (staging pre-release)")
            .put("body",       "Pre-release for staging deployment."
                               + (ticket != null && !ticket.isBlank() ? " Ticket: " + ticket : ""))
            .put("prerelease", true)
            .put("draft",      false);

        Request releaseRequest = buildAuthenticatedRequestBuilder()
            .url(buildRepoApiUrl(repositoryName) + "/releases")
            .post(RequestBody.create(releaseBody.toString(), APPLICATION_JSON))
            .build();

        try (Response response = httpClient.newCall(releaseRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new DeployException(
                    "Release creation failed: HTTP " + response.code(), ErrorCode.NETWORK, repositoryName);
            }
            JsonNode releaseNode = objectMapper.readTree(response.body().string());
            return new TagResult(releaseNode.path("html_url").asText());
        } catch (DeployException e) {
            throw e;
        } catch (IOException e) {
            throw new DeployException("Error creating release: " + e.getMessage(), ErrorCode.NETWORK, repositoryName);
        }
    }
}
