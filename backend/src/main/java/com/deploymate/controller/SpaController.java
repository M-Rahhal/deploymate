package com.deploymate.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards the SPA root to index.html so React Router can take over client-side navigation.
 * Spring's static-resource handler serves all asset files; REST controllers under /api/**
 * take priority over this mapping due to Spring MVC specificity rules.
 */
@Controller
public class SpaController {

    @GetMapping(value = {
        "/",
    })
    public String forwardToIndex() {
        return "forward:/index.html";
    }
}
