package org.sqlcj.parser;

import java.util.List;

/**
 * The queries one source states: the ones the parser accepted and the ones it
 * rejected, each in the order the source writes them.
 *
 * <p>A rejected query does not end its source, so a run reports every rejected
 * query of it at once.
 */
public record ParsedQueries(
    List<Query> queries,
    List<QueryRejection> rejections
) {

    public ParsedQueries(List<Query> queries, List<QueryRejection> rejections) {
        this.queries = List.copyOf(queries);
        this.rejections = List.copyOf(rejections);
    }
}
