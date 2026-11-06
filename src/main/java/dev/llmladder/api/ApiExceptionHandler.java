package dev.llmladder.api;

import dev.llmladder.agent.ApprovalService;
import dev.llmladder.fiscal.NfeXmlParser;
import dev.llmladder.gateway.GatewayException;
import dev.llmladder.llm.LlmUnavailableException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {
    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("erro", message));
    }

    @ExceptionHandler(GatewayException.class)
    ResponseEntity<Map<String, String>> gateway(GatewayException e) {
        HttpStatus status = switch (e.reason) {
            case RATE_LIMITED, BUDGET_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS;
            case INPUT_TOO_LARGE -> HttpStatus.CONTENT_TOO_LARGE;
        };
        return error(status, e.getMessage());
    }

    @ExceptionHandler(LlmUnavailableException.class)
    ResponseEntity<Map<String, String>> unavailable(LlmUnavailableException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler({NfeXmlParser.InvalidXmlException.class, IllegalArgumentException.class})
    ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(ApprovalService.NotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(ApprovalService.NotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ApprovalService.ApprovalDeniedException.class)
    ResponseEntity<Map<String, String>> forbidden(ApprovalService.ApprovalDeniedException e) {
        return error(HttpStatus.FORBIDDEN, e.getMessage());
    }
}
