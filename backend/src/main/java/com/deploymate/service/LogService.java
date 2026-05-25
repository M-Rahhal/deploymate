package com.deploymate.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Service
public class LogService {

    private static final Logger log = LoggerFactory.getLogger("deploymate.events");

    public void info(String service, String stage, String message) {
        withMdc(service, stage, () -> log.info("{}", message));
    }

    public void warn(String service, String stage, String message) {
        withMdc(service, stage, () -> log.warn("{}", message));
    }

    public void error(String service, String stage, String message) {
        withMdc(service, stage, () -> log.error("{}", message));
    }

    private void withMdc(String service, String stage, Runnable action) {
        MDC.put("service", service != null ? service : "SYSTEM");
        MDC.put("stage",   stage   != null ? stage   : "—");
        try {
            action.run();
        } finally {
            MDC.remove("service");
            MDC.remove("stage");
        }
    }
}
