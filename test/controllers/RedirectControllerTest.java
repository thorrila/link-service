package controllers;

import org.junit.Before;
import org.junit.Test;

import controllers.RedirectController;
import play.mvc.Result;
import services.ClickLoggingService;
import services.RedirectService;

import java.util.Optional;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static play.test.Helpers.contentAsString;

/**
 * Unit tests for RedirectController with mocked service dependencies.
 * Covers request handling, responses, and error handling.
 */
public class RedirectControllerTest {

    private RedirectService redirectService;
    private ClickLoggingService clickLoggingService;
    private RedirectController controller;

    @Before
    public void setUp() {
        redirectService = mock(RedirectService.class);
        clickLoggingService = mock(ClickLoggingService.class);
        controller = new RedirectController(redirectService, clickLoggingService);
    }

    @Test
    public void follow_returns404_forMalformedCode() {
        Result result = controller.follow("!!!");

        assertEquals(404, result.status());
        assertTrue(contentAsString(result).contains("Link not found"));
        verifyNoInteractions(redirectService);
    }

    @Test
    public void follow_returns404_whenCodeDoesNotExist() {
        when(redirectService.resolve("abcd1234")).thenReturn(Optional.empty());

        Result result = controller.follow("abcd1234");

        assertEquals(404, result.status());
    }

    @Test
    public void follow_returns503_whenDependenciesAreDown() {
        when(redirectService.resolve("abcd1234"))
                .thenThrow(new RedirectService.ResolutionException(new RuntimeException("db down")));

        Result result = controller.follow("abcd1234");

        assertEquals(503, result.status());
        assertTrue(contentAsString(result).contains("We're on it"));
    }

    @Test
    public void follow_redirects_whenCodeResolves() {
        when(redirectService.resolve("abcd1234"))
                .thenReturn(Optional.of("https://example.com/tours/x"));

        Result result = controller.follow("abcd1234");

        assertEquals(302, result.status());
        assertEquals(Optional.of("https://example.com/tours/x"), result.header("Location"));
        assertEquals(Optional.of("no-store"), result.header("Cache-Control"));
    }
}