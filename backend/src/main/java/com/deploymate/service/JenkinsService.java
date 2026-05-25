package com.deploymate.service;

import com.deploymate.config.AppProperties;
import com.deploymate.model.DeployException;
import com.deploymate.model.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Service
public class JenkinsService {

    private static final Logger log = LoggerFactory.getLogger(JenkinsService.class);

    private final OkHttpClient client;
    private final ObjectMapper mapper;
    private final AppProperties props;

    public JenkinsService(AppProperties props) {
        this.props  = props;
        this.mapper = new ObjectMapper();
        this.client = new OkHttpClient.Builder().build();
    }

    // Package-private constructor for tests
    JenkinsService(AppProperties props, OkHttpClient client) {
        this.props  = props;
        this.mapper = new ObjectMapper();
        this.client = client;
    }

    private String basicAuth() {
        var creds = props.jenkins().user() + ":" + props.jenkins().token();
        return "Basic " + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
    }

    private Request.Builder authed() {
        return new Request.Builder().header("Authorization", basicAuth());
    }

    private String jenkinsBase() {
        return props.jenkins().url();
    }

    private record Crumb(String field, String value) {}

    /**
     * Fetches CSRF crumb — required before every POST to Jenkins.
     */
    private Crumb getCrumb() {
        var req = authed()
            .url(jenkinsBase() + "/crumbIssuer/api/json")
            .build();
        try (var resp = client.newCall(req).execute()) {
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
     * Triggers a build with parameters. Returns the queue item URL from the Location header.
     * paramType is either "BRANCH" or "TAG".
     */
    public String triggerBuild(String jobPath, String paramType, String paramValue) {
        var crumb  = getCrumb();
        // Convert forward-slash job path into Jenkins URL format: a/b → /job/a/job/b
        var urlPath = "/job/" + jobPath.replace("/", "/job/");
        var url    = jenkinsBase() + urlPath + "/buildWithParameters?"
                     + paramType + "=" + URLEncoder.encode(paramValue, StandardCharsets.UTF_8);

        var req = authed()
            .url(url)
            .header(crumb.field(), crumb.value())
            .post(RequestBody.create(new byte[0]))
            .build();

        try (var resp = client.newCall(req).execute()) {
            if (resp.code() != 201) {
                var body = resp.body() != null ? resp.body().string() : "";
                throw new DeployException(
                    "Jenkins trigger failed: HTTP " + resp.code() + " — " + body, ErrorCode.NETWORK);
            }
            var location = resp.header("Location");
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
     * Polls the queue item. Returns null if still queued, or the build URL once assigned.
     */
    public String pollQueueItem(String queueItemUrl) {
        var req = authed().url(queueItemUrl + "api/json").build();
        try (var resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) return null;
            JsonNode node = mapper.readTree(resp.body().string());
            var url = node.path("executable").path("url");
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
        var req = authed().url(buildUrl + "api/json").build();
        try (var resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new DeployException(
                    "Build status poll failed: HTTP " + resp.code(), ErrorCode.NETWORK);
            }
            JsonNode node   = mapper.readTree(resp.body().string());
            var result      = node.path("result").isNull() ? null : node.path("result").asText(null);
            var number      = node.path("number").asInt(0);
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
        var req = authed()
            .url(buildUrl + "logText/progressiveText?start=" + start)
            .build();
        try (var resp = client.newCall(req).execute()) {
            var text     = resp.body() != null ? resp.body().string() : "";
            var nextSize = Integer.parseInt(resp.header("X-Text-Size", "0"));
            var hasMore  = "true".equalsIgnoreCase(resp.header("X-More-Data", "false"));
            return new LogFragment(text, nextSize, hasMore);
        } catch (IOException e) {
            log.warn("Build log fetch error (non-fatal): {}", e.getMessage());
            return new LogFragment("", 0, false);
        }
    }

}

