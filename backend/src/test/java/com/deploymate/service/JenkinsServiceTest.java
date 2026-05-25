package com.deploymate.service;

import com.deploymate.config.AppProperties;
import com.deploymate.model.DeployException;
import com.deploymate.model.ErrorCode;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;

import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test")
class JenkinsServiceTest {

    private MockWebServer server;
    private JenkinsService service;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        var props = new AppProperties(
            new AppProperties.GitHub("gh-token", "org"),
            new AppProperties.Jenkins(server.url("").toString().replaceAll("/$", ""), "user", "token"),
            new AppProperties.Jira(server.url("").toString().replaceAll("/$", ""), "e@e.com", "t"),
            new AppProperties.Defaults("env/staging", "env-stag")
        );

        service = new JenkinsService(props, new OkHttpClient());
        // Override to use mock server: rebuild with proper base URL
        service = new JenkinsService(props, new OkHttpClient.Builder()
            .addInterceptor(chain -> {
                var original = chain.request();
                var newUrl   = original.url().newBuilder()
                    .scheme("http")
                    .host(server.getHostName())
                    .port(server.getPort())
                    .build();
                return chain.proceed(original.newBuilder().url(newUrl).build());
            })
            .build());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    // ── triggerBuild ─────────────────────────────────────────────────────────

    @Test
    void triggerBuild_returnsQueueUrl_on201() throws InterruptedException {
        // Crumb response
        server.enqueue(new MockResponse().setResponseCode(200)
            .setBody("{\"crumbRequestField\":\"Jenkins-Crumb\",\"crumb\":\"test-crumb\"}"));
        // Trigger response
        server.enqueue(new MockResponse().setResponseCode(201)
            .setHeader("Location", "http://jenkins/queue/item/42/"));

        var queueUrl = service.triggerBuild("backend/my-job", "BRANCH", "env/staging");

        assertThat(queueUrl).isEqualTo("http://jenkins/queue/item/42/");

        server.takeRequest(); // crumb
        var triggerReq = server.takeRequest();
        assertThat(triggerReq.getMethod()).isEqualTo("POST");
        assertThat(triggerReq.getHeader("Jenkins-Crumb")).isEqualTo("test-crumb");
    }

    @Test
    void triggerBuild_throwsAuthFailed_whenCrumbFails() {
        server.enqueue(new MockResponse().setResponseCode(403));

        assertThatThrownBy(() -> service.triggerBuild("job", "BRANCH", "main"))
            .isInstanceOf(DeployException.class)
            .extracting(e -> ((DeployException) e).getCode())
            .isEqualTo(ErrorCode.AUTH_FAILED);
    }

    @Test
    void triggerBuild_throwsNetwork_whenLocationHeaderMissing() {
        server.enqueue(new MockResponse().setResponseCode(200)
            .setBody("{\"crumbRequestField\":\"Jenkins-Crumb\",\"crumb\":\"c\"}"));
        server.enqueue(new MockResponse().setResponseCode(201)); // no Location header

        assertThatThrownBy(() -> service.triggerBuild("job", "TAG", "v1.0"))
            .isInstanceOf(DeployException.class)
            .message().contains("Location");
    }

    @Test
    void triggerBuild_throwsNetwork_whenTriggerReturnsNon201() {
        server.enqueue(new MockResponse().setResponseCode(200)
            .setBody("{\"crumbRequestField\":\"Jenkins-Crumb\",\"crumb\":\"c\"}"));
        server.enqueue(new MockResponse().setResponseCode(500));

        assertThatThrownBy(() -> service.triggerBuild("job", "BRANCH", "main"))
            .isInstanceOf(DeployException.class)
            .extracting(e -> ((DeployException) e).getCode())
            .isEqualTo(ErrorCode.NETWORK);
    }

    // ── pollQueueItem ─────────────────────────────────────────────────────────

    @Test
    void pollQueueItem_returnsNull_whenNotYetAssigned() {
        server.enqueue(new MockResponse().setResponseCode(200)
            .setBody("{\"executable\": null}"));

        assertThat(service.pollQueueItem("http://mock/queue/item/1/")).isNull();
    }

    @Test
    void pollQueueItem_returnsBuildUrl_whenAssigned() {
        server.enqueue(new MockResponse().setResponseCode(200)
            .setBody("{\"executable\": {\"url\": \"http://jenkins/job/my-job/42/\"}}"));

        assertThat(service.pollQueueItem("http://mock/queue/item/1/"))
            .isEqualTo("http://jenkins/job/my-job/42/");
    }

    @Test
    void pollQueueItem_returnsNull_onError() {
        server.enqueue(new MockResponse().setResponseCode(404));
        // Should not throw — returns null for resilience
        assertThat(service.pollQueueItem("http://mock/queue/item/99/")).isNull();
    }

    // ── pollBuildStatus ───────────────────────────────────────────────────────

    @Test
    void pollBuildStatus_returnsRunning_whenResultNull() {
        server.enqueue(new MockResponse().setResponseCode(200)
            .setBody("{\"result\": null, \"number\": 42}"));

        var status = service.pollBuildStatus("http://mock/job/j/42/");

        assertThat(status.result()).isNull();
        assertThat(status.number()).isEqualTo(42);
    }

    @Test
    void pollBuildStatus_returnsSuccess() {
        server.enqueue(new MockResponse().setResponseCode(200)
            .setBody("{\"result\": \"SUCCESS\", \"number\": 55}"));

        var status = service.pollBuildStatus("http://mock/job/j/55/");

        assertThat(status.result()).isEqualTo("SUCCESS");
        assertThat(status.number()).isEqualTo(55);
    }

    @Test
    void pollBuildStatus_throwsNetwork_on500() {
        server.enqueue(new MockResponse().setResponseCode(500));

        assertThatThrownBy(() -> service.pollBuildStatus("http://mock/job/j/1/"))
            .isInstanceOf(DeployException.class)
            .extracting(e -> ((DeployException) e).getCode())
            .isEqualTo(ErrorCode.NETWORK);
    }

    // ── getBuildLog ───────────────────────────────────────────────────────────

    @Test
    void getBuildLog_returnsFragment() {
        server.enqueue(new MockResponse().setResponseCode(200)
            .setHeader("X-Text-Size", "500")
            .setHeader("X-More-Data", "true")
            .setBody("Build output line 1\nLine 2\n"));

        var frag = service.getBuildLog("http://mock/job/j/1/", 0);

        assertThat(frag.text()).contains("Build output line 1");
        assertThat(frag.nextStart()).isEqualTo(500);
        assertThat(frag.hasMore()).isTrue();
    }

    @Test
    void getBuildLog_returnsEmptyFragment_onError() {
        server.enqueue(new MockResponse().setResponseCode(500));

        var frag = service.getBuildLog("http://mock/job/j/99/", 0);
        // Should not throw — returns empty fragment for resilience
        assertThat(frag.text()).isEmpty();
        assertThat(frag.hasMore()).isFalse();
    }
}
