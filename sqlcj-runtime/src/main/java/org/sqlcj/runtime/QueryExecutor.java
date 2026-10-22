package org.sqlcj.runtime;

import java.util.List;
import java.util.Optional;

/**
 * Executes the SQL of one generated repository method.
 *
 * <p>Every operation is given the generated repository's class name and the
 * name of the query it was generated from, so an execution or cardinality
 * failure names the query the application called.
 *
 * <p>The parameters of an operation are the query's arguments in the order
 * their placeholders appear in its SQL, and any of them may be {@code null}. An
 * argument may also be an {@link UntypedText}, which carries text an
 * implementation binds without a declared SQL type, or a {@link SqlArray},
 * which carries the elements an implementation binds as a SQL array of one
 * element type.
 */
public interface QueryExecutor {

    /**
     * Executes a query that must return exactly one row and returns that row.
     *
     * @throws QueryCardinalityException if no row or more than one row is
     *                                   returned
     */
    <T> T queryOne(
        String repository,
        String query,
        String sql,
        List<?> parameters,
        RowMapper<T> mapper
    );

    /**
     * Executes a query that may return at most one row and returns that row if
     * there is one, or an empty {@link Optional} if there is none.
     *
     * @throws QueryCardinalityException if more than one row is returned
     */
    <T> Optional<T> queryOptional(
        String repository,
        String query,
        String sql,
        List<?> parameters,
        RowMapper<T> mapper
    );

    /**
     * Executes a query and returns every row it produced, in the order the
     * database produced it, or an empty list if it produced none.
     */
    <T> List<T> queryMany(
        String repository,
        String query,
        String sql,
        List<?> parameters,
        RowMapper<T> mapper
    );

    /**
     * Executes a write statement and returns its affected-row count.
     */
    int execute(String repository, String query, String sql, List<?> parameters);

    /**
     * Executes {@code sql} once per element of {@code batch}, in the order of
     * the batch, as one JDBC batch. Each element holds the arguments of one
     * execution, bound by the same rules as the arguments of every other
     * operation.
     *
     * <p>The returned array holds the count the driver reported for each
     * element, in the order of the batch and unchanged, so it may hold
     * {@link java.sql.Statement#SUCCESS_NO_INFO} for a statement whose count
     * the driver does not report. An empty batch executes nothing and returns
     * an empty array.
     *
     * @throws NullPointerException if {@code batch} is {@code null}
     */
    int[] executeBatch(
        String repository,
        String query,
        String sql,
        List<? extends List<?>> batch
    );
}
