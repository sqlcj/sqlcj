package org.sqlcj.runtime;

/**
 * A unit of work {@link JdbcTransactions#useTransaction} runs on one connection
 * and that returns no value.
 *
 * <p>The callback receives the {@link JdbcTransaction} it runs in, whose
 * connection has auto-commit disabled, and follows the same rules as
 * {@link TransactionCallback}: it binds the generated repositories it needs
 * over {@link JdbcTransaction#executor()}, may run hand-written JDBC code on
 * {@link JdbcTransaction#connection()}, and never commits, rolls back, closes,
 * or reconfigures the connection itself.
 *
 * <p>A normal return commits the transaction. Any exception or error rolls the
 * transaction back and is rethrown unchanged.
 *
 * @param <X> the checked exception the unit of work may throw, inferred as
 *            {@link RuntimeException} when it throws none
 */
@FunctionalInterface
public interface TransactionConsumer<X extends Exception> {

    void accept(JdbcTransaction transaction) throws X;
}
