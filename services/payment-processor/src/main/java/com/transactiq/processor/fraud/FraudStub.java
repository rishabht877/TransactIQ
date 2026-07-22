package com.transactiq.processor.fraud;

import com.transactiq.processor.event.PaymentRequestedEvent;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Phase 1–2 stub: always APPROVE. Replaced in Phase 3 by a call to fraud-service (deterministic
 * pre-checks + LangChain4j LLM triage). Keeping the same {@link FraudDecision} shape means the
 * processing path does not change when the real service is wired in.
 */
@Component
public class FraudStub {

    public FraudDecision decide(PaymentRequestedEvent event) {
        return new FraudDecision(
                Decision.APPROVE,
                0.0,
                List.of("stubbed decision: always APPROVE (real fraud triage arrives in Phase 3)"));
    }
}
