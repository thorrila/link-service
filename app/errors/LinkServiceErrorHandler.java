package errors;

import play.http.HttpErrorHandler;
import play.mvc.Http.RequestHeader;
import play.mvc.Result;

import javax.inject.Singleton;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static play.mvc.Results.*;

// Wired via play.http.errorHandler in application.conf
// Catches anything the controllers themselves don't handle, app-wide.
@Singleton
public class LinkServiceErrorHandler implements HttpErrorHandler {

    @Override
    public CompletionStage<Result> onClientError(RequestHeader request, int statusCode, String message) {
        // Any unmatched route or bad request Play itself rejects, before a controller runs.
        if (statusCode == 404) {
            return CompletableFuture.completedFuture(
                    notFound(brandedPage("Page not found", "That page doesn't exist.")).as("text/html"));
        }
        return CompletableFuture.completedFuture(
                badRequest(brandedPage("Something's not right", "We couldn't process that request.")).as("text/html"));
    }

    @Override
    public CompletionStage<Result> onServerError(RequestHeader request, Throwable exception) {
      // Last line of defence for unhandled server errors.
      // Controllers handle expected failures locally; unexpected failures end up here.
        return CompletableFuture.completedFuture(
                internalServerError(brandedPage(
                        "We're on it",
                        "This link is temporarily unavailable. Please try again shortly.")).as("text/html"));
    }
    
    // Small helper to avoid repeating HTML for each error response.
    private String brandedPage(String heading, String body) {
        return "<html><body style=\"font-family:sans-serif;text-align:center;padding:4rem;\">"
                + "<h1>" + heading + "</h1><p>" + body + "</p></body></html>";
    }
}