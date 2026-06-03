package com.deploymate.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA fallback controller for React Router client-side navigation.
 *
 * <p>When the React app is built, Vite outputs a single {@code index.html} that
 * bootstraps the entire UI. React Router then takes over and renders the correct
 * view based on {@code window.location}. This works fine for in-app navigation
 * (link clicks), but breaks for direct loads and browser refreshes: the browser
 * issues a real HTTP GET for the full URL (e.g. {@code /deploy/some-service}),
 * and Spring Boot would return 404 because no server-side route matches.</p>
 *
 * <p>This controller solves that by forwarding every "HTML-shaped" request to
 * {@code /index.html}. Spring's static-resource handler then serves the file,
 * the React app boots, and React Router renders the right page.</p>
 *
 * <h3>Pattern breakdown</h3>
 *
 * <h3>Why {@code /api/**} routes are not captured</h3>
 * <p>Spring registers {@code @RestController} endpoints at a higher priority
 * than generic {@code @Controller} mappings. Every path under {@code /api/}
 * is dispatched to the appropriate REST controller before this fallback is
 * ever consulted, so no explicit exclusion is needed.</p>
 */
@Controller
public class SpaController {

    /**
     * Forward all non-file, non-API requests to index.html for React Router.
     *
     * The pattern {@code /**}/{path:[^\\.]*}} matches any URL whose LAST segment
     * contains no dot (i.e. is not a filename like index.js or styles.css).
     * Asset files under /assets/ are therefore served by Spring's static-resource
     * handler, not forwarded here.  REST controllers under /api/** take priority
     * over this generic mapping due to Spring MVC's specificity rules.
     */
    @GetMapping(value = {
        "/",
    })
    public String forwardToIndex() {
        return "forward:/index.html";
    }
}
