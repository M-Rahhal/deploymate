package com.deploymate.service;

import com.deploymate.config.AppProperties;
import com.deploymate.model.DeployException;
import com.deploymate.model.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Slf4j
@Service
@RequiredArgsConstructor
public class JiraService {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final ObjectMapper mapper;
    private final AppProperties props;


    private String basicAuth() {
        String creds = props.getJira().email() + ":" + props.getJira().token();
        return "Basic " + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Converts plain text to Atlassian Document Format (ADF) — required by Jira Cloud REST API v3.
     */
    private ObjectNode toAdf(String text) {
        ObjectNode doc      = mapper.createObjectNode().put("type", "doc").put("version", 1);
        ObjectNode para     = mapper.createObjectNode().put("type", "paragraph");
        ObjectNode textNode = mapper.createObjectNode().put("type", "text").put("text", text);
        para.set("content", mapper.createArrayNode().add(textNode));
        doc.set("content", mapper.createArrayNode().add(para));
        return doc;
    }

    /**
     * Posts a comment on the given Jira issue.
     */
    public void addComment(String issueKey, String text) {
        ObjectNode body = mapper.createObjectNode();
        body.set("body", toAdf(text));

        Request req = new Request.Builder()
            .url(props.getJira().url() + "/rest/api/3/issue/" + issueKey + "/comment")
            .header("Authorization", basicAuth())
            .header("Content-Type",  "application/json")
            .header("Accept",        "application/json")
            .post(RequestBody.create(body.toString(), JSON))
            .build();

        try (Response resp = client.newCall(req).execute()) {
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
