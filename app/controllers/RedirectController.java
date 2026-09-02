package controllers;

import services.ClickLoggingService;
import services.RedirectService;
import services.RedirectService.ResolutionException;

import play.mvc.Controller;
import play.mvc.Result;
import play.Logger;

import javax.inject.Inject;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import static play.mvc.Http.Status.SERVICE_UNAVAILABLE;
import static play.mvc.Results.*;

/**
 * Called whenever a recipient clicks a managed link.
 * Resolves the code, then redirects or returns 404/503 depending on why it can't.
 */
public class RedirectController extends Controller {

    // Matches the codes LinkTransformService generates.
    private static final Pattern CODE_FORMAT = Pattern.compile("^[a-zA-Z0-9]{8}$");
    // Virtual threads - dedicated pool, keeps SQS calls off Play's dispatcher.
    private static final Executor clickLoggingExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private static final Logger.ALogger logger = Logger.of(RedirectController.class);

    private final RedirectService redirectService;
    private final ClickLoggingService clickLoggingService;

    @Inject
    public RedirectController(RedirectService redirectService, ClickLoggingService clickLoggingService) {
        this.redirectService = redirectService;
        this.clickLoggingService = clickLoggingService;
    }

    // Mapped from routes: GET /r/:code in application.routes file
    public Result follow(String code) {

        // Fail fast: Reject garbage codes, before spending a Redis/MySQL round trip.
        if (!CODE_FORMAT.matcher(code).matches()) {
            return notFound(linkNotFoundPage()).as("text/html");
        }

        // Try to resolve the code.
        Optional<String> originalUrl;
        try {
            originalUrl = redirectService.resolve(code);
        } catch (ResolutionException e) {
            // Redis and MySQL both failed.
            logger.error("Failed to resolve code={} — Redis and MySQL both unavailable", code, e);
            return status(SERVICE_UNAVAILABLE, serviceUnavailablePage()).as("text/html");
        }
 
        // If Redis returned empty for a valid-looking code, return 404.
        if (originalUrl.isEmpty()) {
            return notFound(linkNotFoundPage()).as("text/html");
        }

        // Fire-and-forget. Runs on a seperate virtual thread. Never blocks the redirect.  
        CompletableFuture.runAsync(() -> clickLoggingService.logClick(code), clickLoggingExecutor)
                .exceptionally(e -> {
                    logger.warn("Failed to log click for code={}", code, e);
                    return null;
                });
                
        // no-store so repeat clicks always hit this server, not a cache.
        return found(originalUrl.get()).withHeader("Cache-Control", "no-store");
    }
    // Small branded error page used when no template rendering is necessary.
    private String linkNotFoundPage() {
        return "<html><body style=\"font-family:sans-serif;text-align:center;padding:4rem;\">"
                + "<h1>Link not found</h1><p>This link doesn't exist or may have expired.</p>"
                + "</body></html>";
    }

    private String serviceUnavailablePage() {
        return "<html><body style=\"font-family:sans-serif;text-align:center;padding:4rem;\">"
                + "<h1>We're on it</h1><p>This link is temporarily unavailable. Please try again shortly.</p>"
                + "</body></html>";
    }
}