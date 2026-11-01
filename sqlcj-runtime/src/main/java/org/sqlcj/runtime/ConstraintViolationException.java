package org.sqlcj.runtime;

/**
 * Thrown when a statement violated a constraint of the schema, which SQLSTATE
 * class {@code 23} reports.
 *
 * <p>A violation the database names precisely raises one of the four
 * subclasses {@link UniqueViolationException},
 * {@link ForeignKeyViolationException}, {@link NotNullViolationException}, and
 * {@link CheckViolationException} instead. This class itself is raised for
 * every other code of class {@code 23}, such as PostgreSQL's exclusion
 * violation {@code 23P01}, so code that catches it covers every constraint
 * violation, however precisely a database reports it.
 *
 * <p>The exception carries no constraint name: the JDBC API does not expose
 * one, and the runtime depends on no driver. The underlying
 * {@code SQLException} is the cause, and {@link #sqlState()} reports its
 * SQLSTATE.
 */
public class ConstraintViolationException extends QueryExecutionException {

    public ConstraintViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
