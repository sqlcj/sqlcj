package org.sqlcj.runtime;

import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Function;

/**
 * Runs a unit of work on one connection inside one transaction.
 *
 * <p>A call obtains a connection from the {@link DataSource}, disables
 * auto-commit on it, builds the application's {@link QueryExecutor} over it,
 * and hands both to the callback as one {@link JdbcTransaction}. The callback
 * binds the generated repositories it needs over
 * {@link JdbcTransaction#executor()} and may run hand-written JDBC code on
 * {@link JdbcTransaction#connection()}, so every operation takes part in the
 * same transaction:
 *
 * <pre>{@code
 * JdbcTransactions transactions = new JdbcTransactions(dataSource);
 *
 * Long id = transactions.inTransaction(transaction -> {
 *     AuthorRepository authors = new AuthorRepository(transaction.executor());
 *
 *     Author author = authors.createAuthor("Jane Austen", "English novelist");
 *
 *     authors.recordAudit(author.id(), "created");
 *
 *     return author.id();
 * });
 * }</pre>
 *
 * <p>The executor is built once per transaction by the function given to
 * {@link #JdbcTransactions(DataSource, Function)}, which defaults to
 * {@code JdbcQueryExecutor::new}, so an application that decorates its executor
 * keeps that decoration inside every transaction.
 *
 * <p>Each call manages the transaction as follows:
 *
 * <ul>
 * <li>A normal return commits the transaction, and
 * {@link #inTransaction} returns the callback's value.</li>
 * <li>Any exception or error the callback throws rolls the transaction back and
 * is rethrown unchanged, as the same instance.</li>
 * <li>Any exception or error the executor function throws rolls the transaction
 * back and is rethrown unchanged in the same way, and the callback does not
 * run.</li>
 * <li>A failure to begin raises {@link TransactionException} carrying the
 * {@code SQLException} as its cause, and the callback does not run.</li>
 * <li>A failure to commit raises the database-neutral type its SQLSTATE
 * names, such as {@link TransactionConflictException} for the serialization
 * failure or deadlock PostgreSQL reports at the {@code COMMIT}, and
 * {@link TransactionException} for every other failure. Either way the
 * message is {@code "Failed to commit the transaction"} and the
 * {@code SQLException} is the cause, so a retry loop around
 * {@link #inTransaction} catches a conflict of a statement and a conflict of
 * the commit as one type.</li>
 * <li>A failure to roll back is attached to the callback's failure as a
 * suppressed {@link TransactionException}.</li>
 * <li>Auto-commit is restored to {@code true} only when this helper disabled it,
 * and only after a successful commit or rollback, because changing
 * auto-commit during an open transaction commits it.</li>
 * <li>The connection is closed on every path. A failure to restore auto-commit
 * or to close raises {@link TransactionException} when no other failure is
 * pending, and is attached to the pending failure as a suppressed
 * {@link TransactionException} otherwise.</li>
 * </ul>
 *
 * <p>Each call uses its own connection, so a call made inside another call's
 * callback is an independent transaction: it commits or rolls back on its own,
 * and the outer transaction's outcome does not change it.
 *
 * <p>This helper covers one transaction over one connection. Anything else a
 * transaction may need, such as an isolation level, a savepoint, or a
 * {@code SET LOCAL} setting, is applied by the application on a connection it
 * owns itself, with repositories bound over
 * {@link JdbcQueryExecutor#JdbcQueryExecutor(Connection)}.
 *
 * <p>This helper is blocking and synchronous, and its connection is confined to
 * the thread that runs the call.
 */
public final class JdbcTransactions {

    private static final String BEGIN_FAILURE = "Failed to begin a transaction";

    private static final String COMMIT_FAILURE = "Failed to commit the transaction";

    private static final String ROLLBACK_FAILURE = "Failed to roll back the transaction";

    private static final String RELEASE_FAILURE = "Failed to release the transaction's connection";

    private final DataSource dataSource;

    private final Function<Connection, QueryExecutor> executorFactory;

    /**
     * Creates a helper that obtains one connection from the given
     * {@code dataSource} per transaction, closes it before the transaction
     * returns, and hands the callback a {@link JdbcQueryExecutor} over that
     * connection.
     */
    public JdbcTransactions(DataSource dataSource) {
        this(dataSource, JdbcQueryExecutor::new);
    }

    /**
     * Creates a helper that obtains one connection from the given
     * {@code dataSource} per transaction, closes it before the transaction
     * returns, and hands the callback the {@link QueryExecutor} the given
     * {@code executorFactory} builds from that connection.
     *
     * <p>The function is applied once per transaction, after auto-commit was
     * disabled and before the callback runs, so an application that decorates
     * its executor keeps that decoration inside the transaction:
     *
     * <pre>{@code
     * JdbcTransactions transactions = new JdbcTransactions(
     *     dataSource,
     *     connection -> new TimedQueryExecutor(new JdbcQueryExecutor(connection))
     * );
     * }</pre>
     *
     * <p>Any exception or error the function throws rolls the transaction back,
     * releases its connection, and is rethrown unchanged, exactly as a failure
     * of the callback is, and the callback does not run.
     */
    public JdbcTransactions(
        DataSource dataSource,
        Function<Connection, QueryExecutor> executorFactory
    ) {
        this.dataSource = dataSource;
        this.executorFactory = executorFactory;
    }

    /**
     * Runs the given {@code callback} on one connection inside one transaction
     * and returns its value, committing on a normal return and rolling back on
     * any failure.
     */
    public <T extends @Nullable Object, X extends Exception> T inTransaction(
        TransactionCallback<T, X> callback
    ) throws X {
        Transaction transaction = begin();

        T result;

        try {
            Connection connection = transaction.connection();

            result = callback.run(
                new JdbcTransaction(executorFactory.apply(connection), connection)
            );
        } catch (Throwable failure) {
            rollBack(transaction, failure);

            throw rethrow(failure);
        }

        commit(transaction);

        return result;
    }

    /**
     * Runs the given {@code callback} on one connection inside one transaction,
     * committing on a normal return and rolling back on any failure.
     */
    public <X extends Exception> void useTransaction(TransactionConsumer<X> callback) throws X {
        this.<@Nullable Void, X>inTransaction(transaction -> {
            callback.accept(transaction);

            return null;
        });
    }

    /**
     * Obtains a connection and disables its auto-commit, remembering whether
     * this call disabled it. A failure closes a connection already obtained and
     * raises a {@link TransactionException}, so the callback never runs.
     */
    private Transaction begin() {
        Connection connection;

        try {
            connection = dataSource.getConnection();
        } catch (SQLException e) {
            throw new TransactionException(BEGIN_FAILURE, e);
        }

        try {
            if (connection.getAutoCommit()) {
                connection.setAutoCommit(false);

                return new Transaction(connection, true);
            }

            return new Transaction(connection, false);
        } catch (SQLException e) {
            TransactionException failure = new TransactionException(BEGIN_FAILURE, e);

            attach(failure, release(connection, false));

            throw failure;
        }
    }

    /**
     * Commits the transaction and releases its connection. A commit failure
     * raises the exception its own SQLSTATE names and leaves auto-commit
     * untouched, so a transaction the commit left open is not committed by the
     * restore.
     */
    private void commit(Transaction transaction) {
        RuntimeException commitFailure = null;

        try {
            transaction.connection().commit();
        } catch (SQLException e) {
            commitFailure = commitFailure(e);
        }

        TransactionException releaseFailure = release(
            transaction.connection(),
            transaction.autoCommitDisabled() && commitFailure == null
        );

        if (commitFailure != null) {
            attach(commitFailure, releaseFailure);

            throw commitFailure;
        }

        if (releaseFailure != null) {
            throw releaseFailure;
        }
    }

    /**
     * The exception a failed commit raises: the database-neutral type its
     * SQLSTATE names, such as a {@link TransactionConflictException} for a
     * serialization failure or a deadlock, and a {@link TransactionException}
     * for every other failure.
     *
     * <p>The mapping is {@link JdbcQueryExecutor}'s own, so where PostgreSQL
     * reports a conflict, at the statement or at the {@code COMMIT}, does not
     * change the type the application catches.
     */
    private static RuntimeException commitFailure(SQLException e) {
        QueryExecutionException mapped = JdbcQueryExecutor.mapped(COMMIT_FAILURE, e);

        return mapped != null ? mapped : new TransactionException(COMMIT_FAILURE, e);
    }

    /**
     * Rolls the transaction back and releases its connection, attaching a
     * rollback failure and a release failure to the pending {@code failure} as
     * suppressed exceptions. A failed rollback leaves auto-commit untouched, so
     * the restore cannot commit the work the rollback failed to discard.
     */
    private void rollBack(Transaction transaction, Throwable failure) {
        boolean rolledBack = true;

        try {
            transaction.connection().rollback();
        } catch (SQLException e) {
            rolledBack = false;

            failure.addSuppressed(new TransactionException(ROLLBACK_FAILURE, e));
        }

        attach(
            failure,
            release(
                transaction.connection(),
                transaction.autoCommitDisabled() && rolledBack
            )
        );
    }

    /**
     * Restores auto-commit when asked and always closes the connection,
     * returning the first failure of either step, with a later one attached to
     * it as suppressed, or {@code null} when both succeeded.
     */
    private @Nullable TransactionException release(Connection connection, boolean restoreAutoCommit) {
        TransactionException failure = null;

        if (restoreAutoCommit) {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException e) {
                failure = new TransactionException(RELEASE_FAILURE, e);
            }
        }

        try {
            connection.close();
        } catch (SQLException e) {
            TransactionException closeFailure = new TransactionException(RELEASE_FAILURE, e);

            if (failure == null) {
                failure = closeFailure;
            } else {
                failure.addSuppressed(closeFailure);
            }
        }

        return failure;
    }

    private static void attach(Throwable failure, @Nullable TransactionException suppressed) {
        if (suppressed != null) {
            failure.addSuppressed(suppressed);
        }
    }

    /**
     * Rethrows the given failure as the same instance, unchanged. The cast is
     * erased, so a checked exception the callback declared is rethrown without
     * the compiler requiring it again here; the caller of
     * {@link #inTransaction} still sees it as the callback's own {@code X}.
     */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException rethrow(Throwable failure) throws E {
        throw (E) failure;
    }

    /** One connection and whether this helper disabled its auto-commit. */
    private record Transaction(
        Connection connection,
        boolean autoCommitDisabled
    ) {
    }
}
