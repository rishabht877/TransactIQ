package com.transactiq.fraud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * fraud-service service entry point.
 *
 * <p>Phase 0: bootable skeleton only. The LangChain4j AiService (structured FraudDecision
 * output) and deterministic pre-checks are added in Phase 3.
 */
@SpringBootApplication
public class FraudServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FraudServiceApplication.class, args);
    }
}
