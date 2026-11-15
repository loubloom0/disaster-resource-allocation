package com.disaster.allocation.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Forwards public SPA routes to the existing static application shell. API security remains unchanged. */
@Controller
public class PublicPageController {
    @GetMapping({"/login", "/register", "/dashboard"})
    public String applicationShell() {
        return "forward:/index.html";
    }
}
