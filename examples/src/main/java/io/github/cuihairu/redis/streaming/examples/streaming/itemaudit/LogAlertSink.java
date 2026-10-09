package io.github.cuihairu.redis.streaming.examples.streaming.itemaudit;

import lombok.extern.slf4j.Slf4j;

/**
 * Logs each alert as one structured JSON line (WARN). This is the default sink: an ops
 * person (or a log-based alerting system) consumes the log stream.
 */
@Slf4j
public class LogAlertSink implements AlertSink {

    @Override
    public void send(Alert alert) {
        log.warn("ITEM-AUDIT {}", alert.jsonLine());
    }
}
