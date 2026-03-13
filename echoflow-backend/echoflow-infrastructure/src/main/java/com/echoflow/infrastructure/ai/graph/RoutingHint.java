package com.echoflow.infrastructure.ai.graph;

/**
 * Parsed routing hint extracted from THINK step output.
 *
 * @param needsResearch whether subsequent RESEARCH steps should run
 * @param reason        LLM's explanation for the routing decision
 */
record RoutingHint(boolean needsResearch, String reason) {

    /** Safe default: always run RESEARCH when hint is missing or unparseable. */
    static final RoutingHint DEFAULT = new RoutingHint(true, "No routing hint found; defaulting to research");
}
