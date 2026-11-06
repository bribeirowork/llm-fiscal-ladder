package dev.llmladder.llm;

import java.util.List;

/**
 * Nem toda requisição merece o modelo mais caro. Preços em US$ por milhão de tokens,
 * conforme a tabela da Anthropic de 2026-10; confira antes de usar a estimativa em decisões financeiras.
 */
public record ModelCatalog(Model small, Model medium, Model large) {

    public record Model(String id, double inputPerM, double outputPerM, double cacheReadPerM) {}

    public static ModelCatalog anthropicDefaults() {
        return new ModelCatalog(
                new Model("claude-haiku-5-5", 0.10, 0.50, 0.01),
                new Model("claude-sonnet-5-5", 2.00, 10.00, 0.20),
                new Model("claude-opus-5-5", 4.00, 20.00, 0.20));
    }

    /** Cascata FrugalGPT: tenta o menor e só escala se a confiança for baixa. */
    public List<String> answerCascade() {
        return List.of(small.id(), large.id());
    }

    public String classifier() {
        return small.id();
    }

    public String agent() {
        return large.id();
    }

    /** O juiz é um modelo diferente de quem respondeu (viés de autofavorecimento). */
    public String judgeFor(String answeringModel) {
        return answeringModel.equals(medium.id()) ? large.id() : medium.id();
    }

    public double estimateCostUsd(String modelId, Usage usage) {
        Model m = find(modelId);
        double cacheWrite = usage.cacheWriteTokens() * m.inputPerM() * 1.25;
        return (usage.inputTokens() * m.inputPerM()
                + usage.outputTokens() * m.outputPerM()
                + usage.cacheReadTokens() * m.cacheReadPerM()
                + cacheWrite) / 1_000_000.0;
    }

    private Model find(String id) {
        return List.of(small, medium, large).stream().filter(m -> m.id().equals(id)).findFirst().orElse(large);
    }
}
