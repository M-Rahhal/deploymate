package com.deploymate.service;

import com.deploymate.config.AppProperties;
import com.deploymate.model.DeployException;
import com.deploymate.model.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;

import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test")
class GitHubServiceTest {

    private MockWebServer server;
    private GitHubService service;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        // Build AppProperties pointing at MockWebServer
        var props = new AppProperties(
            new AppProperties.GitHub("test-token", "test-org"),
            new AppProperties.Jenkins(server.url("").toString(), "u", "t"),
            new AppProperties.Jira(server.url("").toString(), "e@e.com", "t"),
            new AppProperties.Defaults("env/staging", "env-stag")
        );

        // Patch the GitHub BASE URL via a custom OkHttpClient interceptor that rewrites
        // api.github.com → localhost:port (simplest without reflection)
        var client = new OkHttpClient.Builder()
            .addInterceptor(chain -> {
                var original = chain.request();
                var newUrl   = original.url().newBuilder()
                    .scheme("http")
                    .host(server.getHostName())
                    .port(server.getPort())
                    .build();
                return chain.proceed(original.newBuilder().url(newUrl).build());
            })
            .build();

        service = new GitHubService(client, new ObjectMapper(), props);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    // ── verifyBranch ─────────────────────────────────────────────────────────

    @Test
    void verifyBranch_returnsTrue_when200() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        assertThat(service.verifyBranch("my-repo", "main")).isTrue();

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).contains("/git/refs/heads/main");
        assertThat(req.getHeader("Authorization")).startsWith("Bearer ");
    }

    @Test
    void verifyBranch_returnsFalse_when404() {
        server.enqueue(new MockResponse().setResponseCode(404));
        assertThat(service.verifyBranch("my-repo", "non-existent")).isFalse();
    }

    @Test
    void verifyBranch_throwsDeployException_on500() {
        server.enqueue(new MockResponse().setResponseCode(500));
        assertThatThrownBy(() -> service.verifyBranch("my-repo", "main"))
            .isInstanceOf(DeployException.class)
            .extracting(e -> ((DeployException) e).getCode())
            .isEqualTo(ErrorCode.NETWORK);
    }

    // ── mergeBranch ──────────────────────────────────────────────────────────

    @Test
    void mergeBranch_returnsSuccess_when201() {
        server.enqueue(new MockResponse()
            .setResponseCode(201)
            .setBody("{\"sha\": \"abc1234567890\"}"));

        var result = service.mergeBranch("repo", "feature", "main", "PROJ-1");

        assertThat(result.success()).isTrue();
        assertThat(result.conflict()).isFalse();
        assertThat(result.sha()).isEqualTo("abc1234567890");
    }

    @Test
    void mergeBranch_returnsSuccess_when204_alreadyUpToDate() {
        server.enqueue(new MockResponse().setResponseCode(204));

        var result = service.mergeBranch("repo", "feature", "main", null);

        assertThat(result.success()).isTrue();
        assertThat(result.conflict()).isFalse();
        assertThat(result.sha()).isNull();
    }

    @Test
    void mergeBranch_returnsConflict_when409() {
        server.enqueue(new MockResponse().setResponseCode(409));

        var result = service.mergeBranch("repo", "feature", "main", "PROJ-1");

        assertThat(result.success()).isFalse();
        assertThat(result.conflict()).isTrue();
        assertThat(result.sha()).isNull();
    }

    @Test
    void mergeBranch_throwsDeployException_on500() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("Server error"));

        assertThatThrownBy(() -> service.mergeBranch("repo", "f", "main", null))
            .isInstanceOf(DeployException.class)
            .extracting(e -> ((DeployException) e).getCode())
            .isEqualTo(ErrorCode.NETWORK);
    }

    @Test
    void mergeBranch_includesTicketInCommitMessage() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(201).setBody("{\"sha\": \"aaa\"}"));

        service.mergeBranch("repo", "feature/PROJ-5", "main", "PROJ-5");

        var req  = server.takeRequest();
        var body = req.getBody().readUtf8();
        assertThat(body).contains("PROJ-5");
    }

    // ── getBranchSha ─────────────────────────────────────────────────────────

    @Test
    void getBranchSha_returnsSha_on200() {
        server.enqueue(new MockResponse()
            .setResponseCode(200)
            .setBody("{\"object\": {\"sha\": \"deadbeef\"}}"));

        assertThat(service.getBranchSha("repo", "main")).isEqualTo("deadbeef");
    }

    @Test
    void getBranchSha_throwsNotFound_on404() {
        server.enqueue(new MockResponse().setResponseCode(404));

        assertThatThrownBy(() -> service.getBranchSha("repo", "gone"))
            .isInstanceOf(DeployException.class)
            .extracting(e -> ((DeployException) e).getCode())
            .isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ── createTagAndPreRelease ────────────────────────────────────────────────

    @Test
    void createTag_createsRefThenRelease_returnsUrl() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(404)); // tagExists → 404 (tag doesn't exist)
        server.enqueue(new MockResponse().setResponseCode(201).setBody("{}")); // ref
        server.enqueue(new MockResponse()
            .setResponseCode(201)
            .setBody("{\"html_url\": \"https://github.com/org/repo/releases/tag/v1\"}")); // release

        var result = service.createTagAndPreRelease("repo", "v1.0.0", "abc123", "PROJ-1");

        assertThat(result.releaseUrl()).isEqualTo("https://github.com/org/repo/releases/tag/v1");
        assertThat(server.getRequestCount()).isEqualTo(3);

        server.takeRequest(); // tagExists check
        // Second request should be creating the ref
        var refReq = server.takeRequest();
        assertThat(refReq.getBody().readUtf8()).contains("refs/tags/v1.0.0");

        // Third request should be creating the release
        var relReq = server.takeRequest();
        assertThat(relReq.getBody().readUtf8()).contains("prerelease");
    }

    @Test
    void createTag_throwsDeployException_whenRefCreationFails() {
        server.enqueue(new MockResponse().setResponseCode(404)); // tagExists → 404 (tag doesn't exist)
        server.enqueue(new MockResponse().setResponseCode(422)); // ref creation fails

        assertThatThrownBy(() -> service.createTagAndPreRelease("repo", "bad", "sha", null))
            .isInstanceOf(DeployException.class)
            .extracting(e -> ((DeployException) e).getCode())
            .isEqualTo(ErrorCode.NETWORK);
    }

    @Test
    void createTag_throwsInvalidInput_whenTagAlreadyExists() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));  // tagExists → 200

        assertThatThrownBy(() -> service.createTagAndPreRelease("repo", "v1.0.0rc1", "sha", null))
            .isInstanceOf(DeployException.class)
            .extracting(e -> ((DeployException) e).getCode())
            .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void getLatestTag_returnsTagWithHighestId() {
        server.enqueue(new MockResponse()
            .setResponseCode(200)
            .setBody("[" +
                "{\"id\":200,\"tag_name\":\"v1.0.0rc3\",\"prerelease\":true}," +
                "{\"id\":100,\"tag_name\":\"v1.0.0rc1\",\"prerelease\":true}," +
                "{\"id\":150,\"tag_name\":\"v1.0.0rc2\",\"prerelease\":true}" +
                "]"));

        var tag = service.getLatestTag("repo");

        assertThat(tag).isPresent().contains("v1.0.0rc3");
    }

    @Test
    void getLatestTag_returnsEmpty_whenNoReleases() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("[]"));

        var tag = service.getLatestTag("repo");

        assertThat(tag).isEmpty();
    }

    @Test
    void createTag_createsRefThenRelease_withTagExistenceCheck() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(404)); // tagExists → 404 (tag doesn't exist)
        server.enqueue(new MockResponse().setResponseCode(201).setBody("{}")); // ref
        server.enqueue(new MockResponse()
            .setResponseCode(201)
            .setBody("{\"html_url\": \"https://github.com/org/repo/releases/tag/v2\"}")); // release

        var result = service.createTagAndPreRelease("repo", "v1.0.1rc1", "abc123", "PROJ-2");

        assertThat(result.releaseUrl()).contains("v2");
        // 3 requests total: tagExists + ref + release
        assertThat(server.getRequestCount()).isEqualTo(3);
    }
}
