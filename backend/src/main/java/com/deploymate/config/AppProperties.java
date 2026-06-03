package com.deploymate.config;

import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.net.URL;
import java.util.List;

@Component
@Validated
@ConfigurationProperties(prefix = "deploymate")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AppProperties {

    @Valid private GitHub   github;
    @Valid private Jenkins  jenkins;
    @Valid private Jira     jira;
           private Defaults defaults;

    public record GitHub(@NotBlank String token, @NotBlank String org) {}

    public record Jenkins(@NotBlank String url, @NotBlank String user, @NotBlank String token) {}

    public record Jira(@NotBlank String url, @NotBlank String email, @NotBlank String token) {}

    public record Defaults(String targetBranch, String tagPrefix) {
        public Defaults {
            targetBranch = (targetBranch == null || targetBranch.isBlank()) ? "env/staging" : targetBranch;
            tagPrefix    = (tagPrefix    == null || tagPrefix.isBlank())    ? "env-stag"    : tagPrefix;
        }
    }

    @PostConstruct
    void validate() {
        validateUrl("JENKINS_URL", jenkins.url());
        validateUrl("JIRA_URL",    jira.url());
    }

    private void validateUrl(String name, String url) {
        try {
            URL parsed = URI.create(url).toURL();
            if (!List.of("http", "https").contains(parsed.getProtocol())) {
                throw new IllegalStateException(name + " must use http or https scheme");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Invalid " + name + ": " + e.getMessage());
        }
    }
}
