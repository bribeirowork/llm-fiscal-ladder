package dev.llmladder.workflow;

import dev.llmladder.rag.Answer;
import java.util.List;

/** Saída JSON da v2. Só leitura: sugere a ação, não executa. */
public record WorkflowResult(
        String accessKey,
        String documentState,
        String cStat,
        RejectionCategory category,
        double confidence,
        String suggestedAction,
        List<Answer.Citation> rule,
        boolean needsHuman,
        String traceId) {}
