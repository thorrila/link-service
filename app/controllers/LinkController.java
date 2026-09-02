package controllers;

import services.LinkTransformService;

import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.BodyParser;

import javax.inject.Inject;

/**
 * Called once per outbound email, before it is sent.
 * Delegates the parsing and logic to LinkTransformService.
 */
public class LinkController extends Controller {

    private final LinkTransformService linkTransformService;

    @Inject
    public LinkController(LinkTransformService linkTransformService) {
        this.linkTransformService = linkTransformService;
    }

    // Mapped from routes: GET / in application.routes file
    // Returns HTML explaining what the service does and how to use it.
    public Result index() {
        return ok("Link redirect service\n\nPOST /links to transform, GET /r/:code to redirect.\n\nSee README.")
            .as("text/plain; charset=utf-8");
    }

    // Mapped from routes: POST /links in application.routes file
    // Accept raw HTML/text rather than JSON because callers POST email content directly.
    @BodyParser.Of(BodyParser.TolerantText.class) // tells Play to parse the POST body as raw text
    public Result transform(Http.Request request) {
        String emailContent = request.body().asText();  // reads raw HTML into a string

        // Guard against an empty POST body, fail fast with a 400.
        if (emailContent == null || emailContent.isBlank()) {
            return badRequest("Request body must contain email HTML/text content.");
        }

        // Delegates the heavy lifting to LinkTransformService
        String transformed = linkTransformService.transform(emailContent);
        return ok(transformed).as("text/html");
    }
}
 