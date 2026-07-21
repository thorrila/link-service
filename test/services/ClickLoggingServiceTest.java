package services;

import clients.SqsPublisher;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ClickLoggingService.
 * Verifies that click events are published to SQS with the expected payload.
 */
public class ClickLoggingServiceTest {

    private SqsPublisher sqsPublisher;
    private ClickLoggingService service;

    @Before
    public void setUp() {
        sqsPublisher = mock(SqsPublisher.class);
        service = new ClickLoggingService(sqsPublisher);
    }

    @Test
    public void logClick_callsPublishExactlyOnce() {
        service.logClick("abc12345");
        verify(sqsPublisher, times(1)).publish(anyString());
    }

    @Test
    public void logClick_publishedMessageContainsCode() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);

        service.logClick("abc12345");

        verify(sqsPublisher).publish(captor.capture());
        assertTrue("message must contain the code",
            captor.getValue().contains("\"code\":\"abc12345\""));
    }

    @Test
    public void logClick_publishedMessageContainsClickedAt() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);

        service.logClick("abc12345");

        verify(sqsPublisher).publish(captor.capture());
        // ClickDrainService parses this field, so it must be present.
        assertTrue("message must contain a clickedAt timestamp",
            captor.getValue().contains("\"clickedAt\":"));
    }
}
