package org.sqlcj.runtime;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcQueryExecutorTest {

    /** The generated repository every operation of these tests reports. */
    private static final String REPOSITORY = "UsersRepository";

    private JdbcDataSource dataSource;
    private JdbcQueryExecutor executor;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"
        );

        executor = new JdbcQueryExecutor(dataSource);

        try (
            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement()
        ) {

            statement.execute("""
                CREATE TABLE users (
                    id BIGINT PRIMARY KEY,
                    name VARCHAR(255),
                    active BOOLEAN,
                    birth_date DATE,
                    created_at TIMESTAMP,
                    balance DECIMAL(10, 2)
                )
                """);

            statement.execute("""
                INSERT INTO users
                    (id, name, active, birth_date, created_at, balance)
                VALUES
                    (1, 'Alice', TRUE, '1990-01-15',
                     '2026-01-01 10:00:00', 100.50),
                    (2, 'Bob', FALSE, '1985-05-20',
                     '2026-02-01 11:30:00', 200.75),
                    (3, 'Charlie', TRUE, '1995-10-10',
                     '2026-03-01 12:45:00', 300.25)
                """);
        }
    }

    @Test
    void shouldBindSingleParameter() {
        Long result = executor.queryOne(
            REPOSITORY,
            "GetUser",
            "SELECT id FROM users WHERE id = ?",
            List.of(1L),
            resultSet -> resultSet.getLong("id")
        );

        assertEquals(1L, result);
    }

    @Test
    void shouldBindParametersInOrder() {
        String result = executor.queryOne(
            REPOSITORY,
            "GetUser",
            """
                SELECT name
                FROM users
                WHERE id = ?
                  AND active = ?
                """,
            List.of(2L, false),
            resultSet -> resultSet.getString("name")
        );

        assertEquals("Bob", result);
    }

    @Test
    void shouldBindSupportedJavaTypes() {
        String result = executor.queryOne(
            REPOSITORY,
            "GetUser",
            """
                SELECT name
                FROM users
                WHERE birth_date = ?
                  AND created_at = ?
                  AND balance = ?
                """,
            List.of(
                LocalDate.of(1990, 1, 15),
                LocalDateTime.of(2026, 1, 1, 10, 0),
                new BigDecimal("100.50")
            ),
            resultSet -> resultSet.getString("name")
        );

        assertEquals("Alice", result);
    }

    /**
     * An {@link UntypedText} argument is bound as its text without a declared
     * SQL type, so the database types it from the context of its placeholder,
     * while every other argument keeps its untouched {@code setObject} binding.
     */
    @Test
    void shouldBindUntypedTextWithoutADeclaredSqlType() throws SQLException {
        BindingRecorder recorder = new BindingRecorder();

        try (Connection connection = dataSource.getConnection()) {
            JdbcQueryExecutor connectionExecutor = new JdbcQueryExecutor(
                recorder.track(connection)
            );

            assertEquals(
                1,
                connectionExecutor.execute(
                    REPOSITORY,
                    "UpdateUserName",
                    "UPDATE users SET name = ? WHERE id = ?",
                    Arrays.asList(new UntypedText("Alicia"), 1L)
                )
            );
        }

        assertEquals(
            List.of(
                new BoundParameter(1, "Alicia", Types.OTHER),
                new BoundParameter(2, 1L, null)
            ),
            recorder.bindings
        );
    }

    /** A null {@link UntypedText} value is bound without a declared SQL type too. */
    @Test
    void shouldBindNullUntypedTextWithoutADeclaredSqlType() throws SQLException {
        BindingRecorder recorder = new BindingRecorder();

        try (Connection connection = dataSource.getConnection()) {
            JdbcQueryExecutor connectionExecutor = new JdbcQueryExecutor(
                recorder.track(connection)
            );

            assertEquals(
                1,
                connectionExecutor.execute(
                    REPOSITORY,
                    "UpdateUserName",
                    "UPDATE users SET name = ? WHERE id = ?",
                    Arrays.asList(new UntypedText(null), 1L)
                )
            );
        }

        assertEquals(
            List.of(
                new BoundParameter(1, null, Types.OTHER),
                new BoundParameter(2, 1L, null)
            ),
            recorder.bindings
        );

        assertNull(
            executor.queryOne(
                REPOSITORY,
                "GetUser",
                "SELECT name FROM users WHERE id = ?",
                List.of(1L),
                resultSet -> resultSet.getString("name")
            )
        );
    }

    /**
     * A {@link SqlArray} argument is bound as a server array of its element
     * type, built on the connection the operation runs on, and its null
     * elements are carried into that array. Every other argument keeps its
     * untouched {@code setObject} binding.
     */
    @Test
    void shouldBindSqlArrayAsAServerArray() throws SQLException {
        BindingRecorder recorder = new BindingRecorder();

        try (Connection connection = dataSource.getConnection()) {
            createTaggedTable(connection);

            JdbcQueryExecutor connectionExecutor = new JdbcQueryExecutor(
                recorder.track(connection)
            );

            assertEquals(
                1,
                connectionExecutor.execute(
                    REPOSITORY,
                    "InsertTagged",
                    "INSERT INTO tagged (id, tags) VALUES (?, ?)",
                    Arrays.asList(1L, new SqlArray("varchar", Arrays.asList("a", null, "b")))
                )
            );
        }

        assertEquals(
            List.of(new BoundParameter(1, 1L, null)),
            recorder.bindings
        );

        assertEquals(
            List.of(new CreatedArray("varchar", Arrays.asList("a", null, "b"))),
            recorder.createdArrays
        );

        assertEquals(List.of(2), recorder.arrayPositions);
        assertTrue(recorder.nullBindings.isEmpty());
    }

    /**
     * A {@link SqlArray} whose elements are {@code null} is bound as a SQL
     * {@code NULL} array, so no array value is built at all.
     */
    @Test
    void shouldBindNullSqlArrayElementsAsANullArray() throws SQLException {
        BindingRecorder recorder = new BindingRecorder();

        try (Connection connection = dataSource.getConnection()) {
            createTaggedTable(connection);

            JdbcQueryExecutor connectionExecutor = new JdbcQueryExecutor(
                recorder.track(connection)
            );

            assertEquals(
                1,
                connectionExecutor.execute(
                    REPOSITORY,
                    "InsertTagged",
                    "INSERT INTO tagged (id, tags) VALUES (?, ?)",
                    Arrays.asList(1L, new SqlArray("varchar", null))
                )
            );
        }

        assertEquals(
            List.of(new BoundParameter(2, null, Types.ARRAY)),
            recorder.nullBindings
        );

        assertTrue(recorder.createdArrays.isEmpty());
        assertTrue(recorder.arrayPositions.isEmpty());
    }

    private void createTaggedTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE tagged (
                    id   BIGINT PRIMARY KEY,
                    tags VARCHAR ARRAY
                )
                """);
        }
    }

    @Test
    void shouldReturnAllRowsForQueryMany() {
        List<User> results = executor.queryMany(
            REPOSITORY,
            "ListUsers",
            """
                SELECT id, name
                FROM users
                WHERE active = ?
                ORDER BY id
                """,
            List.of(true),
            resultSet -> new User(
                resultSet.getLong("id"),
                resultSet.getString("name")
            )
        );

        assertEquals(
            List.of(
                new User(1L, "Alice"),
                new User(3L, "Charlie")
            ),
            results
        );
    }

    @Test
    void shouldReturnEmptyListWhenQueryManyFindsNoRows() {
        List<User> results = executor.queryMany(
            REPOSITORY,
            "ListUsers",
            """
                SELECT id, name
                FROM users
                WHERE id = ?
                """,
            List.of(999L),
            resultSet -> new User(
                resultSet.getLong("id"),
                resultSet.getString("name")
            )
        );

        assertTrue(results.isEmpty());
    }

    @Test
    void shouldReturnTheRowWhenQueryOneFindsExactlyOneRow() {
        User result = executor.queryOne(
            REPOSITORY,
            "GetUser",
            "SELECT id, name FROM users WHERE id = ?",
            List.of(1L),
            userMapper()
        );

        assertEquals(new User(1L, "Alice"), result);
    }

    @Test
    void shouldFailWhenQueryOneFindsNoRow() {
        QueryCardinalityException exception = assertThrows(
            QueryCardinalityException.class,
            () -> executor.queryOne(
                REPOSITORY,
                "GetUser",
                "SELECT id, name FROM users WHERE id = ?",
                List.of(999L),
                userMapper()
            )
        );

        assertEquals(
            "Query 'GetUser' in UsersRepository returned no row; expected exactly one",
            exception.getMessage()
        );

        assertNull(exception.getCause());
        assertInstanceOf(QueryExecutionException.class, exception);
    }

    @Test
    void shouldFailWhenQueryOneFindsMoreThanOneRow() {
        QueryCardinalityException exception = assertThrows(
            QueryCardinalityException.class,
            () -> executor.queryOne(
                REPOSITORY,
                "GetUser",
                "SELECT id, name FROM users WHERE active = ? ORDER BY id",
                List.of(true),
                userMapper()
            )
        );

        assertEquals(
            "Query 'GetUser' in UsersRepository returned more than one row; expected exactly one",
            exception.getMessage()
        );

        assertNull(exception.getCause());
    }

    @Test
    void shouldReturnTheRowWhenQueryOptionalFindsOneRow() {
        Optional<User> result = executor.queryOptional(
            REPOSITORY,
            "FindUser",
            "SELECT id, name FROM users WHERE id = ?",
            List.of(2L),
            userMapper()
        );

        assertEquals(Optional.of(new User(2L, "Bob")), result);
    }

    @Test
    void shouldReturnEmptyOptionalWhenQueryOptionalFindsNoRow() {
        Optional<User> result = executor.queryOptional(
            REPOSITORY,
            "FindUser",
            "SELECT id, name FROM users WHERE id = ?",
            List.of(999L),
            userMapper()
        );

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldFailWhenQueryOptionalFindsMoreThanOneRow() {
        QueryCardinalityException exception = assertThrows(
            QueryCardinalityException.class,
            () -> executor.queryOptional(
                REPOSITORY,
                "FindUser",
                "SELECT id, name FROM users WHERE active = ? ORDER BY id",
                List.of(true),
                userMapper()
            )
        );

        assertEquals(
            "Query 'FindUser' in UsersRepository returned more than one row; expected at most one",
            exception.getMessage()
        );

        assertNull(exception.getCause());
    }

    @Test
    void shouldReturnAffectedRowCountForExecute() {
        int affected = executor.execute(
            REPOSITORY,
            "UpdateUserName",
            """
                UPDATE users
                SET name = ?
                WHERE id = ?
                """,
            List.of("Alicia", 1L)
        );

        assertEquals(1, affected);

        String name = executor.queryOne(
            REPOSITORY,
            "GetUserName",
            "SELECT name FROM users WHERE id = ?",
            List.of(1L),
            resultSet -> resultSet.getString("name")
        );

        assertEquals("Alicia", name);
    }

    @Test
    void shouldWrapSqlExceptionForExecute() {
        QueryExecutionException exception = assertThrows(
            QueryExecutionException.class,
            () -> executor.execute(
                REPOSITORY,
                "DeleteUsers",
                "DELETE FROM missing_table",
                List.of()
            )
        );

        assertEquals(
            "Failed to execute query 'DeleteUsers' in UsersRepository",
            exception.getMessage()
        );

        assertInstanceOf(SQLException.class, exception.getCause());
    }

    @Test
    void shouldWrapSqlException() {
        QueryExecutionException exception = assertThrows(
            QueryExecutionException.class,
            () -> executor.queryOne(
                REPOSITORY,
                "GetUser",
                "SELECT * FROM missing_table",
                List.of(),
                resultSet -> resultSet.getLong("id")
            )
        );

        assertEquals(
            "Failed to execute query 'GetUser' in UsersRepository",
            exception.getMessage()
        );

        assertInstanceOf(SQLException.class, exception.getCause());
    }

    /**
     * A batch over a DataSource executes its SQL once per argument list, in
     * batch order, reports the count of each one, and is committed.
     */
    @Test
    void shouldReturnPerRowCountsForABatchInsert() {
        int[] counts = executor.executeBatch(
            REPOSITORY,
            "InsertUsers",
            "INSERT INTO users (id, name, active) VALUES (?, ?, ?)",
            List.of(
                List.of(4L, "Dora", true),
                List.of(5L, "Eve", false)
            )
        );

        assertArrayEquals(new int[] { 1, 1 }, counts);

        assertEquals(
            List.of(
                new User(4L, "Dora"),
                new User(5L, "Eve")
            ),
            executor.queryMany(
                REPOSITORY,
                "ListUsers",
                "SELECT id, name FROM users WHERE id > ? ORDER BY id",
                List.of(3L),
                userMapper()
            )
        );
    }

    /** A row that affects nothing reports its own count of zero. */
    @Test
    void shouldReturnPerRowCountsForABatchUpdate() {
        int[] counts = executor.executeBatch(
            REPOSITORY,
            "RenameUser",
            "UPDATE users SET name = ? WHERE id = ?",
            List.of(
                List.of("Alicia", 1L),
                List.of("Nobody", 999L),
                List.of("Bobby", 2L)
            )
        );

        assertArrayEquals(new int[] { 1, 0, 1 }, counts);

        assertEquals(
            List.of(
                new User(1L, "Alicia"),
                new User(2L, "Bobby"),
                new User(3L, "Charlie")
            ),
            executor.queryMany(
                REPOSITORY,
                "ListUsers",
                "SELECT id, name FROM users ORDER BY id",
                List.of(),
                userMapper()
            )
        );
    }

    @Test
    void shouldReturnPerRowCountsForABatchDelete() {
        int[] counts = executor.executeBatch(
            REPOSITORY,
            "DeleteUser",
            "DELETE FROM users WHERE id = ?",
            List.of(
                List.of(1L),
                List.of(999L),
                List.of(3L)
            )
        );

        assertArrayEquals(new int[] { 1, 0, 1 }, counts);

        assertEquals(
            List.of(new User(2L, "Bob")),
            executor.queryMany(
                REPOSITORY,
                "ListUsers",
                "SELECT id, name FROM users ORDER BY id",
                List.of(),
                userMapper()
            )
        );
    }

    /**
     * An empty batch executes nothing: it obtains no connection of a
     * DataSource, prepares no statement of a caller-owned connection, and
     * returns an empty array.
     */
    @Test
    void shouldExecuteNothingForAnEmptyBatch() throws SQLException {
        JdbcResourceTracker tracker = new JdbcResourceTracker();

        JdbcQueryExecutor dataSourceExecutor = new JdbcQueryExecutor(
            tracker.track(DataSource.class, dataSource)
        );

        assertArrayEquals(
            new int[0],
            dataSourceExecutor.executeBatch(
                REPOSITORY,
                "InsertUsers",
                "INSERT INTO users (id, name, active) VALUES (?, ?, ?)",
                List.of()
            )
        );

        assertTrue(tracker.connections.isEmpty());
        assertEquals(List.of(), tracker.calls);

        try (Connection connection = dataSource.getConnection()) {
            JdbcResourceTracker connectionTracker = new JdbcResourceTracker();

            JdbcQueryExecutor connectionExecutor = new JdbcQueryExecutor(
                connectionTracker.track(Connection.class, connection)
            );

            assertArrayEquals(
                new int[0],
                connectionExecutor.executeBatch(
                    REPOSITORY,
                    "InsertUsers",
                    "INSERT INTO users (id, name, active) VALUES (?, ?, ?)",
                    List.of()
                )
            );

            assertTrue(connectionTracker.statements.isEmpty());
            assertEquals(List.of(), connectionTracker.calls);
        }
    }

    @Test
    void shouldRejectANullBatch() {
        assertThrows(
            NullPointerException.class,
            () -> executor.executeBatch(
                REPOSITORY,
                "InsertUsers",
                "INSERT INTO users (id, name, active) VALUES (?, ?, ?)",
                null
            )
        );
    }

    /**
     * An {@link UntypedText} argument of a batch row is bound as its text
     * without a declared SQL type, exactly as a single execution binds it, and
     * every other argument of that row keeps its untouched {@code setObject}
     * binding.
     */
    @Test
    void shouldBindUntypedTextOfEachBatchRow() throws SQLException {
        BindingRecorder recorder = new BindingRecorder();

        try (Connection connection = dataSource.getConnection()) {
            JdbcQueryExecutor connectionExecutor = new JdbcQueryExecutor(
                recorder.track(connection)
            );

            assertArrayEquals(
                new int[] { 1, 1 },
                connectionExecutor.executeBatch(
                    REPOSITORY,
                    "UpdateUserName",
                    "UPDATE users SET name = ? WHERE id = ?",
                    List.of(
                        Arrays.asList(new UntypedText("Alicia"), 1L),
                        Arrays.asList(new UntypedText(null), 2L)
                    )
                )
            );
        }

        assertEquals(
            List.of(
                new BoundParameter(1, "Alicia", Types.OTHER),
                new BoundParameter(2, 1L, null),
                new BoundParameter(1, null, Types.OTHER),
                new BoundParameter(2, 2L, null)
            ),
            recorder.bindings
        );
    }

    /**
     * A {@link SqlArray} argument of a batch row is bound as a server array
     * built on the connection the batch runs on, and a null list of a row is
     * bound as a SQL {@code NULL} array, exactly as a single execution binds
     * either.
     */
    @Test
    void shouldBindSqlArrayOfEachBatchRow() throws SQLException {
        BindingRecorder recorder = new BindingRecorder();

        try (Connection connection = dataSource.getConnection()) {
            createTaggedTable(connection);

            JdbcQueryExecutor connectionExecutor = new JdbcQueryExecutor(
                recorder.track(connection)
            );

            assertArrayEquals(
                new int[] { 1, 1 },
                connectionExecutor.executeBatch(
                    REPOSITORY,
                    "InsertTagged",
                    "INSERT INTO tagged (id, tags) VALUES (?, ?)",
                    List.of(
                        Arrays.asList(1L, new SqlArray("varchar", Arrays.asList("a", null))),
                        Arrays.asList(2L, new SqlArray("varchar", null))
                    )
                )
            );
        }

        assertEquals(
            List.of(
                new BoundParameter(1, 1L, null),
                new BoundParameter(1, 2L, null)
            ),
            recorder.bindings
        );

        assertEquals(
            List.of(new CreatedArray("varchar", Arrays.asList("a", null))),
            recorder.createdArrays
        );

        assertEquals(List.of(2), recorder.arrayPositions);

        assertEquals(
            List.of(new BoundParameter(2, null, Types.ARRAY)),
            recorder.nullBindings
        );
    }

    /**
     * A failing row of a batch over a DataSource rolls the whole batch back,
     * so no row of it is written. The failure names the query and its
     * repository, carries the driver's {@link BatchUpdateException}, and
     * auto-commit is restored on the connection before it is closed.
     */
    @Test
    void shouldRollBackADataSourceBatchWhoseRowFails() throws SQLException {
        JdbcResourceTracker tracker = new JdbcResourceTracker();

        JdbcQueryExecutor dataSourceExecutor = new JdbcQueryExecutor(
            tracker.track(DataSource.class, dataSource)
        );

        QueryExecutionException exception = assertThrows(
            QueryExecutionException.class,
            () -> dataSourceExecutor.executeBatch(
                REPOSITORY,
                "InsertUsers",
                "INSERT INTO users (id, name, active) VALUES (?, ?, ?)",
                List.of(
                    List.of(4L, "Dora", true),
                    List.of(1L, "Duplicate", true),
                    List.of(5L, "Eve", true)
                )
            )
        );

        assertEquals(
            "Failed to execute query 'InsertUsers' in UsersRepository",
            exception.getMessage()
        );

        assertInstanceOf(BatchUpdateException.class, exception.getCause());
        assertEquals(0, exception.getSuppressed().length);

        assertTrue(
            executor.queryMany(
                REPOSITORY,
                "ListUsers",
                "SELECT id, name FROM users WHERE id > ? ORDER BY id",
                List.of(3L),
                userMapper()
            ).isEmpty()
        );

        assertEquals(
            List.of(
                "getConnection",
                "setAutoCommit(false)",
                "rollback",
                "setAutoCommit(true)",
                "close"
            ),
            tracker.calls
        );

        assertEquals(1, tracker.connections.size());
        assertEquals(1, tracker.statements.size());

        tracker.assertAllConnectionsClosed();
        tracker.assertAllStatementsAndResultSetsClosed();
    }

    /**
     * An unchecked failure of a batch over a DataSource, here a null argument
     * list of a row, rolls the whole batch back, so no row of it is written.
     * The failure is rethrown untranslated, and auto-commit is restored on the
     * connection before it is closed.
     */
    @Test
    void shouldReleaseADataSourceConnectionWhenABatchFailsWithAnUncheckedException()
        throws SQLException {
        JdbcResourceTracker tracker = new JdbcResourceTracker();

        JdbcQueryExecutor dataSourceExecutor = new JdbcQueryExecutor(
            tracker.track(DataSource.class, dataSource)
        );

        assertThrows(
            NullPointerException.class,
            () -> dataSourceExecutor.executeBatch(
                REPOSITORY,
                "InsertUsers",
                "INSERT INTO users (id, name, active) VALUES (?, ?, ?)",
                Arrays.asList(List.of(4L, "Dora", true), null)
            )
        );

        assertEquals(
            List.of(
                "getConnection",
                "setAutoCommit(false)",
                "rollback",
                "setAutoCommit(true)",
                "close"
            ),
            tracker.calls
        );

        assertEquals(1, tracker.connections.size());
        assertEquals(1, tracker.statements.size());

        tracker.assertAllConnectionsClosed();
        tracker.assertAllStatementsAndResultSetsClosed();

        assertTrue(
            executor.queryMany(
                REPOSITORY,
                "ListUsers",
                "SELECT id, name FROM users WHERE id > ? ORDER BY id",
                List.of(3L),
                userMapper()
            ).isEmpty()
        );
    }

    /**
     * A connection a DataSource hands out with auto-commit already disabled is
     * in a transaction of its own, so the batch takes part in it and is never
     * committed, rolled back, or reconfigured: closing that connection
     * discards the batch.
     */
    @Test
    void shouldLeaveADataSourceConnectionAlreadyInATransactionUncommitted() throws SQLException {
        JdbcResourceTracker tracker = new JdbcResourceTracker()
            .handingOutConnectionsInATransaction();

        JdbcQueryExecutor dataSourceExecutor = new JdbcQueryExecutor(
            tracker.track(DataSource.class, dataSource)
        );

        assertArrayEquals(
            new int[] { 1, 1 },
            dataSourceExecutor.executeBatch(
                REPOSITORY,
                "InsertUsers",
                "INSERT INTO users (id, name, active) VALUES (?, ?, ?)",
                List.of(
                    List.of(4L, "Dora", true),
                    List.of(5L, "Eve", false)
                )
            )
        );

        assertEquals(List.of("getConnection", "close"), tracker.calls);

        assertEquals(1, tracker.connections.size());
        assertEquals(1, tracker.statements.size());

        tracker.assertAllConnectionsClosed();
        tracker.assertAllStatementsAndResultSetsClosed();

        assertTrue(
            executor.queryMany(
                REPOSITORY,
                "ListUsers",
                "SELECT id, name FROM users WHERE id > ? ORDER BY id",
                List.of(3L),
                userMapper()
            ).isEmpty()
        );
    }

    /**
     * A batch on a caller-owned connection reports its counts and leaves the
     * connection open, uncommitted, and configured as it was, so the
     * application's own rollback discards the batch.
     */
    @Test
    void shouldLeaveCallerOwnedConnectionUnchangedForABatch() throws SQLException {
        JdbcResourceTracker tracker = new JdbcResourceTracker();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            int isolation = connection.getTransactionIsolation();

            JdbcQueryExecutor connectionExecutor = new JdbcQueryExecutor(
                tracker.track(Connection.class, connection)
            );

            assertArrayEquals(
                new int[] { 1, 1 },
                connectionExecutor.executeBatch(
                    REPOSITORY,
                    "InsertUsers",
                    "INSERT INTO users (id, name, active) VALUES (?, ?, ?)",
                    List.of(
                        List.of(4L, "Dora", true),
                        List.of(5L, "Eve", false)
                    )
                )
            );

            assertEquals(List.of(), tracker.calls);

            assertEquals(1, tracker.statements.size());
            tracker.assertAllStatementsAndResultSetsClosed();

            assertFalse(connection.isClosed());
            assertFalse(connection.getAutoCommit());
            assertEquals(isolation, connection.getTransactionIsolation());

            connection.rollback();

            assertTrue(
                connectionExecutor.queryMany(
                    REPOSITORY,
                    "ListUsers",
                    "SELECT id, name FROM users WHERE id > ? ORDER BY id",
                    List.of(3L),
                    userMapper()
                ).isEmpty()
            );
        }
    }

    @Test
    void shouldWrapSqlExceptionForExecuteBatch() throws SQLException {
        QueryExecutionException exception = assertThrows(
            QueryExecutionException.class,
            () -> executor.executeBatch(
                REPOSITORY,
                "DeleteUsers",
                "DELETE FROM missing_table WHERE id = ?",
                List.of(List.of(1L))
            )
        );

        assertEquals(
            "Failed to execute query 'DeleteUsers' in UsersRepository",
            exception.getMessage()
        );

        assertInstanceOf(SQLException.class, exception.getCause());

        try (Connection connection = dataSource.getConnection()) {
            JdbcQueryExecutor connectionExecutor = new JdbcQueryExecutor(connection);

            QueryExecutionException connectionException = assertThrows(
                QueryExecutionException.class,
                () -> connectionExecutor.executeBatch(
                    REPOSITORY,
                    "DeleteUsers",
                    "DELETE FROM missing_table WHERE id = ?",
                    List.of(List.of(1L))
                )
            );

            assertEquals(
                "Failed to execute query 'DeleteUsers' in UsersRepository",
                connectionException.getMessage()
            );

            assertInstanceOf(SQLException.class, connectionException.getCause());

            assertFalse(connection.isClosed());
        }
    }

    @Test
    void shouldQueryThroughCallerOwnedConnection() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            JdbcQueryExecutor connectionExecutor = new JdbcQueryExecutor(connection);

            User result = connectionExecutor.queryOne(
                REPOSITORY,
                "GetUser",
                """
                    SELECT id, name
                    FROM users
                    WHERE id = ?
                      AND active = ?
                    """,
                List.of(1L, true),
                userMapper()
            );

            assertEquals(new User(1L, "Alice"), result);

            assertTrue(
                connectionExecutor.queryOptional(
                    REPOSITORY,
                    "FindUser",
                    "SELECT id, name FROM users WHERE id = ?",
                    List.of(999L),
                    userMapper()
                ).isEmpty()
            );

            QueryCardinalityException exception = assertThrows(
                QueryCardinalityException.class,
                () -> connectionExecutor.queryOne(
                    REPOSITORY,
                    "GetUser",
                    "SELECT id, name FROM users WHERE id = ?",
                    List.of(999L),
                    userMapper()
                )
            );

            assertEquals(
                "Query 'GetUser' in UsersRepository returned no row; expected exactly one",
                exception.getMessage()
            );

            assertFalse(connection.isClosed());
        }
    }

    @Test
    void shouldQueryManyThroughCallerOwnedConnection() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            JdbcQueryExecutor connectionExecutor = new JdbcQueryExecutor(connection);

            List<User> results = connectionExecutor.queryMany(
                REPOSITORY,
                "ListUsers",
                """
                    SELECT id, name
                    FROM users
                    WHERE active = ?
                    ORDER BY id
                    """,
                List.of(true),
                userMapper()
            );

            assertEquals(
                List.of(
                    new User(1L, "Alice"),
                    new User(3L, "Charlie")
                ),
                results
            );

            assertTrue(
                connectionExecutor.queryMany(
                    REPOSITORY,
                    "ListUsers",
                    "SELECT id, name FROM users WHERE id = ?",
                    List.of(999L),
                    userMapper()
                ).isEmpty()
            );
        }
    }

    @Test
    void shouldReturnAffectedRowCountForExecuteThroughCallerOwnedConnection() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            JdbcQueryExecutor connectionExecutor = new JdbcQueryExecutor(connection);

            int affected = connectionExecutor.execute(
                REPOSITORY,
                "RenameActiveUsers",
                """
                    UPDATE users
                    SET name = ?
                    WHERE active = ?
                    """,
                List.of("Renamed", true)
            );

            assertEquals(2, affected);

            assertEquals(
                "Renamed",
                connectionExecutor.queryOne(
                    REPOSITORY,
                    "GetUserName",
                    "SELECT name FROM users WHERE id = ?",
                    List.of(1L),
                    resultSet -> resultSet.getString("name")
                )
            );
        }
    }

    /**
     * Two operations run on the same caller-owned connection inside one
     * application-controlled transaction: the connection stays open, its
     * auto-commit setting and isolation level are unchanged, and the
     * application's rollback discards both operations.
     */
    @Test
    void shouldLeaveCallerOwnedConnectionOpenAndItsTransactionStateUnchanged() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            int isolation = connection.getTransactionIsolation();

            JdbcQueryExecutor connectionExecutor = new JdbcQueryExecutor(connection);

            connectionExecutor.execute(
                REPOSITORY,
                "InsertUser",
                "INSERT INTO users (id, name, active) VALUES (?, ?, ?)",
                List.of(4L, "Dora", true)
            );

            connectionExecutor.execute(
                REPOSITORY,
                "UpdateUserName",
                "UPDATE users SET name = ? WHERE id = ?",
                List.of("Alicia", 1L)
            );

            assertEquals(
                List.of(
                    new User(1L, "Alicia"),
                    new User(4L, "Dora")
                ),
                connectionExecutor.queryMany(
                    REPOSITORY,
                    "ListUsers",
                    """
                        SELECT id, name
                        FROM users
                        WHERE id IN (?, ?)
                        ORDER BY id
                        """,
                    List.of(1L, 4L),
                    userMapper()
                )
            );

            assertFalse(connection.isClosed());
            assertFalse(connection.getAutoCommit());
            assertEquals(isolation, connection.getTransactionIsolation());

            connection.rollback();

            assertEquals(
                List.of(new User(1L, "Alice")),
                connectionExecutor.queryMany(
                    REPOSITORY,
                    "ListUsers",
                    """
                        SELECT id, name
                        FROM users
                        WHERE id IN (?, ?)
                        ORDER BY id
                        """,
                    List.of(1L, 4L),
                    userMapper()
                )
            );
        }
    }

    @Test
    void shouldCloseStatementsAndResultSetsOfCallerOwnedConnection() throws SQLException {
        JdbcResourceTracker tracker = new JdbcResourceTracker();

        try (Connection connection = dataSource.getConnection()) {
            JdbcQueryExecutor connectionExecutor = new JdbcQueryExecutor(
                tracker.track(Connection.class, connection)
            );

            connectionExecutor.queryOne(
                REPOSITORY,
                "GetUser",
                "SELECT id FROM users WHERE id = ?",
                List.of(1L),
                resultSet -> resultSet.getLong("id")
            );

            connectionExecutor.queryOptional(
                REPOSITORY,
                "FindUser",
                "SELECT id FROM users WHERE id = ?",
                List.of(1L),
                resultSet -> resultSet.getLong("id")
            );

            connectionExecutor.queryMany(
                REPOSITORY,
                "ListUsers",
                "SELECT id FROM users",
                List.of(),
                resultSet -> resultSet.getLong("id")
            );

            connectionExecutor.execute(
                REPOSITORY,
                "UpdateUserName",
                "UPDATE users SET name = ? WHERE id = ?",
                List.of("Alicia", 1L)
            );

            assertEquals(4, tracker.statements.size());
            assertEquals(3, tracker.resultSets.size());
            tracker.assertAllStatementsAndResultSetsClosed();

            assertFalse(connection.isClosed());
        }
    }

    @Test
    void shouldCloseStatementWhenExecutionFailsOnCallerOwnedConnection() throws SQLException {
        JdbcResourceTracker tracker = new JdbcResourceTracker();

        try (Connection connection = dataSource.getConnection()) {
            JdbcQueryExecutor connectionExecutor = new JdbcQueryExecutor(
                tracker.track(Connection.class, connection)
            );

            QueryExecutionException exception = assertThrows(
                QueryExecutionException.class,
                () -> connectionExecutor.queryOne(
                    REPOSITORY,
                    "GetUser",
                    "SELECT id FROM users WHERE id = ?",
                    List.of(),
                    resultSet -> resultSet.getLong("id")
                )
            );

            assertEquals(
                "Failed to execute query 'GetUser' in UsersRepository",
                exception.getMessage()
            );

            assertInstanceOf(SQLException.class, exception.getCause());

            assertEquals(1, tracker.statements.size());
            tracker.assertAllStatementsAndResultSetsClosed();

            assertFalse(connection.isClosed());
        }
    }

    /**
     * A cardinality failure is raised after the statement has executed, so the
     * statement and its result set are still closed and the caller-owned
     * connection is left open.
     */
    @Test
    void shouldCloseStatementWhenCardinalityFailsOnCallerOwnedConnection() throws SQLException {
        JdbcResourceTracker tracker = new JdbcResourceTracker();

        try (Connection connection = dataSource.getConnection()) {
            JdbcQueryExecutor connectionExecutor = new JdbcQueryExecutor(
                tracker.track(Connection.class, connection)
            );

            QueryCardinalityException exception = assertThrows(
                QueryCardinalityException.class,
                () -> connectionExecutor.queryOne(
                    REPOSITORY,
                    "GetUser",
                    "SELECT id FROM users WHERE active = ?",
                    List.of(true),
                    resultSet -> resultSet.getLong("id")
                )
            );

            assertEquals(
                "Query 'GetUser' in UsersRepository returned more than one row; expected exactly one",
                exception.getMessage()
            );

            assertEquals(1, tracker.statements.size());
            assertEquals(1, tracker.resultSets.size());
            tracker.assertAllStatementsAndResultSetsClosed();

            assertFalse(connection.isClosed());
        }
    }

    @Test
    void shouldWrapSqlExceptionForCallerOwnedConnection() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            JdbcQueryExecutor connectionExecutor = new JdbcQueryExecutor(connection);

            QueryExecutionException queryException = assertThrows(
                QueryExecutionException.class,
                () -> connectionExecutor.queryOne(
                    REPOSITORY,
                    "GetUser",
                    "SELECT * FROM missing_table",
                    List.of(),
                    resultSet -> resultSet.getLong("id")
                )
            );

            assertEquals(
                "Failed to execute query 'GetUser' in UsersRepository",
                queryException.getMessage()
            );

            assertInstanceOf(SQLException.class, queryException.getCause());

            QueryExecutionException executeException = assertThrows(
                QueryExecutionException.class,
                () -> connectionExecutor.execute(
                    REPOSITORY,
                    "DeleteUsers",
                    "DELETE FROM missing_table",
                    List.of()
                )
            );

            assertEquals(
                "Failed to execute query 'DeleteUsers' in UsersRepository",
                executeException.getMessage()
            );

            assertInstanceOf(SQLException.class, executeException.getCause());

            assertFalse(connection.isClosed());
        }
    }

    @Test
    void shouldCloseAcquiredConnectionForEachDataSourceOperation() throws SQLException {
        JdbcResourceTracker tracker = new JdbcResourceTracker();

        JdbcQueryExecutor dataSourceExecutor = new JdbcQueryExecutor(
            tracker.track(DataSource.class, dataSource)
        );

        dataSourceExecutor.queryOne(
            REPOSITORY,
            "GetUser",
            "SELECT id FROM users WHERE id = ?",
            List.of(1L),
            resultSet -> resultSet.getLong("id")
        );

        dataSourceExecutor.queryOptional(
            REPOSITORY,
            "FindUser",
            "SELECT id FROM users WHERE id = ?",
            List.of(1L),
            resultSet -> resultSet.getLong("id")
        );

        dataSourceExecutor.queryMany(
            REPOSITORY,
            "ListUsers",
            "SELECT id FROM users",
            List.of(),
            resultSet -> resultSet.getLong("id")
        );

        dataSourceExecutor.execute(
            REPOSITORY,
            "UpdateUserName",
            "UPDATE users SET name = ? WHERE id = ?",
            List.of("Alicia", 1L)
        );

        assertEquals(4, tracker.connections.size());
        assertEquals(4, tracker.statements.size());
        assertEquals(3, tracker.resultSets.size());

        tracker.assertAllConnectionsClosed();
        tracker.assertAllStatementsAndResultSetsClosed();
    }

    @Test
    void shouldCloseAcquiredConnectionWhenDataSourceOperationFails() throws SQLException {
        JdbcResourceTracker tracker = new JdbcResourceTracker();

        JdbcQueryExecutor dataSourceExecutor = new JdbcQueryExecutor(
            tracker.track(DataSource.class, dataSource)
        );

        QueryExecutionException exception = assertThrows(
            QueryExecutionException.class,
            () -> dataSourceExecutor.queryOne(
                REPOSITORY,
                "GetUser",
                "SELECT id FROM users WHERE id = ?",
                List.of(),
                resultSet -> resultSet.getLong("id")
            )
        );

        assertEquals(
            "Failed to execute query 'GetUser' in UsersRepository",
            exception.getMessage()
        );

        assertInstanceOf(SQLException.class, exception.getCause());

        assertEquals(1, tracker.connections.size());
        assertEquals(1, tracker.statements.size());

        tracker.assertAllConnectionsClosed();
        tracker.assertAllStatementsAndResultSetsClosed();
    }

    /**
     * A cardinality failure on the DataSource path closes the acquired
     * connection together with the statement and its result set.
     */
    @Test
    void shouldCloseAcquiredConnectionWhenCardinalityFails() throws SQLException {
        JdbcResourceTracker tracker = new JdbcResourceTracker();

        JdbcQueryExecutor dataSourceExecutor = new JdbcQueryExecutor(
            tracker.track(DataSource.class, dataSource)
        );

        QueryCardinalityException exception = assertThrows(
            QueryCardinalityException.class,
            () -> dataSourceExecutor.queryOptional(
                REPOSITORY,
                "FindUser",
                "SELECT id FROM users WHERE active = ?",
                List.of(true),
                resultSet -> resultSet.getLong("id")
            )
        );

        assertEquals(
            "Query 'FindUser' in UsersRepository returned more than one row; expected at most one",
            exception.getMessage()
        );

        assertEquals(1, tracker.connections.size());
        assertEquals(1, tracker.statements.size());
        assertEquals(1, tracker.resultSets.size());

        tracker.assertAllConnectionsClosed();
        tracker.assertAllStatementsAndResultSetsClosed();
    }

    private RowMapper<User> userMapper() {
        return resultSet -> new User(
            resultSet.getLong("id"),
            resultSet.getString("name")
        );
    }

    private record User(
        long id,
        String name
    ) {
    }

    /**
     * One {@code PreparedStatement.setObject} call an executor made, with the
     * SQL type it declared or {@code null} when it declared none.
     */
    private record BoundParameter(
        int position,
        Object value,
        Integer sqlType
    ) {
    }

    /**
     * One {@code Connection.createArrayOf} call an executor made, with the
     * element type name it named and the elements it passed.
     */
    private record CreatedArray(
        String typeName,
        List<Object> elements
    ) {
    }

    /**
     * Records the parameter bindings an executor made, in call order. A tracked
     * connection is returned as a dynamic proxy that delegates every call and
     * records the {@code setObject} calls of the prepared statements it hands
     * out.
     */
    private static final class BindingRecorder {

        private final List<BoundParameter> bindings = new ArrayList<>();

        private final List<CreatedArray> createdArrays = new ArrayList<>();

        private final List<Integer> arrayPositions = new ArrayList<>();

        private final List<BoundParameter> nullBindings = new ArrayList<>();

        private Connection track(Connection connection) {
            return (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, arguments) -> {
                    if ("createArrayOf".equals(method.getName())) {
                        createdArrays.add(
                            new CreatedArray(
                                (String) arguments[0],
                                Arrays.asList((Object[]) arguments[1])
                            )
                        );
                    }

                    Object result = invoke(connection, method, arguments);

                    if (result instanceof PreparedStatement statement) {
                        return trackStatement(statement);
                    }

                    return result;
                }
            );
        }

        private PreparedStatement trackStatement(PreparedStatement statement) {
            return (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { PreparedStatement.class },
                (proxy, method, arguments) -> {
                    if ("setObject".equals(method.getName())) {
                        bindings.add(
                            new BoundParameter(
                                (Integer) arguments[0],
                                arguments[1],
                                arguments.length > 2
                                    ? (Integer) arguments[2]
                                    : null
                            )
                        );
                    }

                    if ("setArray".equals(method.getName())) {
                        arrayPositions.add((Integer) arguments[0]);
                    }

                    if ("setNull".equals(method.getName())) {
                        nullBindings.add(
                            new BoundParameter(
                                (Integer) arguments[0],
                                null,
                                (Integer) arguments[1]
                            )
                        );
                    }

                    return invoke(statement, method, arguments);
                }
            );
        }

        private Object invoke(Object delegate, Method method, Object[] arguments) throws Throwable {
            try {
                return method.invoke(delegate, arguments);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    /**
     * Records the JDBC resources an executor opened, so a test can assert which
     * of them were closed. A tracked object is returned as a dynamic proxy that
     * delegates every call and tracks the connections, prepared statements, and
     * result sets it hands out.
     */
    private static final class JdbcResourceTracker {

        /** The calls of an executor that are recorded, in call order. */
        private static final List<String> RECORDED = List.of(
            "setAutoCommit",
            "commit",
            "rollback",
            "close"
        );

        private final List<Connection> connections = new ArrayList<>();

        private final List<PreparedStatement> statements = new ArrayList<>();

        private final List<ResultSet> resultSets = new ArrayList<>();

        private final List<String> calls = new ArrayList<>();

        private boolean autoCommitAlreadyDisabled;

        /**
         * Hands out connections whose auto-commit is already disabled, without
         * recording that preparation as a call of the executor.
         */
        private JdbcResourceTracker handingOutConnectionsInATransaction() {
            autoCommitAlreadyDisabled = true;

            return this;
        }

        private <T> T track(Class<T> type, Object delegate) {
            return type.cast(
                Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] { type },
                    (proxy, method, arguments) -> {
                        recordCall(type, method, arguments);

                        return record(invoke(delegate, method, arguments));
                    }
                )
            );
        }

        /**
         * Records the connections an executor obtained from a tracked
         * DataSource and the transaction calls it made on a connection, so a
         * test can assert the transaction handling of an operation. A call on
         * a tracked statement or result set is not a call of either kind.
         */
        private void recordCall(Class<?> type, Method method, Object[] arguments) {
            if (type == DataSource.class && "getConnection".equals(method.getName())) {
                calls.add("getConnection");
            }

            if (type != Connection.class || !RECORDED.contains(method.getName())) {
                return;
            }

            calls.add(
                "setAutoCommit".equals(method.getName())
                    ? "setAutoCommit(%s)".formatted(arguments[0])
                    : method.getName()
            );
        }

        private Object record(Object result) throws SQLException {
            if (result instanceof Connection connection) {
                connections.add(connection);

                if (autoCommitAlreadyDisabled) {
                    connection.setAutoCommit(false);
                }

                return track(Connection.class, connection);
            }

            if (result instanceof PreparedStatement statement) {
                statements.add(statement);

                return track(PreparedStatement.class, statement);
            }

            if (result instanceof ResultSet resultSet) {
                resultSets.add(resultSet);

                return track(ResultSet.class, resultSet);
            }

            return result;
        }

        private Object invoke(Object delegate, Method method, Object[] arguments) throws Throwable {
            try {
                return method.invoke(delegate, arguments);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private void assertAllConnectionsClosed() throws SQLException {
            for (Connection connection : connections) {
                assertTrue(connection.isClosed());
            }
        }

        private void assertAllStatementsAndResultSetsClosed() throws SQLException {
            for (PreparedStatement statement : statements) {
                assertTrue(statement.isClosed());
            }

            for (ResultSet resultSet : resultSets) {
                assertTrue(resultSet.isClosed());
            }
        }
    }
}
