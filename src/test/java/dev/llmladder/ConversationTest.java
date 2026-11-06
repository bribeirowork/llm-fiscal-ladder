package dev.llmladder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.llmladder.context.ConversationStore;
import dev.llmladder.core.Principal;
import dev.llmladder.llm.LlmClient;
import dev.llmladder.llm.LlmMessage;
import dev.llmladder.llm.LlmRequest;
import dev.llmladder.llm.LlmResponse;
import dev.llmladder.llm.Usage;
import dev.llmladder.rag.Answer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ConversationTest {
    private static final Principal ACME = new Principal("ana", "acme", Set.of());
    private static final Principal GLOBEX = new Principal("gil", "globex", Set.of());
    private static final String CHUNK = "manual-rejeicoes#3";

    private record Call(String model, String prompt) {}

    private static LlmClient answering(List<Call> calls, Function<String, String> confidenceByModel) {
        return request -> {
            calls.add(new Call(request.model(), ((LlmMessage.User) request.messages().get(0)).text()));
            String json = "{\"answer\":\"Duplicidade de NF-e.\",\"citations\":[\"%s\"],\"confidence\":%s}"
                    .formatted(CHUNK, confidenceByModel.apply(request.model()));
            return new LlmResponse(request.model(), json, List.of(), LlmResponse.Stop.END_TURN, Usage.ZERO, null);
        };
    }

    @Test
    void contextSurvivesTheChangeOfModelInsideAConversation() {
        List<Call> calls = new ArrayList<>();
        List<String> confidence = new ArrayList<>(List.of("0.9", "0.4"));
        Platform platform = Platform.with(answering(calls, m -> m.contains("haiku") ? confidence.remove(0) : "0.9"));

        platform.rag.answer("O que significa a rejeição 204?", ACME, "conversa-1");
        Answer second = platform.rag.answer("E como devo tratar a rejeição 204 duplicidade?", ACME, "conversa-1");

        assertThat(calls).extracting(Call::model).containsExactly("claude-haiku-5-5", "claude-haiku-5-5", "claude-opus-5-5");
        assertThat(second.model()).isEqualTo("claude-opus-5-5");
        assertThat(calls.get(2).prompt()).contains("<historico>", "O que significa a rejeição 204?", "Duplicidade de NF-e.");
    }

    @Test
    void personalDataMarkersStayStableAcrossTurnsAndHistoryIsMasked() {
        List<Call> calls = new ArrayList<>();
        Platform platform = Platform.with(answering(calls, m -> "0.9"));

        platform.rag.answer("Rejeição 204 no CPF 123.456.789-09", ACME, "conversa-2");
        platform.rag.answer("Rejeição 204 de novo no CPF 123.456.789-09", ACME, "conversa-2");

        assertThat(calls.get(1).prompt()).contains("[CPF_1]").doesNotContain("123.456.789-09");
    }

    @Test
    void sameIdInAnotherTenantDoesNotSeeTheHistory() {
        List<Call> calls = new ArrayList<>();
        Platform platform = Platform.with(answering(calls, m -> "0.9"));
        platform.rag.answer("O que significa a rejeição 204?", ACME, "conversa-3");

        platform.rag.answer("O que significa a rejeição 204 mesmo?", GLOBEX, "conversa-3");

        assertThat(calls.get(1).prompt()).doesNotContain("<historico>");
    }

    @Test
    void withoutConversationIdNothingIsRemembered() {
        List<Call> calls = new ArrayList<>();
        Platform platform = Platform.with(answering(calls, m -> "0.9"));
        platform.rag.answer("O que significa a rejeição 204?", ACME);

        platform.rag.answer("O que significa a rejeição 204 mesmo?", ACME);

        assertThat(calls.get(1).prompt()).doesNotContain("<historico>");
    }

    @Test
    void keepsOnlyARecentWindowOfTurns() {
        ConversationStore store = new ConversationStore(Clock.systemUTC());
        var conversation = store.open("acme", "janela");
        for (int i = 1; i <= 8; i++) {
            conversation.record("pergunta " + i, "resposta", "modelo");
        }

        assertThat(conversation.turns()).hasSize(6);
        assertThat(conversation.turns().get(0).question()).isEqualTo("pergunta 3");
    }

    @Test
    void expiredConversationStartsEmpty() {
        MutableClock clock = new MutableClock();
        ConversationStore store = new ConversationStore(clock);
        store.open("acme", "antiga").record("pergunta", "resposta", "modelo");

        clock.advance(ConversationStore.TTL.minusMinutes(1));
        assertThat(store.open("acme", "antiga").turns()).hasSize(1);

        clock.advance(ConversationStore.TTL.plusMinutes(1));
        assertThat(store.open("acme", "antiga").turns()).isEmpty();
    }

    @Test
    void rejectsInvalidConversationId() {
        ConversationStore store = new ConversationStore(Clock.systemUTC());

        assertThatThrownBy(() -> store.open("acme", "../outro tenant")).isInstanceOf(IllegalArgumentException.class);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-05T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
