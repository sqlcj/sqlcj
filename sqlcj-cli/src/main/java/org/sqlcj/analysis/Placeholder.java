package org.sqlcj.analysis;

import net.sf.jsqlparser.expression.Expression;

/**
 * One placeholder occurrence, resolved to the logical parameter it binds
 * and, for a named placeholder, to the name that parameter carries and to
 * the parameter group it belongs to. The placeholder expression identifies
 * the occurrence, which is how a clause's analysis meets the placeholder
 * occurrences the parser reported.
 *
 * @param name  the field name of a group placeholder, the name of a plain
 *              named placeholder, and {@code null} for a {@code $N} one
 * @param group the group of a group placeholder, and {@code null} for every
 *              other placeholder
 */
record Placeholder(Expression expression, int index, String name, String group) {

    /**
     * The name of the parameter this occurrence binds, which a named
     * placeholder states itself and every other placeholder takes from the
     * column or clause it belongs to.
     */
    String nameOr(String clauseName) {
        return name == null
            ? clauseName
            : name;
    }

    /** The placeholder as the query spells it. */
    String spelled() {
        if (name == null) {
            return "$" + index;
        }

        return group == null
            ? QueryAnalyzer.NAME_PREFIX + name
            : QueryAnalyzer.NAME_PREFIX + group + QueryAnalyzer.GROUP_SEPARATOR + name;
    }
}
