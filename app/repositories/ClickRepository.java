package repositories;

import play.db.Database;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * Called only by ClickDrainService, once a click event is drained from SQS.
 * Durable storage for click history. 
 * A separate table from LinkRepository.
 */
@Singleton
public class ClickRepository {

    private final Database db;

    @Inject
    public ClickRepository(Database db) {
        this.db = db;
    }
    
    public void save(String code, Instant clickedAt) {
        String sql = "INSERT INTO clicks (code, clicked_at) VALUES (?, ?)";
        db.withConnection(connection -> {
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, code);
                stmt.setTimestamp(2, Timestamp.from(clickedAt));
                stmt.executeUpdate();
            }
            return null; // withConnection's lambda requires a return value - none needed here
        });
    }
}
