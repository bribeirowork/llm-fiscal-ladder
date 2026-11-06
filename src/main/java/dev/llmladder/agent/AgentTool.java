package dev.llmladder.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.llmladder.llm.ToolSpec;
import java.util.Map;

interface AgentTool {
    ToolSpec spec();

    AutonomyLevel level();

    /** Resultado sempre em JSON. Negativas de política voltam como dados para o modelo poder reagir. */
    Map<String, Object> execute(JsonNode input, AgentSession session);
}
