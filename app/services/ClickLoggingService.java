package services;

import clients.SqsPublisher;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;

/**
 * Called by RedirectController after every successful redirect.
 * Publishes a click event to SQS, ClickDrainService drains it into MySQL.
 */
@Singleton
public class ClickLoggingService {

    private final SqsPublisher sqsPublisher;

    @Inject
    public ClickLoggingService(SqsPublisher sqsPublisher) {
        this.sqsPublisher = sqsPublisher;
    }

    public void logClick(String code) {
        String message = String.format(
                "{\"code\":\"%s\",\"clickedAt\":\"%s\"}",
                code, Instant.now().toString()
        );
        sqsPublisher.publish(message);
    }
}
