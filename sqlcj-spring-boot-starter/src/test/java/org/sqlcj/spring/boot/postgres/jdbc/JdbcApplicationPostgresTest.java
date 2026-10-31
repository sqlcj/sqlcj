package org.sqlcj.spring.boot.postgres.jdbc;

import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.sqlcj.spring.boot.postgres.PostgresApplicationScenarios;

/**
 * Runs every scenario on {@link JdbcApplication}, an application on Spring
 * Boot's JDBC support alone.
 *
 * <p>Spring Boot's JPA support is on the test classpath for the JPA
 * application, so this application is started over a class loader that hides
 * the JPA packages from it. It therefore needs no exclusion and no property of
 * its own, and Spring Boot configures it the way it configures an application
 * that depends on the starter and the driver alone.
 */
@EnabledIf("dockerAvailable")
class JdbcApplicationPostgresTest extends PostgresApplicationScenarios {

    @Override
    protected Class<?> applicationClass() {
        return JdbcApplication.class;
    }

    @Override
    protected ClassLoader parentClassLoader() {
        return new FilteredClassLoader(
            "jakarta.persistence",
            "org.hibernate",
            "org.springframework.orm.jpa",
            "org.springframework.data.jpa"
        );
    }

    @Override
    protected Class<? extends PlatformTransactionManager> transactionManagerType() {
        return JdbcTransactionManager.class;
    }
}
