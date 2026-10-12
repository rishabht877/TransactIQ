package com.transactiq.fraud.config;

import com.transactiq.fraud.triage.FraudReasoner;
import com.transactiq.fraud.triage.RuleBasedReasoner;
import com.transactiq.fraud.triage.llm.FraudAssistant;
import com.transactiq.fraud.triage.llm.LlmCircuitBreaker;
import com.transactiq.fraud.triage.llm.LlmFraudReasoner;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.service.AiServices;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the LLM triage layer. Provider is swappable via config
 * ({@code transactiq.fraud.llm.*}); Ollama (local, no API key) is the default. To use a hosted
 * API model instead, add its LangChain4j module and return that model from
 * {@link #chatLanguageModel} — nothing else changes.
 *
 * <p>When {@code transactiq.fraud.llm.enabled=false} (tests / CI / LLM outage), no model beans
 * are created and the deterministic {@link RuleBasedReasoner} is used — the service still works.
 */
@Configuration
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    @ConditionalOnProperty(prefix = "transactiq.fraud.llm", name = "enabled", havingValue = "true")
    public ChatLanguageModel chatLanguageModel(
            @Value("${transactiq.fraud.llm.provider:ollama}") String provider,
            @Value("${transactiq.fraud.llm.base-url:http://localhost:11434}") String baseUrl,
            @Value("${transactiq.fraud.llm.model:llama3.2}") String model,
            @Value("${transactiq.fraud.llm.temperature:0.0}") double temperature,
            @Value("${transactiq.fraud.llm.timeout-seconds:60}") long timeoutSeconds,
            @Value("${transactiq.fraud.llm.max-retries:1}") int maxRetries) {
        if (!"ollama".equalsIgnoreCase(provider)) {
            throw new IllegalArgumentException(
                    "Unsupported LLM provider '" + provider + "'. Add its LangChain4j module and "
                            + "return the corresponding ChatLanguageModel here.");
        }
        log.info("Fraud LLM: Ollama model '{}' at {}", model, baseUrl);
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(model)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                // This is total ATTEMPTS, not extra retries, and the client default is 3. At 3 a hung
                // model stalls a payment for 3x the timeout (measured: ~180s on a 60s setting), so
                // timeout-seconds only bounds the call when this is 1.
                .maxRetries(maxRetries)
                .format("json")   // ask Ollama for JSON so LangChain4j can parse the structured verdict
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "transactiq.fraud.llm", name = "enabled", havingValue = "true")
    public FraudAssistant fraudAssistant(ChatLanguageModel chatLanguageModel) {
        return AiServices.builder(FraudAssistant.class)
                .chatLanguageModel(chatLanguageModel)
                .build();
    }

    /**
     * Stops calling the model after repeated failures, so a dead model costs one timeout per
     * threshold-and-cooldown window instead of one timeout per payment. A threshold of 0 disables it.
     */
    @Bean
    @ConditionalOnProperty(prefix = "transactiq.fraud.llm", name = "enabled", havingValue = "true")
    public LlmCircuitBreaker llmCircuitBreaker(
            @Value("${transactiq.fraud.llm.circuit-breaker.failure-threshold:3}") int failureThreshold,
            @Value("${transactiq.fraud.llm.circuit-breaker.cooldown-seconds:30}") long cooldownSeconds,
            Clock clock,
            MeterRegistry meterRegistry) {
        log.info("Fraud LLM circuit breaker: opens after {} consecutive failures, {}s cooldown",
                failureThreshold, cooldownSeconds);
        return new LlmCircuitBreaker(failureThreshold, Duration.ofSeconds(cooldownSeconds), clock, meterRegistry);
    }

    @Bean
    @ConditionalOnProperty(prefix = "transactiq.fraud.llm", name = "enabled", havingValue = "true")
    public FraudReasoner llmFraudReasoner(FraudAssistant assistant, LlmCircuitBreaker circuitBreaker) {
        // Deterministic rules are the fallback if the model is unreachable/unusable/skipped.
        return new LlmFraudReasoner(assistant, new RuleBasedReasoner(), circuitBreaker);
    }

    /** Used when the LLM is disabled — deterministic, no external dependency. */
    @Bean
    @ConditionalOnMissingBean(FraudReasoner.class)
    public FraudReasoner ruleBasedReasoner() {
        log.info("Fraud LLM disabled — using deterministic RuleBasedReasoner");
        return new RuleBasedReasoner();
    }
}
