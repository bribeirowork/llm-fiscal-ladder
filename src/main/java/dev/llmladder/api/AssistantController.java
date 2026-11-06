package dev.llmladder.api;

import dev.llmladder.Platform;
import dev.llmladder.agent.ActionLog;
import dev.llmladder.agent.AgentOutcome;
import dev.llmladder.agent.ApprovalService;
import dev.llmladder.agent.AuditLog;
import dev.llmladder.agent.IncidentType;
import dev.llmladder.core.Principal;
import dev.llmladder.fiscal.AccessKey;
import dev.llmladder.observability.TaskTrace;
import dev.llmladder.observability.UsageLedger;
import dev.llmladder.observability.UsageReportImage;
import dev.llmladder.rag.Answer;
import dev.llmladder.workflow.WorkflowResult;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
class AssistantController {
    record QuestionRequest(String question, String conversationId) {}

    record DocumentRequest(String xml) {}

    record IncidentRequest(IncidentType type, String accessKey, String description) {}

    record CompensationRequest(String justification) {}

    record FeedbackRequest(String traceId, String question, String problem) {}

    private final Platform platform;
    private final FeedbackLog feedback;

    AssistantController(Platform platform, FeedbackLog feedback) {
        this.platform = platform;
        this.feedback = feedback;
    }

    /** v1, negócio: responde com fonte, não age. */
    @PostMapping("/answers")
    Answer answer(@RequestBody QuestionRequest request, @RequestAttribute(ApiKeyFilter.PRINCIPAL) Principal principal) {
        return platform.rag.answer(request.question(), principal, request.conversationId());
    }

    /** v2, processo: fluxo fixo, somente leitura. */
    @PostMapping("/documents/analyze")
    WorkflowResult analyze(@RequestBody DocumentRequest request, @RequestAttribute(ApiKeyFilter.PRINCIPAL) Principal principal) {
        return platform.workflow.analyze(request.xml(), principal);
    }

    /** v3, automação: agente com ferramentas e aprovação humana. */
    @PostMapping("/incidents")
    AgentOutcome incident(@RequestBody IncidentRequest request, @RequestAttribute(ApiKeyFilter.PRINCIPAL) Principal principal) {
        return platform.agent.resolve(request.type(), new AccessKey(request.accessKey()), request.description(), principal);
    }

    @GetMapping("/approvals")
    List<ApprovalService.Approval> approvals(@RequestAttribute(ApiKeyFilter.PRINCIPAL) Principal principal) {
        return platform.approvals.pending(principal.tenant());
    }

    @PostMapping("/approvals/{id}/approve")
    ApprovalService.Approval approve(@PathVariable String id, @RequestAttribute(ApiKeyFilter.PRINCIPAL) Principal principal) {
        return platform.approvals.approve(id, principal);
    }

    @PostMapping("/approvals/{id}/reject")
    ApprovalService.Approval reject(@PathVariable String id, @RequestAttribute(ApiKeyFilter.PRINCIPAL) Principal principal) {
        return platform.approvals.reject(id, principal);
    }

    @GetMapping("/actions")
    List<ActionLog.Action> actions(@RequestAttribute(ApiKeyFilter.PRINCIPAL) Principal principal) {
        return platform.actions.forTenant(principal.tenant());
    }

    @PostMapping("/actions/{id}/compensate")
    ApprovalService.Approval compensate(@PathVariable String id, @RequestBody CompensationRequest request,
                                        @RequestAttribute(ApiKeyFilter.PRINCIPAL) Principal principal) {
        return platform.approvals.compensate(id, request.justification(), principal);
    }

    @GetMapping("/audit")
    List<AuditLog.Entry> audit(@RequestAttribute(ApiKeyFilter.PRINCIPAL) Principal principal) {
        return platform.audit.forTenant(principal.tenant());
    }

    @GetMapping("/traces/{id}")
    TaskTrace.View trace(@PathVariable String id, @RequestAttribute(ApiKeyFilter.PRINCIPAL) Principal principal) {
        return platform.traces.find(id, principal.tenant())
                .orElseThrow(() -> new ApprovalService.NotFoundException("trace não encontrado"));
    }

    /** Consumo do próprio tenant. Sem datas, cobre o mês atual. */
    @GetMapping("/usage")
    UsageLedger.Report usage(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestAttribute(ApiKeyFilter.PRINCIPAL) Principal principal) {
        return platform.gateway.usage().report(principal.tenant(), from, to);
    }

    @GetMapping(value = "/usage/report.png", produces = MediaType.IMAGE_PNG_VALUE)
    byte[] usageImage(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestAttribute(ApiKeyFilter.PRINCIPAL) Principal principal) {
        return UsageReportImage.render(platform.gateway.usage().report(principal.tenant(), from, to), platform.simulatedLlm);
    }

    @PostMapping("/feedback")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, String> feedback(@RequestBody FeedbackRequest request, @RequestAttribute(ApiKeyFilter.PRINCIPAL) Principal principal) {
        feedback.append(principal.tenant(), request.traceId(), request.question(), request.problem());
        return Map.of("status", "candidato registrado para revisão");
    }
}
