package dev.llmladder.context;

import dev.llmladder.gateway.PiiVault;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Contexto da conversa guardado do nosso lado, em formato neutro. O prompt cache do provedor é por modelo e
 * se perde na troca; este histórico não depende de modelo, então a próxima resposta continua de onde parou.
 * Fica mascarado, é isolado por tenant, tem janela curta (contexto enxuto) e expira (retenção da LGPD).
 */
public final class ConversationStore {
    public static final Duration TTL = Duration.ofMinutes(30);
    static final int MAX_TURNS = 6;
    private static final int CAPACITY = 1_000;
    private static final Pattern VALID_ID = Pattern.compile("[\\w-]{1,64}");

    public record Turn(String question, String answer, String model) {}

    public static final class Conversation {
        private final PiiVault vault = new PiiVault();
        private final List<Turn> turns = new ArrayList<>();
        private Instant lastUsedAt;

        private Conversation(Instant now) {
            lastUsedAt = now;
        }

        /** O mesmo vault em todos os turnos mantém os marcadores estáveis: [CPF_1] segue sendo a mesma pessoa. */
        public PiiVault vault() {
            return vault;
        }

        public synchronized void record(String question, String answer, String model) {
            turns.add(new Turn(vault.mask(question), vault.mask(answer), model));
            if (turns.size() > MAX_TURNS) {
                turns.remove(0);
            }
        }

        public synchronized List<Turn> turns() {
            return List.copyOf(turns);
        }

        /** Entra na busca para que uma pergunta como "e o prazo?" ainda encontre o assunto. */
        public synchronized String lastQuestion() {
            return turns.isEmpty() ? "" : turns.get(turns.size() - 1).question();
        }

        public synchronized String renderHistory() {
            if (turns.isEmpty()) {
                return "";
            }
            String rendered = turns.stream()
                    .map(t -> "<turno><analista>%s</analista><assistente>%s</assistente></turno>".formatted(t.question(), t.answer()))
                    .collect(Collectors.joining("\n"));
            return "<historico>\n" + rendered + "\n</historico>\n";
        }
    }

    private final Clock clock;
    private final Map<String, Conversation> conversations = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Conversation> eldest) {
            return size() > CAPACITY;
        }
    });

    public ConversationStore(Clock clock) {
        this.clock = clock;
    }

    /** Sem id, a conversa dura apenas a requisição. */
    public Conversation open(String tenant, String id) {
        Instant now = clock.instant();
        if (id == null) {
            return new Conversation(now);
        }
        if (!VALID_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("conversationId inválido");
        }
        String key = tenant + ":" + id;
        synchronized (conversations) {
            Conversation current = conversations.get(key);
            if (current == null || current.lastUsedAt.plus(TTL).isBefore(now)) {
                current = new Conversation(now);
                conversations.put(key, current);
            }
            current.lastUsedAt = now;
            return current;
        }
    }
}
