package repositories;

import play.db.Database;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.sql.ResultSet;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Optional;

/**
 * Called on link creation, or on a Redis cache miss during redirect.
 * Durable storage for code -> URL mappings
 * The source of truth; Redis is just a cache in front.
 */
@Singleton
public class LinkRepository {

    private final Database db;

    // Signals "generate a different code and retry" to LinkTransformService, not a real error.
    public static class DuplicateCodeException extends RuntimeException {
    }

    @Inject
    public LinkRepository(Database db) {
        this.db = db;
    }
    
    public void save(String code, String originalUrl) {
        String sql = "INSERT INTO links (code, original_url, created_at) VALUES (?, ?, ?)";
        try {
            db.withConnection(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    stmt.setString(1, code);
                    stmt.setString(2, originalUrl);
                    stmt.setTimestamp(3, Timestamp.from(Instant.now()));
                    stmt.executeUpdate();
                }
                return null; // withConnection's lambda requires a return value, none needed here
            });
        } catch (RuntimeException e) {
            // Translate the driver's generic exception into a typed one the caller can retry on.
            if (isDuplicateKey(e)) {
                throw new DuplicateCodeException();
            }
            throw e;
        }
    }

    private boolean isDuplicateKey(Throwable e) {
        // JDBC wraps the real cause, walk the chain instead of checking e's own type.
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLIntegrityConstraintViolationException) {
                return true;
            }
        }
        return false;
    }

    public Optional<String> findByCode(String code) {
        String sql = "SELECT original_url FROM links WHERE code = ?";
        // Failures propagate as-is; RedirectService decides whether this counts as an outage.
        return db.withConnection(connection -> {
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, code);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(rs.getString("original_url"));
                    }
                    return Optional.<String>empty();
                }
            }
        });
    }
}
