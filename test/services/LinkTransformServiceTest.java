package services;

import clients.RedisClient;
import com.typesafe.config.Config;
import org.junit.Before;
import org.junit.Test;
import repositories.LinkRepository;

import org.mockito.ArgumentCaptor;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for LinkTransformService with mocked persistence dependencies.
 */
public class LinkTransformServiceTest {

    private RedisClient redisClient;
    private LinkRepository linkRepository;
    private LinkTransformService service;

    @Before
    public void setUp() {
        redisClient = mock(RedisClient.class);
        linkRepository = mock(LinkRepository.class);
        Config config = mock(Config.class);
        when(config.getString("service.baseUrl")).thenReturn("http://localhost:9000"); 
        service = new LinkTransformService(linkRepository, redisClient, config);
    }

    @Test
    public void transform_rewritesLinkAndStoresMapping() {
        String html = "<a href=\"https://bokun.io/tours/x\">View</a>";

        String result = service.transform(html);

        assertTrue(result.contains("http://localhost:9000/r/"));
        assertFalse(result.contains("https://bokun.io/tours/x"));
        verify(linkRepository, times(1)).save(anyString(), eq("https://bokun.io/tours/x"));
        verify(redisClient, times(1)).set(anyString(), eq("https://bokun.io/tours/x"));
    }

    @Test
    public void transform_retriesOnce_whenFirstCodeCollides() {
        doThrow(new LinkRepository.DuplicateCodeException())
                .doNothing()
                .when(linkRepository).save(anyString(), anyString());

        service.transform("<a href=\"https://bokun.io/tours/x\">View</a>");

        verify(linkRepository, times(2)).save(anyString(), eq("https://bokun.io/tours/x"));
        verify(redisClient, times(1)).set(anyString(), eq("https://bokun.io/tours/x"));
    }

    @Test
    public void transform_givesUp_afterExhaustingAllAttempts() {
        doThrow(new LinkRepository.DuplicateCodeException())
                .when(linkRepository).save(anyString(), anyString());

        try {
            service.transform("<a href=\"https://bokun.io/tours/x\">View</a>");
            fail("expected IllegalStateException after exhausting retry attempts");
        } catch (IllegalStateException e) {
            verify(linkRepository, times(5)).save(anyString(), anyString());
        }
    }

    @Test
    public void transform_skipsBlankHrefs() {
        service.transform("<a href=\"\">Empty</a>");

        verifyNoInteractions(linkRepository);
        verifyNoInteractions(redisClient);
    }

    @Test
    public void transform_rewritesAllLinksInMultiLinkEmail() {
        String html = "<a href=\"https://bokun.io/a\">A</a><a href=\"https://bokun.io/b\">B</a>";

        String result = service.transform(html);

        // Both original URLs must be replaced; two separate mappings must be stored.
        assertFalse(result.contains("https://bokun.io/a"));
        assertFalse(result.contains("https://bokun.io/b"));
        verify(linkRepository, times(2)).save(anyString(), anyString());
    }

    @Test
    public void transform_preservesAnchorText() {
        String result = service.transform("<a href=\"https://bokun.io\">Book your tour</a>");

        assertTrue("anchor text must not be altered", result.contains("Book your tour"));
    }

    @Test
    public void transform_generatedCodeIsEightAlphanumericChars() {
        service.transform("<a href=\"https://bokun.io\">click</a>");

        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(linkRepository).save(codeCaptor.capture(), anyString());
        String code = codeCaptor.getValue();
        assertEquals("code must be exactly 8 characters", 8, code.length());
        assertTrue("code must be alphanumeric", code.matches("[a-zA-Z0-9]{8}"));
    }
}