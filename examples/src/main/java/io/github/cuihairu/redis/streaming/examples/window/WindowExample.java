package io.github.cuihairu.redis.streaming.examples.window;

import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.window.TimeWindow;
import io.github.cuihairu.redis.streaming.window.assigners.SessionWindow;
import io.github.cuihairu.redis.streaming.window.assigners.SlidingWindow;
import io.github.cuihairu.redis.streaming.window.assigners.TumblingWindow;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Example demonstrating Window module usage.
 */
@Slf4j
public class WindowExample {

    public static void main(String[] args) {
        log.info("Starting Window Example");

        demonstrateTumblingWindow();
        demonstrateSlidingWindow();
        demonstrateSessionWindow();
        demonstrateWindowOperations();

        log.info("Window Example completed successfully");
    }

    private static void demonstrateTumblingWindow() {
        log.info("=== Tumbling Window Example ===");

        TumblingWindow<String> assigner = TumblingWindow.of(Duration.ofSeconds(5));
        log.info("Window size: {}ms", assigner.getSize());

        long baseTime = Instant.now().toEpochMilli();
        String[] events = {"event1", "event2", "event3", "event4", "event5"};
        long[] timestamps = {
                baseTime,
                baseTime + 1000,
                baseTime + 3000,
                baseTime + 6000,
                baseTime + 7000
        };

        for (int i = 0; i < events.length; i++) {
            List<WindowAssigner.Window> windows = new ArrayList<>();
            assigner.assignWindows(events[i], timestamps[i]).forEach(windows::add);
            for (WindowAssigner.Window window : windows) {
                log.info("{} @ {}ms -> Window[{}ms - {}ms]",
                        events[i],
                        timestamps[i] - baseTime,
                        window.getStart() - baseTime,
                        window.getEnd() - baseTime);
            }
        }
    }

    private static void demonstrateSlidingWindow() {
        log.info("=== Sliding Window Example ===");

        SlidingWindow<String> assigner = SlidingWindow.of(
                Duration.ofSeconds(10),
                Duration.ofSeconds(5)
        );

        log.info("Window size: {}ms, Slide: {}ms", assigner.getSize(), assigner.getSlide());

        long baseTime = Instant.now().toEpochMilli();
        long timestamp = baseTime + 12000;

        List<WindowAssigner.Window> windows = new ArrayList<>();
        assigner.assignWindows("event", timestamp).forEach(windows::add);

        log.info("Event at {}ms belongs to {} windows", timestamp - baseTime, windows.size());
    }

    private static void demonstrateSessionWindow() {
        log.info("=== Session Window Example ===");

        SessionWindow<String> assigner = SessionWindow.withGap(Duration.ofSeconds(30));
        log.info("Session gap: {}ms", assigner.getSessionGap());

        long baseTime = Instant.now().toEpochMilli();
        String[] events = {"click1", "click2", "click3"};
        long[] timestamps = {baseTime, baseTime + 10000, baseTime + 50000};

        for (int i = 0; i < events.length; i++) {
            List<WindowAssigner.Window> windows = new ArrayList<>();
            assigner.assignWindows(events[i], timestamps[i]).forEach(windows::add);
            for (WindowAssigner.Window window : windows) {
                log.info("{} @ {}ms -> Session[{}ms - {}ms]",
                        events[i],
                        timestamps[i] - baseTime,
                        window.getStart() - baseTime,
                        window.getEnd() - baseTime);
            }
        }
    }

    private static void demonstrateWindowOperations() {
        log.info("=== Window Operations Example ===");

        TimeWindow w1 = new TimeWindow(0, 60000);
        TimeWindow w2 = new TimeWindow(30000, 90000);
        TimeWindow w3 = new TimeWindow(100000, 160000);

        log.info("W1 intersects W2: {}", TimeWindow.intersects(w1, w2));
        log.info("W1 intersects W3: {}", TimeWindow.intersects(w1, w3));

        if (TimeWindow.intersects(w1, w2)) {
            TimeWindow merged = TimeWindow.merge(w1, w2);
            log.info("Merged W1 and W2: {}", merged);
        }

        long timestamp = 45000;
        log.info("Timestamp {} in W1: {}", timestamp, w1.contains(timestamp));
    }
}

