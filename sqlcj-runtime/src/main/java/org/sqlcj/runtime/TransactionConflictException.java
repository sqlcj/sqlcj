package org.sqlcj.runtime;

/**
 * Thrown when the database could not run the transaction concurrently with
 * another one and rolled it back: a serialization failure, which PostgreSQL
 * reports as SQLSTATE {@code 40001}, or a deadlock, which it reports as
 * {@code 40P01}.
 *
 * <p>The transaction itself is gone, so the work is run again from its
 * beginning, by the application, to succeed; the runtime retries nothing of
 * its own. PostgreSQL reports such a conflict either while a statement runs or
 * at the {@code COMMIT}, depending on the isolation level, and both are this
 * one type.
 *
 * <p>The underlying {@code SQLException} is the cause, and
 * {@link #sqlState()} reports its SQLSTATE.
 */
public final class TransactionConflictException extends QueryExecutionException {

    public TransactionConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
