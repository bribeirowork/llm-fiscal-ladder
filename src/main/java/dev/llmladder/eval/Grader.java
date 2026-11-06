package dev.llmladder.eval;

import java.util.Map;
import java.util.Optional;

/** Devolve vazio quando o caso não se aplica ao grader. Código primeiro; LLM-juiz só onde código não alcança. */
public interface Grader {
    String name();

    FailureKind kind();

    Optional<Boolean> grade(GoldenCase testCase, Map<String, Object> facts);
}
