package services;

import clients.SqsDrainer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import play.inject.ApplicationLifecycle;
import repositories.ClickRepository;
import software.amazon.awssdk.services.sqs.model.Message;

import java.time.Instant;
import java.util.concurrent.Callable;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import org.mockito.InOrder;


/**
 * Unit tests for ClickDrainService.
 * Tests message processing in isolation with mocked dependencies.
 */
public class ClickDrainServiceTest {

    private SqsDrainer sqsDrainer;
    private ClickRepository clickRepository;
    private ClickDrainService service;
    private Callable<?> stopHook;

    @Before
    public void setUp() {
        sqsDrainer = mock(SqsDrainer.class);
        clickRepository = mock(ClickRepository.class);
        ApplicationLifecycle lifecycle = mock(ApplicationLifecycle.class);

        // Capture the stop hook so tearDown can cleanly shut down the background poll thread.
        doAnswer(inv -> { stopHook = inv.getArgument(0); return null; })
            .when(lifecycle).addStopHook(any());

        service = new ClickDrainService(sqsDrainer, clickRepository, lifecycle);
    }

    @After
    public void tearDown() throws Exception {
        // Signal the background thread to stop; avoids thread leaks between test runs.
        if (stopHook != null) stopHook.call();
    }

    @Test
    public void processMessage_savesClickToRepository() {
        Message message = Message.builder()
            .body("{\"code\":\"abc12345\",\"clickedAt\":\"2024-01-01T10:00:00Z\"}")
            .receiptHandle("receipt-1")
            .build();

        service.processMessage(message);

        verify(clickRepository).save("abc12345", Instant.parse("2024-01-01T10:00:00Z"));
    }

    @Test
    public void processMessage_deletesMessageAfterSave() {
        Message message = Message.builder()
            .body("{\"code\":\"abc12345\",\"clickedAt\":\"2024-01-01T10:00:00Z\"}")
            .receiptHandle("receipt-1")
            .build();

        service.processMessage(message);

        // Save must come before delete. If the process crashes between the two, SQS
        // redelivers the message rather than losing the click — this ordering is the
        // whole point of not deleting up-front.
        InOrder order = inOrder(clickRepository, sqsDrainer);
        order.verify(clickRepository).save(eq("abc12345"), any(Instant.class));
        order.verify(sqsDrainer).deleteMessage("receipt-1");
    }

    @Test
    public void processMessage_doesNotDeleteIfSaveFails() {
        doThrow(new RuntimeException("db down")).when(clickRepository).save(any(), any());
        Message message = Message.builder()
            .body("{\"code\":\"abc12345\",\"clickedAt\":\"2024-01-01T10:00:00Z\"}")
            .receiptHandle("receipt-1")
            .build();

        try {
            service.processMessage(message);
        } catch (RuntimeException ignored) {
            // expected — the point is that deleteMessage was never called
        }

        verify(sqsDrainer, never()).deleteMessage(any());
    }
}
