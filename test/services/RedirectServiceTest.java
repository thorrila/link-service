package services;

import clients.RedisClient;
import org.junit.Before;
import org.junit.Test;
import repositories.LinkRepository;
import java.sql.SQLException;
import java.util.Optional;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RedirectService with mocked cache and persistence
 * dependencies.
 */
public class RedirectServiceTest {

    private RedisClient redisClient;
    private LinkRepository linkRepository;
    private RedirectService redirectService;

    @Before
    public void setUp() {
        redisClient = mock(RedisClient.class);
        linkRepository = mock(LinkRepository.class);
        redirectService = new RedirectService(redisClient, linkRepository);
    }

    @Test
    public void resolve_returnsCachedValue_onCacheHit() {
        when(redisClient.get("abc123")).thenReturn("https://bokun.io/tours/x");

        Optional<String> result = redirectService.resolve("abc123");

        assertEquals(Optional.of("https://bokun.io/tours/x"), result);
        verifyNoInteractions(linkRepository);
    }

    @Test
    public void resolve_fallsBackToDatabase_onCacheMiss() {
        when(redisClient.get("abc123")).thenReturn(null);
        when(linkRepository.findByCode("abc123")).thenReturn(Optional.of("https://bokun.io/tours/x"));

        Optional<String> result = redirectService.resolve("abc123");

        assertEquals(Optional.of("https://bokun.io/tours/x"), result);
        verify(redisClient).set("abc123", "https://bokun.io/tours/x");
    }

    @Test
    public void resolve_returnsEmpty_whenCodeDoesNotExist() {
        when(redisClient.get("missing")).thenReturn(null);
        when(linkRepository.findByCode("missing")).thenReturn(Optional.empty());

        Optional<String> result = redirectService.resolve("missing");

        assertTrue(result.isEmpty());
        verify(redisClient, never()).set(any(), any());
    }

    @Test(expected = RedirectService.ResolutionException.class)
    public void resolve_throwsResolutionException_whenDatabaseFails() {
        when(redisClient.get("abc123")).thenReturn(null);
        when(linkRepository.findByCode("abc123"))
                .thenThrow(new RuntimeException(new SQLException("connection refused")));

        redirectService.resolve("abc123");
    }

    @Test
    public void resolve_rethrowsRawException_whenFailureIsNotDatabaseRelated() {
        when(redisClient.get("abc123")).thenReturn(null);
        RuntimeException bug = new NullPointerException("unexpected bug");
        when(linkRepository.findByCode("abc123")).thenThrow(bug);

        try {
            redirectService.resolve("abc123");
            fail("expected the original exception to propagate");
        } catch (RedirectService.ResolutionException e) {
            fail("a non-database failure should not be wrapped as ResolutionException");
        } catch (NullPointerException e) {
            assertSame(bug, e);
        }
    }
}