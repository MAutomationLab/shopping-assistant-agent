package com.qa.shoppingagent.trace;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.opentelemetry.api.trace.Span;

import java.util.LinkedHashMap;
import java.util.Map;

/** One step of the agent run (AGENT / LLM / TOOL / RETRIEVER), mirrored to an OpenTelemetry span. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"span_id", "parent_span_id", "type", "name", "status", "error", "start_offset_ms", "latency_ms", "attributes"})
public final class SpanRecord {

    public enum Type { AGENT, LLM, TOOL, RETRIEVER }

    private final String spanId;
    private final String parentSpanId;
    private final Type type;
    private final String name;
    private final long startOffsetMs;
    private final long startNanos;
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private Long latencyMs;
    private String status = "IN_PROGRESS";
    private String error;

    @JsonIgnore
    private final Span otelSpan;

    SpanRecord(String spanId, String parentSpanId, Type type, String name, long startOffsetMs, Span otelSpan) {
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
        this.type = type;
        this.name = name;
        this.startOffsetMs = startOffsetMs;
        this.startNanos = System.nanoTime();
        this.otelSpan = otelSpan;
    }

    void end(String status, String error) {
        this.status = status;
        this.error = error;
        this.latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
    }

    public SpanRecord attr(String key, Object value) {
        if (value != null) {
            attributes.put(key, value);
        }
        return this;
    }

    @JsonProperty("span_id") public String getSpanId() { return spanId; }
    @JsonProperty("parent_span_id") public String getParentSpanId() { return parentSpanId; }
    @JsonProperty("type") public Type getType() { return type; }
    @JsonProperty("name") public String getName() { return name; }
    @JsonProperty("start_offset_ms") public long getStartOffsetMs() { return startOffsetMs; }
    @JsonProperty("latency_ms") public Long getLatencyMs() { return latencyMs; }
    @JsonProperty("status") public String getStatus() { return status; }
    @JsonProperty("error") public String getError() { return error; }
    @JsonProperty("attributes") public Map<String, Object> getAttributes() { return attributes; }

    @JsonIgnore
    Span otelSpan() { return otelSpan; }
}
