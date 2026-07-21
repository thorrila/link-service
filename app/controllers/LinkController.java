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

    // Accept raw HTML/text rather than JSON because callers POST email content directly.
    @BodyParser.Of(BodyParser.TolerantText.class)
    public Result transform(Http.Request request) {
        String emailContent = request.body().asText();

        // Guard against an empty POST body, fail fast with a 400.
        if (emailContent == null || emailContent.isBlank()) {
            return badRequest("Request body must contain email HTML/text content.");
        }

        // Delegates the heavy lifting to LinkTransformService
        String transformed = linkTransformService.transform(emailContent);
        return ok(transformed).as("text/html");
    }
}
 