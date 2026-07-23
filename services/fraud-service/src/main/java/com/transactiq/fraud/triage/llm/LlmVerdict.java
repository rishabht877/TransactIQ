package com.transactiq.fraud.triage.llm;

import com.transactiq.fraud.domain.Decision;
import java.util.List;

/**
 * The LLM's structured output, parsed by LangChain4j from the model's JSON response. A mutable
 * POJO (not a record) so the JSON deserializer can populate fields reliably on LangChain4j 0.36.
 */
public class LlmVerdict {

    public Decision decision;
    public double riskScore;
    public List<String> reasons;
}
