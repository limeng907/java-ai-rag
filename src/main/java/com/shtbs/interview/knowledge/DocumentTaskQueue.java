package com.shtbs.interview.knowledge;

import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class DocumentTaskQueue {
    private static final String STREAM = "knowledge:document:tasks";
    private static final String GROUP = "knowledge-document-workers";

    public record Task(String kind, long documentId, int version, RecordId recordId) {}

    private final StringRedisTemplate redis;
    private final String consumerName = "worker-" + UUID.randomUUID();
    private volatile boolean groupReady;

    public DocumentTaskQueue(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void publishIndex(long documentId, int version) {
        publish("INDEX", documentId, version);
    }

    public void publishDelete(long documentId) {
        publish("DELETE", documentId, 0);
    }

    private void publish(String kind, long documentId, int version) {
        ensureGroup();
        redis.opsForStream().add(STREAM, Map.of(
                "kind", kind,
                "documentId", Long.toString(documentId),
                "version", Integer.toString(version)));
    }

    public List<Task> readNew() {
        ensureGroup();
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                Consumer.from(GROUP, consumerName),
                StreamReadOptions.empty().count(5),
                StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
        return parse(records);
    }

    public List<Task> reclaimStale() {
        ensureGroup();
        PendingMessages pending = redis.opsForStream().pending(STREAM, GROUP,
                Range.unbounded(), 20);
        List<Task> result = new ArrayList<>();
        for (PendingMessage message : pending) {
            if (message.getElapsedTimeSinceLastDelivery().compareTo(Duration.ofMinutes(11)) < 0) {
                continue;
            }
            List<MapRecord<String, Object, Object>> claimed = redis.opsForStream().claim(
                    STREAM, GROUP, consumerName, Duration.ofMinutes(11), message.getId());
            result.addAll(parse(claimed));
        }
        return result;
    }

    public void acknowledge(Task task) {
        redis.opsForStream().acknowledge(STREAM, GROUP, task.recordId());
        redis.opsForStream().delete(STREAM, task.recordId());
    }

    private List<Task> parse(List<MapRecord<String, Object, Object>> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<Task> result = new ArrayList<>();
        for (MapRecord<String, Object, Object> record : records) {
            Map<Object, Object> values = record.getValue();
            result.add(new Task(String.valueOf(values.get("kind")),
                    Long.parseLong(String.valueOf(values.get("documentId"))),
                    Integer.parseInt(String.valueOf(values.get("version"))), record.getId()));
        }
        return result;
    }

    private synchronized void ensureGroup() {
        if (groupReady) {
            return;
        }
        try {
            redis.execute((RedisCallback<Object>) connection -> connection.execute("XGROUP",
                    bytes("CREATE"), bytes(STREAM), bytes(GROUP), bytes("0"), bytes("MKSTREAM")));
        } catch (RuntimeException ex) {
            if (!isExistingGroup(ex)) {
                throw ex;
            }
        }
        groupReady = true;
    }

    private boolean isExistingGroup(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
