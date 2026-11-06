package dev.llmladder.gateway;

public class GatewayException extends RuntimeException {
    public enum Reason { RATE_LIMITED, BUDGET_EXCEEDED, INPUT_TOO_LARGE }

    public final Reason reason;

    public GatewayException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }
}
