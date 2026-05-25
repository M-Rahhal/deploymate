package com.deploymate.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards all non-API, non-static-file paths to index.html
 * so React Router can handle client-side navigation.
 */
@Controller
public class SpaController {

    @GetMapping(value = {
        "/",
        "/{path:[^\\.]*}",
        "/{path:^(?!api).*$}/**/{subpath:[^\\.]*}"
    })
    public String forwardToIndex() {
        return "forward:/index.html";
    }
}
