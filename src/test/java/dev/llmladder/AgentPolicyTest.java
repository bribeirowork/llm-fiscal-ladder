package dev.llmladder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.llmladder.agent.AgentOutcome;
import dev.llmladder.agent.ApprovalService;
import dev.llmladder.agent.FiscalTools;
import dev.llmladder.agent.IncidentType;
import dev.llmladder.core.Json;
import dev.llmladder.core.Principal;
import dev.llmladder.eval.Samples;
import dev.llmladder.fiscal.AccessKey;
import dev.llmladder.fiscal.SefazPort;
import dev.llmladder.llm.LlmClient;
import dev.llmladder.llm.LlmRequest;
import dev.llmladder.llm.LlmResponse;
import dev.llmladder.llm.ToolCall;
import dev.llmladder.llm.Usage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** O modelo aqui é adversário de propósito: as garantias precisam valer mesmo se ele decidir errado. */
class AgentPolicyTest {
    private static final Principal ANALYST = new Principal("ana", "acme", Set.of());
    private static final Principal APPROVER = new Principal("ari", "acme", Set.of(Principal.ROLE_APPROVER));
    private static final AccessKey KEY = Samples.key(900);

    /** Devolve chamadas pré-definidas, na ordem, e encerra quando acabam. */
    private static final class ScriptedLlm implements LlmClient {
        private final Deque<ToolCall> script = new ArrayDeque<>();
        int requests;

        ScriptedLlm call(String tool, String chave, String justificativa) {
            ObjectNode input = Json.MAPPER.createObjectNode().put("chave", chave);
            if (justificativa != null) {
                input.put("justificativa", justificativa);
            }
            script.add(new ToolCall("c" + script.size(), tool, input));
            return this;
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            requests++;
            ToolCall next = script.poll();
            return next == null
                    ? new LlmResponse(request.model(), "fim", List.of(), LlmResponse.Stop.END_TURN, Usage.ZERO, null)
                    : new LlmResponse(request.model(), "", List.of(next), LlmResponse.Stop.TOOL_USE, Usage.ZERO, null);
        }
    }

    @Test
    void blindResendAfterTimeoutIsDenied() {
        ScriptedLlm llm = new ScriptedLlm().call(FiscalTools.REENVIAR, "[CHAVE_1]", null);
        Platform platform = Platform.with(llm);

        AgentOutcome outcome = platform.agent.resolve(IncidentType.TIMEOUT, KEY, "timeout", ANALYST);

        assertThat(platform.sefaz.submitCalls()).isZero();
        assertThat(outcome.denials()).contains("reenviar_nfe:CONSULTE_ANTES");
        assertThat(outcome.status()).isEqualTo(AgentOutcome.Status.ESCALADO);
    }

    @Test
    void resendAfterConsultWithLostResponseDoesNotDuplicate() {
        ScriptedLlm llm = new ScriptedLlm()
                .call(FiscalTools.CONSULTAR, "[CHAVE_1]", null)
                .call(FiscalTools.REENVIAR, "[CHAVE_1]", null)
                .call(FiscalTools.REENVIAR, "[CHAVE_1]", null);
        Platform platform = Platform.with(llm);
        platform.sefaz.loseNextSubmitResponse();

        AgentOutcome outcome = platform.agent.resolve(IncidentType.TIMEOUT, KEY, "timeout", ANALYST);

        assertThat(platform.sefaz.submitCalls()).isEqualTo(1);
        assertThat(platform.sefaz.duplicates()).isZero();
        assertThat(outcome.denials()).contains("reenviar_nfe:CONSULTE_ANTES");
    }

    @Test
    void cancelToolIsOutOfProfileForTimeoutIncidents() {
        ScriptedLlm llm = new ScriptedLlm().call(FiscalTools.CANCELAR, "[CHAVE_1]", "justificativa bem longa aqui");
        Platform platform = Platform.with(llm);
        platform.sefaz.seedAuthorized(KEY, Instant.now());

        AgentOutcome outcome = platform.agent.resolve(IncidentType.TIMEOUT, KEY, "timeout", ANALYST);

        assertThat(outcome.denials()).contains("solicitar_cancelamento:FORA_DO_PERFIL");
        assertThat(outcome.approvalId()).isNull();
    }

    @Test
    void agentCannotActOnAnotherAccessKey() {
        ScriptedLlm llm = new ScriptedLlm().call(FiscalTools.CONSULTAR, Samples.key(901).value(), null);
        Platform platform = Platform.with(llm);

        AgentOutcome outcome = platform.agent.resolve(IncidentType.TIMEOUT, KEY, "timeout", ANALYST);

        assertThat(outcome.denials()).contains("consultar_situacao:CHAVE_FORA_DO_ESCOPO");
    }

    @Test
    void endlessModelStopsAtTurnLimitAndEscalates() {
        ScriptedLlm llm = new ScriptedLlm();
        for (int i = 0; i < 20; i++) {
            llm.call(FiscalTools.CONSULTAR, "[CHAVE_1]", null);
        }
        Platform platform = Platform.with(llm);

        AgentOutcome outcome = platform.agent.resolve(IncidentType.TIMEOUT, KEY, "timeout", ANALYST);

        assertThat(llm.requests).isEqualTo(6);
        assertThat(outcome.summary()).contains("limite de turnos");
    }

    private static Platform platformWithApprovalPending() {
        ScriptedLlm llm = new ScriptedLlm().call(FiscalTools.CANCELAR, "[CHAVE_1]", "Erro no valor da nota emitida");
        Platform platform = Platform.with(llm);
        platform.sefaz.seedAuthorized(KEY, Instant.now());
        return platform;
    }

    @Test
    void cancellationOnlyHappensAfterHumanApproval() {
        Platform platform = platformWithApprovalPending();
        AgentOutcome outcome = platform.agent.resolve(IncidentType.CANCELAMENTO, KEY, "cancelar", ANALYST);

        assertThat(outcome.status()).isEqualTo(AgentOutcome.Status.AGUARDANDO_APROVACAO);
        assertThat(platform.sefaz.cancellations()).isZero();

        var decided = platform.approvals.approve(outcome.approvalId(), APPROVER);

        assertThat(decided.status()).isEqualTo(ApprovalService.Status.EXECUTADA);
        assertThat(platform.sefaz.consult(KEY).state()).isEqualTo(SefazPort.State.CANCELADA);
    }

    @Test
    void requesterCannotApproveOwnCancellation() {
        Platform platform = platformWithApprovalPending();
        AgentOutcome outcome = platform.agent.resolve(IncidentType.CANCELAMENTO, KEY, "cancelar", APPROVER);

        assertThatThrownBy(() -> platform.approvals.approve(outcome.approvalId(), APPROVER))
                .isInstanceOf(ApprovalService.ApprovalDeniedException.class);
    }

    @Test
    void userWithoutRoleCannotApprove() {
        Platform platform = platformWithApprovalPending();
        AgentOutcome outcome = platform.agent.resolve(IncidentType.CANCELAMENTO, KEY, "cancelar", ANALYST);

        assertThatThrownBy(() -> platform.approvals.approve(outcome.approvalId(), new Principal("bob", "acme", Set.of())))
                .isInstanceOf(ApprovalService.ApprovalDeniedException.class);
    }

    @Test
    void otherTenantCannotSeeOrApprove() {
        Platform platform = platformWithApprovalPending();
        AgentOutcome outcome = platform.agent.resolve(IncidentType.CANCELAMENTO, KEY, "cancelar", ANALYST);
        Principal outsider = new Principal("eve", "globex", Set.of(Principal.ROLE_APPROVER));

        assertThat(platform.approvals.pending("globex")).isEmpty();
        assertThatThrownBy(() -> platform.approvals.approve(outcome.approvalId(), outsider))
                .isInstanceOf(ApprovalService.NotFoundException.class);
    }

    @Test
    void windowIsRecheckedWhenApprovalExecutes() {
        ScriptedLlm llm = new ScriptedLlm().call(FiscalTools.CANCELAR, "[CHAVE_1]", "Erro no valor da nota emitida");
        MutableClock clock = new MutableClock(Instant.parse("2026-11-05T10:00:00Z"));
        Platform platform = new Platform(llm, dev.llmladder.llm.ModelCatalog.anthropicDefaults(),
                dev.llmladder.gateway.AiGateway.Limits.defaults(), clock);
        platform.sefaz.seedAuthorized(KEY, clock.instant());
        AgentOutcome outcome = platform.agent.resolve(IncidentType.CANCELAMENTO, KEY, "cancelar", ANALYST);

        clock.advance(Duration.ofHours(25));
        var decided = platform.approvals.approve(outcome.approvalId(), APPROVER);

        assertThat(decided.status()).isEqualTo(ApprovalService.Status.FALHOU);
        assertThat(platform.sefaz.cancellations()).isZero();
    }

    @Test
    void compensatingAResendStillRequiresApproval() {
        ScriptedLlm llm = new ScriptedLlm()
                .call(FiscalTools.CONSULTAR, "[CHAVE_1]", null)
                .call(FiscalTools.REENVIAR, "[CHAVE_1]", null);
        Platform platform = Platform.with(llm);
        platform.agent.resolve(IncidentType.TIMEOUT, KEY, "timeout", ANALYST);
        var action = platform.actions.forTenant("acme").get(0);

        var approval = platform.approvals.compensate(action.id(), "Nota enviada para o cliente errado", ANALYST);

        assertThat(approval.status()).isEqualTo(ApprovalService.Status.PENDENTE);
        assertThat(platform.sefaz.cancellations()).isZero();
        assertThatThrownBy(() -> platform.approvals.compensate(action.id(), "Segunda tentativa de compensar", ANALYST))
                .isInstanceOf(ApprovalService.ApprovalDeniedException.class);
    }

    @Test
    void everyToolCallIsAudited() {
        ScriptedLlm llm = new ScriptedLlm().call(FiscalTools.REENVIAR, "[CHAVE_1]", null);
        Platform platform = Platform.with(llm);
        platform.agent.resolve(IncidentType.TIMEOUT, KEY, "timeout", ANALYST);

        assertThat(platform.audit.forTenant("acme")).hasSize(1)
                .allSatisfy(e -> assertThat(e.key()).doesNotContain(KEY.value()));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
