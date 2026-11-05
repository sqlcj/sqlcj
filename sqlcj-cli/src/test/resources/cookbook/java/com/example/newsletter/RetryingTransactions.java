package com.example.newsletter;

import org.sqlcj.runtime.JdbcTransactions;
import org.sqlcj.runtime.TransactionCallback;
import org.sqlcj.runtime.TransactionConflictException;

/**
 * A bounded retry of a transaction the database could not run concurrently
 * with another one: the unit of work is run again from its beginning, because
 * the conflicting transaction itself is gone.
 *
 * <p>Only {@link TransactionConflictException} is retried, which is the one
 * type a serialization failure and a deadlock have, wherever PostgreSQL
 * reports it, in a statement or at the {@code COMMIT}. Every other failure
 * reaches the caller at once, since running the work again would fail the same
 * way.
 *
 * <p>The unit of work is run up to {@code maxAttempts} times, so it must be
 * safe to repeat: it reads what it needs inside the transaction it is given
 * and keeps no state of an attempt that was rolled back.
 *
 * <p>The {@code DataSource} the transactions are opened on must open
 * {@code SERIALIZABLE} transactions, by its own configuration or by the
 * server's {@code default_transaction_isolation}, for the database to report
 * the conflicts this loop retries.
 */
public final class RetryingTransactions {

    /**
     * Runs {@code work} in a transaction and returns its value, running it
     * again after a conflict until it succeeds or {@code maxAttempts} attempts
     * have been made, after which the conflict of the last attempt reaches the
     * caller.
     */
    public <T, X extends Exception> T inTransaction(
        JdbcTransactions transactions,
        int maxAttempts,
        TransactionCallback<T, X> work
    ) throws X {
        int attempt = 1;

        while (true) {
            try {
                return transactions.inTransaction(work);
            } catch (TransactionConflictException conflict) {
                if (attempt >= maxAttempts) {
                    throw conflict;
                }

                attempt++;
            }
        }
    }
}
