package com.qa.shoppingagent.tools;

/** A downstream-service failure inside a tool. Recorded as an ERROR tool span and reported back to the LLM. */
public class ToolFailure extends RuntimeException {

    public ToolFailure(String message) {
        super(message);
    }
}
