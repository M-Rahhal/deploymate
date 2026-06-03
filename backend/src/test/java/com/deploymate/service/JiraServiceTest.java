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
class JiraServiceTest {

    private MockWebServer server;
    private JiraService   service;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        var baseUrl = server.url("").toString().replaceAll("/$", "");
        var props   = new AppProperties(
            new AppProperties.GitHub("gh", "org"),
            new AppProperties.Jenkins(baseUrl, "u", "t"),
            new AppProperties.Jira(baseUrl, "user@example.com", "jira-token"),
            new AppProperties.Defaults("env/staging", "env-stag")
        );

        service = new JiraService(
            new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    var original = chain.request();
                    var newUrl   = original.url().newBuilder()
                        .scheme("http")
                        .host(server.getHostName())
                        .port(server.getPort())
                        .build();
                    return chain.proceed(original.newBuilder().url(newUrl).build());
                })
                .build(),
            new ObjectMapper(),
            props);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void addComment_postsToCorrectEndpoint() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(201).setBody("{}"));

        service.addComment("PROJ-123", "Deployment complete!");

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/rest/api/3/issue/PROJ-123/comment");
        assertThat(req.getMethod()).isEqualTo("POST");
    }

    @Test
    void addComment_sendsBasicAuthHeader() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(201).setBody("{}"));

        service.addComment("PROJ-1", "test");

        var req  = server.takeRequest();
        var auth = req.getHeader("Authorization");
        assertThat(auth).startsWith("Basic ");
    }

    @Test
    void addComment_sendsAdfFormattedBody() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(201).setBody("{}"));

        service.addComment("PROJ-1", "Hello, Jira!");

        var req  = server.takeRequest();
        var body = req.getBody().readUtf8();
        // ADF format must contain "doc" and "paragraph" type nodes
        assertThat(body).contains("\"type\":\"doc\"");
        assertThat(body).contains("\"type\":\"paragraph\"");
        assertThat(body).contains("Hello, Jira!");
    }

    @Test
    void addComment_throwsDeployException_onFailure() {
        server.enqueue(new MockResponse().setResponseCode(401));

        assertThatThrownBy(() -> service.addComment("PROJ-1", "msg"))
            .isInstanceOf(DeployException.class)
            .extracting(e -> ((DeployException) e).getCode())
            .isEqualTo(ErrorCode.NETWORK);
    }

    @Test
    void addComment_throwsDeployException_on500() {
        server.enqueue(new MockResponse().setResponseCode(500));

        assertThatThrownBy(() -> service.addComment("PROJ-5", "msg"))
            .isInstanceOf(DeployException.class);
    }
}
