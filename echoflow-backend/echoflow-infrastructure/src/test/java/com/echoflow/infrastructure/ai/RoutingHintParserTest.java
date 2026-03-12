package com.echoflow.infrastructure.ai;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingHintParserTest {

    @Nested
    class ParsesValidHints {

        @Test
        void parses_YES_hint() {
            var output = """
                    Analysis of the task...

                    [ROUTING]
                    needs_research: YES
                    reason: Task requires external data retrieval""";

            var hint = RoutingHintParser.parse(output);

            assertThat(hint.needsResearch()).isTrue();
            assertThat(hint.reason()).isEqualTo("Task requires external data retrieval");
        }

        @Test
        void parses_NO_hint() {
            var output = """
                    Simple analysis...

                    [ROUTING]
                    needs_research: NO
                    reason: Task can be completed from analysis alone""";

            var hint = RoutingHintParser.parse(output);

            assertThat(hint.needsResearch()).isFalse();
            assertThat(hint.reason()).isEqualTo("Task can be completed from analysis alone");
        }

        @Test
        void case_insensitive_yes() {
            var output = "[ROUTING]\nneeds_research: yes\nreason: r";

            var hint = RoutingHintParser.parse(output);

            assertThat(hint.needsResearch()).isTrue();
        }

        @Test
        void case_insensitive_no() {
            var output = "[ROUTING]\nneeds_research: No\nreason: r";

            var hint = RoutingHintParser.parse(output);

            assertThat(hint.needsResearch()).isFalse();
        }

        @Test
        void handles_extra_whitespace() {
            var output = "[ROUTING]  \n  needs_research:  YES  \n  reason:  spaced reason  ";

            var hint = RoutingHintParser.parse(output);

            assertThat(hint.needsResearch()).isTrue();
            assertThat(hint.reason()).isEqualTo("spaced reason");
        }

        @Test
        void handles_missing_reason() {
            var output = "[ROUTING]\nneeds_research: NO";

            var hint = RoutingHintParser.parse(output);

            assertThat(hint.needsResearch()).isFalse();
            assertThat(hint.reason()).isEmpty();
        }

        @Test
        void parses_hint_after_long_analysis() {
            var longAnalysis = "Very detailed analysis.\n".repeat(100);
            var output = longAnalysis + "\n[ROUTING]\nneeds_research: NO\nreason: Simple task";

            var hint = RoutingHintParser.parse(output);

            assertThat(hint.needsResearch()).isFalse();
            assertThat(hint.reason()).isEqualTo("Simple task");
        }
    }

    @Nested
    class DefaultsOnInvalidInput {

        @Test
        void returns_default_when_null() {
            var hint = RoutingHintParser.parse(null);

            assertThat(hint).isEqualTo(RoutingHint.DEFAULT);
            assertThat(hint.needsResearch()).isTrue();
        }

        @Test
        void returns_default_when_blank() {
            var hint = RoutingHintParser.parse("   ");

            assertThat(hint).isEqualTo(RoutingHint.DEFAULT);
        }

        @Test
        void returns_default_when_no_routing_block() {
            var hint = RoutingHintParser.parse("Just some analysis text without routing");

            assertThat(hint).isEqualTo(RoutingHint.DEFAULT);
        }

        @Test
        void returns_default_when_malformed_value() {
            var output = "[ROUTING]\nneeds_research: MAYBE\nreason: unsure";

            var hint = RoutingHintParser.parse(output);

            assertThat(hint).isEqualTo(RoutingHint.DEFAULT);
        }
    }
}
