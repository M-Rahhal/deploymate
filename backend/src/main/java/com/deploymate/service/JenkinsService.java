package com.deploymate.service;

import com.deploymate.config.AppProperties;
import com.deploymate.model.DeployException;
import com.deploymate.model.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class JenkinsService {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;


    private String buildBasicAuthorizationHeader() {
        String credentials = appProperties.getJenkins().user() + ":" + appProperties.getJenkins().token();
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private Request.Builder buildAuthenticatedRequestBuilder() {
        return new Request.Builder().header("Authorization", buildBasicAuthorizationHeader());
    }

    private String getJenkinsBaseUrl() {
        return appProperties.getJenkins().url();
    }

    private record CsrfCrumb(String headerField, String headerValue) {}

    private CsrfCrumb fetchCsrfCrumb() {
        Request request = buildAuthenticatedRequestBuilder()
            .url(getJenkinsBaseUrl() + "/crumbIssuer/api/json")
            .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new DeployException(
                    "Failed to get Jenkins CSRF crumb: HTTP " + response.code(), ErrorCode.AUTH_FAILED);
            }
            JsonNode crumbNode = objectMapper.readTree(response.body().string());
            return new CsrfCrumb(
                crumbNode.path("crumbRequestField").asText(),
                crumbNode.path("crumb").asText()
            );
        } catch (DeployException e) {
            throw e;
        } catch (IOException e) {
            throw new DeployException("Jenkins network error fetching CSRF crumb: " + e.getMessage(), ErrorCode.NETWORK);
        }
    }

    /**
     * Triggers a Jenkins build with the "git_branch" parameter.
     * For SDK rows: gitBranchValue = "origin/{targetBranch}".
     * For SERVICE rows: gitBranchValue = tagName.
     * Returns the queue item URL from the Location header.
     */
    public String triggerBuild(String jobPath, String gitBranchValue) {
        CsrfCrumb csrfCrumb = fetchCsrfCrumb();
        String jobUrlPath = "/job/" + jobPath.replace("/", "/job/");
        String triggerUrl = getJenkinsBaseUrl() + jobUrlPath + "/buildWithParameters?git_branch="
                            + URLEncoder.encode(gitBranchValue, StandardCharsets.UTF_8);

        Request request = buildAuthenticatedRequestBuilder()
            .url(triggerUrl)
            .header(csrfCrumb.headerField(), csrfCrumb.headerValue())
            .post(RequestBody.create(new byte[0]))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.code() != 201) {
                String responseBody = response.body() != null ? response.body().string() : "";
                throw new DeployException(
                    "Jenkins build trigger failed: HTTP " + response.code() + " — " + responseBody, ErrorCode.NETWORK);
            }
            String queueItemUrl = response.header("Location");
            if (queueItemUrl == null || queueItemUrl.isBlank()) {
                throw new DeployException(
                    "Jenkins did not return a Location header for the queue item", ErrorCode.NETWORK);
            }
            return queueItemUrl;
        } catch (DeployException e) {
            throw e;
        } catch (IOException e) {
            throw new DeployException("Jenkins network error during build trigger: " + e.getMessage(), ErrorCode.NETWORK);
        }
    }

    /**
     * Returns the git_branch parameter value from the last successful build of the given job.
     * Returns Optional.empty() if the job has no successful builds, the job path does not exist,
     * or the git_branch parameter is not present. Never throws — callers treat this as advisory.
     */
    public Optional<String> getLastDeployedBranch(String jobPath) {
        String jobUrlPath = "/job/" + jobPath.replace("/", "/job/");
        String statusUrl  = getJenkinsBaseUrl() + jobUrlPath
                            + "/lastSuccessfulBuild/api/json?tree=actions[parameters[name,value]]";

        Request request = buildAuthenticatedRequestBuilder().url(statusUrl).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) return Optional.empty();
            JsonNode rootNode   = objectMapper.readTree(response.body().string());
            JsonNode actionsArray = rootNode.path("actions");
            if (!actionsArray.isArray()) return Optional.empty();
            for (JsonNode action : actionsArray) {
                JsonNode parametersArray = action.path("parameters");
                if (!parametersArray.isArray()) continue;
                for (JsonNode parameter : parametersArray) {
                    if ("git_branch".equals(parameter.path("name").asText(null))) {
                        String value = parameter.path("value").asText(null);
                        if (value != null && !value.isBlank()) return Optional.of(value);
                    }
                }
            }
            return Optional.empty();
        } catch (IOException e) {
            log.warn("Could not fetch last deployed branch for job {}: {}", jobPath, e.getMessage());
            return Optional.empty();
        }
    }

    public String pollQueueItem(String queueItemUrl) {
        Request request = buildAuthenticatedRequestBuilder().url(queueItemUrl + "api/json").build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) return null;
            JsonNode queueNode  = objectMapper.readTree(response.body().string());
            JsonNode executableUrl = queueNode.path("executable").path("url");
            return executableUrl.isMissingNode() || executableUrl.isNull() ? null : executableUrl.asText(null);
        } catch (IOException e) {
            log.warn("Queue item poll error (non-fatal): {}", e.getMessage());
            return null;
        }
    }

    public record BuildStatus(String result, int number) {}

    public BuildStatus pollBuildStatus(String buildUrl) {
        Request request = buildAuthenticatedRequestBuilder().url(buildUrl + "api/json").build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new DeployException(
                    "Build status poll failed: HTTP " + response.code(), ErrorCode.NETWORK);
            }
            JsonNode buildNode = objectMapper.readTree(response.body().string());
            String result      = buildNode.path("result").isNull() ? null : buildNode.path("result").asText(null);
            int buildNumber    = buildNode.path("number").asInt(0);
            return new BuildStatus(result, buildNumber);
        } catch (DeployException e) {
            throw e;
        } catch (IOException e) {
            throw new DeployException("Build status network error: " + e.getMessage(), ErrorCode.NETWORK);
        }
    }

    public record LogFragment(String text, int nextStart, boolean hasMore) {}

    public LogFragment getBuildLog(String buildUrl, int startOffset) {
        Request request = buildAuthenticatedRequestBuilder()
            .url(buildUrl + "logText/progressiveText?start=" + startOffset)
            .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String logText     = response.body() != null ? response.body().string() : "";
            int nextStart      = Integer.parseInt(response.header("X-Text-Size", "0"));
            boolean hasMore    = "true".equalsIgnoreCase(response.header("X-More-Data", "false"));
            return new LogFragment(logText, nextStart, hasMore);
        } catch (IOException e) {
            log.warn("Build log fetch error (non-fatal): {}", e.getMessage());
            return new LogFragment("", 0, false);
        }
    }
}
