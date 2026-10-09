package com.qa.shoppingagent.trace;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/** A tool invocation as exposed in the API contract ({@code tool_calls[]}). Maps 1:1 to DeepEval's ToolCall. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolCallRecord(
        @JsonProperty("name") String name,
        @JsonProperty("args") Map<String, Object> args,
        @JsonProperty("output") String output,
        @JsonProperty("latency_ms") long latencyMs,
        @JsonProperty("status") String status,
        @JsonProperty("error") String error
) {}
