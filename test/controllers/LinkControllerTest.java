package controllers;

import org.junit.Before;
import org.junit.Test;
import play.mvc.Http;
import play.mvc.Result;
import services.LinkTransformService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;
import static play.test.Helpers.contentAsString;

/**
 * Unit tests for LinkController with mocked service dependencies.
 * Covers request handling, responses, and status codes.
 */
public class LinkControllerTest {

    private LinkTransformService linkTransformService;
    private LinkController controller;

    @Before
    public void setUp() {
        linkTransformService = mock(LinkTransformService.class);
        controller = new LinkController(linkTransformService);
    }

    /** Builds a mocked Play request that returns the given body text. */
    private Http.Request requestWithBody(String body) {
        Http.Request request = mock(Http.Request.class);
        Http.RequestBody requestBody = mock(Http.RequestBody.class);
        when(request.body()).thenReturn(requestBody);
        when(requestBody.asText()).thenReturn(body);
        return request;
    }

    @Test
    public void transform_returns200_withTransformedHtml() {
        String input = "<a href=\"https://example.com\">click</a>";
        String transformed = "<a href=\"http://localhost:9000/r/abc12345\">click</a>";
        when(linkTransformService.transform(input)).thenReturn(transformed);

        Result result = controller.transform(requestWithBody(input));

        assertEquals(200, result.status());
        assertTrue(contentAsString(result).contains("http://localhost:9000/r/abc12345"));
    }

    @Test
    public void transform_returns400_whenBodyIsBlank() {
        Result result = controller.transform(requestWithBody("   "));

        assertEquals(400, result.status());
        verifyNoInteractions(linkTransformService);
    }

    @Test
    public void transform_returns400_whenBodyIsNull() {
        Result result = controller.transform(requestWithBody(null));

        assertEquals(400, result.status());
        verifyNoInteractions(linkTransformService);
    }
}
