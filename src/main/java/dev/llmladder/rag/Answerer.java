package dev.llmladder.rag;

import dev.llmladder.core.Principal;

public interface Answerer {
    Answer answer(String question, Principal principal);
}
