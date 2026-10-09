package com.qa.shoppingagent.trace;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.springframework.stereotype.Component;

import java.util.List;

/** Records every raw LLM round-trip (the agent loop makes one per reasoning step) as an LLM span. */
@Component
public class TracingChatModelListener implements ChatModelListener {

    private static final String SPAN_KEY = "qa.trace.span";

    private final TraceRecorder recorder;

    public TracingChatModelListener(TraceRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        SpanRecord span = recorder.startSpan(SpanRecord.Type.LLM, "gemini.generate");
        if (span == null) {
            return;
        }
        ChatRequest request = ctx.chatRequest();
        List<ChatMessage> messages = request.messages();
        span.attr("llm.provider", "google")
                .attr("llm.input_message_count", messages.size())
                .attr("llm.tools_offered", request.toolSpecifications() == null ? 0 : request.toolSpecifications().size())
                .attr("input.value", messages.isEmpty() ? "" : messages.getLast().toString());
        ctx.attributes().put(SPAN_KEY, span);
    }

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        SpanRecord span = (SpanRecord) ctx.attributes().get(SPAN_KEY);
        ChatResponse response = ctx.chatResponse();
        TokenUsage usage = response.tokenUsage();
        long in = usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
        long out = usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();
        String finish = response.finishReason() == null ? null : response.finishReason().name();
        recorder.recordLlmUsage(response.modelName(), in, out, finish);
        if (span == null) {
            return;
        }
        AiMessage ai = response.aiMessage();
        List<String> requestedTools = ai.hasToolExecutionRequests()
                ? ai.toolExecutionRequests().stream().map(ToolExecutionRequest::name).toList()
                : List.of();
        span.attr("llm.model_name", response.modelName())
                .attr("llm.token_count.prompt", in)
                .attr("llm.token_count.completion", out)
                .attr("llm.token_count.total", in + out)
                .attr("llm.finish_reason", finish)
                .attr("llm.requested_tools", requestedTools.isEmpty() ? null : String.join(",", requestedTools))
                .attr("llm.thinking", ai.thinking())
                .attr("output.value", ai.text() != null ? ai.text() : "tool_calls: " + requestedTools);
        recorder.endSpan(span, "OK", null);
    }

    @Override
    public void onError(ChatModelErrorContext ctx) {
        SpanRecord span = (SpanRecord) ctx.attributes().get(SPAN_KEY);
        recorder.endSpan(span, "ERROR", String.valueOf(ctx.error().getMessage()));
    }
}
