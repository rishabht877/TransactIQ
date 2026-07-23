package com.transactiq.fraud.domain;

/** Final fraud triage call, ordered by severity (APPROVE < ESCALATE < BLOCK). */
public enum Decision {
    APPROVE,
    ESCALATE,
    BLOCK
}
