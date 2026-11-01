package org.sqlcj.runtime;

/**
 * Thrown when a statement left a foreign key unsatisfied, because it wrote a
 * reference to a row that does not exist or removed or changed a row another
 * row still references. PostgreSQL reports it as SQLSTATE {@code 23503}.
 *
 * <p>The underlying {@code SQLException} is the cause, and
 * {@link #sqlState()} reports its SQLSTATE.
 */
public final class ForeignKeyViolationException extends ConstraintViolationException {

    public ForeignKeyViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
