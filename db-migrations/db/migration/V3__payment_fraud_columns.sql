-- V3 — persist the fraud triage result on the payment row so the dashboard (Phase 5) can show
-- the decision + reasoning per payment. Written by the processor when it sets the terminal state.
ALTER TABLE payments
    ADD COLUMN fraud_decision VARCHAR(16) NULL AFTER status,
    ADD COLUMN risk_score     DECIMAL(4,3) NULL AFTER fraud_decision,
    ADD COLUMN fraud_reasons  TEXT NULL AFTER risk_score;
