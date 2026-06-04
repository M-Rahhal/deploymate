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

    private static final MediaType APPLICATION_JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient  httpClient;
    private final ObjectMapper  objectMapper;
    private final AppProperties appProperties;


    private String buildBasicAuthorizationHeader() {
        String credentials = appProperties.getJira().email() + ":" + appProperties.getJira().token();
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private ObjectNode convertToAtlassianDocumentFormat(String text) {
        ObjectNode document  = objectMapper.createObjectNode().put("type", "doc").put("version", 1);
        ObjectNode paragraph = objectMapper.createObjectNode().put("type", "paragraph");
        ObjectNode textNode  = objectMapper.createObjectNode().put("type", "text").put("text", text);
        paragraph.set("content", objectMapper.createArrayNode().add(textNode));
        document.set("content", objectMapper.createArrayNode().add(paragraph));
        return document;
    }

    public void addComment(String issueKey, String commentText) {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.set("body", convertToAtlassianDocumentFormat(commentText));

        Request request = new Request.Builder()
            .url(appProperties.getJira().url() + "/rest/api/3/issue/" + issueKey + "/comment")
            .header("Authorization", buildBasicAuthorizationHeader())
            .header("Content-Type",  "application/json")
            .header("Accept",        "application/json")
            .post(RequestBody.create(requestBody.toString(), APPLICATION_JSON))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new DeployException(
                    "Jira comment failed: HTTP " + response.code(), ErrorCode.NETWORK);
            }
            log.debug("Jira comment posted to {}", issueKey);
        } catch (DeployException e) {
            throw e;
        } catch (IOException e) {
            throw new DeployException("Jira network error: " + e.getMessage(), ErrorCode.NETWORK);
        }
    }
}
