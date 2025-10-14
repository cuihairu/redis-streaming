package io.github.cuihairu.streaming.config.event;

/**
 * Strongly typed config change event payload for Pub/Sub.
 */
public class ConfigChangeEvent {
    private String dataId;
    private String group;
    private String content;
    private String version;
    private long timestamp;

    public ConfigChangeEvent() {}

    public ConfigChangeEvent(String dataId, String group, String content, String version, long timestamp) {
        this.dataId = dataId;
        this.group = group;
        this.content = content;
        this.version = version;
        this.timestamp = timestamp;
    }

    public String getDataId() { return dataId; }
    public String getGroup() { return group; }
    public String getContent() { return content; }
    public String getVersion() { return version; }
    public long getTimestamp() { return timestamp; }

    public void setDataId(String dataId) { this.dataId = dataId; }
    public void setGroup(String group) { this.group = group; }
    public void setContent(String content) { this.content = content; }
    public void setVersion(String version) { this.version = version; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}

