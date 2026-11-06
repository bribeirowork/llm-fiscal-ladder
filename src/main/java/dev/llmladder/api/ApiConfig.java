package dev.llmladder.api;

import dev.llmladder.Platform;
import dev.llmladder.core.Principal;
import dev.llmladder.llm.AnthropicLlmClient;
import dev.llmladder.llm.LlmClient;
import dev.llmladder.llm.SimulatedLlmClient;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ApiConfig {
    @Bean
    Platform platform(@Value("${ladder.llm}") String mode) {
        LlmClient client = mode.equals("anthropic") ? AnthropicLlmClient.fromEnv() : new SimulatedLlmClient();
        return Platform.with(client);
    }

    @Bean
    FeedbackLog feedbackLog(@Value("${ladder.feedback-file}") String file) {
        return new FeedbackLog(Path.of(file));
    }

    /** Formato de cada entrada: chave|sujeito|tenant|papel1+papel2 */
    @Bean
    FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(@Value("${ladder.api-keys}") List<String> entries) {
        Map<String, Principal> principals = new HashMap<>();
        for (String entry : entries) {
            String[] parts = entry.strip().split("\\|");
            Set<String> roles = parts.length > 3 ? Set.copyOf(Arrays.asList(parts[3].split("\\+"))) : Set.of();
            principals.put(parts[0], new Principal(parts[1], parts[2], roles));
        }
        return new FilterRegistrationBean<>(new ApiKeyFilter(principals));
    }
}
