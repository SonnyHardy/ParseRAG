package com.sonny.parserag.config;

import com.sonny.parserag.entity.ApiKey;
import com.sonny.parserag.entity.Plan;
import com.sonny.parserag.repository.ApiKeyRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Amorçage de la clé d'administration (issue #56).
 * <p>
 * Le comportement le plus important est celui de l'absence : sans {@code ADMIN_KEY_HASH}, il ne
 * doit rien se créer. C'est ce qui remplace la clé semée par V1, dont le secret « 123 » était
 * publié dans le dépôt.
 */
class AdminKeyBootstrapTest {

    private static final String HASH = "c66e219a7174453210477d9e9f9eb0dda0fbdafa69196f79390b95418ee18fd8";

    private final ApiKeyRepository repository = mock(ApiKeyRepository.class);

    private AdminKeyBootstrap bootstrap(String hash) {
        AppProperties props = new AppProperties();
        props.getSecurity().setAdminKeyHash(hash);
        return new AdminKeyBootstrap(props, repository);
    }

    @Test
    void withoutAHashNothingIsCreated() {
        bootstrap("").run(null);

        verify(repository, never()).save(any());
        verify(repository, never()).findByKeyHash(any());
    }

    @Test
    void createsTheAdminKeyFromTheConfiguredHash() {
        when(repository.findByKeyHash(HASH)).thenReturn(Optional.empty());

        bootstrap(HASH).run(null);

        ArgumentCaptor<ApiKey> saved = ArgumentCaptor.forClass(ApiKey.class);
        verify(repository).save(saved.capture());

        assertAll(
                () -> assertEquals(HASH, saved.getValue().getKeyHash()),
                () -> assertTrue(saved.getValue().isAdmin()),
                () -> assertTrue(saved.getValue().isActive()),
                // Moindre privilège : la clé sert au diagnostic, pas au volume. Une fuite ne doit
                // pas ouvrir le plan le plus large.
                () -> assertEquals(Plan.FREE, saved.getValue().getPlan()));
    }

    @Test
    void isIdempotentWhenTheKeyIsAlreadyCorrect() {
        ApiKey existing = new ApiKey();
        existing.setKeyHash(HASH);
        existing.setAdmin(true);
        existing.setActive(true);
        when(repository.findByKeyHash(HASH)).thenReturn(Optional.of(existing));

        bootstrap(HASH).run(null);

        verify(repository, never()).save(any());
    }

    @Test
    void restoresAKeyThatLostItsAdminFlagOrWasDeactivated() {
        // Base restaurée, manipulation manuelle : la ligne existe mais n'ouvre plus rien. Laisser
        // l'API sans administrateur au motif que la ligne est là serait le pire des deux mondes.
        ApiKey degraded = new ApiKey();
        degraded.setKeyHash(HASH);
        degraded.setAdmin(false);
        degraded.setActive(false);
        when(repository.findByKeyHash(HASH)).thenReturn(Optional.of(degraded));

        bootstrap(HASH).run(null);

        verify(repository).save(degraded);
        assertAll(
                () -> assertTrue(degraded.isAdmin()),
                () -> assertTrue(degraded.isActive()));
    }

    @Test
    void aConcurrentStartupIsNotAFailure() {
        // Deux instances démarrent ensemble : la contrainte d'unicité tranche, et la violation
        // signifie que l'autre a réussi — ce n'est pas une raison de refuser de démarrer.
        when(repository.findByKeyHash(HASH)).thenReturn(Optional.empty());
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertDoesNotThrow(() -> bootstrap(HASH).run(null));
    }
}
