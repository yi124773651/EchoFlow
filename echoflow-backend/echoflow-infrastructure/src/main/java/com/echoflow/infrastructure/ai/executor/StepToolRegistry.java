package com.echoflow.infrastructure.ai.executor;

import com.echoflow.domain.execution.StepType;

import java.util.List;
import java.util.Map;

class StepToolRegistry {

    private final Map<StepType, List<Object>> tools;

    StepToolRegistry(Map<StepType, List<Object>> tools) {
        this.tools = Map.copyOf(tools);
    }

    List<Object> toolsFor(StepType type) {
        return tools.getOrDefault(type, List.of());
    }
}
