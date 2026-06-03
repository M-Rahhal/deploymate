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

    private final OkHttpClient client;
    private final ObjectMapper mapper;
    private final AppProperties props;


    private String basicAuth() {
        String creds = props.getJenkins().user() + ":" + props.getJenkins().token();
        return "Basic " + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
    }

    private Request.Builder authed() {
        return new Request.Builder().header("Authorization", basicAuth());
    }

    private String jenkinsBase() {
        return props.getJenkins().url();
    }

    private record Crumb(String field, String value) {}

    /**
     * Fetches CSRF crumb — required before every POST to Jenkins.
     */
    private Crumb getCrumb() {
        Request req = authed()
            .url(jenkinsBase() + "/crumbIssuer/api/json")
            .build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new DeployException(
                    "Failed to get Jenkins crumb: HTTP " + resp.code(), ErrorCode.AUTH_FAILED);
            }
            JsonNode node = mapper.readTree(resp.body().string());
            return new Crumb(
                node.path("crumbRequestField").asText(),
                node.path("crumb").asText()
            );
        } catch (DeployException e) {
            throw e;
        } catch (IOException e) {
            throw new DeployException("Jenkins crumb network error: " + e.getMessage(), ErrorCode.NETWORK);
        }
    }

    /**
     * Triggers a build with the "git_branch" parameter.
     * For SDKs: gitBranchValue = "origin/{targetBranch}".
     * For SERVICEs with a tag: gitBranchValue = tagName.
     * Returns the queue item URL from the Location header.
     */
    public String triggerBuild(String jobPath, String gitBranchValue) {
        Crumb crumb = getCrumb();
        // Convert forward-slash job path into Jenkins URL format: a/b → /job/a/job/b
        String urlPath = "/job/" + jobPath.replace("/", "/job/");
        String url = jenkinsBase() + urlPath + "/buildWithParameters?git_branch="
                     + URLEncoder.encode(gitBranchValue, StandardCharsets.UTF_8);

        Request req = authed()
            .url(url)
            .header(crumb.field(), crumb.value())
            .post(RequestBody.create(new byte[0]))
            .build();

        try (Response resp = client.newCall(req).execute()) {
            if (resp.code() != 201) {
                String body = resp.body() != null ? resp.body().string() : "";
                throw new DeployException(
                    "Jenkins trigger failed: HTTP " + resp.code() + " — " + body, ErrorCode.NETWORK);
            }
            String location = resp.header("Location");
            if (location == null || location.isBlank()) {
                throw new DeployException(
                    "Jenkins did not return a Location header for the queue item", ErrorCode.NETWORK);
            }
            return location;
        } catch (DeployException e) {
            throw e;
        } catch (IOException e) {
            throw new DeployException("Jenkins trigger network error: " + e.getMessage(), ErrorCode.NETWORK);
        }
    }

    /**
     * Returns the git_branch parameter value from the last successful build of the given job.
     * Returns Optional.empty() if the job has no successful builds, the job path doesn't exist,
     * or the git_branch parameter isn't present. Never throws — callers treat this as advisory.
     */
    public Optional<String> getLastDeployedBranch(String jobPath) {
        String urlPath = "/job/" + jobPath.replace("/", "/job/");
        String url = jenkinsBase() + urlPath
                     + "/lastSuccessfulBuild/api/json?tree=actions[parameters[name,value]]";

        Request req = authed().url(url).build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) return Optional.empty();
            JsonNode root = mapper.readTree(resp.body().string());
            JsonNode actions = root.path("actions");
            if (!actions.isArray()) return Optional.empty();
            for (JsonNode action : actions) {
                JsonNode params = action.path("parameters");
                if (!params.isArray()) continue;
                for (JsonNode param : params) {
                    if ("git_branch".equals(param.path("name").asText(null))) {
                        String value = param.path("value").asText(null);
                        if (value != null && !value.isBlank()) return Optional.of(value);
                    }
                }
            }
            return Optional.empty();
        } catch (IOException e) {
            log.warn("getLastDeployedBranch failed for {}: {}", jobPath, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Polls the queue item. Returns null if still queued, or the build URL once assigned.
     */
    public String pollQueueItem(String queueItemUrl) {
        Request req = authed().url(queueItemUrl + "api/json").build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) return null;
            JsonNode node = mapper.readTree(resp.body().string());
            JsonNode url = node.path("executable").path("url");
            return url.isMissingNode() || url.isNull() ? null : url.asText(null);
        } catch (IOException e) {
            log.warn("Poll queue item error (non-fatal): {}", e.getMessage());
            return null;
        }
    }

    public record BuildStatus(String result, int number) {}

    /**
     * Polls the build. Returns result=null while still running, or the Jenkins result string when done.
     */
    public BuildStatus pollBuildStatus(String buildUrl) {
        Request req = authed().url(buildUrl + "api/json").build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new DeployException(
                    "Build status poll failed: HTTP " + resp.code(), ErrorCode.NETWORK);
            }
            JsonNode node = mapper.readTree(resp.body().string());
            String result = node.path("result").isNull() ? null : node.path("result").asText(null);
            int number    = node.path("number").asInt(0);
            return new BuildStatus(result, number);
        } catch (DeployException e) {
            throw e;
        } catch (IOException e) {
            throw new DeployException("Build status network error: " + e.getMessage(), ErrorCode.NETWORK);
        }
    }

    public record LogFragment(String text, int nextStart, boolean hasMore) {}

    /**
     * Fetches progressive build log starting at 'start' offset.
     */
    public LogFragment getBuildLog(String buildUrl, int start) {
        Request req = authed()
            .url(buildUrl + "logText/progressiveText?start=" + start)
            .build();
        try (Response resp = client.newCall(req).execute()) {
            String text    = resp.body() != null ? resp.body().string() : "";
            int nextStart  = Integer.parseInt(resp.header("X-Text-Size", "0"));
            boolean hasMore = "true".equalsIgnoreCase(resp.header("X-More-Data", "false"));
            return new LogFragment(text, nextStart, hasMore);
        } catch (IOException e) {
            log.warn("Build log fetch error (non-fatal): {}", e.getMessage());
            return new LogFragment("", 0, false);
        }
    }
}
