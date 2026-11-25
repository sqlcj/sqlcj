package org.sqlcj.generator;

import org.sqlcj.analysis.QueryColumn;

import java.util.List;
import java.util.Locale;

/**
 * One row record of the package, generated once from the group that first
 * returned it.
 */
record SharedRow(
    String groupName,
    String tableName,
    String typeName,
    List<QueryColumn> columns,
    List<String> componentNames
) {

    /**
     * Two rows are the same record when they generate the same row type for
     * the same table, compared as a case-insensitive filesystem compares
     * the table's generated file.
     */
    String identity() {
        return typeName + "\n" + tableName.toLowerCase(Locale.ROOT);
    }

    String portabilityKey() {
        return typeName.toLowerCase(Locale.ROOT);
    }
}
