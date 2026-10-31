package org.sqlcj.spring.boot.postgres;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.sqlcj.runtime.JdbcQueryExecutor;
import org.sqlcj.runtime.QueryExecutor;
import org.sqlcj.runtime.RowMapper;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An application that defines its own {@link QueryExecutor}, which the starter
 * backs off from and registers the generated repositories over.
 *
 * <p>The executor records the name of every query it is given and delegates to
 * an executor built the way the starter builds its own, so a test can assert
 * both that the repositories ran their queries through the application's bean
 * and that those queries reached the database.
 *
 * <p>It is a source of the application's context rather than a class of the
 * application's own package, because component scanning would otherwise apply
 * it to the scenarios that must run on the starter's executor.
 */
@Configuration(proxyBeanMethods = false)
public class ApplicationExecutorConfiguration {

    @Bean
    QueryExecutor applicationQueryExecutor(DataSource dataSource) {
        return new RecordingQueryExecutor(
            new JdbcQueryExecutor(new TransactionAwareDataSourceProxy(dataSource))
        );
    }

    /** The application's executor: a recording decorator of a delegate. */
    public static final class RecordingQueryExecutor implements QueryExecutor {

        private final QueryExecutor delegate;

        private final List<String> queries = new CopyOnWriteArrayList<>();

        RecordingQueryExecutor(QueryExecutor delegate) {
            this.delegate = delegate;
        }

        /** The names of the queries run through this executor, in order. */
        public List<String> queries() {
            return List.copyOf(queries);
        }

        @Override
        public <T> T queryOne(
            String repository,
            String query,
            String sql,
            List<?> parameters,
            RowMapper<T> mapper
        ) {
            queries.add(query);

            return delegate.queryOne(repository, query, sql, parameters, mapper);
        }

        @Override
        public <T> Optional<T> queryOptional(
            String repository,
            String query,
            String sql,
            List<?> parameters,
            RowMapper<T> mapper
        ) {
            queries.add(query);

            return delegate.queryOptional(repository, query, sql, parameters, mapper);
        }

        @Override
        public <T> List<T> queryMany(
            String repository,
            String query,
            String sql,
            List<?> parameters,
            RowMapper<T> mapper
        ) {
            queries.add(query);

            return delegate.queryMany(repository, query, sql, parameters, mapper);
        }

        @Override
        public int execute(String repository, String query, String sql, List<?> parameters) {
            queries.add(query);

            return delegate.execute(repository, query, sql, parameters);
        }

        @Override
        public int[] executeBatch(
            String repository,
            String query,
            String sql,
            List<? extends List<?>> batch
        ) {
            queries.add(query);

            return delegate.executeBatch(repository, query, sql, batch);
        }
    }
}
