package com.sonny.parserag.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Panneau de contrôle central de ParseRAG.
 * Toutes les propriétés préfixées par "parserag" dans application.yaml.
 */
@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "parserag")
public class AppProperties {

    private final OpenAI    openai    = new OpenAI();
    private final Vision    vision    = new Vision();
    private final RateLimit rateLimit = new RateLimit();
    private final Quota     quota     = new Quota();

    @Data
    public static class OpenAI {
        @Value("${parserag.openai.api-key}")
        private String apiKey;     // OPENAI_API_KEY
        @Value("${parserag.openai.model}")
        private String model;
    }

    @Data
    public static class Vision {
        private boolean enabled             = true;
        @Min(0) @Max(1)
        private double  confidenceThreshold = 0.55;
        @Positive
        private int     maxPagesPerDocument = 20;
    }

    @Data
    public static class RateLimit {
        @Positive private int freeRequestsPerMinute    = 10;
        @Positive private int starterRequestsPerMinute = 30;
        @Positive private int proRequestsPerMinute     = 100;
        @Positive private int scaleRequestsPerMinute   = 300;
    }

    @Data
    public static class Quota {
        @Positive private int freeMonthlyDocs    = 100;
        @Positive private int starterMonthlyDocs = 1_000;
        @Positive private int proMonthlyDocs     = 5_000;
        @Positive private int scaleMonthlyDocs   = 20_000;
    }
}