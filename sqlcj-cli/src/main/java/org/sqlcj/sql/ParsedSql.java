package org.sqlcj.sql;

import net.sf.jsqlparser.statement.Statement;

import java.util.List;

/**
 * Syntax-level result of parsing one query SQL source.
 *
 * @param statement    the parsed statement
 * @param parameters   the parameters compiled from the parsed {@code $N} and
 *                     {@code :name} placeholder tokens of the same source,
 *                     whose {@link SqlParameters#executableSql()} semantic
 *                     analysis writes the executed statement from
 * @param placeholders the placeholder occurrences of the same source in textual
 *                     order, one per placeholder the parse tree spells, at every
 *                     clause and nesting depth
 * @param wildcards    the wildcard select items of the same source, one per
 *                     item the parse tree spells, at every clause and nesting
 *                     depth, each with the span the item takes in
 *                     {@link SqlParameters#executableSql()}
 */
public record ParsedSql(
    Statement statement,
    SqlParameters parameters,
    List<PlaceholderOccurrence> placeholders,
    List<WildcardOccurrence> wildcards
) {

    public ParsedSql {
        placeholders = List.copyOf(placeholders);
        wildcards = List.copyOf(wildcards);
    }
}
