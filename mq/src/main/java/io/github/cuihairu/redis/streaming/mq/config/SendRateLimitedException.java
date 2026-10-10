package io.github.cuihairu.redis.streaming.mq.config;

/**
 * Thrown (as the send future's exception) when {@link SendQuota} rejects a send.
 * Deliberately a distinct type so callers can tell quota rejections apart from
 * transport failures when deciding whether to retry.
 */
public class SendRateLimitedException extends RuntimeException {

    public SendRateLimitedException(String tenant, String topic) {
        super("Send rate limit exceeded for tenant '" + tenant + "' topic '" + topic + "'");
    }
}
