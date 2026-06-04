package com.deploymate.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/log")
public class LogController {

    @GetMapping
    public ResponseEntity<List<String>> getRecentLogLines(
        @RequestParam(defaultValue = "200") int lines
    ) {
        Path logFilePath = Path.of("logs/deploymate.log");

        if (!Files.exists(logFilePath)) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        try {
            List<String> allLines  = Files.readAllLines(logFilePath);
            int          fromIndex = Math.max(0, allLines.size() - lines);
            return ResponseEntity.ok(allLines.subList(fromIndex, allLines.size()));
        } catch (IOException e) {
            log.error("Failed to read log file", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
