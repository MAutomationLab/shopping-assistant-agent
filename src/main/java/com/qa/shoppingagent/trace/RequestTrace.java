package com.qa.shoppingagent.trace;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.qa.shoppingagent.faults.Fault;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Everything that happened while serving one /chat request. Serialized as the {@code trace} block. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"trace_id", "session_id", "status", "error", "model", "started_at", "total_latency_ms",
        "llm_calls", "tool_call_count", "tokens", "finish_reason", "budget_exceeded", "faults_injected",
        "input", "answer", "spans"})
public final class RequestTrace {

    private final String traceId;
    private final String sessionId;
    private final String input;
    private final Set<Fault> faults;
    private final Instant startedAt = Instant.now();
    private final long startNanos = System.nanoTime();
    private final List<SpanRecord> spans = new ArrayList<>();
    private final List<ToolCallRecord> toolCalls = new ArrayList<>();

    private String model;
    private String answer;
    private String status = "IN_PROGRESS";
    private String error;
    private String finishReason;
    private Long totalLatencyMs;
    private int llmCalls;
    private long inputTokens;
    private long outputTokens;
    private boolean budgetExceeded;

    RequestTrace(String traceId, String sessionId, String input, Set<Fault> faults) {
        this.traceId = traceId;
        this.sessionId = sessionId;
        this.input = input;
        this.faults = faults;
    }

    // --- mutation (package-private, used by TraceRecorder / listeners) ---

    long elapsedMs() { return (System.nanoTime() - startNanos) / 1_000_000; }
    void addSpan(SpanRecord span) { spans.add(span); }
    void addToolCall(ToolCallRecord call) { toolCalls.add(call); }
    void addLlmCall(long in, long out) { llmCalls++; inputTokens += in; outputTokens += out; }
    void markBudgetExceeded() { budgetExceeded = true; }
    void setModel(String model) { if (model != null) this.model = model; }
    void setFinishReason(String finishReason) { if (finishReason != null) this.finishReason = finishReason; }

    void complete(String status, String answer, String error) {
        this.status = status;
        this.answer = answer;
        this.error = error;
        this.totalLatencyMs = elapsedMs();
    }

    public boolean hasFault(Fault fault) { return faults.contains(fault); }

    @JsonIgnore
    public List<ToolCallRecord> toolCalls() { return List.copyOf(toolCalls); }

    // --- JSON view ---

    @JsonProperty("trace_id") public String getTraceId() { return traceId; }
    @JsonProperty("session_id") public String getSessionId() { return sessionId; }
    @JsonProperty("status") public String getStatus() { return status; }
    @JsonProperty("error") public String getError() { return error; }
    @JsonProperty("input") public String getInput() { return input; }
    @JsonProperty("answer") public String getAnswer() { return answer; }
    @JsonProperty("model") public String getModel() { return model; }
    @JsonProperty("started_at") public String getStartedAt() { return startedAt.toString(); }
    @JsonProperty("total_latency_ms") public Long getTotalLatencyMs() { return totalLatencyMs; }
    @JsonProperty("llm_calls") public int getLlmCalls() { return llmCalls; }
    @JsonProperty("tool_call_count") public int getToolCallCount() { return toolCalls.size(); }
    @JsonProperty("finish_reason") public String getFinishReason() { return finishReason; }
    @JsonProperty("budget_exceeded") public boolean isBudgetExceeded() { return budgetExceeded; }
    @JsonProperty("faults_injected") public List<String> getFaultsInjected() { return faults.stream().map(Fault::code).toList(); }
    @JsonProperty("spans") public List<SpanRecord> getSpans() { return spans; }

    @JsonProperty("tokens")
    public Tokens getTokens() {
        return new Tokens(inputTokens, outputTokens, inputTokens + outputTokens);
    }

    public record Tokens(
            @JsonProperty("input") long input,
            @JsonProperty("output") long output,
            @JsonProperty("total") long total) {}
}
