package org.sqlcj.spring.boot.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.sqlcj.runtime.JdbcQueryExecutor;
import org.sqlcj.runtime.QueryExecutor;

import javax.sql.DataSource;

/**
 * Auto-configures the {@link QueryExecutor} that sqlcj generated repositories
 * execute their queries through.
 *
 * <p>The executor is a {@link JdbcQueryExecutor} over a
 * {@link TransactionAwareDataSourceProxy} of the application's single
 * {@link DataSource} candidate, so every generated operation takes part in the
 * Spring-managed transaction bound to that {@code DataSource} when there is
 * one, and runs on its own connection when there is none. The proxy is not
 * registered as a bean, so the application keeps exactly one {@code DataSource}
 * candidate.
 *
 * <p>The condition sits on the bean method alone, so the auto-configuration
 * itself always applies. It backs off when the application defines its own
 * {@code QueryExecutor}, and when no single {@code DataSource} candidate
 * qualifies the context simply has no sqlcj executor.
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
public final class SqlcjAutoConfiguration {

    /**
     * Returns the executor generated repositories run on.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnMissingBean(QueryExecutor.class)
    QueryExecutor sqlcjQueryExecutor(DataSource dataSource) {
        return new JdbcQueryExecutor(new TransactionAwareDataSourceProxy(dataSource));
    }
}
