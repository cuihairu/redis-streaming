package io.github.cuihairu.redis.streaming.sink.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.sink.*;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * File sink connector for writing data to files
 */
@Slf4j
public class FileSinkConnector extends AbstractSinkConnector {

    private static final String DIRECTORY_PROPERTY = "directory";
    private static final String FILENAME_PROPERTY = "filename";
    private static final String FILENAME_PATTERN_PROPERTY = "filename.pattern";
    private static final String FORMAT_PROPERTY = "format";
    private static final String APPEND_PROPERTY = "append";
    private static final String ROTATION_SIZE_PROPERTY = "rotation.size.bytes";
    private static final String ROTATION_TIME_PROPERTY = "rotation.time.ms";

    public enum FileFormat {
        JSON, TEXT, CSV
    }

    private Path outputDirectory;
    private String filenamePattern;
    private String staticFilename;
    private FileFormat format;
    private boolean append;
    private long rotationSizeBytes;
    private long rotationTimeMs;
    private ObjectMapper objectMapper;
    private final ReadWriteLock fileLock = new ReentrantReadWriteLock();
    private volatile Path currentFile;
    private volatile long currentFileSize;
    private volatile Instant lastRotationTime;

    public FileSinkConnector(SinkConfiguration configuration) {
        super(configuration);
        this.objectMapper = new ObjectMapper();
    }

    @Override
    protected void doStart() throws Exception {
        // Validate configuration
        configuration.validate();

        // Parse configuration
        String directory = (String) configuration.getProperty(DIRECTORY_PROPERTY, "./output");
        this.outputDirectory = Paths.get(directory);
        this.staticFilename = (String) configuration.getProperty(FILENAME_PROPERTY);
        this.filenamePattern = (String) configuration.getProperty(FILENAME_PATTERN_PROPERTY, "data-{date}.{format}");
        String formatStr = (String) configuration.getProperty(FORMAT_PROPERTY, "JSON");
        this.format = FileFormat.valueOf(formatStr.toUpperCase());
        this.append = Boolean.parseBoolean(String.valueOf(configuration.getProperty(APPEND_PROPERTY, "true")));
        this.rotationSizeBytes = Long.parseLong(String.valueOf(configuration.getProperty(ROTATION_SIZE_PROPERTY, "0")));
        this.rotationTimeMs = Long.parseLong(String.valueOf(configuration.getProperty(ROTATION_TIME_PROPERTY, "0")));

        // Create output directory if it doesn't exist
        Files.createDirectories(outputDirectory);

        // Initialize current file
        this.currentFile = determineCurrentFile();
        this.currentFileSize = Files.exists(currentFile) ? Files.size(currentFile) : 0;
        this.lastRotationTime = Instant.now();

        log.info("File sink connector initialized: directory={}, format={}, append={}",
                outputDirectory, format, append);
    }

    @Override
    protected void doStop() throws Exception {
        // No specific cleanup needed for file connector
        log.debug("File sink connector stopped: {}", getName());
    }

    @Override
    protected SinkResult doWrite(Collection<SinkRecord> records) throws Exception {
        if (records.isEmpty()) {
            return SinkResult.success(0, "No records to write");
        }

        List<SinkError> errors = new ArrayList<>();
        int successCount = 0;

        fileLock.writeLock().lock();
        try {
            // Check if file rotation is needed
            if (needsRotation()) {
                rotateFile();
            }

            // Write records to file
            try (BufferedWriter writer = Files.newBufferedWriter(currentFile,
                    StandardCharsets.UTF_8,
                    append ? StandardOpenOption.CREATE : StandardOpenOption.CREATE,
                    append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {

                for (SinkRecord record : records) {
                    try {
                        String line = formatRecord(record);
                        writer.write(line);
                        writer.newLine();

                        successCount++;
                        currentFileSize += line.getBytes(StandardCharsets.UTF_8).length + 1; // +1 for newline

                    } catch (Exception e) {
                        errors.add(new SinkError(record, "Error formatting record: " + e.getMessage(), e));
                    }
                }

                writer.flush();

            } catch (IOException e) {
                log.error("Error writing to file: {}", currentFile, e);
                return SinkResult.failure("File write failed: " + e.getMessage());
            }

        } finally {
            fileLock.writeLock().unlock();
        }

        if (errors.isEmpty()) {
            return SinkResult.success(successCount, "All records written to " + currentFile.getFileName());
        } else {
            return SinkResult.partialSuccess(successCount, errors.size(), errors);
        }
    }

    @Override
    protected void doFlush() throws Exception {
        // Files are flushed after each write, no additional action needed
        log.debug("Flush requested for file connector: {}", getName());
    }

    private Path determineCurrentFile() {
        if (staticFilename != null) {
            return outputDirectory.resolve(staticFilename);
        }

        // Use pattern with date substitution
        String filename = filenamePattern
                .replace("{date}", DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm").format(
                        java.time.LocalDateTime.now()))
                .replace("{format}", format.name().toLowerCase())
                .replace("{timestamp}", String.valueOf(System.currentTimeMillis()));

        return outputDirectory.resolve(filename);
    }

    private boolean needsRotation() {
        if (staticFilename != null) {
            return false; // No rotation for static filenames
        }

        // Check size-based rotation
        if (rotationSizeBytes > 0 && currentFileSize >= rotationSizeBytes) {
            return true;
        }

        // Check time-based rotation
        if (rotationTimeMs > 0) {
            long timeSinceRotation = System.currentTimeMillis() - lastRotationTime.toEpochMilli();
            return timeSinceRotation >= rotationTimeMs;
        }

        return false;
    }

    private void rotateFile() {
        Path newFile = determineCurrentFile();

        if (!newFile.equals(currentFile)) {
            log.info("Rotating file from {} to {}", currentFile.getFileName(), newFile.getFileName());
            this.currentFile = newFile;
            this.currentFileSize = 0;
            this.lastRotationTime = Instant.now();
        }
    }

    private String formatRecord(SinkRecord record) throws Exception {
        switch (format) {
            case JSON:
                return formatAsJson(record);
            case TEXT:
                return formatAsText(record);
            case CSV:
                return formatAsCsv(record);
            default:
                throw new IllegalArgumentException("Unsupported format: " + format);
        }
    }

    private String formatAsJson(SinkRecord record) throws Exception {
        Map<String, Object> jsonRecord = Map.of(
                "topic", record.getTopic() != null ? record.getTopic() : "",
                "key", record.getKey() != null ? record.getKey() : "",
                "value", record.getValue() != null ? record.getValue() : "",
                "headers", record.getHeaders() != null ? record.getHeaders() : Map.of(),
                "timestamp", record.getTimestamp() != null ? record.getTimestamp().toString() : Instant.now().toString()
        );

        return objectMapper.writeValueAsString(jsonRecord);
    }

    private String formatAsText(SinkRecord record) {
        StringBuilder sb = new StringBuilder();

        if (record.getTimestamp() != null) {
            sb.append("[").append(record.getTimestamp()).append("] ");
        }

        if (record.getTopic() != null) {
            sb.append("topic=").append(record.getTopic()).append(" ");
        }

        if (record.getKey() != null) {
            sb.append("key=").append(record.getKey()).append(" ");
        }

        if (record.getValue() != null) {
            sb.append("value=").append(record.getValue());
        }

        return sb.toString();
    }

    private String formatAsCsv(SinkRecord record) {
        // Simple CSV format: timestamp,topic,key,value
        StringBuilder sb = new StringBuilder();

        // Escape commas and quotes in values
        String timestamp = record.getTimestamp() != null ? record.getTimestamp().toString() : "";
        String topic = record.getTopic() != null ? escapeCsv(record.getTopic()) : "";
        String key = record.getKey() != null ? escapeCsv(record.getKey()) : "";
        String value = record.getValue() != null ? escapeCsv(record.getValue().toString()) : "";

        sb.append(timestamp).append(",")
          .append(topic).append(",")
          .append(key).append(",")
          .append(value);

        return sb.toString();
    }

    private String escapeCsv(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * Get current output file path
     */
    public Path getCurrentFile() {
        fileLock.readLock().lock();
        try {
            return currentFile;
        } finally {
            fileLock.readLock().unlock();
        }
    }

    /**
     * Get current file size
     */
    public long getCurrentFileSize() {
        fileLock.readLock().lock();
        try {
            return currentFileSize;
        } finally {
            fileLock.readLock().unlock();
        }
    }

    /**
     * Force file rotation
     */
    public void forceRotation() {
        fileLock.writeLock().lock();
        try {
            rotateFile();
        } finally {
            fileLock.writeLock().unlock();
        }
    }
}