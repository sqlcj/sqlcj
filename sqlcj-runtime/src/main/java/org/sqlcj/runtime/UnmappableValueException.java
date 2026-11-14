package org.sqlcj.runtime;

import org.jspecify.annotations.Nullable;

/**
 * Thrown when a value the database returned has no representation in the
 * generated Java API. Today one value is detected: a label of an enum type the
 * generated enum does not hold, which means the database declares a label the
 * schema sqlcj generated from does not.
 *
 * <p>{@link #type()} names the database type of the value and
 * {@link #value()} is the value itself, as text, so an application can report
 * both without reading the message. The generated enum's {@code fromLabel}
 * raises this type while a row is mapped, and {@link JdbcQueryExecutor}
 * rethrows it with the message naming the query and its repository, the same
 * type and value, and the original as its cause.
 *
 * <p>No database reported the failure, so {@link #sqlState()} is {@code null}.
 */
public final class UnmappableValueException extends QueryExecutionException {

    private final String type;

    private final String value;

    public UnmappableValueException(
        String message,
        String type,
        String value,
        @Nullable Throwable cause
    ) {
        super(message, cause);

        this.type = type;
        this.value = value;
    }

    /** The database type of the value that has no representation. */
    public String type() {
        return type;
    }

    /** The value itself, as the text the database returned for it. */
    public String value() {
        return value;
    }
}
