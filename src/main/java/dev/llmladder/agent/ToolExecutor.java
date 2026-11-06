package dev.llmladder.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.llmladder.core.Json;
import dev.llmladder.llm.ToolCall;
import dev.llmladder.llm.ToolResult;
import dev.llmladder.llm.ToolSpec;
import java.util.List;
import java.util.Map;

/**
 * Único caminho entre o modelo e o mundo. Toda chamada passa por: perfil (menor privilégio), limite de
 * chamadas (consumo ilimitado), escopo da chave, execução e auditoria. O argumento do modelo é não confiável.
 */
public final class ToolExecutor {
    private static final int MAX_CALLS_PER_SESSION = 8;

    private final Map<String, AgentTool> tools;
    private final AuditLog audit;

    ToolExecutor(Map<String, AgentTool> tools, AuditLog audit) {
        this.tools = tools;
        this.audit = audit;
    }

    List<ToolSpec> specsFor(IncidentType type) {
        return tools.values().stream()
                .filter(t -> type.allowedTools.contains(t.spec().name()))
                .map(AgentTool::spec)
                .toList();
    }

    ToolResult execute(ToolCall call, AgentSession session) {
        session.toolsCalled.add(call.name());
        AgentTool tool = tools.get(call.name());
        Map<String, Object> result = tool == null ? FiscalTools.denied("FERRAMENTA_DESCONHECIDA", call.name()) : guarded(tool, call, session);
        AutonomyLevel level = tool == null ? AutonomyLevel.SUGERE : tool.level();
        boolean denied = result.containsKey("negado");
        if (denied) {
            session.denials.add(call.name() + ":" + result.get("negado"));
            session.trace.event("guardrail.ferramenta", Map.of("ferramenta", call.name(), "motivo", String.valueOf(result.get("negado"))));
        }
        audit.record(session.principal.tenant(), "agente:" + session.principal.subject(), session.trace.id(), call.name(),
                level, session.key.masked(), Json.write(result));
        return new ToolResult(call.id(), Json.write(result), denied);
    }

    private Map<String, Object> guarded(AgentTool tool, ToolCall call, AgentSession session) {
        if (!session.type.allowedTools.contains(call.name())) {
            return FiscalTools.denied("FORA_DO_PERFIL", "ferramenta não liberada para incidente " + session.type);
        }
        if (++session.calls > MAX_CALLS_PER_SESSION) {
            return FiscalTools.denied("LIMITE_CHAMADAS", "limite de chamadas da sessão atingido");
        }
        JsonNode input = call.input();
        if (input.has("chave") && !session.vault.unmask(input.path("chave").asText()).equals(session.key.value())) {
            return FiscalTools.denied("CHAVE_FORA_DO_ESCOPO", "a sessão só opera sobre a chave do incidente");
        }
        return session.trace.step("ferramenta:" + call.name(), () -> tool.execute(input, session));
    }
}
