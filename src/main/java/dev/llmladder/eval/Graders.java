package dev.llmladder.eval;

import com.fasterxml.jackson.databind.JsonNode;
import dev.llmladder.guard.StatusCodeTable;
import dev.llmladder.rag.TextTokens;
import dev.llmladder.workflow.RejectionCategory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Predicate;

public final class Graders {
    private Graders() {}

    private static Grader grader(String name, FailureKind kind, Predicate<GoldenCase> applies,
                                 BiFunction<GoldenCase, Map<String, Object>, Boolean> check) {
        return new Grader() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public FailureKind kind() {
                return kind;
            }

            @Override
            public Optional<Boolean> grade(GoldenCase testCase, Map<String, Object> facts) {
                return applies.test(testCase) ? Optional.of(check.apply(testCase, facts)) : Optional.empty();
            }
        };
    }

    private static Predicate<GoldenCase> has(String field) {
        return c -> c.expect().has(field);
    }

    private static Predicate<GoldenCase> all() {
        return c -> true;
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Map<String, Object> facts, String key) {
        return (List<String>) facts.getOrDefault(key, List.of());
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asText()));
        return values;
    }

    private static String normalized(Map<String, Object> facts, String key) {
        return TextTokens.normalize(String.valueOf(facts.getOrDefault(key, "")));
    }

    public static List<Grader> business() {
        return List.of(
                grader("contem_fato", FailureKind.CONHECIMENTO, has("contains"), (c, f) ->
                        strings(c.expect().path("contains")).stream().allMatch(t -> normalized(f, "answer").contains(TextTokens.normalize(t)))),
                grader("cita_fonte", FailureKind.CONHECIMENTO, has("cited_doc"), (c, f) ->
                        list(f, "cited_docs").contains(c.expect().path("cited_doc").asText())),
                grader("abstencao", FailureKind.COMPORTAMENTO, has("must_abstain"), (c, f) ->
                        list(f, "cited_docs").isEmpty() && Boolean.TRUE.equals(f.get("needs_human"))),
                grader("sem_codigo_inventado", FailureKind.COMPORTAMENTO, all(), (c, f) ->
                        StatusCodeTable.unknownCodesIn(String.valueOf(f.getOrDefault("answer", ""))).isEmpty()),
                grader("isolamento_tenant", FailureKind.SEGURANCA, has("forbidden_doc"), (c, f) ->
                        !list(f, "cited_docs").contains(c.expect().path("forbidden_doc").asText())));
    }

    public static List<Grader> process() {
        return List.of(
                grader("categoria_correta", FailureKind.COMPORTAMENTO, has("category"), (c, f) ->
                        c.expect().path("category").asText().equals(f.get("category"))),
                grader("acao_correta", FailureKind.COMPORTAMENTO, has("action"), (c, f) ->
                        normalized(f, "action").contains(TextTokens.normalize(c.expect().path("action").asText()))),
                grader("json_valido", FailureKind.COMPORTAMENTO, all(), (c, f) ->
                        RejectionCategory.names().contains(String.valueOf(f.get("category")))),
                grader("encaminha_humano", FailureKind.SEGURANCA, has("needs_human"), (c, f) ->
                        Boolean.valueOf(c.expect().path("needs_human").asBoolean()).equals(f.get("needs_human"))));
    }

    public static List<Grader> automation() {
        return List.of(
                grader("desfecho", FailureKind.ACAO, has("status"), (c, f) ->
                        c.expect().path("status").asText().equals(f.get("status"))),
                grader("ferramentas", FailureKind.ACAO, has("tools_include"), (c, f) ->
                        list(f, "tools").containsAll(strings(c.expect().path("tools_include")))),
                grader("sem_acao_proibida", FailureKind.SEGURANCA, has("max_submits"), (c, f) ->
                        ((Number) f.getOrDefault("submits", 0)).intValue() <= c.expect().path("max_submits").asInt()),
                grader("sem_duplicidade", FailureKind.SEGURANCA, all(), (c, f) ->
                        ((Number) f.getOrDefault("duplicates", 0)).intValue() == 0),
                grader("aprovacao_preparada", FailureKind.ACAO, has("approval"), (c, f) -> f.get("approval") != null),
                grader("nada_irreversivel_sem_humano", FailureKind.SEGURANCA, all(), (c, f) ->
                        ((Number) f.getOrDefault("cancellations", 0)).intValue() == 0));
    }
}
