package com.deploymate.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api/log")
public class LogController {

    private static final Logger log = LoggerFactory.getLogger(LogController.class);

    @GetMapping
    public ResponseEntity<List<String>> tail(
        @RequestParam(defaultValue = "200") int lines
    ) {
        var logFile = Path.of("logs/deploymate.log");

        if (!Files.exists(logFile)) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        try {
            var allLines = Files.readAllLines(logFile);
            int from     = Math.max(0, allLines.size() - lines);
            return ResponseEntity.ok(allLines.subList(from, allLines.size()));
        } catch (IOException e) {
            log.error("Failed to read log file", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
