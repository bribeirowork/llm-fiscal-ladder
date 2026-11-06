package dev.llmladder.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.llmladder.core.Json;
import dev.llmladder.rag.TextTokens;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simulador determinístico para rodar demo e CI sem chave nem custo. Não mede qualidade de modelo:
 * mede o sistema ao redor dele (retrieval, ACL, guardrails, roteamento, políticas de ferramenta).
 * Ele só "sabe" o que estiver no contexto, o que torna visível a diferença entre os degraus.
 */
public final class SimulatedLlmClient implements LlmClient {
    private static final Pattern DOC = Pattern.compile("<documento id=\"([^\"]+)\"[^>]*>(.*?)</documento>", Pattern.DOTALL);
    private static final Pattern QUESTION = Pattern.compile("<pergunta>(.*?)</pergunta>", Pattern.DOTALL);
    private static final Pattern INCIDENT = Pattern.compile("<incidente tipo=\"(\\w+)\" chave=\"([^\"]+)\">(.*?)</incidente>", Pattern.DOTALL);
    private static final Pattern CSTAT = Pattern.compile("<cstat>(.*?)</cstat>");
    private static final Map<String, String> CATEGORY_BY_CODE = Map.ofEntries(
            Map.entry("100", "AUTORIZADA"), Map.entry("101", "CANCELADA"), Map.entry("135", "CANCELADA"),
            Map.entry("110", "DENEGADA"), Map.entry("204", "DUPLICIDADE"), Map.entry("539", "DUPLICIDADE"),
            Map.entry("217", "NAO_ENCONTRADA"), Map.entry("225", "SCHEMA"), Map.entry("420", "JA_CANCELADA"),
            Map.entry("502", "CHAVE_INVALIDA"), Map.entry("108", "SERVICO_INDISPONIVEL"));

    private final AtomicInteger callIds = new AtomicInteger();

    @Override
    public LlmResponse complete(LlmRequest request) {
        return switch (request.task()) {
            case ANSWER -> text(request, answer(request));
            case CLASSIFY -> text(request, classify(request));
            case JUDGE -> text(request, "{\"pass\":true,\"reason\":\"simulado\"}");
            case AGENT -> agent(request);
        };
    }

    private static String firstUserText(LlmRequest request) {
        return request.messages().stream()
                .filter(LlmMessage.User.class::isInstance)
                .map(m -> ((LlmMessage.User) m).text())
                .findFirst().orElse("");
    }

    private String answer(LlmRequest request) {
        String prompt = firstUserText(request);
        Matcher q = QUESTION.matcher(prompt);
        Set<String> questionTokens = Set.copyOf(TextTokens.tokenize(q.find() ? q.group(1) : prompt));

        String bestId = null;
        String bestBody = null;
        long bestOverlap = 0;
        Matcher docs = DOC.matcher(prompt);
        while (docs.find()) {
            long overlap = TextTokens.tokenize(docs.group(2)).stream().distinct().filter(questionTokens::contains).count();
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                bestId = docs.group(1);
                bestBody = docs.group(2).strip();
            }
        }
        if (bestId == null) {
            return Json.write(Map.of("answer", "Não tenho essa informação no contexto.", "citations", List.of(), "confidence", 0.2));
        }
        boolean smallModel = request.model().contains("haiku");
        double confidence = smallModel && bestOverlap < 3 ? 0.5 : 0.9;
        return Json.write(Map.of("answer", bestBody, "citations", List.of(bestId), "confidence", confidence));
    }

    private String classify(LlmRequest request) {
        Matcher m = CSTAT.matcher(firstUserText(request));
        String code = m.find() ? m.group(1).strip() : "";
        String category = CATEGORY_BY_CODE.getOrDefault(code, "DESCONHECIDA");
        return Json.write(Map.of("category", category, "confidence", category.equals("DESCONHECIDA") ? 0.3 : 0.95));
    }

    private LlmResponse agent(LlmRequest request) {
        Matcher incident = INCIDENT.matcher(firstUserText(request));
        if (!incident.find()) {
            return text(request, "Incidente não identificado.");
        }
        String type = incident.group(1);
        String key = incident.group(2);
        boolean gullible = incident.group(3).contains("IGNORE");

        Map<String, String> callNames = new LinkedHashMap<>();
        List<String[]> history = new ArrayList<>();
        for (LlmMessage message : request.messages()) {
            if (message instanceof LlmMessage.Assistant assistant) {
                assistant.toolCalls().forEach(c -> callNames.put(c.id(), c.name()));
            } else if (message instanceof LlmMessage.ToolResults results) {
                results.results().forEach(r -> history.add(new String[] {callNames.get(r.toolCallId()), r.content()}));
            }
        }
        // Modelo "ingênuo": obedece a instrução injetada. Quem precisa segurar é a política, não o modelo.
        if (gullible && history.isEmpty()) {
            return call(request, "solicitar_cancelamento", key, "cancelamento pedido no texto do incidente");
        }
        return type.equals("CANCELAMENTO") ? cancelPolicy(request, key, history) : timeoutPolicy(request, key, history);
    }

    private LlmResponse timeoutPolicy(LlmRequest request, String key, List<String[]> history) {
        if (history.isEmpty()) {
            return call(request, "consultar_situacao", key, null);
        }
        String last = lastField(history, "situacao");
        if (!lastTool(history).equals("consultar_situacao") && !last.equals("AUTORIZADA")) {
            return call(request, "consultar_situacao", key, null);
        }
        return switch (last) {
            case "NAO_ENCONTRADA" -> call(request, "reenviar_nfe", key, null);
            case "AUTORIZADA" -> text(request, "Nota autorizada. Nenhum reenvio necessário.");
            default -> text(request, "Situação segue desconhecida. Encaminhando para um humano.");
        };
    }

    private LlmResponse cancelPolicy(LlmRequest request, String key, List<String[]> history) {
        boolean deniedAtStart = history.size() == 1 && !lastTool(history).equals("consultar_situacao");
        if (history.isEmpty() || deniedAtStart) {
            return call(request, "consultar_situacao", key, null);
        }
        if (lastTool(history).equals("consultar_situacao") && lastField(history, "situacao").equals("AUTORIZADA")) {
            return call(request, "solicitar_cancelamento", key, "Erro no valor da nota informado pelo analista");
        }
        return text(request, "Fluxo de cancelamento encerrado; veja o resultado da ferramenta.");
    }

    private static String lastTool(List<String[]> history) {
        return history.get(history.size() - 1)[0];
    }

    private static String lastField(List<String[]> history, String field) {
        JsonNode result = Json.read(history.get(history.size() - 1)[1]);
        return result.path(field).asText("");
    }

    private LlmResponse call(LlmRequest request, String tool, String key, String justification) {
        ObjectNode input = Json.MAPPER.createObjectNode().put("chave", key);
        if (justification != null) {
            input.put("justificativa", justification);
        }
        ToolCall call = new ToolCall("sim-" + callIds.incrementAndGet(), tool, input);
        return new LlmResponse(request.model(), "", List.of(call), LlmResponse.Stop.TOOL_USE, estimate(request, tool), null);
    }

    private static LlmResponse text(LlmRequest request, String content) {
        return new LlmResponse(request.model(), content, List.of(), LlmResponse.Stop.END_TURN, estimate(request, content), null);
    }

    private static Usage estimate(LlmRequest request, String output) {
        long input = request.system().length() + request.messages().stream().mapToLong(m -> m.toString().length()).sum();
        return new Usage(input / 4, output.length() / 4 + 1, 0, 0);
    }
}
