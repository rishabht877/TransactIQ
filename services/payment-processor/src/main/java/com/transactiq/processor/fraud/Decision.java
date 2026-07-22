package com.transactiq.processor.fraud;

/** Final fraud triage call. APPROVE -> PROCESSED; ESCALATE / BLOCK -> BLOCKED (routed in Phase 3). */
public enum Decision {
    APPROVE,
    ESCALATE,
    BLOCK
}
