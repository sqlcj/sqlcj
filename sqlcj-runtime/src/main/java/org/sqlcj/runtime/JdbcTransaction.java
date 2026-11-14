package org.sqlcj.runtime;

import java.sql.Connection;

/**
 * The one transaction a {@link JdbcTransactions} call runs its unit of work in.
 *
 * <p>It carries the application's {@link QueryExecutor}, built for this
 * transaction over its connection, and that {@link Connection} itself. The unit
 * of work binds the generated repositories it needs over {@link #executor()}
 * and runs hand-written JDBC code on {@link #connection()}, so every operation
 * takes part in the same transaction:
 *
 * <pre>{@code
 * Long id = transactions.inTransaction(transaction -> {
 *     AuthorRepository authors = new AuthorRepository(transaction.executor());
 *
 *     Author author = authors.createAuthor("Jane Austen", "English novelist");
 *
 *     try (
 *         PreparedStatement statement = transaction.connection().prepareStatement(
 *             "INSERT INTO author_audit (author_id, action) VALUES (?, ?)"
 *         )
 *     ) {
 *         statement.setLong(1, author.id());
 *         statement.setString(2, "created");
 *         statement.executeUpdate();
 *     }
 *
 *     return author.id();
 * });
 * }</pre>
 *
 * <p>The executor is the one the function given to
 * {@link JdbcTransactions#JdbcTransactions(javax.sql.DataSource, java.util.function.Function)}
 * built from the connection, so an application that decorates its executor for
 * logging, metrics, or tracing keeps that decoration inside the transaction. By
 * default it is a {@link JdbcQueryExecutor} over the connection.
 *
 * <p>The connection has auto-commit disabled, and the unit of work never
 * commits, rolls back, closes, or reconfigures it; {@link JdbcTransactions}
 * does that around it. Neither the executor nor the connection is used after
 * the unit of work returns.
 *
 * @param executor   the application's executor, bound to this transaction's
 *                   connection
 * @param connection the connection this transaction runs on
 */
public record JdbcTransaction(QueryExecutor executor, Connection connection) {
}
