package io.github.cuihairu.redis.streaming.runtime.redis;

import org.redisson.api.stream.StreamMessageId;

import java.util.regex.Pattern;

/**
 * Pure stream/source-id helpers shared by the runtime job wiring: Redis Stream id
 * parsing/comparison (deferred-ack frontier bookkeeping), log-message truncation and
 * stable source-id validation. Stateless by design.
 */
final class StreamIds {

    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9_.-]+");

    private StreamIds() {
    }

    static StreamMessageId parseStreamId(String id) {
        try {
            if (id == null) return StreamMessageId.MIN;
            String[] parts = id.split("-", 2);
            if (parts.length == 2) {
                long ms = Long.parseLong(parts[0]);
                long seq = Long.parseLong(parts[1]);
                return new StreamMessageId(ms, seq);
            }
            long ms = Long.parseLong(id);
            return new StreamMessageId(ms);
        } catch (Exception e) {
            return StreamMessageId.MIN;
        }
    }

    static int compareStreamId(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        try {
            String[] ap = a.split("-", 2);
            String[] bp = b.split("-", 2);
            long ams = Long.parseLong(ap[0]);
            long bms = Long.parseLong(bp[0]);
            int c = Long.compare(ams, bms);
            if (c != 0) return c;
            long ase = ap.length > 1 ? Long.parseLong(ap[1]) : 0L;
            long bse = bp.length > 1 ? Long.parseLong(bp[1]) : 0L;
            return Long.compare(ase, bse);
        } catch (Exception ignore) {
            return a.compareTo(b);
        }
    }

    static String truncate(String s, int maxLen) {
        if (s == null) return null;
        if (maxLen <= 0) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    static String requireValidId(String id) {
        if (id == null) {
            throw new NullPointerException("sourceId");
        }
        String v = id.trim();
        if (v.isEmpty()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        if (!ID_PATTERN.matcher(v).matches()) {
            throw new IllegalArgumentException("sourceId must match " + ID_PATTERN.pattern() + ", but got: " + id);
        }
        return v;
    }
}
