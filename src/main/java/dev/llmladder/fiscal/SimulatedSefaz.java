package dev.llmladder.fiscal;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** SEFAZ em memória com injeção de falhas, incluindo o caso clássico: autoriza e a resposta se perde. */
public final class SimulatedSefaz implements SefazPort {
    private record Doc(State state, String protocol, Instant authorizedAt) {}

    private final Map<String, Doc> documents = new ConcurrentHashMap<>();
    private final Clock clock;
    private final AtomicInteger protocols = new AtomicInteger(135_000_000);
    private final AtomicInteger submitCalls = new AtomicInteger();
    private final AtomicInteger duplicates = new AtomicInteger();
    private final AtomicInteger cancellations = new AtomicInteger();
    private volatile boolean loseNextSubmitResponse;
    private volatile boolean consultTimesOut;

    public SimulatedSefaz(Clock clock) {
        this.clock = clock;
    }

    public void seedAuthorized(AccessKey key, Instant authorizedAt) {
        documents.put(key.value(), new Doc(State.AUTORIZADA, nextProtocol(), authorizedAt));
    }

    public void loseNextSubmitResponse() {
        loseNextSubmitResponse = true;
    }

    public void consultTimesOut(boolean value) {
        consultTimesOut = value;
    }

    public int submitCalls() {
        return submitCalls.get();
    }

    public int duplicates() {
        return duplicates.get();
    }

    public int cancellations() {
        return cancellations.get();
    }

    @Override
    public Status consult(AccessKey key) {
        if (consultTimesOut) {
            return Status.unknown();
        }
        Doc doc = documents.get(key.value());
        if (doc == null) {
            return new Status(State.NAO_ENCONTRADA, "217", null, null);
        }
        String cStat = doc.state() == State.CANCELADA ? "101" : "100";
        return new Status(doc.state(), cStat, doc.protocol(), doc.authorizedAt());
    }

    @Override
    public Status submit(AccessKey key, String idempotencyKey) {
        submitCalls.incrementAndGet();
        Doc existing = documents.get(key.value());
        if (existing != null) {
            duplicates.incrementAndGet();
            return new Status(existing.state(), "204", existing.protocol(), existing.authorizedAt());
        }
        Doc created = new Doc(State.AUTORIZADA, nextProtocol(), clock.instant());
        documents.put(key.value(), created);
        if (loseNextSubmitResponse) {
            loseNextSubmitResponse = false;
            return Status.unknown();
        }
        return new Status(State.AUTORIZADA, "100", created.protocol(), created.authorizedAt());
    }

    @Override
    public CancelResult cancel(AccessKey key, String protocol, String justification) {
        Doc doc = documents.get(key.value());
        if (doc == null || !doc.protocol().equals(protocol)) {
            return new CancelResult(false, "217");
        }
        if (doc.state() == State.CANCELADA) {
            return new CancelResult(false, "420");
        }
        documents.put(key.value(), new Doc(State.CANCELADA, doc.protocol(), doc.authorizedAt()));
        cancellations.incrementAndGet();
        return new CancelResult(true, "135");
    }

    private String nextProtocol() {
        return "1" + protocols.incrementAndGet();
    }
}
