package org.sqlcj.parser;

import java.util.List;

/**
 * One named query.
 *
 * @param line       the one-based line of the query header in its source, or
 *                   {@code 0} when the source line is unknown
 * @param comment    the comment lines written directly under the query's
 *                   header, each without its {@code --} and one following
 *                   space, in source order
 * @param writtenSql the query's SQL as the source writes it, without its
 *                   comment lines and without the blank lines before its first
 *                   and after its last line, joined by {@code \n}
 */
public record Query(
    String name,
    QueryType type,
    String sql,
    int line,
    List<String> comment,
    String writtenSql
) {

    public Query(String name, QueryType type, String sql) {
        this(name, type, sql, 0);
    }

    public Query(String name, QueryType type, String sql, int line) {
        this(name, type, sql, line, List.of(), sql);
    }
}
