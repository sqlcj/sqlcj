package org.sqlcj.analysis;

import java.util.List;

/**
 * The analyzed facts of one query that produces rows — a plain
 * {@code SELECT} or a set operation of them: the name the query writes for
 * its first source, its projection in selected order, and the table whose
 * complete row it returns, which is {@code null} for a query that returns a
 * result of its own.
 */
record AnalyzedSelect(
    String table,
    ResolvedProjection projection,
    String rowTable
) {

    /** The columns this query exposes as a derived source. */
    List<org.sqlcj.schema.Column> columns() {
        return projection.columns();
    }
}
