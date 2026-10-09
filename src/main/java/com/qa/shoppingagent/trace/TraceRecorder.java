package com.qa.shoppingagent.trace;

import com.qa.shoppingagent.config.AgentProperties;
import com.qa.shoppingagent.faults.Fault;
import com.qa.shoppingagent.tools.ToolFailure;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Records a per-request trace. The agent loop (LLM calls, tools, retrieval) runs synchronously on the
 * request thread, so the active trace lives in a ThreadLocal.
 *
 * <p>Every step is recorded twice: into {@link RequestTrace} (returned in the API response / GET /traces)
 * and as an OpenTelemetry span using OpenInference attribute names so Arize Phoenix renders it as an LLM trace.
 */
@Component
public class TraceRecorder {

    private static final int MAX_ATTR_CHARS = 4000;

    private final ThreadLocal<RequestTrace> current = new ThreadLocal<>();
    private final ThreadLocal<SpanRecord> root = new ThreadLocal<>();
    private final Tracer tracer;
    private final int maxToolCalls;
    private final Map<String, RequestTrace> store;

    public TraceRecorder(Tracer tracer, AgentProperties props) {
        this.tracer = tracer;
        this.maxToolCalls = props.budget().maxToolCalls();
        int storeSize = props.tracing().traceStoreSize();
        this.store = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, RequestTrace> eldest) {
                return size() > storeSize;
            }
        });
    }

    // ---------------------------------------------------------------- lifecycle

    public RequestTrace begin(String sessionId, String input, Set<Fault> faults) {
        Span otel = tracer.spanBuilder("shopping_assistant.chat")
                .setNoParent()
                .setAttribute("openinference.span.kind", "AGENT")
                .setAttribute("session.id", sessionId)
                .setAttribute("input.value", truncate(input))
                .startSpan();
        String traceId = otel.getSpanContext().getTraceId();
        RequestTrace trace = new RequestTrace(traceId, sessionId, input, faults);
        SpanRecord rootSpan = new SpanRecord(otel.getSpanContext().getSpanId(), null,
                SpanRecord.Type.AGENT, "shopping_assistant.chat", 0, otel);
        faults.forEach(f -> otel.setAttribute("fault." + f.code(), true));
        trace.addSpan(rootSpan);
        current.set(trace);
        root.set(rootSpan);
        return trace;
    }

    public RequestTrace finish(String status, String answer, String error) {
        RequestTrace trace = current.get();
        SpanRecord rootSpan = root.get();
        current.remove();
        root.remove();
        if (trace == null) {
            return null;
        }
        trace.complete(status, answer, error);
        rootSpan.attr("tool_call_count", trace.getToolCallCount())
                .attr("llm_calls", trace.getLlmCalls());
        Span otel = rootSpan.otelSpan();
        otel.setAttribute("output.value", truncate(answer == null ? "" : answer));
        otel.setAttribute("llm.token_count.total", trace.getTokens().total());
        endSpan(rootSpan, "OK".equals(status) ? "OK" : "ERROR", error);
        store.put(trace.getTraceId(), trace);
        return trace;
    }

    public Optional<RequestTrace> current() {
        return Optional.ofNullable(current.get());
    }

    public boolean faultActive(Fault fault) {
        return current().map(t -> t.hasFault(fault)).orElse(false);
    }

    public Optional<RequestTrace> find(String traceId) {
        return Optional.ofNullable(store.get(traceId));
    }

    // ---------------------------------------------------------------- spans

    /** Starts a child span of the request's root AGENT span. Returns null outside a request. */
    public SpanRecord startSpan(SpanRecord.Type type, String name) {
        RequestTrace trace = current.get();
        SpanRecord parent = root.get();
        if (trace == null || parent == null) {
            return null;
        }
        Span otel = tracer.spanBuilder(name)
                .setParent(Context.root().with(parent.otelSpan()))
                .setAttribute("openinference.span.kind", type.name())
                .startSpan();
        SpanRecord span = new SpanRecord(otel.getSpanContext().getSpanId(), parent.getSpanId(),
                type, name, trace.elapsedMs(), otel);
        trace.addSpan(span);
        return span;
    }

    public void endSpan(SpanRecord span, String status, String error) {
        if (span == null) {
            return;
        }
        span.end(status, error);
        Span otel = span.otelSpan();
        span.getAttributes().forEach((k, v) -> {
            if (v instanceof Double || v instanceof Float) {
                otel.setAttribute(k, ((Number) v).doubleValue());
            } else if (v instanceof Number n) {
                otel.setAttribute(k, n.longValue());
            } else if (v instanceof Boolean b) {
                otel.setAttribute(k, b);
            } else {
                otel.setAttribute(k, truncate(String.valueOf(v)));
            }
        });
        if (error != null) {
            otel.setStatus(StatusCode.ERROR, error);
        } else {
            otel.setStatus(StatusCode.OK);
        }
        otel.end();
    }

    public void recordLlmUsage(String model, long inputTokens, long outputTokens, String finishReason) {
        current().ifPresent(t -> {
            t.addLlmCall(inputTokens, outputTokens);
            t.setModel(model);
            t.setFinishReason(finishReason);
        });
    }

    // ---------------------------------------------------------------- tools

    /**
     * Wraps a tool body: enforces the tool-call budget, times it, records a TOOL span and a
     * {@code tool_calls[]} entry. {@link ToolFailure}s are converted to an "ERROR: ..." tool result so the
     * LLM sees the failure (and tests can check it does not hallucinate around it).
     */
    public String tool(String toolName, Map<String, Object> args, Supplier<String> body) {
        RequestTrace trace = current.get();
        if (trace != null && trace.getToolCallCount() >= maxToolCalls) {
            trace.markBudgetExceeded();
            String msg = "Tool call budget of " + maxToolCalls + " exceeded; no further tools may be called.";
            trace.addToolCall(new ToolCallRecord(toolName, args, null, 0, "BLOCKED", msg));
            return "ERROR: " + msg + " Answer with the information you already have.";
        }
        SpanRecord span = startSpan(SpanRecord.Type.TOOL, toolName);
        if (span != null) {
            span.attr("tool.name", toolName).attr("tool.parameters", args.toString()).attr("input.value", args.toString());
        }
        long start = System.nanoTime();
        String status = "OK";
        String error = null;
        String output;
        try {
            output = body.get();
        } catch (ToolFailure e) {
            status = "ERROR";
            error = e.getMessage();
            output = "ERROR: " + e.getMessage();
        }
        long latency = (System.nanoTime() - start) / 1_000_000;
        if (span != null) {
            span.attr("output.value", output);
            endSpan(span, status, error);
        }
        if (trace != null) {
            trace.addToolCall(new ToolCallRecord(toolName, args, output, latency, status, error));
        }
        return output;
    }

    static String truncate(String s) {
        return s.length() <= MAX_ATTR_CHARS ? s : s.substring(0, MAX_ATTR_CHARS) + "...[truncated]";
    }
}
