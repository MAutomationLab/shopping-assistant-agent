package com.qa.shoppingagent.trace;

import com.qa.shoppingagent.faults.Fault;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import java.util.List;

/** Decorates the RAG retriever with a RETRIEVER span (query, chunks, similarity scores) and the retrieval_empty fault. */
public class TracingContentRetriever implements ContentRetriever {

    private final ContentRetriever delegate;
    private final TraceRecorder recorder;
    private final String storeName;

    public TracingContentRetriever(ContentRetriever delegate, TraceRecorder recorder, String storeName) {
        this.delegate = delegate;
        this.recorder = recorder;
        this.storeName = storeName;
    }

    @Override
    public List<Content> retrieve(Query query) {
        SpanRecord span = recorder.startSpan(SpanRecord.Type.RETRIEVER, "policy_retriever");
        List<Content> contents = recorder.faultActive(Fault.RETRIEVAL_EMPTY) ? List.of() : delegate.retrieve(query);
        if (span != null) {
            span.attr("input.value", query.text())
                    .attr("retrieval.store", storeName)
                    .attr("retrieval.document_count", contents.size());
            for (int i = 0; i < contents.size(); i++) {
                Content c = contents.get(i);
                span.attr("retrieval.documents." + i + ".document.content", c.textSegment().text());
                span.attr("retrieval.documents." + i + ".document.metadata.source", c.textSegment().metadata().getString("source"));
                Object score = c.metadata().get(ContentMetadata.SCORE);
                if (score instanceof Number n) {
                    span.attr("retrieval.documents." + i + ".document.score", Math.round(n.doubleValue() * 1000) / 1000.0);
                }
            }
            recorder.endSpan(span, "OK", null);
        }
        return contents;
    }
}
