package io.github.cuihairu.redis.streaming.config.event;

/**
 * Strongly typed config change event payload for Pub/Sub.
 */
public class ConfigChangeEvent {
    private String dataId;
    private String group;
    private String content;
    private String version;
    private long timestamp;
    /**
     * Identifies the service instance that published the event (B-07). A publisher
     * delivers to its in-process listeners synchronously and must therefore skip its own
     * event when it loops back through pub/sub, or local listeners fire twice per change.
     * Null on events from pre-marker publishers, which still deliver through both paths.
     */
    private String publisherId;

    public ConfigChangeEvent() {}

    public ConfigChangeEvent(String dataId, String group, String content, String version, long timestamp) {
        this(dataId, group, content, version, timestamp, null);
    }

    public ConfigChangeEvent(String dataId, String group, String content, String version, long timestamp,
                             String publisherId) {
        this.dataId = dataId;
        this.group = group;
        this.content = content;
        this.version = version;
        this.timestamp = timestamp;
        this.publisherId = publisherId;
    }

    public String getDataId() { return dataId; }
    public String getGroup() { return group; }
    public String getContent() { return content; }
    public String getVersion() { return version; }
    public long getTimestamp() { return timestamp; }
    public String getPublisherId() { return publisherId; }

    public void setDataId(String dataId) { this.dataId = dataId; }
    public void setGroup(String group) { this.group = group; }
    public void setContent(String content) { this.content = content; }
    public void setVersion(String version) { this.version = version; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    public void setPublisherId(String publisherId) { this.publisherId = publisherId; }
}
