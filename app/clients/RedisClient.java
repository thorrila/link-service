package clients;

import com.typesafe.config.Config;

import play.Logger;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisException;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Duration;

/**
 * A thin Redis wrapper that delegates to MySQL when Redis is unavailable.
 */
@Singleton
public class RedisClient {

    private static final Logger.ALogger logger = Logger.of(RedisClient.class);
    // Fails fast on a hung connection instead of blocking the redirect for the much longer default timeout.
    private static final int CONNECT_TIMEOUT_MS = 200;
    private final int ttlSeconds;
    private final JedisPool pool;

    @Inject
    public RedisClient(Config config) {
        String host = config.getString("redis.host");
        int port = config.getInt("redis.port");
        this.ttlSeconds = config.getInt("redis.linkTtlSeconds");

        // Pool tuning for high-traffic redirect lookups with a fallback to MySQL.
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(64);
        poolConfig.setMaxIdle(32);
        // Periodically checks idle connections in the background instead of on every borrow.
        poolConfig.setTestWhileIdle(true);
        // Checks every 30 seconds instead of the default much longer interval.
        poolConfig.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));
        
        String password = config.getString("redis.password");
        if (password.isBlank()) {
            this.pool = new JedisPool(poolConfig, host, port, CONNECT_TIMEOUT_MS);
        } else {
            this.pool = new JedisPool(poolConfig, host, port, CONNECT_TIMEOUT_MS, password);
        }
    }

    public String get(String key) {
        // try-with-resources - returns the connection to the pool automatically, even on exception.
        try (Jedis jedis = pool.getResource()) {
            return jedis.get(key);
        } catch (JedisException e) {
            logger.warn("Redis GET failed, falling back to MySQL for key={}", key, e);
            return null; // caller's fallback (MySQL) takes it from here
        }
    }

    public void set(String key, String value) {
        try (Jedis jedis = pool.getResource()) {
            jedis.setex(key, ttlSeconds, value); // TTL bounds memory growth, keeps hit rate high
        } catch (JedisException e) {
            logger.warn("Redis SET failed for key={} — link still durable in MySQL", key, e);
        }
    }
}