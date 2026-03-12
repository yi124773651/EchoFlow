package com.echoflow.infrastructure.ai;

import java.util.regex.Pattern;

/**
 * Parses the {@code [ROUTING]} block from THINK step output.
 *
 * <p>Expected format (appended by THINK prompt):
 * <pre>
 * [ROUTING]
 * needs_research: YES|NO
 * reason: ...
 * </pre>
 *
 * <p>If the block is missing, malformed, or contains unexpected values,
 * returns {@link RoutingHint#DEFAULT} (safe default = always run RESEARCH).</p>
 */
final class RoutingHintParser {

    private static final Pattern ROUTING_BLOCK = Pattern.compile(
            "\\[ROUTING]\\s*\\n\\s*needs_research:\\s*(YES|NO)(?:\\s*\\n\\s*reason:\\s*(.+))?",
            Pattern.CASE_INSENSITIVE);

    private RoutingHintParser() {
    }

    /**
     * Parse a routing hint from THINK step output.
     *
     * @param thinkOutput the raw THINK step output (may be null)
     * @return parsed hint, or {@link RoutingHint#DEFAULT} on any parse failure
     */
    static RoutingHint parse(String thinkOutput) {
        if (thinkOutput == null || thinkOutput.isBlank()) {
            return RoutingHint.DEFAULT;
        }
        var matcher = ROUTING_BLOCK.matcher(thinkOutput);
        if (!matcher.find()) {
            return RoutingHint.DEFAULT;
        }
        var needsResearch = "YES".equalsIgnoreCase(matcher.group(1).strip());
        var reason = matcher.group(2) != null ? matcher.group(2).strip() : "";
        return new RoutingHint(needsResearch, reason);
    }
}
