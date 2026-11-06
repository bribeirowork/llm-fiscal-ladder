package dev.llmladder.fiscal;

import java.time.Instant;

/** Porta para a SEFAZ. Timeout não vira exceção: vira DESCONHECIDO, porque é falta de informação, não falha. */
public interface SefazPort {
    enum State { AUTORIZADA, NAO_ENCONTRADA, CANCELADA, DESCONHECIDO }

    record Status(State state, String cStat, String protocol, Instant authorizedAt) {
        public static Status unknown() {
            return new Status(State.DESCONHECIDO, null, null, null);
        }
    }

    record CancelResult(boolean registered, String cStat) {}

    Status consult(AccessKey key);

    /** @param idempotencyKey o mesmo valor em retentativas de uma mesma intenção de envio. */
    Status submit(AccessKey key, String idempotencyKey);

    /** Evento 110111. */
    CancelResult cancel(AccessKey key, String protocol, String justification);
}
