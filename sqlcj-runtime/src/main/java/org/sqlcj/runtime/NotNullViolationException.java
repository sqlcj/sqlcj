package org.sqlcj.runtime;

/**
 * Thrown when a statement wrote SQL {@code NULL} into a column declared
 * {@code NOT NULL}, which PostgreSQL reports as SQLSTATE {@code 23502}.
 *
 * <p>The underlying {@code SQLException} is the cause, and
 * {@link #sqlState()} reports its SQLSTATE.
 */
public final class NotNullViolationException extends ConstraintViolationException {

    public NotNullViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
