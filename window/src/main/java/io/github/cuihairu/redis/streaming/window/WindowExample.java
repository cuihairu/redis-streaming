package io.github.cuihairu.redis.streaming.window;

import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.window.assigners.SlidingWindow;
import io.github.cuihairu.redis.streaming.window.assigners.SessionWindow;
import io.github.cuihairu.redis.streaming.window.assigners.TumblingWindow;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Example demonstrating Window module usage
 */
@Slf4j
public class WindowExample {

    public static void main(String[] args) {
        log.info("Starting Window Example");

        // Example 1: Tumbling Windows
        demonstrateTumblingWindow();

        // Example 2: Sliding Windows
        demonstrateSlidingWindow();

        // Example 3: Session Windows
        demonstrateSessionWindow();

        // Example 4: Window Operations
        demonstrateWindowOperations();

        log.info("Window Example completed successfully");
    }

    private static void demonstrateTumblingWindow() {
        log.info("=== Tumbling Window Example ===");

        // Create a 5-second tumbling window
        TumblingWindow<String> assigner = TumblingWindow.of(Duration.ofSeconds(5));

        log.info("Window size: {}ms", assigner.getSize());

        // Simulate events
        long baseTime = Instant.now().toEpochMilli();
        String[] events = {"event1", "event2", "event3", "event4", "event5"};
        long[] timestamps = {
                baseTime,
                baseTime + 1000,  // +1s
                baseTime + 3000,  // +3s
                baseTime + 6000,  // +6s (new window)
                baseTime + 7000   // +7s (same window as event4)
        };

        for (int i = 0; i < events.length; i++) {
            List<WindowAssigner.Window> windows = new ArrayList<>();
            assigner.assignWindows(events[i], timestamps[i]).forEach(windows::add);

            for (WindowAssigner.Window window : windows) {
                long windowStart = window.getStart() - baseTime;
                long windowEnd = window.getEnd() - baseTime;
                log.info("{} @ {}ms -> Window[{}ms - {}ms]",
                        events[i], timestamps[i] - baseTime, windowStart, windowEnd);
            }
        }
    }

    private static void demonstrateSlidingWindow() {
        log.info("=== Sliding Window Example ===");

        // Create a 10-second sliding window with 5-second slide
        SlidingWindow<String> assigner = SlidingWindow.of(
                Duration.ofSeconds(10),  // window size
                Duration.ofSeconds(5)    // slide
        );

        log.info("Window size: {}ms, Slide: {}ms", assigner.getSize(), assigner.getSlide());

        long baseTime = Instant.now().toEpochMilli();
        long timestamp = baseTime + 12000; // 12 seconds

        List<WindowAssigner.Window> windows = new ArrayList<>();
        assigner.assignWindows("event", timestamp).forEach(windows::add);

        log.info("Event at {}ms belongs to {} windows:", timestamp - baseTime, windows.size());
        for (WindowAssigner.Window window : windows) {
            long windowStart = window.getStart() - baseTime;
            long windowEnd = window.getEnd() - baseTime;
            log.info("  Window[{}ms - {}ms]", windowStart, windowEnd);
        }
    }

    private static void demonstrateSessionWindow() {
        log.info("=== Session Window Example ===");

        // Create session window with 30-second gap
        SessionWindow<String> assigner = SessionWindow.withGap(Duration.ofSeconds(30));

        log.info("Session gap: {}ms", assigner.getSessionGap());

        long baseTime = Instant.now().toEpochMilli();

        // Simulate user clicks
        String[] events = {"click1", "click2", "click3"};
        long[] timestamps = {
                baseTime,
                baseTime + 10000,  // +10s (within session)
                baseTime + 50000   // +50s (new session)
        };

        for (int i = 0; i < events.length; i++) {
            List<WindowAssigner.Window> windows = new ArrayList<>();
            assigner.assignWindows(events[i], timestamps[i]).forEach(windows::add);

            for (WindowAssigner.Window window : windows) {
                long windowStart = window.getStart() - baseTime;
                long windowEnd = window.getEnd() - baseTime;
                log.info("{} @ {}ms -> Session[{}ms - {}ms]",
                        events[i], timestamps[i] - baseTime, windowStart, windowEnd);
            }
        }
    }

    private static void demonstrateWindowOperations() {
        log.info("=== Window Operations Example ===");

        TimeWindow w1 = new TimeWindow(0, 60000);
        TimeWindow w2 = new TimeWindow(30000, 90000);
        TimeWindow w3 = new TimeWindow(100000, 160000);

        log.info("Window 1: {}", w1);
        log.info("Window 2: {}", w2);
        log.info("Window 3: {}", w3);

        // Check intersections
        log.info("W1 intersects W2: {}", TimeWindow.intersects(w1, w2));
        log.info("W1 intersects W3: {}", TimeWindow.intersects(w1, w3));

        // Merge overlapping windows
        if (TimeWindow.intersects(w1, w2)) {
            TimeWindow merged = TimeWindow.merge(w1, w2);
            log.info("Merged W1 and W2: {}", merged);
        }

        // Check if timestamp is in window
        long timestamp = 45000;
        log.info("Timestamp {} in W1: {}", timestamp, w1.contains(timestamp));
        log.info("Timestamp {} in W2: {}", timestamp, w2.contains(timestamp));
        log.info("Timestamp {} in W3: {}", timestamp, w3.contains(timestamp));
    }
}
