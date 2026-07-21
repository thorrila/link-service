package services;

import clients.RedisClient;
import com.typesafe.config.Config;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import repositories.LinkRepository;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.security.SecureRandom;

/**
 * Called once per outbound email, before it is sent.
 * Rewrites every embedded link to a managed URL, storing code -> original URL
 * in MySQL and Redis.
 */
@Singleton
public class LinkTransformService {

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_LENGTH = 8;
    private static final int MAX_CODE_ATTEMPTS = 5;
    // SecureRandom, not Random, because codes are guessable/enumerable otherwise.
    private final SecureRandom random = new SecureRandom();
    private final LinkRepository linkRepository;
    private final RedisClient redisClient;
    private final String baseUrl;

    @Inject
    public LinkTransformService(LinkRepository linkRepository, RedisClient redisClient, Config config) {
        this.linkRepository = linkRepository;
        this.redisClient = redisClient;
        this.baseUrl = config.getString("service.baseUrl");
    }

    /**
     * Transforms all embedded links in the given email HTML/text into
     * managed redirect URLs, and returns the transformed content.
     */
    public String transform(String emailContent) {
        Document doc = Jsoup.parse(emailContent);
        Elements links = doc.select("a[href]");

        for (Element link : links) {
            String originalUrl = link.attr("href");
            if (originalUrl.isBlank()) {
                continue;
            }

            String code = saveWithUniqueCode(originalUrl);
            redisClient.set(code, originalUrl);

            link.attr("href", baseUrl + "/r/" + code);
        }
        return doc.outerHtml();
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private String saveWithUniqueCode(String originalUrl) {
        // For 62^8 possible codes, a collision here is very unlikely, but not impossible.
        // Retries a few times if a collision occurs, then fails.
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_CODE_ATTEMPTS; attempt++) {
            String code = generateCode();
            try {
                linkRepository.save(code, originalUrl);
                return code;
            } catch (LinkRepository.DuplicateCodeException e) {
                lastFailure = e;
            }
        }
        throw new IllegalStateException(
                "Failed to generate a unique short code after " + MAX_CODE_ATTEMPTS + " attempts", lastFailure);
    }
}
