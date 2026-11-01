package org.sqlcj.parser;

/**
 * One query of a source the parser rejected, which therefore states no query.
 *
 * <p>A rejected query is identified by its header line alone, because a header
 * the parser cannot read claims no name.
 *
 * @param line the one-based line of the rejected query's header in its source
 * @param reason the corrective fact, stated without a location
 */
public record QueryRejection(int line, String reason) {
}
