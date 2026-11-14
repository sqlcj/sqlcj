package org.sqlcj.runtime;

import org.jspecify.annotations.Nullable;

import java.sql.SQLException;

/**
 * Thrown when a generated operation failed. The message names the query and
 * the generated repository it belongs to, and the {@code SQLException} the
 * JDBC driver raised is the cause.
 *
 * <p>Every runtime failure of a generated operation is of this type, so an
 * application that catches it catches them all. A failure a SQLSTATE names is
 * one of its subclasses, each named by what went wrong rather than by the
 * database that reported it, so an application catches the failure it expects
 * without unwrapping the {@code SQLException}:
 *
 * <ul>
 * <li>{@link QueryCardinalityException}, whose cause is {@code null}</li>
 * <li>{@link ConstraintViolationException}, with
 * {@link UniqueViolationException}, {@link ForeignKeyViolationException},
 * {@link NotNullViolationException}, and {@link CheckViolationException} under
 * it</li>
 * <li>{@link TransactionConflictException}</li>
 * <li>{@link QueryTimeoutException}</li>
 * <li>{@link UnmappableValueException}, which no SQLSTATE names and whose
 * {@link #sqlState()} is {@code null}</li>
 * </ul>
 *
 * <p>A failure no subclass covers, and every failure of acquiring or releasing
 * a connection, is this type itself. {@link #sqlState()} reports the SQLSTATE
 * behind it, so a rarer code stays reachable all the same.
 *
 * <p>Two causes are raised by the runtime rather than by a driver. A column the
 * schema sqlcj generated from states {@code NOT NULL} read as SQL
 * {@code NULL}, which {@link NotNullColumn} reports as a {@code SQLException}
 * without a SQLSTATE. It is this type itself, and {@link #sqlState()} is
 * {@code null} for it. An unchecked failure of a row mapper is this type
 * itself as well, with the mapper's own exception as its cause and the message
 * naming the query and its repository, unless the mapper read a value the
 * generated API cannot represent, which is an
 * {@link UnmappableValueException}.
 */
public class QueryExecutionException extends RuntimeException {

    public QueryExecutionException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }

    /**
     * The SQLSTATE the driver reported for this failure, which is the
     * {@code SQLState} of the {@code SQLException} that caused it, or
     * {@code null} when the cause is no {@code SQLException}, as for a
     * {@link QueryCardinalityException}, or when the driver reported none.
     */
    public @Nullable String sqlState() {
        return getCause() instanceof SQLException cause ? cause.getSQLState() : null;
    }
}
