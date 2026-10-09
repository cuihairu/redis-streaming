package io.github.cuihairu.redis.streaming.examples.streaming.itemaudit;

/**
 * Outlet for audit alerts. The gates push every alert here; implementations decide the
 * transport. Keep this interface synchronous and exception-free at the call site — a
 * failing sink must never corrupt the pipeline that detected the anomaly.
 */
public interface AlertSink {

    void send(Alert alert);
}
