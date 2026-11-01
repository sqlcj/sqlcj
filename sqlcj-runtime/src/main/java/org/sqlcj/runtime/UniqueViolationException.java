package org.sqlcj.runtime;

/**
 * Thrown when a statement wrote a row a unique constraint or a primary key
 * already holds, which PostgreSQL reports as SQLSTATE {@code 23505}.
 *
 * <p>The underlying {@code SQLException} is the cause, and
 * {@link #sqlState()} reports its SQLSTATE.
 */
public final class UniqueViolationException extends ConstraintViolationException {

    public UniqueViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
