package io.github.cuihairu.redis.streaming.source.impl;

import io.github.cuihairu.redis.streaming.source.SourceConfiguration;
import io.github.cuihairu.redis.streaming.source.SourceHealthStatus;
import io.github.cuihairu.redis.streaming.source.SourceRecord;
import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryWatcher;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * File system source connector for monitoring file changes and reading file contents
 */
@Slf4j
public class FileSystemSourceConnector extends AbstractSourceConnector {

    private static final String DIRECTORY_PROPERTY = "directory";
    private static final String FILE_PATTERN_PROPERTY = "file.pattern";
    private static final String TOPIC_PROPERTY = "topic";
    private static final String WATCH_MODE_PROPERTY = "watch.mode";
    private static final String READ_EXISTING_PROPERTY = "read.existing";

    private DirectoryWatcher watcher;
    private Path watchDirectory;
    private String filePattern;
    private String topic;
    private String watchMode; // "create", "modify", "delete", "all"
    private boolean readExisting;
    private final BlockingQueue<SourceRecord> recordQueue = new LinkedBlockingQueue<>();

    public FileSystemSourceConnector(SourceConfiguration configuration) {
        super(configuration);
    }

    @Override
    protected void doStart() throws Exception {
        // Validate configuration
        configuration.validate();

        // Parse configuration
        String directoryPath = (String) configuration.getProperty(DIRECTORY_PROPERTY);
        this.watchDirectory = Paths.get(directoryPath);
        this.filePattern = (String) configuration.getProperty(FILE_PATTERN_PROPERTY, "*");
        this.topic = (String) configuration.getProperty(TOPIC_PROPERTY, "file-system-data");
        this.watchMode = (String) configuration.getProperty(WATCH_MODE_PROPERTY, "all");
        this.readExisting = Boolean.parseBoolean(
                String.valueOf(configuration.getProperty(READ_EXISTING_PROPERTY, "false")));

        // Validate directory exists
        if (!Files.exists(watchDirectory)) {
            throw new IllegalArgumentException("Directory does not exist: " + watchDirectory);
        }

        if (!Files.isDirectory(watchDirectory)) {
            throw new IllegalArgumentException("Path is not a directory: " + watchDirectory);
        }

        // Read existing files if configured
        if (readExisting) {
            readExistingFiles();
        }

        // Start file watcher
        startFileWatcher();

        log.info("File system source connector started watching: {}", watchDirectory);
    }

    @Override
    protected void doStop() throws Exception {
        if (watcher != null) {
            watcher.close();
        }
        recordQueue.clear();
    }

    @Override
    protected List<SourceRecord> doPoll() throws Exception {
        List<SourceRecord> records = new ArrayList<>();

        // Poll records from queue (non-blocking)
        SourceRecord record;
        int batchSize = configuration.getBatchSize();
        int count = 0;

        while (count < batchSize && (record = recordQueue.poll()) != null) {
            records.add(record);
            count++;
        }

        if (!records.isEmpty()) {
            updateHealthStatus(SourceHealthStatus.healthy(
                    "Polled " + records.size() + " file records"));
        }

        return records;
    }

    private void startFileWatcher() throws IOException {
        this.watcher = DirectoryWatcher.builder()
                .path(watchDirectory)
                .listener(this::handleFileEvent)
                .build();

        // Start watching in a separate thread
        Thread watcherThread = new Thread(() -> {
            try {
                watcher.watch();
            } catch (Exception e) {
                log.error("Error in file watcher", e);
                updateHealthStatus(SourceHealthStatus.unhealthy("File watcher error: " + e.getMessage()));
            }
        });
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    private void handleFileEvent(DirectoryChangeEvent event) {
        try {
            Path file = event.path();
            DirectoryChangeEvent.EventType eventType = event.eventType();

            // Check if file matches pattern
            if (!matchesPattern(file.getFileName().toString())) {
                return;
            }

            // Check if event type matches watch mode
            if (!shouldProcessEvent(eventType)) {
                return;
            }

            log.debug("File event: {} - {}", eventType, file);

            // Create source record based on event type
            SourceRecord record = createFileRecord(file, eventType);
            if (record != null) {
                recordQueue.offer(record);
            }

        } catch (Exception e) {
            log.error("Error handling file event: {}", event, e);
            errorsCount.incrementAndGet();
        }
    }

    private void readExistingFiles() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(watchDirectory, filePattern)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    SourceRecord record = createFileRecord(file, DirectoryChangeEvent.EventType.CREATE);
                    if (record != null) {
                        recordQueue.offer(record);
                    }
                }
            }
        }
        log.info("Read {} existing files from directory: {}", recordQueue.size(), watchDirectory);
    }

    private SourceRecord createFileRecord(Path file, DirectoryChangeEvent.EventType eventType) {
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("file.path", file.toString());
            headers.put("file.name", file.getFileName().toString());
            headers.put("file.event", eventType.toString());
            headers.put("file.size", String.valueOf(Files.size(file)));
            headers.put("file.lastModified", Files.getLastModifiedTime(file).toString());

            Object value;
            String key = file.getFileName().toString();

            // For delete events, we don't read file content
            if (eventType == DirectoryChangeEvent.EventType.DELETE) {
                value = Map.of(
                        "event", "DELETE",
                        "file", file.toString(),
                        "timestamp", Instant.now().toString()
                );
            } else {
                // Read file content
                if (Files.isRegularFile(file) && Files.isReadable(file)) {
                    String content = Files.readString(file, StandardCharsets.UTF_8);

                    // Create structured record
                    value = Map.of(
                            "event", eventType.toString(),
                            "file", file.toString(),
                            "content", content,
                            "size", Files.size(file),
                            "lastModified", Files.getLastModifiedTime(file).toString(),
                            "timestamp", Instant.now().toString()
                    );
                } else {
                    log.warn("Cannot read file: {}", file);
                    return null;
                }
            }

            return new SourceRecord(getName(), topic, key, value, headers);

        } catch (IOException e) {
            log.error("Error creating file record for: {}", file, e);
            return null;
        }
    }

    private boolean matchesPattern(String fileName) {
        // Simple glob pattern matching
        if ("*".equals(filePattern)) {
            return true;
        }

        if (filePattern.contains("*")) {
            String regex = filePattern.replace("*", ".*");
            return fileName.matches(regex);
        }

        return fileName.equals(filePattern);
    }

    private boolean shouldProcessEvent(DirectoryChangeEvent.EventType eventType) {
        switch (watchMode.toLowerCase()) {
            case "create":
                return eventType == DirectoryChangeEvent.EventType.CREATE;
            case "modify":
                return eventType == DirectoryChangeEvent.EventType.MODIFY;
            case "delete":
                return eventType == DirectoryChangeEvent.EventType.DELETE;
            case "all":
            default:
                return true;
        }
    }
}