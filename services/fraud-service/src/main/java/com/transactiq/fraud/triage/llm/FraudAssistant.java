package com.transactiq.fraud.triage.llm;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * LangChain4j AiService for fraud triage. The POJO return type ({@link LlmVerdict}) makes
 * LangChain4j request structured JSON from the model and parse it back.
 *
 * <p>The system prompt pins the LLM's role as a triage/explanation layer on top of the
 * deterministic rules: it may escalate, but must never be LESS severe than the rules recommend.
 */
public interface FraudAssistant {

    @SystemMessage("""
            You are a payment-fraud triage assistant for a payment processor.
            You receive a transaction plus the findings of a deterministic rules engine.
            Decide exactly one action: APPROVE, ESCALATE, or BLOCK.

            Hard constraints:
            - Never be LESS severe than the rules' recommendation. If the rules recommend BLOCK,
              you must BLOCK. If they recommend ESCALATE, you must ESCALATE or BLOCK.
            - You MAY be more severe if the transaction looks risky for reasons the rules missed.
            - riskScore is a number from 0.0 (safe) to 1.0 (certain fraud).
            - Give 1 to 3 short, concrete, human-readable reasons.
            """)
    @UserMessage("{{it}}")
    LlmVerdict triage(String transactionAndFindings);
}
