package io.github.cuihairu.redis.streaming.source.impl;

import io.github.cuihairu.redis.streaming.source.SourceConfiguration;
import io.github.cuihairu.redis.streaming.source.SourceHealthStatus;
import io.github.cuihairu.redis.streaming.source.SourceRecord;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * IoT device source connector for simulating IoT device data
 * In a real implementation, this would integrate with actual IoT platforms like AWS IoT, Azure IoT Hub, etc.
 */
@Slf4j
public class IoTDeviceSourceConnector extends AbstractSourceConnector {

    private static final String DEVICE_COUNT_PROPERTY = "device.count";
    private static final String DEVICE_TYPES_PROPERTY = "device.types";
    private static final String TOPIC_PROPERTY = "topic";
    private static final String SIMULATION_MODE_PROPERTY = "simulation.mode";
    private static final String DATA_INTERVAL_PROPERTY = "data.interval.ms";

    private int deviceCount;
    private List<String> deviceTypes;
    private String topic;
    private boolean simulationMode;
    private long dataIntervalMs;
    private final BlockingQueue<SourceRecord> dataQueue = new LinkedBlockingQueue<>();
    private final Map<String, IoTDevice> devices = new HashMap<>();
    private volatile boolean generating = false;

    public IoTDeviceSourceConnector(SourceConfiguration configuration) {
        super(configuration);
    }

    @Override
    protected void doStart() throws Exception {
        // Validate configuration
        configuration.validate();

        // Parse configuration
        this.deviceCount = Integer.parseInt(
                String.valueOf(configuration.getProperty(DEVICE_COUNT_PROPERTY, "10")));
        this.topic = (String) configuration.getProperty(TOPIC_PROPERTY, "iot-device-data");
        this.simulationMode = Boolean.parseBoolean(
                String.valueOf(configuration.getProperty(SIMULATION_MODE_PROPERTY, "true")));
        this.dataIntervalMs = Long.parseLong(
                String.valueOf(configuration.getProperty(DATA_INTERVAL_PROPERTY, "5000")));

        // Parse device types
        String deviceTypesStr = (String) configuration.getProperty(DEVICE_TYPES_PROPERTY,
                "temperature,humidity,pressure,motion,light");
        this.deviceTypes = Arrays.asList(deviceTypesStr.split(","));

        // Initialize virtual devices
        initializeDevices();

        // Start data generation if in simulation mode
        if (simulationMode) {
            startDataGeneration();
        }

        log.info("IoT device source connector started with {} devices", deviceCount);
    }

    @Override
    protected void doStop() throws Exception {
        generating = false;
        devices.clear();
        dataQueue.clear();
    }

    @Override
    protected List<SourceRecord> doPoll() throws Exception {
        List<SourceRecord> records = new ArrayList<>();

        // Poll records from queue (non-blocking)
        SourceRecord record;
        int batchSize = configuration.getBatchSize();
        int count = 0;

        while (count < batchSize && (record = dataQueue.poll()) != null) {
            records.add(record);
            count++;
        }

        if (!records.isEmpty()) {
            updateHealthStatus(SourceHealthStatus.healthy(
                    "Polled " + records.size() + " IoT device records"));
        }

        return records;
    }

    private void initializeDevices() {
        for (int i = 0; i < deviceCount; i++) {
            String deviceId = "device-" + (i + 1);
            String deviceType = deviceTypes.get(i % deviceTypes.size());
            IoTDevice device = new IoTDevice(deviceId, deviceType);
            devices.put(deviceId, device);
        }

        log.info("Initialized {} IoT devices with types: {}", deviceCount, deviceTypes);
    }

    private void startDataGeneration() {
        generating = true;

        Thread generatorThread = new Thread(() -> {
            while (generating && running.get()) {
                try {
                    // Generate data for all devices
                    for (IoTDevice device : devices.values()) {
                        SourceRecord record = generateDeviceData(device);
                        dataQueue.offer(record);
                    }

                    // Wait for next generation cycle
                    Thread.sleep(dataIntervalMs);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Error generating IoT device data", e);
                    errorsCount.incrementAndGet();
                }
            }
        });

        generatorThread.setDaemon(true);
        generatorThread.start();

        log.info("Started IoT data generation with interval: {}ms", dataIntervalMs);
    }

    private SourceRecord generateDeviceData(IoTDevice device) {
        Instant timestamp = Instant.now();
        Map<String, Object> sensorData = generateSensorData(device.getType());

        Map<String, Object> deviceData = new HashMap<>();
        deviceData.put("deviceId", device.getId());
        deviceData.put("deviceType", device.getType());
        deviceData.put("timestamp", timestamp.toString());
        deviceData.put("location", device.getLocation());
        deviceData.put("status", device.getStatus());
        deviceData.put("sensorData", sensorData);

        // Update device last seen
        device.setLastSeen(timestamp);

        Map<String, String> headers = new HashMap<>();
        headers.put("iot.device.id", device.getId());
        headers.put("iot.device.type", device.getType());
        headers.put("iot.device.status", device.getStatus());
        headers.put("timestamp", timestamp.toString());

        return new SourceRecord(
                getName(),
                topic,
                device.getId(), // Use device ID as key for partitioning
                deviceData,
                headers
        );
    }

    private Map<String, Object> generateSensorData(String deviceType) {
        Map<String, Object> sensorData = new HashMap<>();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        switch (deviceType.toLowerCase()) {
            case "temperature":
                sensorData.put("temperature", random.nextDouble(15.0, 35.0));
                sensorData.put("unit", "celsius");
                break;

            case "humidity":
                sensorData.put("humidity", random.nextDouble(30.0, 90.0));
                sensorData.put("unit", "percent");
                break;

            case "pressure":
                sensorData.put("pressure", random.nextDouble(980.0, 1030.0));
                sensorData.put("unit", "hPa");
                break;

            case "motion":
                sensorData.put("motion", random.nextBoolean());
                sensorData.put("motionCount", random.nextInt(0, 10));
                break;

            case "light":
                sensorData.put("brightness", random.nextDouble(0.0, 100.0));
                sensorData.put("unit", "lux");
                break;

            default:
                // Generic sensor data
                sensorData.put("value", random.nextDouble(0.0, 100.0));
                sensorData.put("unit", "generic");
        }

        return sensorData;
    }

    /**
     * Simulate device connection/disconnection
     */
    public void simulateDeviceEvent(String deviceId, String event) {
        IoTDevice device = devices.get(deviceId);
        if (device != null) {
            switch (event.toLowerCase()) {
                case "connect":
                    device.setStatus("online");
                    break;
                case "disconnect":
                    device.setStatus("offline");
                    break;
                case "error":
                    device.setStatus("error");
                    break;
            }

            // Generate event record
            Map<String, Object> eventData = Map.of(
                    "deviceId", deviceId,
                    "event", event,
                    "timestamp", Instant.now().toString(),
                    "deviceStatus", device.getStatus()
            );

            Map<String, String> headers = Map.of(
                    "iot.device.id", deviceId,
                    "iot.event.type", event,
                    "event.type", "device_event"
            );

            SourceRecord eventRecord = new SourceRecord(
                    getName(),
                    topic + ".events",
                    deviceId,
                    eventData,
                    headers
            );

            dataQueue.offer(eventRecord);
            log.info("Device event: {} - {}", deviceId, event);
        }
    }

    /**
     * Get current device status
     */
    public Map<String, IoTDevice> getDevices() {
        return new HashMap<>(devices);
    }

    /**
     * IoT Device representation
     */
    public static class IoTDevice {
        private final String id;
        private final String type;
        private String status;
        private Instant lastSeen;
        private final Map<String, Double> location;

        public IoTDevice(String id, String type) {
            this.id = id;
            this.type = type;
            this.status = "online";
            this.lastSeen = Instant.now();

            // Generate random location
            ThreadLocalRandom random = ThreadLocalRandom.current();
            this.location = Map.of(
                    "latitude", random.nextDouble(-90.0, 90.0),
                    "longitude", random.nextDouble(-180.0, 180.0)
            );
        }

        public String getId() { return id; }
        public String getType() { return type; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Instant getLastSeen() { return lastSeen; }
        public void setLastSeen(Instant lastSeen) { this.lastSeen = lastSeen; }
        public Map<String, Double> getLocation() { return location; }
    }
}