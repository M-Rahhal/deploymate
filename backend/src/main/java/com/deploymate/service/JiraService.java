package com.deploymate.service;

import com.deploymate.config.AppProperties;
import com.deploymate.model.DeployException;
import com.deploymate.model.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Service
public class JiraService {

    private static final Logger log = LoggerFactory.getLogger(JiraService.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final ObjectMapper mapper;
    private final AppProperties props;

    public JiraService(AppProperties props) {
        this.props  = props;
        this.mapper = new ObjectMapper();
        this.client = new OkHttpClient.Builder().build();
    }

    // Package-private constructor for tests
    JiraService(AppProperties props, OkHttpClient client) {
        this.props  = props;
        this.mapper = new ObjectMapper();
        this.client = client;
    }

    private String basicAuth() {
        var creds = props.jira().email() + ":" + props.jira().token();
        return "Basic " + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Converts plain text to Atlassian Document Format (ADF) — required by Jira Cloud REST API v3.
     */
    private ObjectNode toAdf(String text) {
        var doc  = mapper.createObjectNode().put("type", "doc").put("version", 1);
        var para = mapper.createObjectNode().put("type", "paragraph");
        var textNode = mapper.createObjectNode().put("type", "text").put("text", text);
        para.set("content", mapper.createArrayNode().add(textNode));
        doc.set("content", mapper.createArrayNode().add(para));
        return doc;
    }

    /**
     * Posts a comment on the given Jira issue.
     */
    public void addComment(String issueKey, String text) {
        var body = mapper.createObjectNode();
        body.set("body", toAdf(text));

        var req = new Request.Builder()
            .url(props.jira().url() + "/rest/api/3/issue/" + issueKey + "/comment")
            .header("Authorization", basicAuth())
            .header("Content-Type",  "application/json")
            .header("Accept",        "application/json")
            .post(RequestBody.create(body.toString(), JSON))
            .build();

        try (var resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new DeployException(
                    "Jira comment failed: HTTP " + resp.code(), ErrorCode.NETWORK);
            }
            log.debug("Jira comment posted to {}", issueKey);
        } catch (DeployException e) {
            throw e;
        } catch (IOException e) {
            throw new DeployException("Jira network error: " + e.getMessage(), ErrorCode.NETWORK);
        }
    }
}
