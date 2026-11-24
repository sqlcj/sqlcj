package org.sqlcj.analysis;

/**
 * One column one join merges: the merged column itself, named as the left
 * side of the join declares it, and the exposed name of the member that
 * side reads it through, which is {@code null} for a column the group's
 * merged source already holds.
 */
record JoinColumn(org.sqlcj.schema.Column column, String leftSource) {

    /** The name the merged column exposes. */
    String name() {
        return column.name();
    }
}
