package com.qa.shoppingagent.api;

import com.qa.shoppingagent.agent.ShoppingAssistant;
import com.qa.shoppingagent.api.ChatDtos.ChatRequest;
import com.qa.shoppingagent.api.ChatDtos.ChatResponse;
import com.qa.shoppingagent.api.ChatDtos.ErrorResponse;
import com.qa.shoppingagent.config.AgentProperties;
import com.qa.shoppingagent.faults.Fault;
import com.qa.shoppingagent.tools.FakeStore;
import com.qa.shoppingagent.trace.RequestTrace;
import com.qa.shoppingagent.trace.TraceRecorder;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final int MAX_INPUT_CHARS = 2000;

    private final ShoppingAssistant assistant;
    private final TraceRecorder recorder;
    private final FakeStore store;
    private final AgentProperties props;

    public ChatController(ShoppingAssistant assistant, TraceRecorder recorder, FakeStore store, AgentProperties props) {
        this.assistant = assistant;
        this.recorder = recorder;
        this.store = store;
        this.props = props;
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request,
                                  @RequestHeader(value = "X-Debug-Trace", defaultValue = "false") boolean debugTrace,
                                  @RequestHeader(value = "X-Fault", required = false) String faultHeader) {
        if (request == null || request.input() == null || request.input().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "'input' must be a non-empty string", null);
        }
        if (request.input().length() > MAX_INPUT_CHARS) {
            return error(HttpStatus.BAD_REQUEST, "INPUT_TOO_LONG", "'input' exceeds " + MAX_INPUT_CHARS + " characters", null);
        }
        Set<Fault> faults;
        try {
            faults = Fault.parse(faultHeader);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "UNKNOWN_FAULT", e.getMessage(), null);
        }
        if (!faults.isEmpty() && !props.faults().enabled()) {
            return error(HttpStatus.BAD_REQUEST, "FAULTS_DISABLED", "Fault injection is disabled on this instance", null);
        }

        String sessionId = request.sessionId() == null || request.sessionId().isBlank()
                ? "sess-" + UUID.randomUUID()
                : request.sessionId();
        RequestTrace trace = recorder.begin(sessionId, request.input(), faults);

        Result<String> result;
        try {
            result = assistant.chat(sessionId, request.input());
        } catch (RuntimeException e) {
            recorder.finish("ERROR", null, e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("trace_id={} agent failed: {}", trace.getTraceId(), e.toString());
            // Transient provider problems (quota, overload) -> 503 so test clients can retry/skip
            // instead of reporting an agent-quality failure.
            String msg = String.valueOf(e.getMessage());
            boolean transientError = e.getClass().getSimpleName().contains("RateLimit")
                    || msg.contains("\"code\": 429") || msg.contains("RESOURCE_EXHAUSTED")
                    || msg.contains("\"code\": 503") || msg.contains("UNAVAILABLE");
            return transientError
                    ? error(HttpStatus.SERVICE_UNAVAILABLE, "LLM_UNAVAILABLE", msg, trace.getTraceId())
                    : error(HttpStatus.BAD_GATEWAY, "LLM_UPSTREAM_ERROR", msg, trace.getTraceId());
        }

        String answer = result.content();
        recorder.finish("OK", answer, null);
        List<String> retrievalContext = result.sources() == null ? List.of()
                : result.sources().stream().map(Content::textSegment).map(s -> s.text()).toList();

        log.info("trace_id={} session={} tools={} llm_calls={} latency_ms={}", trace.getTraceId(), sessionId,
                trace.getToolCallCount(), trace.getLlmCalls(), trace.getTotalLatencyMs());

        return ResponseEntity.ok()
                .header("X-Trace-Id", trace.getTraceId())
                .body(new ChatResponse(answer, retrievalContext, trace.toolCalls(), sessionId, trace.getTraceId(),
                        debugTrace ? trace : null));
    }

    /** Fetch a trace after the fact (like querying Langfuse/Phoenix by trace id). */
    @GetMapping("/traces/{traceId}")
    public ResponseEntity<?> trace(@PathVariable String traceId) {
        return recorder.find(traceId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> error(HttpStatus.NOT_FOUND, "TRACE_NOT_FOUND", "No trace " + traceId, traceId));
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "model", props.gemini().model(), "faults_enabled", props.faults().enabled(),
                "rag_store", props.rag().store());
    }

    /** Restores seed data (e.g. cancelled orders). Test instances only. */
    @PostMapping("/test/reset")
    public ResponseEntity<?> reset() {
        if (!props.faults().enabled()) {
            return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Test endpoints disabled", null);
        }
        store.reset();
        return ResponseEntity.ok(Map.of("status", "RESET"));
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message, String traceId) {
        var builder = ResponseEntity.status(status);
        if (traceId != null) {
            builder.header("X-Trace-Id", traceId);
        }
        return builder.body(new ErrorResponse(code, message, traceId));
    }
}
