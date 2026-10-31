package org.sqlcj.spring.boot.postgres.jpa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.sqlcj.spring.boot.postgres.PostgresApplicationScenarios;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Runs every scenario on {@link JpaApplication}, an application on Spring
 * Boot's JPA support, and adds the two the entity manager makes possible: an
 * entity's write and a generated write commit together and roll back together.
 *
 * <p>The application is started over the whole test classpath, so Spring Boot
 * configures its entity manager factory and its {@code JpaTransactionManager}.
 */
@EnabledIf("dockerAvailable")
class JpaApplicationPostgresTest extends PostgresApplicationScenarios {

    @Override
    protected Class<?> applicationClass() {
        return JpaApplication.class;
    }

    @Override
    protected ClassLoader parentClassLoader() {
        return JpaApplicationPostgresTest.class.getClassLoader();
    }

    @Override
    protected Class<? extends PlatformTransactionManager> transactionManagerType() {
        return JpaTransactionManager.class;
    }

    /**
     * A persisted entity and a generated write made in one transaction share
     * that transaction's connection: another connection sees neither while it
     * is open, and both are stored once it commits.
     */
    @Test
    void commitsAnEntityWriteAndAGeneratedWriteTogether() {
        EntityManager entityManager = entityManager();

        run(() -> {
            entityManager.persist(new AuthorEntity(1L, "Persisted"));

            assertEquals(1, insertAuthor(authors(), 2L, "Generated"));

            assertEquals(List.of(), storedAuthorNames());
        });

        assertEquals(List.of("Persisted", "Generated"), authorNames(authors()));
    }

    /**
     * The flushed entity write and the generated write are rolled back
     * together when the transactional method throws, so neither row remains.
     */
    @Test
    void rollsBackAnEntityWriteAndAGeneratedWriteTogether() {
        EntityManager entityManager = entityManager();

        CallbackFailure failure = new CallbackFailure();

        assertSame(
            failure,
            assertThrows(
                CallbackFailure.class,
                () -> run(() -> {
                    entityManager.persist(new AuthorEntity(1L, "Persisted"));
                    entityManager.flush();

                    assertEquals(1, insertAuthor(authors(), 2L, "Generated"));

                    throw failure;
                })
            )
        );

        assertEquals(List.of(), authorNames(authors()));
    }

    /** The shared entity manager {@code @PersistenceContext} injects. */
    private EntityManager entityManager() {
        return SharedEntityManagerCreator.createSharedEntityManager(
            context().getBean(EntityManagerFactory.class)
        );
    }
}
