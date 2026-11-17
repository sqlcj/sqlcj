package org.sqlcj.sql;

import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.Expression;

/**
 * One placeholder token of a parsed SQL source, as the parse tree spells it.
 *
 * <p>An occurrence is syntax, not a type: it reports where a placeholder stands
 * and whether the query casts it, and leaves to semantic analysis which
 * parameter it binds and of which type.
 *
 * @param placeholder the placeholder expression the parser produced, which
 *                    identifies this occurrence
 * @param cast        the cast whose direct operand the placeholder is, which is
 *                    the innermost cast of a chain such as
 *                    {@code :x::text::int}, and {@code null} when the
 *                    placeholder is not cast directly
 * @param enclosing   where the placeholder stands, written as the query writes
 *                    it: the smallest enclosing expression the parse tree
 *                    reports, on one line, or the keyword of the clause the
 *                    placeholder stands alone in, in upper case
 * @param nullTested  whether the query tests this occurrence for null, which
 *                    gives a {@code null} bound at it a meaning of its own: it
 *                    is the operand of {@code IS [NOT] NULL}, either side of
 *                    {@code IS [NOT] DISTINCT FROM}, or an argument of an
 *                    unqualified {@code COALESCE} or {@code NULLIF}. The
 *                    placeholder itself, a cast chain of it, and a
 *                    parenthesized one count as that operand or argument,
 *                    while an occurrence inside any other expression, such as
 *                    the {@code lower(:x)} of {@code lower(:x) IS NULL}, does
 *                    not
 * @param call        the call this occurrence is a direct argument of, which is
 *                    a plain function call, a {@code trim}, or a window call,
 *                    and {@code null} when it is the direct argument of none: a
 *                    cast or a parenthesized argument states the argument
 *                    itself, and a window's {@code PARTITION BY} or
 *                    {@code ORDER BY} expression is no argument of the call.
 *                    Where the parse tree holds a window call's arguments twice,
 *                    the window call is reported rather than the plain call
 *                    below it
 * @param argument    the zero-based position this occurrence takes among the
 *                    arguments its call writes, and {@code -1} when it is the
 *                    direct argument of no call
 */
public record PlaceholderOccurrence(
    Expression placeholder,
    CastExpression cast,
    String enclosing,
    boolean nullTested,
    Expression call,
    int argument
) {

    /** The position reported for an occurrence that is no call's argument. */
    public static final int NO_ARGUMENT = -1;
}
