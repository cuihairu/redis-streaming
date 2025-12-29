package io.github.cuihairu.redis.streaming.cdc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CDCEventListener interface
 */
class CDCEventListenerTest {

    @Test
    void testDefaultInterfaceMethodsDoNotThrowExceptions() {
        CDCEventListener listener = new CDCEventListener() {};

        assertDoesNotThrow(() -> listener.onConnectorStarted("test-connector"));
        assertDoesNotThrow(() -> listener.onConnectorStopped("test-connector"));
        assertDoesNotThrow(() -> listener.onEventsCapture("test-connector", 10));
        assertDoesNotThrow(() -> listener.onConnectorError("test-connector", new RuntimeException("test error")));
        assertDoesNotThrow(() -> listener.onHealthStatusChanged("test-connector",
                CDCHealthStatus.healthy("OK"), CDCHealthStatus.unhealthy("Error")));
        assertDoesNotThrow(() -> listener.onPositionCommitted("test-connector", "position-123"));
        assertDoesNotThrow(() -> listener.onSnapshotStarted("test-connector", 5));
        assertDoesNotThrow(() -> listener.onSnapshotCompleted("test-connector", 1000));
    }

    @Test
    void testCustomListenerImplementation() {
        boolean[] started = {false};
        boolean[] stopped = {false};
        int[] eventCount = {0};

        CDCEventListener listener = new CDCEventListener() {
            @Override
            public void onConnectorStarted(String connectorName) {
                started[0] = true;
                assertEquals("my-connector", connectorName);
            }

            @Override
            public void onConnectorStopped(String connectorName) {
                stopped[0] = true;
                assertEquals("my-connector", connectorName);
            }

            @Override
            public void onEventsCapture(String connectorName, int count) {
                eventCount[0] = count;
                assertEquals("my-connector", connectorName);
            }
        };

        assertFalse(started[0]);
        assertFalse(stopped[0]);
        assertEquals(0, eventCount[0]);

        listener.onConnectorStarted("my-connector");
        assertTrue(started[0]);

        listener.onConnectorStopped("my-connector");
        assertTrue(stopped[0]);

        listener.onEventsCapture("my-connector", 42);
        assertEquals(42, eventCount[0]);
    }

    @Test
    void testListenerWithErrorHandling() {
        Throwable[] capturedError = new Throwable[1];

        CDCEventListener listener = new CDCEventListener() {
            @Override
            public void onConnectorError(String connectorName, Throwable error) {
                capturedError[0] = error;
                assertEquals("error-connector", connectorName);
            }
        };

        RuntimeException testError = new RuntimeException("Test error");
        listener.onConnectorError("error-connector", testError);

        assertNotNull(capturedError[0]);
        assertSame(testError, capturedError[0]);
    }

    @Test
    void testListenerWithHealthStatusChanges() {
        CDCHealthStatus[] oldStatus = new CDCHealthStatus[1];
        CDCHealthStatus[] newStatus = new CDCHealthStatus[1];

        CDCEventListener listener = new CDCEventListener() {
            @Override
            public void onHealthStatusChanged(String connectorName, CDCHealthStatus oldS, CDCHealthStatus newS) {
                assertEquals("health-connector", connectorName);
                oldStatus[0] = oldS;
                newStatus[0] = newS;
            }
        };

        CDCHealthStatus healthy = CDCHealthStatus.healthy("OK");
        CDCHealthStatus unhealthy = CDCHealthStatus.unhealthy("Error");
        listener.onHealthStatusChanged("health-connector", healthy, unhealthy);

        assertEquals(CDCHealthStatus.Status.HEALTHY, oldStatus[0].getStatus());
        assertEquals(CDCHealthStatus.Status.UNHEALTHY, newStatus[0].getStatus());
    }

    @Test
    void testListenerWithPositionCommit() {
        String[] capturedPosition = new String[1];

        CDCEventListener listener = new CDCEventListener() {
            @Override
            public void onPositionCommitted(String connectorName, String position) {
                assertEquals("position-connector", connectorName);
                capturedPosition[0] = position;
            }
        };

        listener.onPositionCommitted("position-connector", "mysql-bin.000003:12345");

        assertEquals("mysql-bin.000003:12345", capturedPosition[0]);
    }

    @Test
    void testListenerWithSnapshotEvents() {
        int[] tableCount = new int[1];
        long[] recordCount = new long[1];

        CDCEventListener listener = new CDCEventListener() {
            @Override
            public void onSnapshotStarted(String connectorName, int count) {
                assertEquals("snapshot-connector", connectorName);
                tableCount[0] = count;
            }

            @Override
            public void onSnapshotCompleted(String connectorName, long count) {
                assertEquals("snapshot-connector", connectorName);
                recordCount[0] = count;
            }
        };

        listener.onSnapshotStarted("snapshot-connector", 10);
        assertEquals(10, tableCount[0]);

        listener.onSnapshotCompleted("snapshot-connector", 50000);
        assertEquals(50000, recordCount[0]);
    }

    @Test
    void testMultipleListenerInstances() {
        int[] callCount1 = {0};
        int[] callCount2 = {0};

        CDCEventListener listener1 = new CDCEventListener() {
            @Override
            public void onEventsCapture(String connectorName, int eventCount) {
                callCount1[0]++;
            }
        };

        CDCEventListener listener2 = new CDCEventListener() {
            @Override
            public void onEventsCapture(String connectorName, int eventCount) {
                callCount2[0]++;
            }
        };

        listener1.onEventsCapture("connector1", 10);
        listener1.onEventsCapture("connector2", 20);

        listener2.onEventsCapture("connector1", 10);
        listener2.onEventsCapture("connector2", 20);

        assertEquals(2, callCount1[0]);
        assertEquals(2, callCount2[0]);
    }

    @Test
    void testListenerWithNullConnectorName() {
        String[] capturedName = new String[1];

        CDCEventListener listener = new CDCEventListener() {
            @Override
            public void onConnectorStarted(String connectorName) {
                capturedName[0] = connectorName;
            }
        };

        listener.onConnectorStarted(null);
        assertNull(capturedName[0]);
    }

    @Test
    void testListenerWithEmptyConnectorName() {
        String[] capturedName = new String[1];

        CDCEventListener listener = new CDCEventListener() {
            @Override
            public void onConnectorStarted(String connectorName) {
                capturedName[0] = connectorName;
            }
        };

        listener.onConnectorStarted("");
        assertEquals("", capturedName[0]);
    }

    @Test
    void testListenerWithSpecialCharactersInConnectorName() {
        String[] capturedName = new String[1];

        CDCEventListener listener = new CDCEventListener() {
            @Override
            public void onConnectorStarted(String connectorName) {
                capturedName[0] = connectorName;
            }
        };

        String specialName = "connector-with_special.chars:123";
        listener.onConnectorStarted(specialName);
        assertEquals(specialName, capturedName[0]);
    }

    @Test
    void testListenerWithZeroEventCount() {
        int[] capturedCount = new int[1];

        CDCEventListener listener = new CDCEventListener() {
            @Override
            public void onEventsCapture(String connectorName, int eventCount) {
                capturedCount[0] = eventCount;
            }
        };

        listener.onEventsCapture("test-connector", 0);
        assertEquals(0, capturedCount[0]);
    }

    @Test
    void testListenerWithLargeEventCount() {
        int[] capturedCount = new int[1];

        CDCEventListener listener = new CDCEventListener() {
            @Override
            public void onEventsCapture(String connectorName, int eventCount) {
                capturedCount[0] = eventCount;
            }
        };

        listener.onEventsCapture("test-connector", 1000000);
        assertEquals(1000000, capturedCount[0]);
    }

    @Test
    void testListenerWithNullError() {
        Throwable[] capturedError = new Throwable[1];

        CDCEventListener listener = new CDCEventListener() {
            @Override
            public void onConnectorError(String connectorName, Throwable error) {
                capturedError[0] = error;
            }
        };

        listener.onConnectorError("test-connector", null);
        assertNull(capturedError[0]);
    }

    @Test
    void testListenerWithDifferentErrorTypes() {
        Class<?>[] errorTypes = new Class<?>[3];

        CDCEventListener listener = new CDCEventListener() {
            @Override
            public void onConnectorError(String connectorName, Throwable error) {
                // IllegalArgumentException extends RuntimeException, so check order matters
                if (error instanceof InterruptedException) {
                    errorTypes[1] = error.getClass();
                } else if (error instanceof IllegalArgumentException) {
                    errorTypes[2] = error.getClass();
                } else if (error instanceof RuntimeException) {
                    errorTypes[0] = error.getClass();
                }
            }
        };

        listener.onConnectorError("test", new RuntimeException("error1"));
        listener.onConnectorError("test", new InterruptedException("error2"));
        listener.onConnectorError("test", new IllegalArgumentException("error3"));

        assertEquals(RuntimeException.class, errorTypes[0]);
        assertEquals(InterruptedException.class, errorTypes[1]);
        assertEquals(IllegalArgumentException.class, errorTypes[2]);
    }

    @Test
    void testListenerWithAllHealthStatusTransitions() {
        java.util.List<CDCHealthStatus.Status[]> transitions = new java.util.ArrayList<>();

        CDCEventListener listener = new CDCEventListener() {
            @Override
            public void onHealthStatusChanged(String connectorName, CDCHealthStatus oldS, CDCHealthStatus newS) {
                transitions.add(new CDCHealthStatus.Status[]{oldS.getStatus(), newS.getStatus()});
            }
        };

        listener.onHealthStatusChanged("test", CDCHealthStatus.healthy("OK"), CDCHealthStatus.unhealthy("Error"));
        listener.onHealthStatusChanged("test", CDCHealthStatus.unhealthy("Error"), CDCHealthStatus.healthy("OK"));
        listener.onHealthStatusChanged("test", CDCHealthStatus.healthy("OK"), CDCHealthStatus.degraded("Warning"));
        listener.onHealthStatusChanged("test", CDCHealthStatus.degraded("Warning"), CDCHealthStatus.healthy("OK"));
        listener.onHealthStatusChanged("test", CDCHealthStatus.degraded("Warning"), CDCHealthStatus.unhealthy("Error"));
        listener.onHealthStatusChanged("test", CDCHealthStatus.unhealthy("Error"), CDCHealthStatus.degraded("Warning"));

        assertEquals(6, transitions.size());
        assertArrayEquals(new CDCHealthStatus.Status[]{CDCHealthStatus.Status.HEALTHY, CDCHealthStatus.Status.UNHEALTHY}, transitions.get(0));
        assertArrayEquals(new CDCHealthStatus.Status[]{CDCHealthStatus.Status.UNHEALTHY, CDCHealthStatus.Status.HEALTHY}, transitions.get(1));
        assertArrayEquals(new CDCHealthStatus.Status[]{CDCHealthStatus.Status.HEALTHY, CDCHealthStatus.Status.DEGRADED}, transitions.get(2));
        assertArrayEquals(new CDCHealthStatus.Status[]{CDCHealthStatus.Status.DEGRADED, CDCHealthStatus.Status.HEALTHY}, transitions.get(3));
        assertArrayEquals(new CDCHealthStatus.Status[]{CDCHealthStatus.Status.DEGRADED, CDCHealthStatus.Status.UNHEALTHY}, transitions.get(4));
        assertArrayEquals(new CDCHealthStatus.Status[]{CDCHealthStatus.Status.UNHEALTHY, CDCHealthStatus.Status.DEGRADED}, transitions.get(5));
    }

    @Test
    void testListenerWithNullPosition() {
        String[] capturedPosition = new String[1];

        CDCEventListener listener = new CDCEventListener() {
            @Override
            public void onPositionCommitted(String connectorName, String position) {
                capturedPosition[0] = position;
            }
        };

        listener.onPositionCommitted("test-connector", null);
        assertNull(capturedPosition[0]);
    }

    @Test
    void testListenerWithZeroTableCount() {
        int[] tableCount = new int[1];

        CDCEventListener listener = new CDCEventListener() {
            @Override
            public void onSnapshotStarted(String connectorName, int count) {
                tableCount[0] = count;
            }
        };

        listener.onSnapshotStarted("test-connector", 0);
        assertEquals(0, tableCount[0]);
    }

    @Test
    void testListenerWithZeroRecordCount() {
        long[] recordCount = new long[1];

        CDCEventListener listener = new CDCEventListener() {
            @Override
            public void onSnapshotCompleted(String connectorName, long count) {
                recordCount[0] = count;
            }
        };

        listener.onSnapshotCompleted("test-connector", 0);
        assertEquals(0, recordCount[0]);
    }

    @Test
    void testListenerWithLargeRecordCount() {
        long[] recordCount = new long[1];

        CDCEventListener listener = new CDCEventListener() {
            @Override
            public void onSnapshotCompleted(String connectorName, long count) {
                recordCount[0] = count;
            }
        };

        listener.onSnapshotCompleted("test-connector", 10_000_000_000L);
        assertEquals(10_000_000_000L, recordCount[0]);
    }
}
