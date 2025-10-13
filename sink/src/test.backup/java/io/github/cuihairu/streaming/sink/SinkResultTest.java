package io.github.cuihairu.redis.streaming.sink;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SinkResultTest {

    @Test
    void testSuccessResult() {
        SinkResult result = SinkResult.success(10);

        assertEquals(SinkResult.Status.SUCCESS, result.getStatus());
        assertEquals(10, result.getRecordsWritten());
        assertEquals(0, result.getRecordsFailed());
        assertTrue(result.isSuccess());
        assertFalse(result.isFailure());
        assertFalse(result.hasErrors());
    }

    @Test
    void testSuccessResultWithMessage() {
        SinkResult result = SinkResult.success(5, "All records processed");

        assertEquals(SinkResult.Status.SUCCESS, result.getStatus());
        assertEquals(5, result.getRecordsWritten());
        assertEquals("All records processed", result.getMessage());
    }

    @Test
    void testPartialSuccessResult() {
        SinkRecord failedRecord = new SinkRecord("topic", "failed-value");
        SinkError error = new SinkError(failedRecord, "Processing failed");
        List<SinkError> errors = List.of(error);

        SinkResult result = SinkResult.partialSuccess(8, 2, errors);

        assertEquals(SinkResult.Status.PARTIAL_SUCCESS, result.getStatus());
        assertEquals(8, result.getRecordsWritten());
        assertEquals(2, result.getRecordsFailed());
        assertFalse(result.isSuccess());
        assertFalse(result.isFailure());
        assertTrue(result.hasErrors());
        assertEquals(1, result.getErrors().size());
    }

    @Test
    void testFailureResult() {
        SinkResult result = SinkResult.failure("Connection failed");

        assertEquals(SinkResult.Status.FAILURE, result.getStatus());
        assertEquals(0, result.getRecordsWritten());
        assertEquals("Connection failed", result.getMessage());
        assertFalse(result.isSuccess());
        assertTrue(result.isFailure());
    }

    @Test
    void testFailureResultWithErrors() {
        SinkRecord failedRecord = new SinkRecord("topic", "failed-value");
        SinkError error = new SinkError(failedRecord, "Validation failed");
        List<SinkError> errors = List.of(error);

        SinkResult result = SinkResult.failure(3, "Multiple failures", errors);

        assertEquals(SinkResult.Status.FAILURE, result.getStatus());
        assertEquals(0, result.getRecordsWritten());
        assertEquals(3, result.getRecordsFailed());
        assertEquals("Multiple failures", result.getMessage());
        assertTrue(result.hasErrors());
    }

    @Test
    void testStatusConstructor() {
        SinkResult result = new SinkResult(SinkResult.Status.SUCCESS, 15, 0);

        assertEquals(SinkResult.Status.SUCCESS, result.getStatus());
        assertEquals(15, result.getRecordsWritten());
        assertEquals(0, result.getRecordsFailed());
        assertNotNull(result.getTimestamp());
    }
}