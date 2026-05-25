package com.deploymate.service;

import com.deploymate.config.AppProperties;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
public class TagGeneratorService {

    private final AppProperties props;

    public TagGeneratorService(AppProperties props) {
        this.props = props;
    }

    /**
     * Generates a tag name in the format: {prefix}-{yyyyMMdd}-{repo}-{sequence:03d}
     * Example: env-stag-20260519-my-service-001
     */
    public String generate(String repo, int sequence) {
        var prefix = props.defaults().tagPrefix();
        var date   = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        var seq    = String.format("%03d", sequence);
        return prefix + "-" + date + "-" + repo + "-" + seq;
    }
}
