package com.deploymate.service;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Slf4j(topic = "deploymate.events")
@Service
public class LogService {

    public void info(String serviceName, String stageLabel, String message) {
        executeWithMdcContext(serviceName, stageLabel, () -> log.info("{}", message));
    }

    public void warn(String serviceName, String stageLabel, String message) {
        executeWithMdcContext(serviceName, stageLabel, () -> log.warn("{}", message));
    }

    public void error(String serviceName, String stageLabel, String message) {
        executeWithMdcContext(serviceName, stageLabel, () -> log.error("{}", message));
    }

    private void executeWithMdcContext(String serviceName, String stageLabel, Runnable loggingAction) {
        MDC.put("service", serviceName != null ? serviceName : "SYSTEM");
        MDC.put("stage",   stageLabel  != null ? stageLabel  : "—");
        try {
            loggingAction.run();
        } finally {
            MDC.remove("service");
            MDC.remove("stage");
        }
    }
}
