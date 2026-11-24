package org.sqlcj.analysis;

import java.util.List;

/**
 * One source a bare {@code *} expands, with the columns it reads from it:
 * the merged columns of a join group, the columns one member of such a
 * group still exposes, or every column of a source in a group that merges
 * none.
 *
 * <p>A merging group's columns are flat result columns, as PostgreSQL
 * lists them, while a source of a group that merges none keeps the
 * embedded row of its own table.
 */
record WildcardSource(
    Source source,
    List<org.sqlcj.schema.Column> columns,
    boolean flat
) {

    /** One source of a group that merges no column at all. */
    static WildcardSource of(Source source) {
        return new WildcardSource(source, source.table().columns(), false);
    }

    /** One source of a merging group, whose columns are listed flat. */
    static WildcardSource flat(Source source, List<org.sqlcj.schema.Column> columns) {
        return new WildcardSource(source, columns, true);
    }
}
