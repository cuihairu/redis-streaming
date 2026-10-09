package io.github.cuihairu.redis.streaming.examples.streaming.itemaudit;

import lombok.extern.slf4j.Slf4j;

/**
 * Placeholder for a webhook (IM / on-call system) outlet. Reserved slot of the alert
 * funnel: implement {@link #post} with an HTTP client and wire retry + dedup there —
 * the pipeline contract stays "send and never throw".
 */
@Slf4j
public class WebhookAlertSink implements AlertSink {

    private final AlertSink fallback;

    public WebhookAlertSink() {
        this(new LogAlertSink());
    }

    public WebhookAlertSink(AlertSink fallback) {
        this.fallback = fallback;
    }

    @Override
    public void send(Alert alert) {
        try {
            post(alert);
        } catch (Exception e) {
            log.warn("webhook delivery failed, falling back to log sink: {}", e.toString());
        } finally {
            fallback.send(alert);
        }
    }

    /** TODO: real HTTP POST with retry/backoff; kept as a slot so the funnel shape is fixed. */
    private void post(Alert alert) {
        log.info("webhook slot: would POST {}", alert.jsonLine());
    }
}
