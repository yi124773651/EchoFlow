package com.echoflow.infrastructure.ai.executor;

import com.echoflow.domain.execution.StepType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StepToolRegistryTest {

    @Test
    void should_return_tools_for_registered_step_type() {
        var tool1 = new Object();
        var tool2 = new Object();
        var registry = new StepToolRegistry(Map.of(
                StepType.THINK, List.of(tool1),
                StepType.RESEARCH, List.of(tool1, tool2)
        ));
        assertThat(registry.toolsFor(StepType.THINK)).containsExactly(tool1);
        assertThat(registry.toolsFor(StepType.RESEARCH)).containsExactly(tool1, tool2);
    }

    @Test
    void should_return_empty_list_for_unregistered_step_type() {
        var registry = new StepToolRegistry(Map.of(StepType.THINK, List.of(new Object())));
        assertThat(registry.toolsFor(StepType.WRITE)).isEmpty();
    }

    @Test
    void should_be_immutable() {
        var registry = new StepToolRegistry(Map.of(StepType.THINK, List.of(new Object())));
        var tools = registry.toolsFor(StepType.THINK);
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class, () -> tools.add(new Object()));
    }
}
