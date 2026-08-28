package com.sonny.parserag.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

/**
 * Fabrique de {@link ParseRagMetrics} pour les tests unitaires (issue #38). Registre en mémoire :
 * aucun export réseau, et les compteurs restent inspectables via {@link #registry}.
 *
 * <p>Le {@link DefaultMeterObservationHandler} est enregistré explicitement : c'est lui qui
 * transforme une {@code Observation} en {@code Timer}. En production Spring Boot le pose
 * lui-même ; sans lui ici, {@code stage(...)} exécuterait bien le travail mais ne chronométrerait
 * rien, et les tests de timer passeraient à côté de leur objet.
 */
public final class TestMetrics {

    private TestMetrics() {
    }

    /** Façade jetable, quand le test ne s'intéresse pas aux compteurs mais doit fournir la dépendance. */
    public static ParseRagMetrics metrics() {
        return metrics(new SimpleMeterRegistry());
    }

    /** Façade adossée à un registre fourni, pour asserter les compteurs. */
    public static ParseRagMetrics metrics(MeterRegistry registry) {
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(registry));
        return new ParseRagMetrics(registry, observations);
    }
}
