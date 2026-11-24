package org.sqlcj.analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * One query source and the name it exposes to column references, which is
 * its alias when present and otherwise its table name. A null-extended
 * source contributes no row when its join finds no match, so every column
 * read from it is nullable regardless of its schema declaration, and a
 * wildcard of one embeds a row that may itself be absent.
 *
 * <p>A derived source — a common table expression or a subquery source —
 * carries the result columns of its own {@code SELECT} under its exposed
 * name instead of a schema table's columns.
 *
 * <p>A name of a recursive {@code WITH} list that no source there may read
 * stands in the scope as an unreadable source instead: it carries the
 * diagnostic a source naming it is rejected with, and no table at all, so
 * that the name shadows the schema table of that name exactly as a
 * readable clause does.
 *
 * <p>A join group whose joins merge columns carries one merged source of
 * its own beside its members: it holds the group's merged columns, names
 * the members it merges, in {@code groupSources}, and exposes no name a
 * qualifier can reach. Each member records the names a join merged away
 * from it, in {@code mergedAway}, which an unqualified reference and a
 * bare {@code *} read from the merged source instead.
 *
 * <p>{@code sqlName} is the name PostgreSQL stores for the name this source
 * exposes, which an expanded wildcard writes as the qualifier of every
 * column it reads through this source. A merged source and an unreadable
 * name expose no qualifier at all, so neither carries one.
 */
record Source(
    String name,
    String sqlName,
    org.sqlcj.schema.Table table,
    boolean derived,
    boolean nullable,
    String unreadable,
    List<String> groupSources,
    List<String> mergedAway
) {

    /** One readable source, which every analyzed query resolves against. */
    Source(
        String name,
        String sqlName,
        org.sqlcj.schema.Table table,
        boolean derived,
        boolean nullable
    ) {
        this(name, sqlName, table, derived, nullable, null);
    }

    /** One source under the diagnostic or the absence of one it carries. */
    private Source(
        String name,
        String sqlName,
        org.sqlcj.schema.Table table,
        boolean derived,
        boolean nullable,
        String unreadable
    ) {
        this(name, sqlName, table, derived, nullable, unreadable, List.of(), List.of());
    }

    /**
     * One name in scope that no source may read, under the diagnostic that
     * states why.
     */
    static Source unreadable(String name, String rejection) {
        return new Source(name, null, null, true, false, rejection);
    }

    /**
     * The merged source of one join group, which holds the columns its
     * joins merged and is named by the members it merges, joined with
     * {@code JOIN}, because the merged column belongs to no one of them.
     * Its own columns carry the nullability its joins resolved, so no join
     * that merged them null-extends it.
     */
    static Source merged(
        List<org.sqlcj.schema.Column> columns,
        List<String> groupSources
    ) {
        String name = String.join(" JOIN ", groupSources);

        return new Source(
            name,
            null,
            new org.sqlcj.schema.Table(name, columns, List.of()),
            true,
            false,
            null,
            List.copyOf(groupSources),
            List.of()
        );
    }

    /**
     * The same source read as a row that may be absent, which an outer
     * join whose other side finds no match contributes. A source already
     * read that way stays so, because a later join cannot restore a row an
     * earlier one null-extended.
     */
    Source nullExtended() {
        return new Source(
            name,
            sqlName,
            table,
            derived,
            true,
            unreadable,
            groupSources,
            mergedAway
        );
    }

    /**
     * The same merged source, named by one more member of its join group,
     * which a join that merges no column adds to the group this source
     * holds. Its merged columns stay as the joins that merged them
     * resolved them, and so does the null extension a later join read it
     * under.
     */
    Source grouping(String groupSource) {
        List<String> names = new ArrayList<>(groupSources);

        names.add(groupSource);

        Source grouped = merged(table.columns(), names);

        return nullable
            ? grouped.nullExtended()
            : grouped;
    }

    /**
     * The same source with the names one more join merged away from it,
     * which it exposes to no unqualified reference and to no bare
     * {@code *}.
     */
    Source merging(List<String> names) {
        List<String> merged = new ArrayList<>(mergedAway);

        merged.addAll(names);

        return new Source(
            name,
            sqlName,
            table,
            derived,
            nullable,
            unreadable,
            groupSources,
            List.copyOf(merged)
        );
    }

    /** Reports whether this source is the merged source of a join group. */
    boolean merged() {
        return !groupSources.isEmpty();
    }

    /**
     * Reports whether a join merged one of this source's columns away, so
     * that the merged source of its group exposes that name instead.
     */
    boolean mergesAway(String columnName) {
        return mergedAway.stream()
            .anyMatch(merged -> merged.equalsIgnoreCase(columnName));
    }

    /**
     * The schema's declared name of the table whose complete row this
     * source contributes, and {@code null} for a derived source, whose
     * columns are specific to the query that declares it.
     */
    String rowTable() {
        return derived
            ? null
            : table.name();
    }
}
