package org.sqlcj.analysis;

import net.sf.jsqlparser.statement.select.SelectItem;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The columns each analyzed wildcard of one statement selects, written as
 * the executed statement lists them, recorded against the select item the
 * SQL parser reported, which is how the expansion of a clause's analysis
 * meets the wildcard span that parser reported.
 *
 * <p>A wildcard this analyzer never expands — one of a predicate subquery,
 * whose items reach the database as written — is recorded by no clause and
 * therefore stays in the executed statement as the query writes it.
 */
final class Wildcards {

    /** The columns one wildcard selects, by its own select item. */
    private final Map<SelectItem<?>, String> columnLists = new IdentityHashMap<>();

    /**
     * Records the columns one wildcard select item selects. The same item
     * is analyzed twice where two clauses of one statement read the same
     * query, so recording it again states the same columns, and stating
     * other columns for it is a defect of this analyzer.
     */
    void record(SelectItem<?> selectItem, String columnList) {
        String recorded = columnLists.putIfAbsent(selectItem, columnList);

        if (recorded != null && !recorded.equals(columnList)) {
            throw new IllegalStateException(
                "Wildcard %s was expanded as both '%s' and '%s'."
                    .formatted(selectItem, recorded, columnList)
            );
        }
    }

    /**
     * The columns one wildcard select item selects, and {@code null} when no
     * clause of the statement expanded it.
     */
    String columnList(SelectItem<?> selectItem) {
        return columnLists.get(selectItem);
    }
}
