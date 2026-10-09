package com.qa.shoppingagent.faults;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Faults a tester can inject per request via the {@code X-Fault} header
 * (comma separated, e.g. {@code X-Fault: inventory_error,retrieval_empty}).
 * Only honoured when {@code agent.faults.enabled=true}.
 */
public enum Fault {
    /** Inventory tool hangs ~2s then times out. */
    INVENTORY_TIMEOUT,
    /** Inventory tool returns an upstream 503. */
    INVENTORY_ERROR,
    /** Order status / cancel tools are unavailable. */
    ORDER_SERVICE_DOWN,
    /** Inventory tool output carries an indirect prompt-injection payload. */
    POISONED_PRODUCT,
    /** RAG retriever returns no policy chunks. */
    RETRIEVAL_EMPTY;

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses the header value; throws IllegalArgumentException on unknown codes. */
    public static Set<Fault> parse(String header) {
        Set<Fault> faults = EnumSet.noneOf(Fault.class);
        if (header == null || header.isBlank()) {
            return faults;
        }
        for (String raw : header.split(",")) {
            String code = raw.trim();
            if (code.isEmpty()) {
                continue;
            }
            faults.add(Arrays.stream(values())
                    .filter(f -> f.code().equalsIgnoreCase(code))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown fault '" + code + "'. Known: " + Arrays.stream(values()).map(Fault::code).toList())));
        }
        return faults;
    }
}
