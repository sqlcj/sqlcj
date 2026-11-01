package org.sqlcj.runtime;

/**
 * Thrown when the statement was cancelled before it finished, which PostgreSQL
 * reports as SQLSTATE {@code 57014}.
 *
 * <p>PostgreSQL gives that code to every cancelled statement alike: one a
 * {@code statement_timeout} or {@code java.sql.Statement.setQueryTimeout}
 * ended, including a statement that was waiting for a row lock, and one a
 * cancel request ended. A {@code lock_timeout}, on the other hand, reports
 * {@code 55P03}, which stays a plain {@link QueryExecutionException}.
 *
 * <p>The underlying {@code SQLException} is the cause, and
 * {@link #sqlState()} reports its SQLSTATE.
 */
public final class QueryTimeoutException extends QueryExecutionException {

    public QueryTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
