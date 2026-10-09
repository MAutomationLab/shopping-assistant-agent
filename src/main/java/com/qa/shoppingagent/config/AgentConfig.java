package com.qa.shoppingagent.config;

import com.qa.shoppingagent.agent.ShoppingAssistant;
import com.qa.shoppingagent.tools.ShoppingTools;
import com.qa.shoppingagent.trace.TraceRecorder;
import com.qa.shoppingagent.trace.TracingChatModelListener;
import com.qa.shoppingagent.trace.TracingContentRetriever;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Configuration
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    // ------------------------------------------------------------ tracing

    @Bean(destroyMethod = "close")
    OpenTelemetrySdk openTelemetry(AgentProperties props) {
        Resource resource = Resource.getDefault().merge(Resource.create(Attributes.of(
                AttributeKey.stringKey("service.name"), "shopping-assistant-agent",
                AttributeKey.stringKey("openinference.project.name"), "shopping-assistant")));
        SdkTracerProviderBuilder provider = SdkTracerProvider.builder().setResource(resource);
        String endpoint = props.tracing().otlpEndpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            provider.addSpanProcessor(BatchSpanProcessor.builder(
                    OtlpHttpSpanExporter.builder().setEndpoint(endpoint).build()).build());
            log.info("Exporting OpenTelemetry traces to {}", endpoint);
        } else {
            log.info("OTLP export disabled (set OTLP_ENDPOINT to enable). Traces available via API only.");
        }
        return OpenTelemetrySdk.builder().setTracerProvider(provider.build()).build();
    }

    @Bean
    Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("shopping-assistant-agent");
    }

    // ------------------------------------------------------------ LLM

    @Bean
    ChatModel chatModel(AgentProperties props, TracingChatModelListener listener) {
        AgentProperties.Gemini g = props.gemini();
        if (g.apiKey() == null || g.apiKey().isBlank()) {
            throw new IllegalStateException("GOOGLE_API_KEY is not set (env var or .env file in the agent folder).");
        }
        return GoogleAiGeminiChatModel.builder()
                .apiKey(g.apiKey())
                .modelName(g.model())
                .temperature(g.temperature())
                .timeout(Duration.ofSeconds(g.timeoutSeconds()))
                .maxRetries(g.maxRetries())
                .logRequestsAndResponses(g.logRequests())
                // Gemini 3 requires thought signatures to be echoed back on tool-call turns.
                // returnThinking also surfaces the model's reasoning, which we put on the LLM span.
                .returnThinking(true)
                .sendThinking(true)
                .listeners(List.of(listener))
                .build();
    }

    // ------------------------------------------------------------ RAG

    @Bean
    EmbeddingModel embeddingModel() {
        return new AllMiniLmL6V2EmbeddingModel();
    }

    @Bean
    EmbeddingStore<TextSegment> embeddingStore(AgentProperties props, EmbeddingModel embeddingModel) {
        if (!props.rag().usePgvector()) {
            log.info("RAG store: in-memory");
            return new InMemoryEmbeddingStore<>();
        }
        AgentProperties.Pgvector pg = props.rag().pgvector();
        if (pg.password() == null || pg.password().isBlank()) {
            throw new IllegalStateException("PGVECTOR_PASSWORD is not set (env var or .env). "
                    + "Or run with RAG_STORE=in-memory.");
        }
        log.info("RAG store: pgvector {}:{}/{} table={}", pg.host(), pg.port(), pg.database(), pg.table());
        return PgVectorEmbeddingStore.builder()
                .host(pg.host())
                .port(pg.port())
                .database(pg.database())
                .user(pg.user())
                .password(pg.password())
                .table(pg.table())
                .dimension(embeddingModel.dimension()) // 384 for all-MiniLM-L6-v2; must match the model
                .createTable(true)                      // auto-creates the table + vector column on first run
                .build();
    }

    @Bean
    ContentRetriever contentRetriever(EmbeddingStore<TextSegment> store, EmbeddingModel embeddingModel,
                                      AgentProperties props, TraceRecorder recorder) throws IOException {
        // In-memory always needs ingesting; pgvector persists, so only re-ingest when configured to.
        boolean ingest = !props.rag().usePgvector() || props.rag().pgvector().reingestOnStartup();
        if (ingest) {
            if (props.rag().usePgvector()) {
                store.removeAll(); // avoid duplicate chunks from previous runs
            }
            List<Document> docs = new ArrayList<>();
            for (var res : new PathMatchingResourcePatternResolver().getResources("classpath:policies/*.txt")) {
                String text = res.getContentAsString(StandardCharsets.UTF_8);
                docs.add(Document.from(text, Metadata.from("source", res.getFilename())));
            }
            EmbeddingStoreIngestor.builder()
                    .documentSplitter(DocumentSplitters.recursive(200, 0))
                    .embeddingModel(embeddingModel)
                    .embeddingStore(store)
                    .build()
                    .ingest(docs);
            log.info("Ingested {} policy documents into the {} RAG store", docs.size(), props.rag().store());
        } else {
            log.info("Skipping ingestion; using existing pgvector data (PGVECTOR_REINGEST=false)");
        }

        ContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(store)
                .embeddingModel(embeddingModel)
                .maxResults(props.rag().maxResults())
                .minScore(props.rag().minScore())
                .build();
        return new TracingContentRetriever(retriever, recorder, props.rag().store());
    }

    // ------------------------------------------------------------ agent

    @Bean
    ShoppingAssistant shoppingAssistant(ChatModel chatModel, ShoppingTools tools, ContentRetriever retriever,
                                        AgentProperties props) {
        return AiServices.builder(ShoppingAssistant.class)
                .chatModel(chatModel)
                .tools(tools)
                .contentRetriever(retriever)
                .chatMemoryProvider(id -> MessageWindowChatMemory.withMaxMessages(props.memory().maxMessages()))
                .build();
    }
}
