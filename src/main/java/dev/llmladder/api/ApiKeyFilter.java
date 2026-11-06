package dev.llmladder.api;

import dev.llmladder.core.Principal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;

/** Autenticação do gateway. Em produção, troque por OAuth2/JWT no API gateway da empresa. */
final class ApiKeyFilter extends OncePerRequestFilter {
    static final String PRINCIPAL = "ladder.principal";

    private final Map<String, Principal> principals;

    ApiKeyFilter(Map<String, Principal> principals) {
        this.principals = principals;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader("X-Api-Key");
        Principal principal = key == null ? null : principals.get(key);
        if (principal == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "X-Api-Key ausente ou inválida");
            return;
        }
        request.setAttribute(PRINCIPAL, principal);
        chain.doFilter(request, response);
    }
}
