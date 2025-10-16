package io.github.cuihairu.redis.streaming.mq.broker.jdbc;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.broker.BrokerPersistence;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

/**
 * JDBC-based persistence for Broker. Intended for MySQL (works with other JDBC drivers if schema matches).
 *
 * Schema expectation (simplified):
 *
 *  CREATE TABLE rs_messages (
 *    id BIGINT PRIMARY KEY AUTO_INCREMENT,
 *    topic VARCHAR(255) NOT NULL,
 *    partition_id INT NOT NULL,
 *    ts TIMESTAMP NOT NULL,
 *    msg_key VARCHAR(255) NULL,
 *    headers TEXT NULL,
 *    payload BLOB NULL
 *  );
 *  CREATE INDEX idx_rs_messages_topic_partition_ts ON rs_messages(topic, partition_id, ts);
 */
public class JdbcBrokerPersistence implements BrokerPersistence {
    private final DataSource dataSource;

    public JdbcBrokerPersistence(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String append(String topic, int partitionId, Message message) {
        String sql = "INSERT INTO rs_messages (topic, partition_id, ts, msg_key, headers, payload) VALUES (?,?,?,?,?,?)";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, new String[]{"id"})) {
            ps.setString(1, topic);
            ps.setInt(2, partitionId);
            ps.setTimestamp(3, Timestamp.from(message.getTimestamp() != null ? message.getTimestamp() : Instant.now()));
            ps.setString(4, message.getKey());
            ps.setString(5, toJson(message.getHeaders()));
            ps.setBytes(6, toBytes(message.getPayload()));
            ps.executeUpdate();
            try (var rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    return Long.toString(id);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("JDBC append failed", e);
        }
        return null;
    }

    private String toJson(Map<String,String> headers) {
        if (headers == null || headers.isEmpty()) return null;
        try {
            // minimal JSON building to avoid extra deps
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            boolean first = true;
            for (var e : headers.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(escapeJson(e.getKey())).append('"').append(':')
                  .append('"').append(escapeJson(e.getValue())).append('"');
            }
            sb.append('}');
            return sb.toString();
        } catch (Exception ignore) {
            return null;
        }
    }

    private String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private byte[] toBytes(Object payload) {
        if (payload == null) return null;
        if (payload instanceof byte[]) return (byte[]) payload;
        return String.valueOf(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}

