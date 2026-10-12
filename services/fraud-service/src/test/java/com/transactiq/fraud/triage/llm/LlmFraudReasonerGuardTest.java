package com.transactiq.fraud.triage.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.transactiq.fraud.api.FraudEvaluationRequest;
import com.transactiq.fraud.domain.Decision;
import com.transactiq.fraud.domain.FraudDecision;
import com.transactiq.fraud.rules.RuleFindings;
import com.transactiq.fraud.triage.RuleBasedReasoner;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests the guards around the LLM, not the LLM. The model is replaced by a lambda that returns a
 * chosen verdict, so every case is deterministic. The real {@link LlmFraudReasoner} and its real
 * reconcile logic run; only the {@link FraudAssistant} is fake. No Docker, no Ollama, no network.
 *
 * <p>Two layers: an exhaustive sweep that asserts invariants over the whole input space, and a
 * handful of named scenarios so the intent is readable.
 */
class LlmFraudReasonerGuardTest {

    // The risk bands from LlmFraudReasoner.reconcile, restated on purpose: if someone moves a
    // threshold, these tests should fail until the change is made here too.
    private static final double ESCALATE_AT = 0.4;
    private static final double BLOCK_AT = 0.8;

    /** Values straddling each band edge, the extremes, and two a misbehaving model could emit. */
    private static final double[] RISKS = {-5.0, 0.0, 0.39, 0.40, 0.41, 0.79, 0.80, 0.81, 1.0, 99.0};

    private static final FraudEvaluationRequest REQUEST = new FraudEvaluationRequest(
            "p-1", new BigDecimal("42.00"), "USD", "cust-1", "4242", "US", "Acme");

    /** A rule outcome as the real engine produces it: the floor and its risk score come as a pair. */
    private record RuleOutcome(Decision floor, double risk) {
        RuleFindings findings() {
            return new RuleFindings(floor, risk, List.of("rule signal for " + floor));
        }
    }

    private static final List<RuleOutcome> RULE_OUTCOMES = List.of(
            new RuleOutcome(Decision.APPROVE, 0.0),
            new RuleOutcome(Decision.ESCALATE, 0.5),
            new RuleOutcome(Decision.BLOCK, 0.9));

    // ---------------------------------------------------------------- helpers

    private static FraudAssistant modelSays(Decision decision, double risk, String... reasons) {
        return prompt -> {
            LlmVerdict v = new LlmVerdict();
            v.decision = decision;
            v.riskScore = risk;
            v.reasons = List.of(reasons);
            return v;
        };
    }

    private static LlmFraudReasoner reasonerWith(FraudAssistant model) {
        return new LlmFraudReasoner(model, new RuleBasedReasoner());
    }

    private static FraudDecision run(FraudAssistant model, RuleOutcome rules) {
        return reasonerWith(model).reason(REQUEST, rules.findings());
    }

    private static Decision riskImplied(double risk) {
        return risk >= BLOCK_AT ? Decision.BLOCK : risk >= ESCALATE_AT ? Decision.ESCALATE : Decision.APPROVE;
    }

    private static Decision moreSevere(Decision a, Decision b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    private static final RuleOutcome CLEAN = RULE_OUTCOMES.get(0);
    private static final RuleOutcome RULE_ESCALATE = RULE_OUTCOMES.get(1);
    private static final RuleOutcome RULE_BLOCK = RULE_OUTCOMES.get(2);

    // ---------------------------------------------------------------- exhaustive sweep

    @Test
    void invariantsHoldForEveryCombination() {
        List<String> violations = new ArrayList<>();
        int cases = 0;

        for (RuleOutcome rules : RULE_OUTCOMES) {
            for (Decision modelDecision : Decision.values()) {
                for (double modelRisk : RISKS) {
                    cases++;
                    FraudDecision out = run(modelSays(modelDecision, modelRisk, "model reason"), rules);
                    String label = "rules=" + rules.floor() + "@" + rules.risk()
                            + " model=" + modelDecision + "@" + modelRisk + " -> " + out.decision()
                            + "@" + out.riskScore();

                    // 1. The floor always holds.
                    if (out.decision().ordinal() < rules.floor().ordinal()) {
                        violations.add("below rule floor: " + label);
                    }
                    // 2. Never more severe than the final risk score justifies (or the floor).
                    Decision ceiling = moreSevere(rules.floor(), riskImplied(out.riskScore()));
                    if (out.decision().ordinal() > ceiling.ordinal()) {
                        violations.add("above ceiling: " + label);
                    }
                    // 3. Risk stays inside [0, 1].
                    if (out.riskScore() < 0.0 || out.riskScore() > 1.0) {
                        violations.add("risk out of range: " + label);
                    }
                    // 4. The model can raise risk but never lower it below the rules' score.
                    if (out.riskScore() < rules.risk()) {
                        violations.add("risk below rule risk: " + label);
                    }
                    // 5. The rule signals are always kept alongside the model's reasons.
                    if (out.reasons().stream().noneMatch(r -> r.startsWith("rule signals:"))) {
                        violations.add("rule signals missing: " + label);
                    }
                }
            }
        }

        // Guards against the sweep quietly shrinking if the arrays above are edited.
        assertThat(cases).isEqualTo(RULE_OUTCOMES.size() * Decision.values().length * RISKS.length);
        assertThat(violations).as("every violated invariant, by combination").isEmpty();
    }

    // ---------------------------------------------------------------- severity floor

    @Test
    void modelCannotApproveAwayARuleBlock() {
        FraudDecision out = run(modelSays(Decision.APPROVE, 0.0, "looks fine to me"), RULE_BLOCK);

        assertThat(out.decision()).isEqualTo(Decision.BLOCK);
        assertThat(out.riskScore()).isEqualTo(0.9);
    }

    @Test
    void modelCannotApproveAwayARuleEscalate() {
        FraudDecision out = run(modelSays(Decision.APPROVE, 0.0, "fine"), RULE_ESCALATE);

        assertThat(out.decision()).isEqualTo(Decision.ESCALATE);
    }

    // ---------------------------------------------------------------- risk-consistency

    @Test
    void blockWithNearZeroRiskOnACleanPaymentCollapsesToApprove() {
        FraudDecision out = run(
                modelSays(Decision.BLOCK, 0.02, "Card is linked to a known fraud ring"), CLEAN);

        assertThat(out.decision()).isEqualTo(Decision.APPROVE);
    }

    @Test
    void genuineEscalationPassesThroughWhenTheRiskScoreBacksIt() {
        assertThat(run(modelSays(Decision.BLOCK, 0.95, "mule merchant"), CLEAN).decision())
                .isEqualTo(Decision.BLOCK);
        assertThat(run(modelSays(Decision.ESCALATE, 0.55, "unusual merchant"), CLEAN).decision())
                .isEqualTo(Decision.ESCALATE);
    }

    @Test
    void bandEdgesAreInclusiveOnTheLowerBound() {
        assertThat(run(modelSays(Decision.ESCALATE, 0.40, "x"), CLEAN).decision()).isEqualTo(Decision.ESCALATE);
        assertThat(run(modelSays(Decision.ESCALATE, 0.39, "x"), CLEAN).decision()).isEqualTo(Decision.APPROVE);
        assertThat(run(modelSays(Decision.BLOCK, 0.80, "x"), CLEAN).decision()).isEqualTo(Decision.BLOCK);
        assertThat(run(modelSays(Decision.BLOCK, 0.79, "x"), CLEAN).decision()).isEqualTo(Decision.ESCALATE);
    }

    @Test
    void outOfRangeRiskIsClamped() {
        assertThat(run(modelSays(Decision.APPROVE, -5.0, "x"), CLEAN).riskScore()).isEqualTo(0.0);
        assertThat(run(modelSays(Decision.BLOCK, 99.0, "x"), CLEAN).riskScore()).isEqualTo(1.0);
    }

    // ---------------------------------------------------------------- known gap (backlog B1)

    /**
     * Pins CURRENT behaviour, which is a gap: the risk score is only a ceiling, never a floor, so a
     * model that reports high risk but says APPROVE is approved. It is latent today because the
     * model anchors its score to the rules' score and never gets this high on a clean payment.
     * If the guard is made two-directional, this is the test to flip to expect ESCALATE/BLOCK.
     */
    @Test
    void knownGap_approveAtHighRiskIsNotRaised() {
        FraudDecision out = run(modelSays(Decision.APPROVE, 0.95, "seems risky but approving"), CLEAN);

        assertThat(out.decision()).isEqualTo(Decision.APPROVE);
        assertThat(out.riskScore()).isEqualTo(0.95);
    }

    // ---------------------------------------------------------------- fallback

    @Test
    void fallsBackToRulesWhenTheModelThrows() {
        FraudAssistant down = prompt -> {
            throw new RuntimeException("Failed to connect to localhost:11434");
        };

        assertFallsBackToRules(down);
    }

    @Test
    void fallsBackToRulesWhenTheModelReturnsNothing() {
        assertFallsBackToRules(prompt -> null);
    }

    @Test
    void fallsBackToRulesWhenTheVerdictHasNoDecision() {
        assertFallsBackToRules(prompt -> {
            LlmVerdict v = new LlmVerdict();
            v.decision = null;
            v.riskScore = 0.9;
            v.reasons = List.of("no decision field");
            return v;
        });
    }

    private static void assertFallsBackToRules(FraudAssistant model) {
        for (RuleOutcome rules : RULE_OUTCOMES) {
            FraudDecision out = run(model, rules);

            assertThat(out.decision()).as("floor %s", rules.floor()).isEqualTo(rules.floor());
            assertThat(out.riskScore()).isEqualTo(rules.risk());
            assertThat(out.reasons()).anyMatch(r -> r.contains("LLM triage not applied"));
        }
    }
}
