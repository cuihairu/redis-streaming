package io.github.cuihairu.redis.streaming.mq.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PausableMessageConsumer interface
 */
class PausableMessageConsumerTest {

    @Test
    void testInterfaceHasPauseMethod() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(PausableMessageConsumer.class.getMethod("pause"));
    }

    @Test
    void testInterfaceHasResumeMethod() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(PausableMessageConsumer.class.getMethod("resume"));
    }

    @Test
    void testInterfaceHasIsPausedMethod() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(PausableMessageConsumer.class.getMethod("isPaused"));
    }

    @Test
    void testInterfaceHasInFlightMethod() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(PausableMessageConsumer.class.getMethod("inFlight"));
    }

    @Test
    void testSimpleImplementation() {
        // Given
        PausableMessageConsumer consumer = new PausableMessageConsumer() {
            private boolean paused = false;
            private long inFlightCount = 0;

            @Override
            public void pause() {
                paused = true;
            }

            @Override
            public void resume() {
                paused = false;
            }

            @Override
            public boolean isPaused() {
                return paused;
            }

            @Override
            public long inFlight() {
                return inFlightCount;
            }
        };

        // When & Then - initial state
        assertFalse(consumer.isPaused());
        assertEquals(0, consumer.inFlight());
    }

    @Test
    void testPauseAndResume() {
        // Given
        PausableMessageConsumer consumer = new PausableMessageConsumer() {
            private boolean paused = false;

            @Override
            public void pause() {
                paused = true;
            }

            @Override
            public void resume() {
                paused = false;
            }

            @Override
            public boolean isPaused() {
                return paused;
            }

            @Override
            public long inFlight() {
                return 0;
            }
        };

        // When - pause
        consumer.pause();

        // Then
        assertTrue(consumer.isPaused());

        // When - resume
        consumer.resume();

        // Then
        assertFalse(consumer.isPaused());
    }

    @Test
    void testMultiplePauseCalls() {
        // Given
        PausableMessageConsumer consumer = new PausableMessageConsumer() {
            private boolean paused = false;

            @Override
            public void pause() {
                paused = true;
            }

            @Override
            public void resume() {
                paused = false;
            }

            @Override
            public boolean isPaused() {
                return paused;
            }

            @Override
            public long inFlight() {
                return 0;
            }
        };

        // When - pause multiple times
        consumer.pause();
        consumer.pause();
        consumer.pause();

        // Then - should still be paused
        assertTrue(consumer.isPaused());
    }

    @Test
    void testMultipleResumeCalls() {
        // Given
        PausableMessageConsumer consumer = new PausableMessageConsumer() {
            private boolean paused = false;

            @Override
            public void pause() {
                paused = true;
            }

            @Override
            public void resume() {
                paused = false;
            }

            @Override
            public boolean isPaused() {
                return paused;
            }

            @Override
            public long inFlight() {
                return 0;
            }
        };

        consumer.pause();

        // When - resume multiple times
        consumer.resume();
        consumer.resume();
        consumer.resume();

        // Then - should still be resumed
        assertFalse(consumer.isPaused());
    }

    @Test
    void testInFlightCount() {
        // Given
        PausableMessageConsumer consumer = new PausableMessageConsumer() {
            private long inFlightCount = 5;

            @Override
            public void pause() {
            }

            @Override
            public void resume() {
            }

            @Override
            public boolean isPaused() {
                return false;
            }

            @Override
            public long inFlight() {
                return inFlightCount;
            }
        };

        // When & Then
        assertEquals(5, consumer.inFlight());
    }

    @Test
    void testInFlightNeverNegative() {
        // Given
        PausableMessageConsumer consumer = new PausableMessageConsumer() {
            private long inFlightCount = 0;

            @Override
            public void pause() {
            }

            @Override
            public void resume() {
            }

            @Override
            public boolean isPaused() {
                return false;
            }

            @Override
            public long inFlight() {
                return inFlightCount;
            }
        };

        // When & Then - should not go negative
        assertEquals(0, consumer.inFlight());
        assertTrue(consumer.inFlight() >= 0);
    }

    @Test
    void testPauseWithInFlight() {
        // Given
        PausableMessageConsumer consumer = new PausableMessageConsumer() {
            private boolean paused = false;
            private long inFlightCount = 5;

            @Override
            public void pause() {
                paused = true;
            }

            @Override
            public void resume() {
                paused = false;
            }

            @Override
            public boolean isPaused() {
                return paused;
            }

            @Override
            public long inFlight() {
                return inFlightCount;
            }
        };

        // When - pause while having in-flight messages
        consumer.pause();

        // Then - should be paused with in-flight count preserved
        assertTrue(consumer.isPaused());
        assertEquals(5, consumer.inFlight());
    }

    @Test
    void testResumeWithInFlight() {
        // Given
        PausableMessageConsumer consumer = new PausableMessageConsumer() {
            private boolean paused = true;
            private long inFlightCount = 10;

            @Override
            public void pause() {
                paused = true;
            }

            @Override
            public void resume() {
                paused = false;
            }

            @Override
            public boolean isPaused() {
                return paused;
            }

            @Override
            public long inFlight() {
                return inFlightCount;
            }
        };

        // When - resume while having in-flight messages
        consumer.resume();

        // Then - should be resumed with in-flight count preserved
        assertFalse(consumer.isPaused());
        assertEquals(10, consumer.inFlight());
    }

    @Test
    void testTogglePauseResume() {
        // Given
        PausableMessageConsumer consumer = new PausableMessageConsumer() {
            private boolean paused = false;

            @Override
            public void pause() {
                paused = true;
            }

            @Override
            public void resume() {
                paused = false;
            }

            @Override
            public boolean isPaused() {
                return paused;
            }

            @Override
            public long inFlight() {
                return 0;
            }
        };

        // When & Then - toggle multiple times
        assertFalse(consumer.isPaused());

        consumer.pause();
        assertTrue(consumer.isPaused());

        consumer.resume();
        assertFalse(consumer.isPaused());

        consumer.pause();
        assertTrue(consumer.isPaused());

        consumer.resume();
        assertFalse(consumer.isPaused());
    }

    @Test
    void testLargeInFlightCount() {
        // Given
        PausableMessageConsumer consumer = new PausableMessageConsumer() {
            private long inFlightCount = Long.MAX_VALUE;

            @Override
            public void pause() {
            }

            @Override
            public void resume() {
            }

            @Override
            public boolean isPaused() {
                return false;
            }

            @Override
            public long inFlight() {
                return inFlightCount;
            }
        };

        // When & Then
        assertEquals(Long.MAX_VALUE, consumer.inFlight());
    }
}
