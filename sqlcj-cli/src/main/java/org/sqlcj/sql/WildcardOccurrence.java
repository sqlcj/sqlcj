package org.sqlcj.sql;

import net.sf.jsqlparser.statement.select.SelectItem;

/**
 * One wildcard select item of a parsed SQL source — a {@code *} or a
 * {@code qualifier.*}, in a select list or in a {@code RETURNING} clause — and
 * the span the whole item takes in the compiled SQL.
 *
 * <p>An occurrence is syntax, not an expansion: it reports which item is a
 * wildcard and where it stands, and leaves to semantic analysis which columns
 * the item selects and whether the executed statement lists them.
 *
 * @param item  the select item the parser produced, whose expression is a
 *              wildcard, which identifies this occurrence
 * @param begin the offset in {@link SqlParameters#executableSql()} of the first
 *              character of the item, its qualifier and any alias included
 * @param end   the offset in {@link SqlParameters#executableSql()} directly
 *              after the last character of the item
 */
public record WildcardOccurrence(
    SelectItem<?> item,
    int begin,
    int end
) {
}
