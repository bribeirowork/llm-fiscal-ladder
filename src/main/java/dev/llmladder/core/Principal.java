package dev.llmladder.core;

import java.util.Set;

/** Quem está chamando. O tenant define o que o retriever enxerga; os papéis definem quem pode aprovar. */
public record Principal(String subject, String tenant, Set<String> roles) {
    public static final String ROLE_APPROVER = "approver";

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
