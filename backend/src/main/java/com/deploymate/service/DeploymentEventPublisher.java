package com.deploymate.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Broadcasts deployment progress events to all active SSE connections.
 * Thread-safe: CopyOnWriteArrayList handles concurrent registration and removal;
 * Spring's SseEmitter.send() is internally synchronized.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeploymentEventPublisher {

    private static final long SSE_TIMEOUT_MILLIS = 10 * 60 * 1_000L;

    private final ObjectMapper objectMapper;

    private final List<SseEmitter> activeEmitters = new CopyOnWriteArrayList<>();

    public SseEmitter openStream() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        activeEmitters.add(emitter);
        emitter.onCompletion(() -> activeEmitters.remove(emitter));
        emitter.onTimeout(()  -> { emitter.complete(); activeEmitters.remove(emitter); });
        emitter.onError(e     -> activeEmitters.remove(emitter));
        return emitter;
    }

    public void publishLogEvent(String level, String serviceName, String stageLabel, String message) {
        broadcast("log", new LogPayload(level, serviceName, stageLabel, message));
    }

    public void publishStepUpdate(String rowId, String step, String state,
                                  String commitSha, String releaseUrl,
                                  String buildUrl, Integer buildNumber, String errorMessage) {
        broadcast("step-update", new StepUpdatePayload(
            rowId, step, state, commitSha, releaseUrl, buildUrl, buildNumber, errorMessage));
    }

    public void publishDeploymentCompleted(boolean success) {
        broadcast("done", new DonePayload(success));
        for (SseEmitter emitter : activeEmitters) {
            try { emitter.complete(); } catch (Exception ignored) {}
        }
        activeEmitters.clear();
    }

    private void broadcast(String eventName, Object payload) {
        if (activeEmitters.isEmpty()) return;
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("Could not serialize SSE event '{}': {}", eventName, e.getMessage());
            return;
        }
        List<SseEmitter> staleEmitters = new ArrayList<>();
        for (SseEmitter emitter : activeEmitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(json));
            } catch (Exception e) {
                staleEmitters.add(emitter);
            }
        }
        activeEmitters.removeAll(staleEmitters);
    }

    public record LogPayload(String level, String serviceName, String stageLabel, String message) {}

    public record StepUpdatePayload(
        String  rowId,
        String  step,
        String  state,
        String  commitSha,
        String  releaseUrl,
        String  buildUrl,
        Integer buildNumber,
        String  errorMessage
    ) {}

    public record DonePayload(boolean success) {}
}
