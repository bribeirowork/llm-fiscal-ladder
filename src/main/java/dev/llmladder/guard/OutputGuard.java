package dev.llmladder.guard;

import com.fasterxml.jackson.databind.JsonNode;
import dev.llmladder.core.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Saída de modelo é entrada externa (OWASP LLM05): nada é usado antes de passar por aqui. */
public final class OutputGuard {
    public record ParsedAnswer(String answer, List<String> citations, double confidence) {}

    public record ParsedClassification(String category, double confidence) {}

    public record Verdict<T>(Optional<T> value, List<String> violations) {
        public boolean ok() {
            return violations.isEmpty();
        }
    }

    private OutputGuard() {}

    /** @param allowedCitations ids entregues no contexto; citar outro id é fonte inventada. */
    public static Verdict<ParsedAnswer> checkAnswer(String raw, Set<String> allowedCitations) {
        JsonNode node;
        try {
            node = Json.read(raw);
        } catch (IllegalArgumentException e) {
            return new Verdict<>(Optional.empty(), List.of("resposta não é JSON"));
        }
        if (!node.path("answer").isTextual() || !node.path("citations").isArray() || !node.path("confidence").isNumber()) {
            return new Verdict<>(Optional.empty(), List.of("JSON fora do schema"));
        }
        List<String> citations = new ArrayList<>();
        node.path("citations").forEach(c -> citations.add(c.asText()));
        ParsedAnswer parsed = new ParsedAnswer(node.path("answer").asText(), citations, node.path("confidence").asDouble());

        List<String> violations = new ArrayList<>();
        citations.stream().filter(c -> !allowedCitations.contains(c)).forEach(c -> violations.add("citação fora do contexto: " + c));
        StatusCodeTable.unknownCodesIn(parsed.answer()).forEach(c -> violations.add("código inexistente na tabela: " + c));
        return new Verdict<>(Optional.of(parsed), violations);
    }

    public static Verdict<ParsedClassification> checkClassification(String raw, Set<String> allowedCategories) {
        JsonNode node;
        try {
            node = Json.read(raw);
        } catch (IllegalArgumentException e) {
            return new Verdict<>(Optional.empty(), List.of("resposta não é JSON"));
        }
        String category = node.path("category").asText("");
        if (!allowedCategories.contains(category) || !node.path("confidence").isNumber()) {
            return new Verdict<>(Optional.empty(), List.of("categoria fora do enum: " + category));
        }
        return new Verdict<>(Optional.of(new ParsedClassification(category, node.path("confidence").asDouble())), List.of());
    }
}
