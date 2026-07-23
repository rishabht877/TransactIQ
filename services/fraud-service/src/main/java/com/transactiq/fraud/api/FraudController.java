package com.transactiq.fraud.api;

import com.transactiq.fraud.domain.FraudDecision;
import com.transactiq.fraud.triage.FraudTriageService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fraud")
public class FraudController {

    private final FraudTriageService triageService;

    public FraudController(FraudTriageService triageService) {
        this.triageService = triageService;
    }

    /** Evaluate a payment and return the structured triage decision. Called by payment-processor. */
    @PostMapping("/evaluate")
    public FraudDecision evaluate(@Valid @RequestBody FraudEvaluationRequest request) {
        return triageService.evaluate(request);
    }
}
