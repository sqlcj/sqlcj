package org.sqlcj.runtime;

/**
 * Thrown when a statement wrote a row a {@code CHECK} constraint rejects,
 * which PostgreSQL reports as SQLSTATE {@code 23514}.
 *
 * <p>The underlying {@code SQLException} is the cause, and
 * {@link #sqlState()} reports its SQLSTATE.
 */
public final class CheckViolationException extends ConstraintViolationException {

    public CheckViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
