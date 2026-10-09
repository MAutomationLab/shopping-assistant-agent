package com.qa.shoppingagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent")
public record AgentProperties(
        Gemini gemini,
        Memory memory,
        Rag rag,
        Budget budget,
        Faults faults,
        Tracing tracing
) {
    public record Gemini(String apiKey, String model, double temperature, int timeoutSeconds, int maxRetries,
                         boolean logRequests) {}

    public record Memory(int maxMessages) {}

    /** store = "pgvector" (PostgreSQL) or "in-memory". */
    public record Rag(int maxResults, double minScore, String store, Pgvector pgvector) {
        public boolean usePgvector() {
            return "pgvector".equalsIgnoreCase(store);
        }
    }

    public record Pgvector(String host, int port, String database, String user, String password, String table,
                           boolean reingestOnStartup) {}

    public record Budget(int maxToolCalls) {}

    public record Faults(boolean enabled) {}

    public record Tracing(String otlpEndpoint, int traceStoreSize) {}
}
