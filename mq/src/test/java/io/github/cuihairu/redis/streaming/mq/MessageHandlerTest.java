package io.github.cuihairu.redis.streaming.mq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MessageHandler interface
 */
class MessageHandlerTest {

    @Test
    void testInterfaceIsFunctionalInterface() {
        // Given & When & Then
        assertTrue(MessageHandler.class.isAnnotationPresent(FunctionalInterface.class));
    }

    @Test
    void testInterfaceHasHandleMethod() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MessageHandler.class.getMethod("handle", Message.class));
    }

    @Test
    void testLambdaImplementation() {
        // Given
        MessageHandler handler = message -> MessageHandleResult.SUCCESS;

        // When
        MessageHandleResult result = handler.handle(new Message());

        // Then
        assertEquals(MessageHandleResult.SUCCESS, result);
    }

    @Test
    void testMethodReferenceImplementation() {
        // Given - create a handler using method reference
        MessageHandler handler = this::processMessage;

        // When
        MessageHandleResult result = handler.handle(new Message());

        // Then
        assertEquals(MessageHandleResult.SUCCESS, result);
    }

    @Test
    void testAnonymousClassImplementation() {
        // Given - anonymous class implementation
        MessageHandler handler = new MessageHandler() {
            @Override
            public MessageHandleResult handle(Message message) {
                return MessageHandleResult.SUCCESS;
            }
        };

        // When
        MessageHandleResult result = handler.handle(new Message());

        // Then
        assertEquals(MessageHandleResult.SUCCESS, result);
    }

    @Test
    void testConditionalHandler() {
        // Given - handler that returns different results based on message content
        MessageHandler handler = message -> {
            if (message != null && message.getPayload() != null) {
                return MessageHandleResult.SUCCESS;
            } else {
                return MessageHandleResult.FAIL;
            }
        };

        // When & Then
        Message msgWithPayload = new Message();
        msgWithPayload.setPayload("test");
        assertEquals(MessageHandleResult.SUCCESS, handler.handle(msgWithPayload));

        Message msgWithoutPayload = new Message();
        assertEquals(MessageHandleResult.FAIL, handler.handle(msgWithoutPayload));
    }

    @Test
    void testChainedHandlers() {
        // Given - chain multiple handlers
        MessageHandler handler1 = message -> {
            // First handler processes
            return MessageHandleResult.SUCCESS;
        };

        MessageHandler handler2 = message -> {
            // Second handler also processes
            return MessageHandleResult.SUCCESS;
        };

        // When
        MessageHandleResult result1 = handler1.handle(new Message());
        MessageHandleResult result2 = handler2.handle(new Message());

        // Then
        assertEquals(MessageHandleResult.SUCCESS, result1);
        assertEquals(MessageHandleResult.SUCCESS, result2);
    }

    @Test
    void testHandlerWithMessageProcessing() {
        // Given - handler that processes message content
        MessageHandler handler = message -> {
            if (message != null) {
                Object payload = message.getPayload();
                if (payload instanceof String) {
                    String content = (String) payload;
                    if (content.startsWith("ERROR")) {
                        return MessageHandleResult.FAIL;
                    }
                }
            }
            return MessageHandleResult.SUCCESS;
        };

        // When & Then
        Message normalMsg = new Message();
        normalMsg.setPayload("Normal message");
        assertEquals(MessageHandleResult.SUCCESS, handler.handle(normalMsg));

        Message errorMsg = new Message();
        errorMsg.setPayload("ERROR: Something went wrong");
        assertEquals(MessageHandleResult.FAIL, handler.handle(errorMsg));
    }

    @Test
    void testHandlerThatThrowsException() {
        // Given - handler that may throw exception
        MessageHandler handler = message -> {
            if (message == null) {
                throw new IllegalArgumentException("Message cannot be null");
            }
            return MessageHandleResult.SUCCESS;
        };

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> handler.handle(null));
        assertEquals(MessageHandleResult.SUCCESS, handler.handle(new Message()));
    }

    @Test
    void testHandlerWithRetrying() {
        // Given - handler that simulates retry logic
        MessageHandler handler = message -> {
            int attempts = 0;
            MessageHandleResult result = MessageHandleResult.RETRY;
            while (attempts < 3 && result == MessageHandleResult.RETRY) {
                // Simulate processing
                attempts++;
                if (attempts >= 2) {
                    result = MessageHandleResult.SUCCESS;
                }
            }
            return result;
        };

        // When
        MessageHandleResult result = handler.handle(new Message());

        // Then
        assertEquals(MessageHandleResult.SUCCESS, result);
    }

    @Test
    void testHandlerWithDifferentResults() {
        // Given - handler that returns different results
        MessageHandler handler = message -> {
            if (message == null) {
                return MessageHandleResult.FAIL;
            } else if (message.getPayload() == null) {
                return MessageHandleResult.RETRY;
            } else {
                return MessageHandleResult.SUCCESS;
            }
        };

        // When & Then
        assertEquals(MessageHandleResult.FAIL, handler.handle(null));

        Message emptyMsg = new Message();
        assertEquals(MessageHandleResult.RETRY, handler.handle(emptyMsg));

        Message validMsg = new Message();
        validMsg.setPayload("data");
        assertEquals(MessageHandleResult.SUCCESS, handler.handle(validMsg));
    }

    @Test
    void testHandlerComposition() {
        // Given - compose multiple handlers
        MessageHandler validator = message -> {
            if (message == null || message.getPayload() == null) {
                return MessageHandleResult.FAIL;
            }
            return MessageHandleResult.SUCCESS;
        };

        MessageHandler processor = message -> {
            // Process the message
            return MessageHandleResult.SUCCESS;
        };

        // When - use handlers in sequence
        Message message = new Message();
        message.setPayload("test");

        MessageHandleResult validationResult = validator.handle(message);
        MessageHandleResult processorResult = processor.handle(message);

        // Then
        assertEquals(MessageHandleResult.SUCCESS, validationResult);
        assertEquals(MessageHandleResult.SUCCESS, processorResult);
    }

    @Test
    void testHandlerStateless() {
        // Given - stateless handler (should not maintain state between calls)
        MessageHandler handler = message -> MessageHandleResult.SUCCESS;

        // When - call multiple times
        MessageHandleResult result1 = handler.handle(new Message());
        MessageHandleResult result2 = handler.handle(new Message());
        MessageHandleResult result3 = handler.handle(new Message());

        // Then - all calls should return the same result
        assertEquals(MessageHandleResult.SUCCESS, result1);
        assertEquals(MessageHandleResult.SUCCESS, result2);
        assertEquals(MessageHandleResult.SUCCESS, result3);
    }

    // Helper method for method reference test
    private MessageHandleResult processMessage(Message message) {
        return MessageHandleResult.SUCCESS;
    }

    @Test
    void testHandlerWithMessageTransformation() {
        // Given - handler that transforms message
        MessageHandler handler = message -> {
            if (message != null && message.getPayload() instanceof String) {
                String content = (String) message.getPayload();
                // Transform to uppercase
                message.setPayload(content.toUpperCase());
            }
            return MessageHandleResult.SUCCESS;
        };

        // When
        Message message = new Message();
        message.setPayload("hello");
        handler.handle(message);

        // Then
        assertEquals("HELLO", message.getPayload());
    }

    @Test
    void testAllHandleResultTypes() {
        // Given - handler that can return all result types
        MessageHandler handler = message -> {
            if (message == null) {
                return MessageHandleResult.FAIL;
            }
            String payload = (String) message.getPayload();
            if (payload.startsWith("retry")) {
                return MessageHandleResult.RETRY;
            } else if (payload.startsWith("success")) {
                return MessageHandleResult.SUCCESS;
            } else {
                return MessageHandleResult.FAIL;
            }
        };

        // When & Then
        Message msg1 = new Message();
        msg1.setPayload("success-test");
        assertEquals(MessageHandleResult.SUCCESS, handler.handle(msg1));

        Message msg2 = new Message();
        msg2.setPayload("retry-test");
        assertEquals(MessageHandleResult.RETRY, handler.handle(msg2));

        Message msg3 = new Message();
        msg3.setPayload("fail-test");
        assertEquals(MessageHandleResult.FAIL, handler.handle(msg3));
    }
}
