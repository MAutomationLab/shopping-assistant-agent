package com.qa.shoppingagent.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.qa.shoppingagent.trace.RequestTrace;
import com.qa.shoppingagent.trace.ToolCallRecord;

import java.util.List;

/** API contract for POST /chat. Field names match framework/clients/shopping_assistant_client.py. */
public final class ChatDtos {

    private ChatDtos() {}

    public record ChatRequest(
            @JsonProperty("input") String input,
            @JsonProperty("session_id") String sessionId) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"answer", "retrieval_context", "tool_calls", "session_id", "trace_id", "trace"})
    public record ChatResponse(
            @JsonProperty("answer") String answer,
            @JsonProperty("retrieval_context") List<String> retrievalContext,
            @JsonProperty("tool_calls") List<ToolCallRecord> toolCalls,
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("trace_id") String traceId,
            /* Full trace, only when the request has X-Debug-Trace: true */
            @JsonProperty("trace") RequestTrace trace) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(
            @JsonProperty("error") String error,
            @JsonProperty("message") String message,
            @JsonProperty("trace_id") String traceId) {}
}
