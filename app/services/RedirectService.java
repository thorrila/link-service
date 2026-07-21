package services;

import clients.RedisClient;
import repositories.LinkRepository;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Optional;

/**
 * Called by RedirectController on every redirect.
 * Resolves a code to its original URL — Redis first, MySQL on a cache miss.
 */
@Singleton
public class RedirectService {

    // Thrown only when both Redis and MySQL have failed 
    // RedirectController catches this specifically to return a 503 instead of a generic 500.
    public static class ResolutionException extends RuntimeException {
        public ResolutionException(Throwable cause) {
            super(cause);
        }
    }

    private final RedisClient redisClient;
    private final LinkRepository linkRepository;

    @Inject
    public RedirectService(RedisClient redisClient, LinkRepository linkRepository) {
        this.redisClient = redisClient;
        this.linkRepository = linkRepository;
    }

    public Optional<String> resolve(String code) {
        // never throws, null means miss or Redis trouble
        String cached = redisClient.get(code); 
        if (cached != null) {
            return Optional.of(cached);
        }

        try {
            return linkRepository.findByCode(code).map(originalUrl -> {
                redisClient.set(code, originalUrl);
                return originalUrl;
            });
        } catch (RuntimeException e) {
            if (isDatabaseFailure(e)) {
                // MySQL is the source of truth, so this failing is a real outage
                throw new ResolutionException(e); 
            }
            // not a DB failure, a real bug, let it surface as an actual 500
            throw e; 
        }
    }

    private boolean isDatabaseFailure(Throwable e) {
        // JDBC wraps the real cause, walk the chain instead of checking e's own type.
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException) {
                return true;
            }
        }
        return false;
    }
}