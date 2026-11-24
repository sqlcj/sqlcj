package org.sqlcj.analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * The column scope of one query level: the sources that level resolves a
 * column reference against, the common table expressions in scope there,
 * and the level enclosing it, which is {@code null} for a query no
 * analyzed query encloses.
 *
 * <p>A predicate subquery is the level inside the level its predicate
 * stands in, so a column resolves in its own sources first and then in
 * each enclosing level outward, as PostgreSQL resolves a correlated
 * reference. A join condition's level is the sources of its own join group
 * up to and including its own joined source. The body of a
 * {@code LATERAL} source is the level inside a level of the sources
 * written before that source.
 */
record Scope(List<Source> sources, List<Source> commonTableExpressions, Scope enclosing) {

    /** A lone query level, which no analyzed query encloses. */
    static Scope of(List<Source> sources, List<Source> commonTableExpressions) {
        return new Scope(sources, commonTableExpressions, null);
    }

    /** A lone query level that declares no common table expression. */
    static Scope of(List<Source> sources) {
        return of(sources, List.of());
    }

    /**
     * The sources every level makes visible, from the innermost level
     * outward, which are the sources a column reference may name. A join
     * group's merged source exposes no name of its own, so it is none of
     * them.
     */
    List<Source> visibleSources() {
        List<Source> visible = new ArrayList<>();

        for (Scope level = this; level != null; level = level.enclosing()) {
            level.sources().stream()
                .filter(source -> !source.merged())
                .forEach(visible::add);
        }

        return visible;
    }
}
