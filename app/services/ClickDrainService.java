package services;

import clients.SqsDrainer;
import play.inject.ApplicationLifecycle;
import play.libs.Json;
import play.Logger;

import repositories.ClickRepository;
import software.amazon.awssdk.services.sqs.model.Message;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Started at app boot by Module.java, not called by any request.
 * Background loop: drains SQS click events and persists each one into MySQL.
 */
@Singleton
public class ClickDrainService {

    private final SqsDrainer sqsDrainer;
    private final ClickRepository clickRepository;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Logger.ALogger logger = Logger.of(ClickDrainService.class);
    // volatile - pollLoop() runs on a different thread than the stop hook that flips this.
    private volatile boolean running = true;

    @Inject
    public ClickDrainService(SqsDrainer sqsDrainer, ClickRepository clickRepository, ApplicationLifecycle lifecycle) {
        this.sqsDrainer = sqsDrainer;
        this.clickRepository = clickRepository;
        // Loop starts immediately, there's no separate "start()" call anywhere.
        executor.submit(this::pollLoop);
        // Play calls this on shutdown, so the loop and its thread don't outlive the app.
        lifecycle.addStopHook(() -> {
            running = false;
            executor.shutdownNow();
            return CompletableFuture.completedFuture(null);
        });
    }

   private void pollLoop() {
        while (running) {
            try {
                List<Message> messages = sqsDrainer.receiveMessages();
                for (Message message : messages) {
                    processMessage(message);
                }
            } catch (Exception e) {
                // Back off instead of retrying immediately. Keeps a sustained outage from
                // spinning the CPU and flooding logs.
                logger.error("Click-drain poll cycle failed", e);
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException interrupted) {
                    // Restore the interrupt flag before exiting. Standard practice so anything
                    // upstream checking interrupt status still sees it.
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    void processMessage(Message message) {
        var json = Json.parse(message.body());
        String code = json.get("code").asText();
        Instant clickedAt = Instant.parse(json.get("clickedAt").asText());

        // Delete only after a successful save. A crash between these two lines means
        // SQS redelivers the message rather than losing the click.
        clickRepository.save(code, clickedAt);
        sqsDrainer.deleteMessage(message.receiptHandle());
    }
}
