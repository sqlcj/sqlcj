package org.sqlcj.spring.boot.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.dao.TypeMismatchDataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator;
import org.springframework.jdbc.support.SQLExceptionTranslator;
import org.sqlcj.runtime.QueryExecutionException;
import org.sqlcj.runtime.QueryExecutor;
import org.sqlcj.runtime.RowMapper;
import org.sqlcj.runtime.UnmappableValueException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the translation rules of {@link ExceptionTranslatingQueryExecutor}
 * that need no database: an unmappable value, a driver failure the translator
 * maps to nothing, a failure that is none of its business, and the results and
 * arguments of a successful operation.
 *
 * <p>The delegate is a stub rather than a {@code JdbcQueryExecutor}, so each
 * case hands the decorator exactly the failure it is about. The translator is
 * Spring's own {@link SQLExceptionSubclassTranslator}, which is what a
 * {@code JdbcTemplate} without a {@code sql-error-codes.xml} chooses, so the
 * untranslatable failure below is one Spring really does not map. The
 * exceptions a SQLSTATE names are proven against the databases that report
 * them, in {@link SqlcjAutoConfigurationTest} and in the starter's PostgreSQL
 * scenarios.
 */
class ExceptionTranslatingQueryExecutorTest {

    private static final String REPOSITORY = "AuthorsRepository";

    private static final String QUERY = "GetAuthor";

    private static final String SQL = "SELECT name FROM authors WHERE id = ?";

    /** The message the runtime gives every failure of an operation. */
    private static final String MESSAGE = "Failed to execute query '" + QUERY + "' in " + REPOSITORY;

    private static final List<Object> PARAMETERS = Arrays.asList(1L, null);

    private static final String ROW = "Alice";

    private final SQLExceptionTranslator translator = new SQLExceptionSubclassTranslator();

    /**
     * A value the generated API cannot represent is Spring's type mismatch,
     * with the runtime's exception, which keeps the database type and the
     * value, as its cause and its message.
     */
    @Test
    void translatesAnUnmappableValueIntoATypeMismatch() {
        UnmappableValueException unmappable = new UnmappableValueException(
            MESSAGE,
            "article_status",
            "retired",
            new IllegalArgumentException("no enum constant")
        );

        TypeMismatchDataAccessException failure = assertThrows(
            TypeMismatchDataAccessException.class,
            () -> translating(unmappable).queryOne(REPOSITORY, QUERY, SQL, PARAMETERS, resultSet -> ROW)
        );

        assertEquals(TypeMismatchDataAccessException.class, failure.getClass());
        assertEquals(MESSAGE, failure.getMessage());
        assertSame(unmappable, failure.getCause());
    }

    /**
     * A driver failure the translator maps to nothing becomes the
     * {@link UncategorizedSQLException} a {@code JdbcTemplate} raises in that
     * case, over the driver's exception and the executed SQL, with a message
     * that still begins with the runtime's.
     */
    @Test
    void translatesAnUnmappedDriverFailureIntoAnUncategorizedFailure() {
        SQLException driverFailure = new SQLException("connection reset");

        UncategorizedSQLException failure = assertThrows(
            UncategorizedSQLException.class,
            () -> translating(new QueryExecutionException(MESSAGE, driverFailure))
                .execute(REPOSITORY, QUERY, SQL, PARAMETERS)
        );

        assertSame(driverFailure, failure.getSQLException());
        assertSame(driverFailure, failure.getCause());
        assertEquals(SQL, failure.getSql());
        assertTrue(
            failure.getMessage().startsWith(MESSAGE),
            () -> "the message should begin with the runtime's: " + failure.getMessage()
        );
    }

    /**
     * A failure that carries no {@code SQLException}, such as a row mapper's
     * own exception, reaches the caller as the same instance: Spring's own
     * {@code JdbcTemplate} lets a row mapper's exception through too.
     */
    @Test
    void rethrowsAFailureThatCarriesNoDriverException() {
        QueryExecutionException mapperFailure = new QueryExecutionException(
            MESSAGE,
            new IllegalStateException("the row mapper failed")
        );

        assertSame(
            mapperFailure,
            assertThrows(
                QueryExecutionException.class,
                () -> translating(mapperFailure)
                    .queryMany(REPOSITORY, QUERY, SQL, PARAMETERS, resultSet -> ROW)
            )
        );
    }

    /**
     * A successful operation returns what the delegate returned, and the
     * delegate was given the same arguments, the parameters in their order
     * included.
     */
    @Test
    void handsOnEveryArgumentAndResultOfASuccessfulOperation() {
        StubExecutor delegate = new StubExecutor(null);
        QueryExecutor executor = new ExceptionTranslatingQueryExecutor(delegate, translator);
        RowMapper<String> mapper = resultSet -> ROW;

        assertEquals(ROW, executor.queryOne(REPOSITORY, QUERY, SQL, PARAMETERS, mapper));
        assertEquals(Optional.of(ROW), executor.queryOptional(REPOSITORY, QUERY, SQL, PARAMETERS, mapper));
        assertEquals(List.of(ROW), executor.queryMany(REPOSITORY, QUERY, SQL, PARAMETERS, mapper));
        assertEquals(StubExecutor.COUNT, executor.execute(REPOSITORY, QUERY, SQL, PARAMETERS));
        assertArrayEquals(
            StubExecutor.BATCH_COUNTS,
            executor.executeBatch(REPOSITORY, QUERY, SQL, List.of(PARAMETERS))
        );

        assertEquals(
            List.of(
                new Call(REPOSITORY, QUERY, SQL, PARAMETERS),
                new Call(REPOSITORY, QUERY, SQL, PARAMETERS),
                new Call(REPOSITORY, QUERY, SQL, PARAMETERS),
                new Call(REPOSITORY, QUERY, SQL, PARAMETERS),
                new Call(REPOSITORY, QUERY, SQL, List.of(PARAMETERS))
            ),
            delegate.calls()
        );
    }

    /** The decorator over a delegate that raises {@code failure}. */
    private QueryExecutor translating(RuntimeException failure) {
        return new ExceptionTranslatingQueryExecutor(new StubExecutor(failure), translator);
    }

    /** What one operation was given, so a test can assert it unchanged. */
    private record Call(String repository, String query, String sql, List<?> parameters) {
    }

    /**
     * The delegate of these tests: it records what each operation was given
     * and then either raises the one failure the test is about or returns a
     * fixed result.
     */
    private static final class StubExecutor implements QueryExecutor {

        private static final int COUNT = 7;

        private static final int[] BATCH_COUNTS = { 1, 1 };

        /**
         * The result set a row mapper is handed. No mapper of these tests
         * reads it, because the stub stands in for the JDBC executor and not
         * for a database.
         */
        private static final ResultSet NO_RESULT_SET = null;

        private final RuntimeException failure;

        private final List<Call> calls = new ArrayList<>();

        StubExecutor(RuntimeException failure) {
            this.failure = failure;
        }

        List<Call> calls() {
            return List.copyOf(calls);
        }

        @Override
        public <T> T queryOne(
            String repository,
            String query,
            String sql,
            List<?> parameters,
            RowMapper<T> mapper
        ) {
            return record(repository, query, sql, parameters, mapper);
        }

        @Override
        public <T> Optional<T> queryOptional(
            String repository,
            String query,
            String sql,
            List<?> parameters,
            RowMapper<T> mapper
        ) {
            return Optional.of(record(repository, query, sql, parameters, mapper));
        }

        @Override
        public <T> List<T> queryMany(
            String repository,
            String query,
            String sql,
            List<?> parameters,
            RowMapper<T> mapper
        ) {
            return List.of(record(repository, query, sql, parameters, mapper));
        }

        @Override
        public int execute(String repository, String query, String sql, List<?> parameters) {
            calls.add(new Call(repository, query, sql, parameters));

            raise();

            return COUNT;
        }

        @Override
        public int[] executeBatch(
            String repository,
            String query,
            String sql,
            List<? extends List<?>> batch
        ) {
            calls.add(new Call(repository, query, sql, batch));

            raise();

            return BATCH_COUNTS.clone();
        }

        private <T> T record(
            String repository,
            String query,
            String sql,
            List<?> parameters,
            RowMapper<T> mapper
        ) {
            calls.add(new Call(repository, query, sql, parameters));

            raise();

            try {
                return mapper.map(NO_RESULT_SET);
            } catch (SQLException impossible) {
                throw new IllegalStateException("The row mapper of a test read the result set", impossible);
            }
        }

        private void raise() {
            if (failure != null) {
                throw failure;
            }
        }
    }
}
