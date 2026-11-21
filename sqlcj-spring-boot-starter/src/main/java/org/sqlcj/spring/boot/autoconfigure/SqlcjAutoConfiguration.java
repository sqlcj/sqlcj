package org.sqlcj.spring.boot.autoconfigure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.jdbc.support.SQLExceptionTranslator;
import org.sqlcj.runtime.JdbcQueryExecutor;
import org.sqlcj.runtime.QueryExecutor;

import javax.sql.DataSource;

/**
 * Auto-configures the {@link QueryExecutor} that sqlcj generated repositories
 * execute their queries through, and registers those repositories as beans.
 *
 * <p>The executor is a {@link JdbcQueryExecutor} over a
 * {@link TransactionAwareDataSourceProxy} of the application's single
 * {@link DataSource} candidate, so every generated operation takes part in the
 * Spring-managed transaction bound to that {@code DataSource} when there is
 * one, and runs on its own connection when there is none. The proxy is not
 * registered as a bean, so the application keeps exactly one {@code DataSource}
 * candidate.
 *
 * <p>That executor is wrapped in an {@link ExceptionTranslatingQueryExecutor},
 * so a generated operation fails with the {@code DataAccessException} a
 * {@link JdbcTemplate} over the same {@code DataSource} raises for the same
 * database error. It translates with the application's one
 * {@link SQLExceptionTranslator} bean when there is exactly one, which is also
 * the translator Spring Boot's own {@code JdbcTemplate} takes, and otherwise
 * with the translator a {@code JdbcTemplate} over that {@code DataSource}
 * chooses, so one error raises one class from both.
 *
 * <p>The condition sits on the bean method alone, so the auto-configuration
 * itself always applies. It backs off when the application defines its own
 * {@code QueryExecutor}, which then translates nothing unless the application
 * wraps it in an {@code ExceptionTranslatingQueryExecutor} itself, and when no
 * single {@code DataSource} candidate qualifies the context simply has no
 * sqlcj executor.
 *
 * <p>Beside the executor, the {@link GeneratedRepositoryRegistrar} registers
 * every generated repository in the application's auto-configuration packages
 * as a singleton built from the application's one {@code QueryExecutor} bean
 * definition, whether that is the executor above or the application's own, so
 * a repository is injected like any other bean.
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
public final class SqlcjAutoConfiguration {

    /**
     * Returns the executor generated repositories run on.
     *
     * <p>The fallback translator is the one a {@link JdbcTemplate} over the
     * plain {@code DataSource} chooses, because the choice itself — which
     * reads whether a {@code sql-error-codes.xml} stands at the root of the
     * class path — is Spring's own and not reachable from outside it.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnMissingBean(QueryExecutor.class)
    QueryExecutor sqlcjQueryExecutor(
        DataSource dataSource,
        ObjectProvider<SQLExceptionTranslator> translators
    ) {
        return new ExceptionTranslatingQueryExecutor(
            new JdbcQueryExecutor(new TransactionAwareDataSourceProxy(dataSource)),
            translators.getIfUnique(() -> new JdbcTemplate(dataSource).getExceptionTranslator())
        );
    }

    /**
     * Returns the registry post-processor that registers the generated
     * repositories. It is static so that registering them never instantiates
     * this auto-configuration or the executor.
     */
    @Bean
    static GeneratedRepositoryRegistrar sqlcjGeneratedRepositoryRegistrar() {
        return new GeneratedRepositoryRegistrar();
    }
}
