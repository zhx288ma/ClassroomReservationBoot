package com.xuan.boot.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Avoid logging the browser's optional favicon probe as an application error. */
@RestController
public class StaticResourceController {

    @GetMapping("/favicon.ico")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void favicon() {
        // The application currently has no branded favicon.
    }
}
