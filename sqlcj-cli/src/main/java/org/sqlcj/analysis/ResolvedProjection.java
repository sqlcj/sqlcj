package org.sqlcj.analysis;

import java.util.List;

/**
 * One analyzed projection in selected order: the columns it expands to,
 * which are the columns a derived source exposes, and the result components
 * of a top-level read, where a wildcard of a schema table is one embedded
 * row, nullable when its source is null-extended.
 */
record ResolvedProjection(
    List<org.sqlcj.schema.Column> columns,
    List<QueryColumn> resultColumns
) {
}
