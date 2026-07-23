package com.transactiq.processor.fraud;

import com.transactiq.processor.event.PaymentRequestedEvent;

/**
 * Abstraction over the fraud decision so the processing path doesn't depend on HOW the decision
 * is made. Production uses {@link RemoteFraudDecider} (calls fraud-service); tests supply a stub.
 */
public interface FraudDecider {

    FraudDecision decide(PaymentRequestedEvent event);
}
