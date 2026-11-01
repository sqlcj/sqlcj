package org.sqlcj.runtime;

import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Executes generated queries over JDBC.
 *
 * <p>An executor is constructed either with a {@link DataSource} or with a
 * caller-owned {@link Connection}. Both construction paths share the same
 * positional parameter binding, row mapping, cardinality enforcement,
 * affected-row counting, and exception translation. Only connection ownership
 * differs:
 *
 * <ul>
 * <li>{@link #JdbcQueryExecutor(DataSource)} obtains one connection per
 * operation and closes it before the operation returns. Each operation
 * therefore runs on that connection's own transaction state, typically one
 * auto-committed statement.</li>
 * <li>{@link #JdbcQueryExecutor(Connection)} runs every operation on the
 * supplied connection and never closes, commits, or rolls it back, never
 * changes its auto-commit setting, and never otherwise configures it. The
 * application alone controls the connection's lifetime and transaction, so
 * several generated operations can take part in one application-controlled
 * commit or rollback.</li>
 * </ul>
 *
 * <p>Each argument is bound at its one-based position with
 * {@link PreparedStatement#setObject(int, Object)}, so a null argument is bound
 * as SQL {@code NULL}. There are two exceptions. An {@link UntypedText}
 * argument's {@link UntypedText#value() value} is bound with
 * {@link java.sql.Types#OTHER}, which sends the text without a declared SQL
 * type and lets the database type it from the context of its placeholder. A
 * {@link SqlArray} argument's {@link SqlArray#elements() elements} are bound
 * with {@link PreparedStatement#setArray(int, java.sql.Array)} as an array
 * built with {@link Connection#createArrayOf} on the connection the operation
 * runs on, and a null list is bound with
 * {@link PreparedStatement#setNull(int, int)} and
 * {@link java.sql.Types#ARRAY}.
 *
 * <p>{@link #queryOne} reads the first row, maps it, and then advances the
 * result set once more to prove that there is no second row.
 * {@link #queryOptional} does the same but reports no row as
 * {@link Optional#empty()}. A row count an annotation does not allow raises a
 * {@link QueryCardinalityException} naming the query and its repository; the
 * statement has already executed, so a returning write that fails the check has
 * already changed the database.
 *
 * <p>{@link #executeBatch} executes its SQL once per element of the batch, in
 * the order of the batch, as one JDBC batch on one connection, binds each
 * element exactly as a single execution binds its arguments, and returns the
 * counts the driver reported, unchanged. An empty batch executes nothing,
 * obtains no connection, and returns an empty array. Its transaction handling
 * follows the connection it runs on:
 *
 * <ul>
 * <li>On a connection obtained from the {@link DataSource} in auto-commit mode
 * the batch is one transaction: auto-commit is disabled for it, a successful
 * batch is committed, a failed one is rolled back, and auto-commit is restored
 * only after a successful commit or rollback, because changing it during an
 * open transaction commits that transaction.</li>
 * <li>A connection the {@link DataSource} hands out with auto-commit already
 * disabled is in a transaction of its own, so the batch takes part in it and is
 * never committed, rolled back, or reconfigured.</li>
 * <li>On a caller-owned connection the batch is never committed or rolled back,
 * and the connection's auto-commit setting is never changed, as for every other
 * operation.</li>
 * </ul>
 *
 * <p>Every {@link SQLException} a batch raises, a
 * {@link java.sql.BatchUpdateException} included, is translated into the same
 * {@link QueryExecutionException} as every other operation's, and a failure to
 * roll back, to restore auto-commit, or to close the obtained connection that
 * follows it is attached to it as a suppressed
 * {@link QueryExecutionException}.
 *
 * <p>An unchecked failure of a batch on a connection obtained from the
 * {@link DataSource} is rethrown unchanged, as the same instance, after the
 * batch is rolled back, when auto-commit was disabled for it, and the
 * connection is closed. A failure to roll back, to restore auto-commit, or to
 * close that connection is attached to it as a suppressed
 * {@link QueryExecutionException}.
 *
 * <p>In both paths the {@link PreparedStatement} and any {@link ResultSet}
 * opened for an operation are closed before that operation returns, on success,
 * on a cardinality failure, and on any other failure, and a connection obtained
 * for an operation is closed on every path.
 *
 * <p>Every {@link SQLException} raised while acquiring a connection, preparing
 * a statement, binding parameters, executing, reading results, or closing a
 * DataSource-acquired connection is translated into a
 * {@link QueryExecutionException} with the message {@code "Failed to execute
 * query '<query>' in <repository>"} and the {@code SQLException} as its cause.
 *
 * <p>This executor is blocking and synchronous. An instance constructed with a
 * caller-owned connection inherits that connection's confinement to a single
 * thread at a time.
 */
public final class JdbcQueryExecutor implements QueryExecutor {

    private final @Nullable DataSource dataSource;

    private final @Nullable Connection connection;

    /**
     * Creates an executor that acquires and closes one connection from the
     * given {@code dataSource} per operation.
     */
    public JdbcQueryExecutor(DataSource dataSource) {
        this.dataSource = dataSource;
        this.connection = null;
    }

    /**
     * Creates an executor that runs every operation on the given caller-owned
     * {@code connection}. The connection is never closed, committed, rolled
     * back, or reconfigured by this executor.
     */
    public JdbcQueryExecutor(Connection connection) {
        this.dataSource = null;
        this.connection = connection;
    }

    @Override
    public <T> T queryOne(
        String repository,
        String query,
        String sql,
        List<? extends @Nullable Object> parameters,
        RowMapper<T> mapper
    ) {
        return onStatement(repository, query, sql, parameters, statement -> {
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new QueryCardinalityException(
                        cardinalityMessage(
                            repository,
                            query,
                            "returned no row; expected exactly one"
                        )
                    );
                }

                T row = mapper.map(resultSet);

                if (resultSet.next()) {
                    throw new QueryCardinalityException(
                        cardinalityMessage(
                            repository,
                            query,
                            "returned more than one row; expected exactly one"
                        )
                    );
                }

                return row;
            }
        });
    }

    @Override
    public <T> Optional<T> queryOptional(
        String repository,
        String query,
        String sql,
        List<? extends @Nullable Object> parameters,
        RowMapper<T> mapper
    ) {
        return onStatement(repository, query, sql, parameters, statement -> {
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }

                T row = mapper.map(resultSet);

                if (resultSet.next()) {
                    throw new QueryCardinalityException(
                        cardinalityMessage(
                            repository,
                            query,
                            "returned more than one row; expected at most one"
                        )
                    );
                }

                return Optional.of(row);
            }
        });
    }

    @Override
    public <T> List<T> queryMany(
        String repository,
        String query,
        String sql,
        List<? extends @Nullable Object> parameters,
        RowMapper<T> mapper
    ) {
        return onStatement(repository, query, sql, parameters, statement -> {
            try (ResultSet resultSet = statement.executeQuery()) {
                List<T> results = new ArrayList<>();

                while (resultSet.next()) {
                    results.add(mapper.map(resultSet));
                }

                return results;
            }
        });
    }

    @Override
    public int execute(
        String repository,
        String query,
        String sql,
        List<? extends @Nullable Object> parameters
    ) {
        return onStatement(repository, query, sql, parameters, PreparedStatement::executeUpdate);
    }

    @Override
    public int[] executeBatch(
        String repository,
        String query,
        String sql,
        List<? extends List<? extends @Nullable Object>> batch
    ) {
        if (Objects.requireNonNull(batch, "batch").isEmpty()) {
            return new int[0];
        }

        String failureMessage = failureMessage(repository, query);

        if (connection != null) {
            try {
                return addAndExecuteBatch(connection, sql, batch);
            } catch (SQLException e) {
                throw new QueryExecutionException(failureMessage, e);
            }
        }

        return batchOnAcquiredConnection(failureMessage, sql, batch);
    }

    /**
     * Runs the batch on a connection acquired for it, as one transaction when
     * that connection is in auto-commit mode and as part of the connection's
     * open transaction otherwise, and closes the connection on every path.
     */
    private int[] batchOnAcquiredConnection(
        String failureMessage,
        String sql,
        List<? extends List<? extends @Nullable Object>> batch
    ) {
        Connection acquired = acquireConnection(failureMessage);

        QueryExecutionException failure = null;
        boolean autoCommitDisabled = false;
        boolean restoreAutoCommit = false;
        int[] counts = null;

        try {
            if (acquired.getAutoCommit()) {
                acquired.setAutoCommit(false);

                autoCommitDisabled = true;
            }

            try {
                counts = addAndExecuteBatch(acquired, sql, batch);
            } catch (SQLException e) {
                failure = new QueryExecutionException(failureMessage, e);

                if (autoCommitDisabled) {
                    restoreAutoCommit = rollBack(acquired, failureMessage, failure);
                }
            }

            if (failure == null && autoCommitDisabled) {
                try {
                    acquired.commit();

                    restoreAutoCommit = true;
                } catch (SQLException e) {
                    failure = new QueryExecutionException(failureMessage, e);
                }
            }
        } catch (SQLException e) {
            failure = new QueryExecutionException(failureMessage, e);
        } catch (RuntimeException | Error e) {
            discard(acquired, autoCommitDisabled, failureMessage, e);

            throw e;
        }

        failure = release(acquired, restoreAutoCommit, failureMessage, failure);

        if (failure != null) {
            throw failure;
        }

        return counts;
    }

    private Connection acquireConnection(String failureMessage) {
        try {
            return Objects.requireNonNull(dataSource).getConnection();
        } catch (SQLException e) {
            throw new QueryExecutionException(failureMessage, e);
        }
    }

    /**
     * Prepares the statement once, adds one batch entry per argument list, and
     * returns the counts the driver reported, closing the statement on every
     * path.
     */
    private int[] addAndExecuteBatch(
        Connection target,
        String sql,
        List<? extends List<? extends @Nullable Object>> batch
    ) throws SQLException {
        try (PreparedStatement statement = target.prepareStatement(sql)) {
            for (List<? extends @Nullable Object> parameters : batch) {
                bindParameters(target, statement, parameters);

                statement.addBatch();
            }

            return statement.executeBatch();
        }
    }

    /**
     * Rolls the batch back and releases its connection after an unchecked
     * failure, so that failure can be rethrown untranslated with a rollback,
     * restore, or close failure attached to it as a suppressed
     * {@link QueryExecutionException}.
     */
    private void discard(
        Connection target,
        boolean autoCommitDisabled,
        String failureMessage,
        Throwable failure
    ) {
        boolean restoreAutoCommit = autoCommitDisabled
            && rollBack(target, failureMessage, failure);

        QueryExecutionException released = release(
            target,
            restoreAutoCommit,
            failureMessage,
            null
        );

        if (released != null) {
            failure.addSuppressed(released);
        }
    }

    /**
     * Rolls the failed batch back, attaching a rollback failure to the pending
     * {@code failure} as suppressed, and reports whether the rollback
     * succeeded, because a failed rollback leaves a transaction the restore of
     * auto-commit would commit.
     */
    private boolean rollBack(
        Connection target,
        String failureMessage,
        Throwable failure
    ) {
        try {
            target.rollback();

            return true;
        } catch (SQLException e) {
            failure.addSuppressed(new QueryExecutionException(failureMessage, e));

            return false;
        }
    }

    /**
     * Restores auto-commit when asked and always closes the connection,
     * returning the pending {@code failure} with a failure of either step
     * attached to it as suppressed, that failure itself when none was pending,
     * or {@code null} when nothing failed.
     */
    private @Nullable QueryExecutionException release(
        Connection target,
        boolean restoreAutoCommit,
        String failureMessage,
        @Nullable QueryExecutionException failure
    ) {
        QueryExecutionException pending = failure;

        if (restoreAutoCommit) {
            try {
                target.setAutoCommit(true);
            } catch (SQLException e) {
                pending = attach(pending, failureMessage, e);
            }
        }

        try {
            target.close();
        } catch (SQLException e) {
            pending = attach(pending, failureMessage, e);
        }

        return pending;
    }

    private static QueryExecutionException attach(
        @Nullable QueryExecutionException pending,
        String failureMessage,
        SQLException e
    ) {
        QueryExecutionException failure = new QueryExecutionException(failureMessage, e);

        if (pending == null) {
            return failure;
        }

        pending.addSuppressed(failure);

        return pending;
    }

    /**
     * Runs the given operation on a prepared statement of the connection this
     * executor owns or was given, closing the statement afterward and closing
     * the connection only when this executor acquired it.
     */
    private <T> T onStatement(
        String repository,
        String query,
        String sql,
        List<? extends @Nullable Object> parameters,
        StatementOperation<T> operation
    ) {
        if (connection != null) {
            return onConnection(connection, repository, query, sql, parameters, operation);
        }

        try (Connection acquired = Objects.requireNonNull(dataSource).getConnection()) {
            return onConnection(acquired, repository, query, sql, parameters, operation);
        } catch (SQLException e) {
            throw new QueryExecutionException(
                failureMessage(repository, query),
                e
            );
        }
    }

    private <T> T onConnection(
        Connection target,
        String repository,
        String query,
        String sql,
        List<? extends @Nullable Object> parameters,
        StatementOperation<T> operation
    ) {
        try (PreparedStatement statement = target.prepareStatement(sql)) {
            bindParameters(target, statement, parameters);

            return operation.run(statement);
        } catch (SQLException e) {
            throw new QueryExecutionException(
                failureMessage(repository, query),
                e
            );
        }
    }

    /**
     * Binds each argument at its one-based position: an {@link UntypedText} as
     * its text without a declared SQL type, a {@link SqlArray} as an array of
     * its element type built on the connection the operation runs on, and every
     * other argument as itself.
     */
    private void bindParameters(
        Connection target,
        PreparedStatement statement,
        List<? extends @Nullable Object> parameters
    ) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            Object parameter = parameters.get(i);

            if (parameter instanceof UntypedText text) {
                statement.setObject(i + 1, text.value(), Types.OTHER);
            } else if (parameter instanceof SqlArray array) {
                bindArray(target, statement, i + 1, array.elementType(), array.elements());
            } else {
                statement.setObject(i + 1, parameter);
            }
        }
    }

    /**
     * Binds one array argument, a null list as a SQL {@code NULL} array and
     * every other list as a server array of its element type, built with
     * {@link Connection#createArrayOf}.
     */
    private void bindArray(
        Connection target,
        PreparedStatement statement,
        int position,
        String elementType,
        @Nullable List<? extends @Nullable Object> elements
    ) throws SQLException {
        if (elements == null) {
            statement.setNull(position, Types.ARRAY);

            return;
        }

        statement.setArray(
            position,
            target.createArrayOf(elementType, elements.toArray())
        );
    }

    private static String failureMessage(String repository, String query) {
        return "Failed to execute query '%s' in %s".formatted(query, repository);
    }

    private static String cardinalityMessage(String repository, String query, String outcome) {
        return "Query '%s' in %s %s".formatted(query, repository, outcome);
    }

    private interface StatementOperation<T> {

        T run(PreparedStatement statement) throws SQLException;
    }
}
