package org.sqlcj.runtime;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the reads generated code makes for a component or single-column value
 * the schema sqlcj generated from states {@code NOT NULL}: each reads the value
 * the nullable read reads, and each fails on a SQL {@code NULL} with a message
 * naming the column, which the executor reports as a plain
 * {@link QueryExecutionException}. The reads run against an in-memory database,
 * because they are defined by the JDBC {@link ResultSet} API rather than by
 * sqlcj.
 */
class NotNullColumnTest {

    /** The generated repository the executor test reports. */
    private static final String REPOSITORY = "ReadingsRepository";

    private JdbcDataSource dataSource;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"
        );

        try (
            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement()
        ) {

            statement.execute("""
                CREATE TABLE readings (
                    id     BIGINT PRIMARY KEY,
                    "code" BIGINT,
                    "name" VARCHAR(255),
                    "tags" VARCHAR ARRAY
                )
                """);

            statement.execute("""
                INSERT INTO readings (id, "code", "name", "tags")
                VALUES (1, 7, 'alpha', ARRAY['a', NULL, 'b']),
                       (2, NULL, NULL, NULL)
                """);
        }
    }

    /** Every read returns the value the column holds. */
    @Test
    void shouldReadTheValueOfEachColumn() throws SQLException {
        withRow(1, resultSet -> {
            assertEquals(7L, NotNullColumn.getObject(resultSet, 1, Long.class));
            assertEquals("alpha", NotNullColumn.getString(resultSet, 2));
            assertEquals("ALPHA", NotNullColumn.getString(resultSet, 2, String::toUpperCase));

            assertEquals(
                Arrays.asList("a", null, "b"),
                NotNullColumn.getList(resultSet, 3, String.class)
            );

            assertEquals(
                Arrays.asList("A", null, "B"),
                NotNullColumn.getList(resultSet, 3, String.class, String::toUpperCase)
            );
        });
    }

    /**
     * A SQL {@code NULL} fails every read, with a message naming the column's
     * one-based position and its label, and without a SQLSTATE, because no
     * database reported the failure.
     */
    @Test
    void shouldFailOnASqlNull() throws SQLException {
        withRow(2, resultSet -> {
            assertFailure(
                () -> NotNullColumn.getObject(resultSet, 1, Long.class),
                1,
                "code"
            );
            assertFailure(() -> NotNullColumn.getString(resultSet, 2), 2, "name");
            assertFailure(
                () -> NotNullColumn.getString(resultSet, 2, String::toUpperCase),
                2,
                "name"
            );
            assertFailure(() -> NotNullColumn.getList(resultSet, 3, String.class), 3, "tags");
            assertFailure(
                () -> NotNullColumn.getList(resultSet, 3, String.class, String::toUpperCase),
                3,
                "tags"
            );
        });
    }

    /**
     * A converter that returns {@code null} for the text it was given, as a
     * generated enum's {@code fromLabel} does for a label of its own that is
     * {@code null}, fails the read the same way, because the component would
     * hold {@code null} either way.
     */
    @Test
    void shouldFailWhenTheConverterReturnsNull() throws SQLException {
        Function<String, String> toNull = text -> null;

        withRow(
            1,
            resultSet -> assertFailure(
                () -> NotNullColumn.getString(resultSet, 2, toNull),
                2,
                "name"
            )
        );
    }

    /**
     * A {@code NULL} element of an array column stays a {@code null} element,
     * and a converter that returns {@code null} for an element does too,
     * because only the column itself is {@code NOT NULL}.
     */
    @Test
    void shouldKeepNullElementsOfAnArrayColumn() throws SQLException {
        Function<String, String> toNull = text -> null;

        withRow(
            1,
            resultSet -> assertEquals(
                Arrays.asList(null, null, null),
                NotNullColumn.getList(resultSet, 3, String.class, toNull)
            )
        );
    }

    /**
     * A failed read inside a generated row mapper is a {@code SQLException}, so
     * the executor reports it as a plain {@link QueryExecutionException} naming
     * the query and the repository, with the read's own failure as its cause
     * and no SQLSTATE.
     */
    @Test
    void shouldReportTheFailureThroughTheExecutor() {
        QueryExecutor executor = new JdbcQueryExecutor(dataSource);

        QueryExecutionException failure = assertThrows(
            QueryExecutionException.class,
            () -> executor.queryOne(
                REPOSITORY,
                "GetReadingName",
                "SELECT \"name\" FROM readings WHERE id = ?",
                List.of(2L),
                resultSet -> NotNullColumn.getString(resultSet, 1)
            )
        );

        assertEquals(QueryExecutionException.class, failure.getClass());

        assertEquals(
            "Failed to execute query 'GetReadingName' in ReadingsRepository",
            failure.getMessage()
        );

        assertNull(failure.sqlState());

        SQLException cause = assertInstanceOf(SQLException.class, failure.getCause());

        assertEquals(
            "Column 1 (name) is NULL, but the schema sqlcj generated from declares it NOT NULL",
            cause.getMessage()
        );

        assertNull(cause.getSQLState());
    }

    /** Asserts that one read fails naming the column it read. */
    private void assertFailure(Read read, int position, String label) {
        SQLException failure = assertThrows(SQLException.class, read::read);

        assertEquals(
            "Column %d (%s) is NULL, but the schema sqlcj generated from declares it NOT NULL"
                .formatted(position, label),
            failure.getMessage()
        );

        assertNull(failure.getSQLState());
    }

    /**
     * Runs one assertion over the three readable columns of one row, at the
     * positions the generated reads would read them at.
     */
    private void withRow(long id, Row row) throws SQLException {
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(
                "SELECT \"code\", \"name\", \"tags\" FROM readings WHERE id = ?"
            )
        ) {

            statement.setLong(1, id);

            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());

                row.check(resultSet);
            }
        }
    }

    /** One assertion over the columns of one read row. */
    private interface Row {

        void check(ResultSet resultSet) throws SQLException;
    }

    /** One read of a column, which fails with a {@code SQLException}. */
    private interface Read {

        void read() throws SQLException;
    }
}
