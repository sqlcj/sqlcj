package org.sqlcj.spring.boot.autoconfigure;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.dao.TypeMismatchDataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SQLExceptionTranslator;
import org.sqlcj.runtime.QueryCardinalityException;
import org.sqlcj.runtime.QueryExecutionException;
import org.sqlcj.runtime.QueryExecutor;
import org.sqlcj.runtime.RowMapper;
import org.sqlcj.runtime.UnmappableValueException;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link QueryExecutor} that runs every operation on another executor and
 * raises Spring's {@link DataAccessException} for the failures
 * {@link JdbcTemplate} raises one for, so a generated repository fails the way
 * the rest of a Spring application's data layer does.
 *
 * <p>The starter's own executor is one of these around the executor it builds
 * over the application's {@code DataSource}. An application that defines its
 * own {@code QueryExecutor} bean gets no translation, and wraps its executor
 * in one of these itself to get it, with the
 * {@link SQLExceptionTranslator} of its own {@code JdbcTemplate}.
 *
 * <p>Parameters, row order, and results are handed on unchanged. Only a
 * failure is translated, by these rules:
 *
 * <ul>
 * <li>A {@link QueryExecutionException} whose cause is the driver's
 * {@link SQLException} raises exactly what the translator builds from that
 * {@code SQLException}, given the sqlcj exception's message as its task and
 * the executed SQL, or an {@link UncategorizedSQLException} when the
 * translator builds none, as {@code JdbcTemplate} does. The driver's exception
 * is the cause, and the message begins with the sqlcj message, which names the
 * query and its repository.</li>
 * <li>A {@link QueryCardinalityException} of {@link #queryOne} raises an
 * {@link EmptyResultDataAccessException} when no row was mapped and an
 * {@link IncorrectResultSizeDataAccessException} when one was, and one of
 * {@link #queryOptional} always the latter, as
 * {@link JdbcTemplate#queryForObject} raises them; each expects one row and
 * carries the sqlcj message and the {@code QueryCardinalityException} as its
 * cause.</li>
 * <li>An {@link UnmappableValueException} raises a
 * {@link TypeMismatchDataAccessException} with the sqlcj message and that
 * exception as its cause, which is the type Spring's own
 * {@code SingleColumnRowMapper} raises for a value it cannot convert.</li>
 * <li>Every other failure, a {@code QueryExecutionException} carrying a row
 * mapper's own exception included, is rethrown as the same instance, as
 * {@code JdbcTemplate} lets a row mapper's own exception through.</li>
 * </ul>
 */
@NullMarked
public final class ExceptionTranslatingQueryExecutor implements QueryExecutor {

    private final QueryExecutor delegate;

    private final SQLExceptionTranslator translator;

    /**
     * Wraps {@code delegate} and translates its failures with
     * {@code translator}.
     */
    public ExceptionTranslatingQueryExecutor(QueryExecutor delegate, SQLExceptionTranslator translator) {
        this.delegate = delegate;
        this.translator = translator;
    }

    @Override
    public <T> T queryOne(
        String repository,
        String query,
        String sql,
        List<? extends @Nullable Object> parameters,
        RowMapper<T> mapper
    ) {
        /*
         * The delegate raises one cardinality type for no row and for several,
         * and maps the first row before it looks for a second, so the mapper it
         * is handed reports which of the two happened.
         */
        AtomicBoolean mapped = new AtomicBoolean();

        try {
            return delegate.queryOne(repository, query, sql, parameters, resultSet -> {
                T row = mapper.map(resultSet);

                mapped.set(true);

                return row;
            });
        } catch (QueryCardinalityException failure) {
            throw mapped.get()
                ? new IncorrectResultSizeDataAccessException(failure.getMessage(), 1, failure)
                : new EmptyResultDataAccessException(failure.getMessage(), 1, failure);
        } catch (QueryExecutionException failure) {
            throw translated(failure, sql);
        }
    }

    @Override
    public <T> Optional<T> queryOptional(
        String repository,
        String query,
        String sql,
        List<? extends @Nullable Object> parameters,
        RowMapper<T> mapper
    ) {
        try {
            return delegate.queryOptional(repository, query, sql, parameters, mapper);
        } catch (QueryCardinalityException failure) {
            throw new IncorrectResultSizeDataAccessException(failure.getMessage(), 1, failure);
        } catch (QueryExecutionException failure) {
            throw translated(failure, sql);
        }
    }

    @Override
    public <T> List<T> queryMany(
        String repository,
        String query,
        String sql,
        List<? extends @Nullable Object> parameters,
        RowMapper<T> mapper
    ) {
        try {
            return delegate.queryMany(repository, query, sql, parameters, mapper);
        } catch (QueryExecutionException failure) {
            throw translated(failure, sql);
        }
    }

    @Override
    public int execute(
        String repository,
        String query,
        String sql,
        List<? extends @Nullable Object> parameters
    ) {
        try {
            return delegate.execute(repository, query, sql, parameters);
        } catch (QueryExecutionException failure) {
            throw translated(failure, sql);
        }
    }

    @Override
    public int[] executeBatch(
        String repository,
        String query,
        String sql,
        List<? extends List<? extends @Nullable Object>> batch
    ) {
        try {
            return delegate.executeBatch(repository, query, sql, batch);
        } catch (QueryExecutionException failure) {
            throw translated(failure, sql);
        }
    }

    /**
     * The exception {@code failure} reaches the caller as: Spring's for a
     * driver failure and for an unmappable value, and {@code failure} itself
     * for everything else.
     */
    private RuntimeException translated(QueryExecutionException failure, String sql) {
        if (failure instanceof UnmappableValueException) {
            return new TypeMismatchDataAccessException(failure.getMessage(), failure);
        }

        if (failure.getCause() instanceof SQLException cause) {
            DataAccessException translated = translator.translate(failure.getMessage(), sql, cause);

            return translated != null
                ? translated
                : new UncategorizedSQLException(failure.getMessage(), sql, cause);
        }

        return failure;
    }
}
