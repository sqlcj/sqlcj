package org.sqlcj.analysis;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.TrimFunction;
import net.sf.jsqlparser.schema.MultiPartName;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.LateralSubSelect;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.TableFunction;
import org.sqlcj.diagnostic.Suggestions;
import org.sqlcj.schema.ColumnType;
import org.sqlcj.schema.Schema;
import org.sqlcj.schema.View;
import org.sqlcj.schema.parser.SchemaNamespace;
import org.sqlcj.sql.ParsedSql;
import org.sqlcj.sql.SqlParser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Resolves the sources one query reads and the columns a reference, a
 * wildcard, and a join read through them, and states the rejection of a
 * source and of a join outside the supported subset. It reads the analyzer
 * it belongs to for the facts the rest of one query's analysis resolves.
 */
final class SourceResolver {

    /**
     * The rejection of a bare {@code *} in a {@code SELECT} without
     * {@code FROM}, which has no source for the wildcard to expand, so it names
     * the items such a {@code SELECT} states its result with.
     */
    private static final String SOURCELESS_WILDCARD_REJECTION = """
        SELECT * without FROM selects no columns; list each value with a cast and an alias, \
        such as SELECT now()::timestamptz AS now.""";

    /**
     * The rejection of a column the schema recorded without a mapped type that
     * a wildcard expands to, which names the column and the recorded type as a
     * direct column's rejection does. The query never wrote the column, so the
     * rejection also states that the wildcard selected it and names the fix
     * that leaves it out.
     */
    private static final String WILDCARD_COLUMN_TYPE_REJECTION = """
        Column '%s' has unsupported type %s; a wildcard selects it, so list the columns the \
        query needs instead.""";

    /**
     * The rejection of a {@code CROSS JOIN} that carries a {@code USING} list,
     * which PostgreSQL does not have although the parser reads it, naming the
     * join that merges join columns.
     */
    private static final String CROSS_JOIN_USING_REJECTION = """
        CROSS JOIN takes no USING list; write JOIN ... USING.""";

    /**
     * The rejection of a {@code NATURAL} join that carries an {@code ON}
     * condition or a {@code USING} list, which PostgreSQL does not have
     * although the parser reads it, because {@code NATURAL} states the join
     * columns itself.
     */
    private static final String NATURAL_JOIN_CONDITION_REJECTION = """
        NATURAL JOIN takes no ON condition or USING list.""";

    /**
     * The rejection of a {@code USING} column the left side of its join does
     * not expose, which is the group's output before that join.
     */
    private static final String USING_LEFT_COLUMN_REJECTION = """
        USING column %s does not exist in the left side of the join.""";

    /**
     * The rejection of a {@code USING} column the joined source does not
     * expose.
     */
    private static final String USING_RIGHT_COLUMN_REJECTION = """
        USING column %s does not exist in the right side of the join.""";

    /**
     * The rejection of a join column the left side of its join exposes more
     * than once, which names no single column to merge, as PostgreSQL also
     * rejects it.
     */
    private static final String REPEATED_JOIN_COLUMN_REJECTION = """
        Join column %s appears more than once in the left side of the join.""";

    /**
     * The rejection of one join column whose sides state types this subset
     * resolves to no single type, which names the column and both types as
     * PostgreSQL spells them. A merged column takes no cast, so the named fix
     * is the condition written with {@code ON}.
     */
    private static final String JOIN_COLUMN_TYPE_REJECTION = """
        Join column %s has types %s and %s; write the join condition with ON and a cast.""";

    /**
     * The rejection of a source qualified with a schema other than the one its
     * query group models, which names the source as the query writes it,
     * without its SQL identifier delimiters, the schema it names, the schema
     * the group models, and the setting that would model the named schema
     * instead.
     */
    private static final String OTHER_SCHEMA_SOURCE_REJECTION = """
        %s belongs to schema %s, which this query group does not model; \
        it models schema %s. Set sql[].namespace to %s to model that schema \
        instead.""";

    /**
     * The rejection of a source that names a view or a materialized view whose
     * defining query sqlcj cannot analyze, which names the view as the query
     * writes it, without its SQL identifier delimiters and without the modeled
     * schema qualifier, its kind as PostgreSQL spells it, and the reason the
     * query cannot be analyzed, which is the failure's own text.
     */
    private static final String UNANALYZABLE_VIEW_REJECTION = """
        %s is a %s whose query sqlcj cannot analyze: %s""";

    /**
     * The reason a recursive view's query is one sqlcj cannot analyze, which is
     * the whole of its defining query rather than a failure of one clause.
     */
    private static final String RECURSIVE_VIEW_REASON = """
        sqlcj does not analyze a recursive view.""";

    /**
     * The reason the query of a view that writes a placeholder is one sqlcj
     * cannot analyze, which PostgreSQL does not accept in a view at all.
     */
    private static final String VIEW_PLACEHOLDER_REASON = """
        sqlcj does not analyze a view whose query writes a placeholder.""";

    /**
     * The rejection of a write whose target names a view or a materialized view
     * the schema records, which names the view as the query writes it, without
     * its SQL identifier delimiters and without the modeled schema qualifier,
     * and its kind as PostgreSQL spells it. sqlcj reads a view as a source, so
     * the named fix is the table the view reads.
     */
    private static final String VIEW_WRITE_REJECTION = """
        %s is a %s, which sqlcj reads but does not write through; write to the table it reads \
        instead.""";

    /**
     * The rejection of a source that calls {@code unnest}, which names the
     * function as the query writes it. sqlcj models no function source at all,
     * so the named fixes are the two shapes a set of rows is written in
     * instead: a batch of {@code VALUES} rows for a write, and {@code = ANY}
     * for a read that matches a list.
     */
    private static final String FUNCTION_SOURCE_REJECTION = """
        %s(...) is a function source, which sqlcj does not model; insert many rows with a \
        :batchexec INSERT ... VALUES query, or match a list with id = ANY(:ids).""";

    /**
     * The rejection of a source that calls {@code generate_series}, which names
     * the function as the query writes it. sqlcj models no function source at
     * all, so the named fix is the recursive {@code WITH} query that produces
     * the same series: both of its terms state {@code integer}, which
     * PostgreSQL requires of a recursive term and sqlcj reads the column's type
     * from, and its bounds are literals, so the example analyzes and runs as
     * the diagnostic writes it.
     */
    private static final String SERIES_FUNCTION_SOURCE_REJECTION = """
        %s(...) is a function source, which sqlcj does not model; write the series as a \
        recursive WITH query, such as WITH RECURSIVE series (n) AS (SELECT 1::integer UNION ALL \
        SELECT (n + 1)::integer FROM series WHERE n < 10) SELECT n FROM series.""";

    /**
     * The rejection of a source that calls any other function, which names the
     * function as the query writes it. sqlcj models no function source at all,
     * and the two rejections above name the fix of the one function each of
     * them states, so this one states only what a source may be instead.
     */
    private static final String UNMODELED_FUNCTION_SOURCE_REJECTION = """
        %s(...) is a function source, which sqlcj does not model; a source must be a table, a \
        view, a WITH query, or a subquery.""";

    /** The rejection of a source of a write's {@code FROM} or {@code USING}. */
    private static final String WRITE_SOURCE_REJECTION = "Only table sources are supported.";

    /**
     * The rejection of a subquery source without an alias, which exposes no
     * name its columns could be read through.
     */
    private static final String UNALIASED_FROM_SUBQUERY_REJECTION = """
        A subquery in FROM requires an alias, such as (SELECT ...) AS recent.""";

    /**
     * The rejection of a column list on a subquery alias, which renames the
     * columns of the subquery's {@code SELECT} by position.
     */
    private static final String DERIVED_SOURCE_COLUMN_LIST_REJECTION = """
        A column list on %s is not supported; alias the columns in its SELECT instead.""";

    /**
     * The rejection of a derived source two of whose columns expose one name,
     * which makes a reference to that name ambiguous.
     */
    private static final String DERIVED_SOURCE_COLUMN_NAME_REJECTION = """
        Derived source %s has more than one column named %s; give each a distinct alias.""";

    /** The analysis whose query these sources are resolved for. */
    private final QueryAnalyzer analyzer;

    /**
     * Parses the defining query of a recorded view, so that a view's query and
     * a user's query are read by one SQL parser.
     */
    private final SqlParser sqlParser = new SqlParser();

    SourceResolver(QueryAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    /**
     * The rejection one function source states, which follows the function the
     * diagnostic names, the first of a {@code ROWS FROM} list: {@code unnest}
     * and {@code generate_series} each name a fix of their own, and every other
     * function, a qualified or quoted name included, states what a source may
     * be instead.
     */
    private String functionSourceRejection(Function function) {
        return switch (analyzer.builtinFunction(function)) {
            case "unnest" -> FUNCTION_SOURCE_REJECTION;
            case "generate_series" -> SERIES_FUNCTION_SOURCE_REJECTION;
            default -> UNMODELED_FUNCTION_SOURCE_REJECTION;
        };
    }

    /**
     * Resolves one query source: a subquery in the clause, a source that names
     * a common table expression in scope, which reads that expression rather
     * than the schema table of the same name as PostgreSQL resolves it, a
     * table of the schema, or a view the schema records, which is resolved
     * after the table of its name.
     *
     * <p>{@code lateral} is the level a {@code LATERAL} subquery's body reads
     * as the one enclosing it, which the clause resolving this source states;
     * every other subquery source sees no source of the query around it, so it
     * is analyzed with no enclosing level at all.
     *
     * <p>Every source position reaches this method, so a source that calls a
     * function is rejected here with a message of its own, naming the function
     * and the fix that function has; every other source outside the subset
     * keeps the rejection its clause states.
     */
    private Source resolveSource(
        FromItem fromItem,
        String unsupportedSourceRejection,
        Analysis analysis,
        List<Source> commonTableExpressions,
        boolean nullable,
        Scope lateral
    ) {
        if (fromItem instanceof ParenthesedSelect subquery) {
            return toSubquerySource(
                subquery,
                analysis,
                commonTableExpressions,
                nullable,
                fromItem instanceof LateralSubSelect
                    ? lateral
                    : null
            );
        }

        if (fromItem instanceof TableFunction tableFunction) {
            Function function = tableFunction.getFunctions().get(0);

            throw new UnsupportedOperationException(
                functionSourceRejection(function).formatted(function.getName())
            );
        }

        if (!(fromItem instanceof Table table)) {
            throw new UnsupportedOperationException(unsupportedSourceRejection);
        }

        Source commonTableExpression = findCommonTableExpression(table, commonTableExpressions);

        if (commonTableExpression != null) {
            requireReadableCommonTableExpression(commonTableExpression);

            return toDerivedSource(
                table.getAlias() == null
                    ? commonTableExpression.name()
                    : table.getAlias().getUnquotedName(),
                sourceSqlName(table),
                commonTableExpression.table().columns(),
                nullable
            );
        }

        View view = recordedView(table, analysis.schema());

        if (view != null) {
            return toViewSource(table, view, nullable);
        }

        return toSource(table, analysis.schema(), nullable);
    }

    /**
     * The view the schema records under the name one source writes, and
     * {@code null} where it records none, where the schema models a table of
     * that name, which a source resolves first, and where the name is qualified
     * with a schema the group does not model, which keeps the rejection its
     * qualifier states.
     */
    private View recordedView(Table table, Schema schema) {
        if (!new SchemaNamespace(schema.namespace()).isModeled(table)) {
            return null;
        }

        String name = table.getUnquotedName();

        if (findTable(schema, name).isPresent()) {
            return null;
        }

        return schema.views().stream()
            .filter(view -> view.name().equalsIgnoreCase(name))
            .findFirst()
            .orElse(null);
    }

    /**
     * Resolves a source that names a recorded view into the derived source its
     * own defining query exposes, under the name the query exposes it as: its
     * alias where it writes one and the view's written name otherwise, as a
     * table source is exposed.
     */
    private Source toViewSource(Table table, View view, boolean nullable) {
        return toDerivedSource(
            table.getAlias() == null
                ? table.getUnquotedName()
                : table.getAlias().getUnquotedName(),
            sourceSqlName(table),
            analyzedViewColumns(table.getUnquotedName(), view),
            nullable
        );
    }

    /**
     * The columns one recorded view exposes, which are the result columns of
     * its own defining query, renamed by its column list and reached by the
     * later statements recorded with the view: every column is nullable where
     * a later {@code DROP NOT NULL} stands, and a column's enum type is named
     * as the later renames leave it.
     *
     * <p>The query is parsed by the one SQL parser and analyzed in an analysis
     * of its own, against the schema recorded with the view, so none of its
     * wildcards and none of its placeholders belongs to the query that names
     * the view and a view over a view resolves the inner view from that inner
     * view's own recorded schema.
     *
     * <p>Every failure of that analysis — a query the parser cannot read, a
     * query that writes a placeholder, which PostgreSQL does not accept in a
     * view, a column list longer than the query's columns, a recursive view,
     * and every failure of a clause — makes this a view sqlcj cannot analyze,
     * reported with the failure's own text as its reason. A failure that states
     * no text at all is a defect of this analyzer and stays as it is.
     */
    private List<org.sqlcj.schema.Column> analyzedViewColumns(String writtenName, View view) {
        try {
            return viewColumns(view);
        } catch (RuntimeException e) {
            if (e.getMessage() == null || e.getMessage().isBlank()) {
                throw e;
            }

            throw new UnsupportedOperationException(
                UNANALYZABLE_VIEW_REJECTION.formatted(writtenName, viewKind(view), e.getMessage())
            );
        }
    }

    private List<org.sqlcj.schema.Column> viewColumns(View view) {
        if (view.recursive()) {
            throw new UnsupportedOperationException(RECURSIVE_VIEW_REASON);
        }

        ParsedSql parsedSql = sqlParser.parse(view.query());

        if (!parsedSql.placeholders().isEmpty()) {
            throw new UnsupportedOperationException(VIEW_PLACEHOLDER_REASON);
        }

        if (!(parsedSql.statement() instanceof Select select)) {
            throw new UnsupportedOperationException(QueryAnalyzer.UNSUPPORTED_STATEMENT_REJECTION);
        }

        Schema schema = view.schema();
        Analysis analysis = new Analysis(parsedSql, schema, analyzer.toPlaceholders(parsedSql, schema));

        List<org.sqlcj.schema.Column> columns = analyzer.renamedDerivedColumns(
            view.name(),
            view.columnNames(),
            analyzer.analyzeNestedSelect(select, analysis, List.of(), view.columnNames().size(), null)
                .columns()
        );

        return columns.stream()
            .map(
                column -> new org.sqlcj.schema.Column(
                    column.name(),
                    column.type(),
                    column.nullable() || view.columnsNullable(),
                    column.unsupportedType(),
                    renamedEnumType(column.enumType(), view.enumRenames()),
                    column.array(),
                    column.blankPadded(),
                    column.sqlName()
                )
            )
            .toList();
    }

    /**
     * The declared name one column's enum type is known by after the renames
     * stated after its view was created, applied in their order, and
     * {@code null} for a column of no enum type. A view's own analysis resolves
     * the type by the name the defining query names it under, which is the name
     * it had there, while every later name is the name the final schema
     * declares the type under.
     */
    private String renamedEnumType(String enumType, List<View.EnumRename> renames) {
        String name = enumType;

        if (name == null) {
            return null;
        }

        for (View.EnumRename rename : renames) {
            if (name.equalsIgnoreCase(rename.oldName())) {
                name = rename.newName();
            }
        }

        return name;
    }

    /** The kind of one recorded view, as PostgreSQL spells it. */
    private String viewKind(View view) {
        return view.materialized()
            ? "materialized view"
            : "view";
    }

    /**
     * Rejects a source that names a name of a recursive {@code WITH} list no
     * source may read there: the clause's own name outside its recursive term,
     * and the name of a clause declared after it. The name shadows the schema
     * table of that name, as a readable clause does, so it is reported with its
     * own diagnostic rather than read as a table.
     */
    private void requireReadableCommonTableExpression(Source commonTableExpression) {
        if (commonTableExpression.unreadable() != null) {
            throw new UnsupportedOperationException(
                commonTableExpression.unreadable().formatted(commonTableExpression.name())
            );
        }
    }

    /**
     * Resolves a subquery source into the derived source its own
     * {@code SELECT} exposes. The body sees the common table expressions in
     * scope and, for a {@code LATERAL} source, the {@code enclosing} level its
     * clause states; a subquery source that is not {@code LATERAL} receives
     * none, so it sees no source of the query around it, as PostgreSQL scopes
     * it.
     *
     * <p>The alias is the only name the subquery's columns can be read through,
     * so it is required, and a column list on it would rename those columns by
     * position, which this subset does not model. A {@code LATERAL} source
     * keeps both rejections, because its body exposes its columns the same way.
     */
    private Source toSubquerySource(
        ParenthesedSelect subquery,
        Analysis analysis,
        List<Source> commonTableExpressions,
        boolean nullable,
        Scope enclosing
    ) {
        Alias alias = subquery.getAlias();

        if (alias == null) {
            throw new UnsupportedOperationException(UNALIASED_FROM_SUBQUERY_REJECTION);
        }

        String name = alias.getUnquotedName();

        if (alias.getAliasColumns() != null && !alias.getAliasColumns().isEmpty()) {
            throw new UnsupportedOperationException(
                DERIVED_SOURCE_COLUMN_LIST_REJECTION.formatted(name)
            );
        }

        return toDerivedSource(
            name,
            SchemaNamespace.storedName(alias.getName()),
            analyzer.analyzeNestedSelect(
                subquery.getSelect(),
                analysis,
                commonTableExpressions,
                0,
                enclosing
            )
                .columns(),
            nullable
        );
    }

    /**
     * The common table expression in scope one source names, and {@code null}
     * when the source names none. Only an unqualified name can name one, and
     * the innermost declaration of that name, compared ignoring case, is the
     * one the source reads.
     */
    private Source findCommonTableExpression(Table table, List<Source> commonTableExpressions) {
        if (table.getSchemaName() != null) {
            return null;
        }

        String name = table.getUnquotedName();

        for (int index = commonTableExpressions.size() - 1; index >= 0; index--) {
            Source commonTableExpression = commonTableExpressions.get(index);

            if (commonTableExpression.name().equalsIgnoreCase(name)) {
                return commonTableExpression;
            }
        }

        return null;
    }

    /**
     * One derived source — a common table expression or a subquery source —
     * under the name the query exposes it as, whose columns are the result
     * columns of its own {@code SELECT}. A join that null-extends the source
     * makes every one of them nullable, as it does for a table source.
     *
     * <p>{@code sqlName} is the name PostgreSQL stores for the name the source
     * is exposed under, which a wildcard of it writes as its qualifier.
     */
    Source toDerivedSource(
        String name,
        String sqlName,
        List<org.sqlcj.schema.Column> columns,
        boolean nullable
    ) {
        requireDistinctDerivedColumnNames(name, columns);

        return new Source(
            name,
            sqlName,
            new org.sqlcj.schema.Table(name, columns, List.of()),
            true,
            nullable
        );
    }

    /**
     * Requires the columns of a derived source to expose distinct names,
     * compared ignoring case as a column reference resolves them, because a
     * repeated name makes every reference to it ambiguous.
     */
    private void requireDistinctDerivedColumnNames(
        String name,
        List<org.sqlcj.schema.Column> columns
    ) {
        for (int index = 0; index < columns.size(); index++) {
            String columnName = columns.get(index).name();

            boolean repeated = columns.subList(0, index).stream()
                .anyMatch(column -> column.name().equalsIgnoreCase(columnName));

            if (repeated) {
                throw new UnsupportedOperationException(
                    DERIVED_SOURCE_COLUMN_NAME_REJECTION.formatted(name, columnName)
                );
            }
        }
    }

    /**
     * Resolves the sources of one plain {@code SELECT}, which are the sources
     * its {@code FROM} clause states and no source at all where it writes no
     * {@code FROM} item, as PostgreSQL reads one row of the written values
     * there.
     *
     * <p>Such a {@code SELECT} has no source for a bare {@code *} to expand, so
     * a bare {@code *} there is rejected, while {@code qualifier.*} keeps the
     * rejection its unknown qualifier states.
     */
    List<Source> resolvePlainSelectSources(
        PlainSelect plainSelect,
        Analysis analysis,
        List<Source> commonTableExpressions,
        Scope enclosing
    ) {
        if (plainSelect.getFromItem() != null) {
            return resolveSources(plainSelect, analysis, commonTableExpressions, enclosing);
        }

        for (SelectItem<?> selectItem : plainSelect.getSelectItems()) {
            if (selectItem.getExpression().getClass() == AllColumns.class) {
                throw new UnsupportedOperationException(SOURCELESS_WILDCARD_REJECTION);
            }
        }

        return List.of();
    }

    /**
     * Resolves the ordered query sources of one read: its first {@code FROM}
     * item followed by every comma-separated and every joined source, in the
     * order the query writes them, rejecting an exposed name that repeats in
     * this level.
     *
     * <p>Every caller resolves a {@code SELECT} that writes no {@code FROM}
     * item of its own before this, so the first item here is one the query
     * writes.
     */
    List<Source> resolveSources(
        PlainSelect plainSelect,
        Analysis analysis,
        List<Source> commonTableExpressions,
        Scope enclosing
    ) {
        List<Source> sources = new ArrayList<>();

        resolveJoinGroups(
            sources,
            Collections.singletonList(plainSelect.getFromItem()),
            plainSelect.getJoins(),
            "Only table sources are supported.",
            "Only table join sources are supported.",
            analysis,
            commonTableExpressions,
            enclosing
        );

        return List.copyOf(sources);
    }

    /**
     * Resolves the ordered sources of one write: its target, which its rows
     * belong to, followed by every source its {@code FROM} or {@code USING}
     * clause adds, in the order the query writes them. An exposed name that
     * repeats among them is rejected as it is in a read, the target included.
     *
     * <p>The target is in no join group, so no join condition sees it and no
     * join null-extends it, as PostgreSQL scopes a condition inside
     * {@code FROM} and reads the rows the write updates or deletes.
     */
    List<Source> resolveWriteSources(
        Source target,
        List<FromItem> addedItems,
        List<Join> joins,
        Analysis analysis,
        List<Source> commonTableExpressions
    ) {
        List<Source> sources = new ArrayList<>();

        addSource(sources, target);

        resolveJoinGroups(
            sources,
            addedItems,
            joins,
            WRITE_SOURCE_REJECTION,
            WRITE_SOURCE_REJECTION,
            analysis,
            commonTableExpressions,
            null
        );

        return List.copyOf(sources);
    }

    /**
     * Resolves the join groups of one {@code FROM} or {@code USING} clause into
     * the ordered sources of its level, which a write has already added its
     * target to.
     *
     * <p>Each item written beside the others — the first {@code FROM} item, a
     * comma-separated item after it, and each {@code USING} source — starts a
     * join group, and each joined source joins the group it is written onto,
     * because PostgreSQL binds a {@code JOIN} more tightly than a comma. A
     * join condition therefore resolves its columns and types its placeholders
     * over its own group up to and including its joined source, and then over
     * the levels enclosing this one, which are none for a query no analyzed
     * query encloses. A {@code CROSS JOIN} and a comma carry no condition at
     * all.
     *
     * <p>A {@code RIGHT} or {@code FULL} join reads the earlier sources of its
     * own group as rows that may be absent, so each of them is replaced by a
     * null-extended source, in the group its later conditions resolve against
     * and in the level's sources alike. The joined source's own nullability is
     * decided where it is resolved.
     *
     * <p>A {@code USING} list and a {@code NATURAL} join merge their join
     * columns into the group's own merged source and carry no condition of
     * their own. Their columns are resolved from the nullability both sides
     * expose before this join null-extends either of them, and the merged
     * source is installed after that null extension, because no join
     * null-extends the columns it merges itself.
     *
     * <p>A {@code LATERAL} source's body reads, as the one level enclosing it,
     * the sources of this clause resolved before it — the earlier join groups
     * and, unless a {@code RIGHT} or {@code FULL} join joins it, the earlier
     * sources of its own group — and then the levels enclosing this one. A
     * write's target is resolved before this clause, so it is in none of them,
     * as PostgreSQL scopes a source of {@code FROM} or {@code USING}.
     *
     * <p>A parenthesized join group that opens this clause is read as the same
     * joins unparenthesized; see
     * {@link #unparenthesizedJoins(List, List)}.
     */
    private void resolveJoinGroups(
        List<Source> sources,
        List<FromItem> clauseItems,
        List<Join> clauseJoins,
        String groupItemRejection,
        String joinedItemRejection,
        Analysis analysis,
        List<Source> commonTableExpressions,
        Scope enclosing
    ) {
        List<FromItem> groupItems = new ArrayList<>(clauseItems);
        List<Join> joins = unparenthesizedJoins(groupItems, clauseJoins);

        int firstGroupIndex = sources.size();
        List<Source> group = new ArrayList<>();

        for (FromItem groupItem : groupItems) {
            addGroupSource(
                sources,
                group,
                resolveSource(
                    groupItem,
                    groupItemRejection,
                    analysis,
                    commonTableExpressions,
                    false,
                    lateralScope(
                        sources,
                        firstGroupIndex,
                        sources.size(),
                        commonTableExpressions,
                        enclosing
                    )
                )
            );
        }

        for (Join join : joins) {
            if (join.isSimple()) {
                addGroupSource(
                    sources,
                    group,
                    resolveSource(
                        join.getFromItem(),
                        groupItemRejection,
                        analysis,
                        commonTableExpressions,
                        false,
                        lateralScope(
                            sources,
                            firstGroupIndex,
                            sources.size(),
                            commonTableExpressions,
                            enclosing
                        )
                    )
                );

                continue;
            }

            Source joined = requireSupportedJoin(
                join,
                joinedItemRejection,
                analysis,
                commonTableExpressions,
                lateralScope(
                    sources,
                    firstGroupIndex,
                    join.isRight() || join.isFull()
                        ? sources.indexOf(group.get(0))
                        : sources.size(),
                    commonTableExpressions,
                    enclosing
                )
            );

            addSource(sources, joined);
            group.add(joined);

            List<JoinColumn> joinColumns = resolveJoinColumns(join, group, joined);

            nullExtendEarlierGroupSources(join, sources, group);

            mergeJoinColumns(joinColumns, sources, group, joined);

            if (join.isCross() || mergesJoinColumns(join)) {
                continue;
            }

            Expression condition = requireJoinCondition(join);
            Scope scope = new Scope(List.copyOf(group), commonTableExpressions, enclosing);

            resolveJoinConditionColumns(condition, scope);
            analyzer.resolveParameters(condition, scope, analysis);
        }
    }

    /**
     * The joins of one {@code FROM} or {@code USING} clause with the joins of
     * the parenthesized group that opens it written before them, replacing
     * that group in {@code groupItems} by the source it encloses.
     *
     * <p>A join is left-associative, so {@code (a JOIN b) JOIN c} joins the
     * same sources in the same order as {@code a JOIN b JOIN c}, at any depth
     * of leading groups. {@code pg_dump} writes every join of a view that way,
     * so a view's query and the dump of the database it was created in are
     * read alike.
     *
     * <p>A group that writes an alias exposes its own name for its columns,
     * which this subset does not model, and a group written anywhere but at
     * the start of the clause is no leading group at all; both keep the
     * rejection their clause states.
     */
    private List<Join> unparenthesizedJoins(List<FromItem> groupItems, List<Join> clauseJoins) {
        List<Join> joins = new ArrayList<>();

        while (
            !groupItems.isEmpty()
                && groupItems.get(0) instanceof ParenthesedFromItem group
                && group.getAlias() == null
        ) {
            groupItems.set(0, group.getFromItem());

            if (group.getJoins() != null) {
                joins.addAll(0, group.getJoins());
            }
        }

        if (clauseJoins != null) {
            joins.addAll(clauseJoins);
        }

        return joins;
    }

    /**
     * The level a {@code LATERAL} source written at one position reads as the
     * one enclosing it: the sources of this clause from {@code firstGroupIndex},
     * which is where this clause's own first group starts, up to {@code end},
     * which is where the source itself is written, and then the levels
     * enclosing this one. The sources are copied, because the level is a
     * snapshot of what the body can see rather than the list this clause goes
     * on resolving.
     */
    private Scope lateralScope(
        List<Source> sources,
        int firstGroupIndex,
        int end,
        List<Source> commonTableExpressions,
        Scope enclosing
    ) {
        return new Scope(
            List.copyOf(sources.subList(firstGroupIndex, end)),
            commonTableExpressions,
            enclosing
        );
    }

    /**
     * Adds one source written beside the others, which starts a join group of
     * its own.
     */
    private void addGroupSource(List<Source> sources, List<Source> group, Source source) {
        addSource(sources, source);

        group.clear();
        group.add(source);
    }

    /**
     * Replaces the sources a {@code RIGHT} or {@code FULL} join null-extends —
     * the sources of its own group written before it — by null-extended
     * sources, so that every column read from them is nullable. Every other
     * join leaves its group as it is.
     */
    private void nullExtendEarlierGroupSources(
        Join join,
        List<Source> sources,
        List<Source> group
    ) {
        if (!join.isRight() && !join.isFull()) {
            return;
        }

        for (int index = 0; index < group.size() - 1; index++) {
            Source nullExtended = group.get(index).nullExtended();

            sources.set(sources.indexOf(group.get(index)), nullExtended);
            group.set(index, nullExtended);
        }
    }

    /**
     * Reports whether one join merges its join columns, which a
     * {@code USING} list and a {@code NATURAL} join do and every other join
     * does not, so neither of them carries an {@code ON} condition. A
     * {@code NATURAL} join of no common column merges none and joins its
     * sources as a cross join does.
     */
    private boolean mergesJoinColumns(Join join) {
        return join.isNatural() || !join.getUsingColumns().isEmpty();
    }

    /**
     * Resolves the columns one join merges, in the order PostgreSQL exposes
     * them: a {@code USING} list names them in its own order, and a
     * {@code NATURAL} join takes the names its two sides share, in the left
     * side's order. Every join that merges none reports an empty list.
     *
     * <p>The left side of the join is the group's output before it: the merged
     * columns of its latest merge first, then the columns no earlier join
     * merged away from each member, in group order. A name that side exposes
     * more than once names no single column to merge and is rejected, as
     * PostgreSQL rejects it, and a {@code USING} name either side does not
     * expose is rejected naming the side that lacks it.
     */
    private List<JoinColumn> resolveJoinColumns(Join join, List<Source> group, Source joined) {
        if (!mergesJoinColumns(join)) {
            return List.of();
        }

        List<ResolvedColumn> leftSide = leftJoinSide(group);
        List<JoinColumn> joinColumns = new ArrayList<>();

        for (String name : joinColumnNames(join, leftSide, joined)) {
            joinColumns.add(resolveJoinColumn(join, name, leftSide, joined));
        }

        return List.copyOf(joinColumns);
    }

    /**
     * The columns the left side of the group's latest join exposes, each with
     * the source it is read through, which decides its effective nullability:
     * the group's merged columns first, then the columns each member still
     * exposes, in group order. The joined source itself, which the group
     * already holds as its last source, is the right side and none of them.
     */
    private List<ResolvedColumn> leftJoinSide(List<Source> group) {
        List<Source> leftSources = group.subList(0, group.size() - 1);

        List<ResolvedColumn> leftSide = new ArrayList<>();

        mergedGroupSource(leftSources)
            .ifPresent(
                merged -> merged.table().columns()
                    .forEach(column -> leftSide.add(new ResolvedColumn(merged, column)))
            );

        for (Source source : leftSources) {
            if (source.merged()) {
                continue;
            }

            exposedColumns(source)
                .forEach(column -> leftSide.add(new ResolvedColumn(source, column)));
        }

        return List.copyOf(leftSide);
    }

    /** The merged source of one join group, which merges no column without. */
    private Optional<Source> mergedGroupSource(List<Source> group) {
        return group.stream()
            .filter(Source::merged)
            .findFirst();
    }

    /**
     * The columns one source exposes under its own name, which are the columns
     * of its table that no join of its group merged away.
     */
    List<org.sqlcj.schema.Column> exposedColumns(Source source) {
        return source.table().columns().stream()
            .filter(column -> !source.mergesAway(column.name()))
            .toList();
    }

    /**
     * The names one join merges, which a {@code USING} list writes itself and
     * a {@code NATURAL} join takes from the names its left side and its joined
     * source share, in the left side's order and once each.
     */
    private List<String> joinColumnNames(
        Join join,
        List<ResolvedColumn> leftSide,
        Source joined
    ) {
        if (!join.getUsingColumns().isEmpty()) {
            return join.getUsingColumns().stream()
                .map(net.sf.jsqlparser.schema.Column::getUnquotedColumnName)
                .toList();
        }

        List<String> names = new ArrayList<>();

        for (ResolvedColumn left : leftSide) {
            String name = left.column().name();

            boolean common = lookupColumn(joined.table(), name).isPresent()
                && names.stream().noneMatch(listed -> listed.equalsIgnoreCase(name));

            if (common) {
                names.add(name);
            }
        }

        return List.copyOf(names);
    }

    /**
     * Resolves one join column into the merged column it exposes and the
     * member it is merged away from, which is none for a column the group's
     * merged source already holds.
     */
    private JoinColumn resolveJoinColumn(
        Join join,
        String name,
        List<ResolvedColumn> leftSide,
        Source joined
    ) {
        List<ResolvedColumn> matches = leftSide.stream()
            .filter(left -> left.column().name().equalsIgnoreCase(name))
            .toList();

        if (matches.size() > 1) {
            throw new UnsupportedOperationException(
                REPEATED_JOIN_COLUMN_REJECTION.formatted(name)
            );
        }

        if (matches.isEmpty()) {
            throw new UnsupportedOperationException(
                USING_LEFT_COLUMN_REJECTION.formatted(name)
            );
        }

        ResolvedColumn left = matches.get(0);

        org.sqlcj.schema.Column right = lookupColumn(joined.table(), name)
            .orElseThrow(
                () -> new UnsupportedOperationException(
                    USING_RIGHT_COLUMN_REJECTION.formatted(name)
                )
            );

        return new JoinColumn(
            mergedColumn(join, left, right),
            left.source().merged()
                ? null
                : left.source().name()
        );
    }

    /**
     * The merged column of one join column, which is named as the left side
     * declares it, typed as the set operation rules resolve both sides, and
     * nullable by the join kind.
     *
     * <p>A pair those rules resolve to no single type is rejected, because a
     * merged column takes no cast, so the condition belongs in an {@code ON}
     * clause instead. A column whose type the schema left unmapped is reported
     * as that unsupported type, which the diagnostic would otherwise have to
     * spell.
     */
    private org.sqlcj.schema.Column mergedColumn(
        Join join,
        ResolvedColumn left,
        org.sqlcj.schema.Column right
    ) {
        org.sqlcj.schema.Column leftColumn = left.column();
        org.sqlcj.schema.Column resolved = analyzer.resolveCommonColumnType(leftColumn, right);

        if (resolved == null) {
            requireSupportedType(leftColumn);
            requireSupportedType(right);

            throw new UnsupportedOperationException(
                JOIN_COLUMN_TYPE_REJECTION.formatted(
                    leftColumn.name(),
                    analyzer.sqlType(leftColumn),
                    analyzer.sqlType(right)
                )
            );
        }

        return new org.sqlcj.schema.Column(
            leftColumn.name(),
            resolved.type(),
            mergedNullability(
                join,
                leftColumn.nullable() || left.source().nullable(),
                right.nullable()
            ),
            null,
            resolved.enumType(),
            resolved.array(),
            resolved.blankPadded(),
            leftColumn.sqlName()
        );
    }

    /**
     * The nullability of one merged column, from the nullability both sides
     * expose before this join null-extends either of them: an inner join reads
     * the pair's equal values and a full join coalesces them, so their merged
     * column is nullable only when both sides are, while a left join reads the
     * left column and a right join the right column, which is the value
     * PostgreSQL exposes.
     */
    private boolean mergedNullability(Join join, boolean leftNullable, boolean rightNullable) {
        if (join.isLeft()) {
            return leftNullable;
        }

        if (join.isRight()) {
            return rightNullable;
        }

        return leftNullable && rightNullable;
    }

    /**
     * Merges the columns one join merges into its group: the group's merged
     * source becomes the columns this join merges followed by the columns its
     * latest merge exposed and this join does not merge again, and every
     * source a name is merged away from records that name.
     *
     * <p>An unqualified reference and a bare {@code *} therefore read the
     * merged column alone, while a qualified reference, a {@code source.*},
     * and a later condition still read each source's own column. The merged
     * source stands right after its group's first source, where a bare
     * {@code *} expands the group's columns, and is replaced in place by every
     * later merge, so it is never the first source of its level.
     *
     * <p>A join that merges no column joins one more member into its group
     * all the same, so the group's merged source records that member too,
     * which keeps a bare {@code *} listing the group's columns flat.
     */
    private void mergeJoinColumns(
        List<JoinColumn> joinColumns,
        List<Source> sources,
        List<Source> group,
        Source joined
    ) {
        if (joinColumns.isEmpty()) {
            recordGroupMember(sources, group, joined);

            return;
        }

        List<org.sqlcj.schema.Column> columns = new ArrayList<>(
            joinColumns.stream()
                .map(JoinColumn::column)
                .toList()
        );

        Optional<Source> previous = mergedGroupSource(group);

        previous.ifPresent(merged -> columns.addAll(carriedMergedColumns(merged, joinColumns)));

        Source merged = Source.merged(List.copyOf(columns), groupSourceNames(group));

        if (previous.isPresent()) {
            replaceGroupSource(sources, group, previous.get(), merged);
        } else {
            sources.add(sources.indexOf(group.get(0)) + 1, merged);
            group.add(1, merged);
        }

        mergeAwayJoinColumns(joinColumns, sources, group, joined);
    }

    /**
     * Records one more member in the merged source of the group a join that
     * merges no column — an {@code ON} join, a cross join, and a
     * {@code NATURAL} join of no common column — adds a source to, so that a
     * bare {@code *} lists that member's columns flat after the group's
     * merged columns instead of embedding its row. A group that merges no
     * column at all holds no merged source and keeps its members as they are
     * written.
     */
    private void recordGroupMember(List<Source> sources, List<Source> group, Source joined) {
        mergedGroupSource(group)
            .ifPresent(
                merged -> replaceGroupSource(
                    sources,
                    group,
                    merged,
                    merged.grouping(joined.name())
                )
            );
    }

    /**
     * The merged columns of one group's latest merge that one more join does
     * not merge again, which stay in its merged source after the columns the
     * later join merges. A {@code RIGHT} or {@code FULL} join null-extends the
     * merged source it carries them over from, so each of them carries that
     * nullability too.
     */
    private List<org.sqlcj.schema.Column> carriedMergedColumns(
        Source merged,
        List<JoinColumn> joinColumns
    ) {
        List<org.sqlcj.schema.Column> carried = merged.table().columns().stream()
            .filter(
                column -> joinColumns.stream()
                    .noneMatch(joinColumn -> joinColumn.name().equalsIgnoreCase(column.name()))
            )
            .toList();

        return resolveColumns(carried, merged.nullable());
    }

    /** The exposed names of one group's sources, in the order it joins them. */
    private List<String> groupSourceNames(List<Source> group) {
        return group.stream()
            .filter(source -> !source.merged())
            .map(Source::name)
            .toList();
    }

    /**
     * Records the names one join merged away from each source it merged them
     * from: the member the left column was read through, which is none for a
     * column the group's merged source already held, and the joined source.
     */
    private void mergeAwayJoinColumns(
        List<JoinColumn> joinColumns,
        List<Source> sources,
        List<Source> group,
        Source joined
    ) {
        Map<String, List<String>> mergedAway = new LinkedHashMap<>();

        for (JoinColumn joinColumn : joinColumns) {
            if (joinColumn.leftSource() != null) {
                mergedAway
                    .computeIfAbsent(joinColumn.leftSource(), name -> new ArrayList<>())
                    .add(joinColumn.name());
            }

            mergedAway
                .computeIfAbsent(joined.name(), name -> new ArrayList<>())
                .add(joinColumn.name());
        }

        mergedAway.forEach(
            (name, names) -> {
                Source source = findSource(group, name);

                replaceGroupSource(sources, group, source, source.merging(names));
            }
        );
    }

    /**
     * Replaces one source of a join group, in the group its later conditions
     * resolve against and in the level's sources alike, as null extension
     * replaces the sources it reads as rows that may be absent.
     */
    private void replaceGroupSource(
        List<Source> sources,
        List<Source> group,
        Source source,
        Source replacement
    ) {
        sources.set(sources.indexOf(source), replacement);
        group.set(group.indexOf(source), replacement);
    }

    private void addSource(List<Source> sources, Source source) {
        boolean duplicate = sources.stream()
            .filter(existing -> !existing.merged())
            .anyMatch(existing -> existing.name().equalsIgnoreCase(source.name()));

        if (duplicate) {
            throw new IllegalArgumentException(
                "Duplicate source name in query: " + source.name()
            );
        }

        sources.add(source);
    }

    /**
     * A write's target, which always contributes a row of its own. sqlcj reads
     * a view as a source but writes through none, so a target that names a
     * recorded view, and no modeled table, is rejected here, where every write
     * resolves its target.
     */
    Source toSource(Table table, Schema schema) {
        View view = recordedView(table, schema);

        if (view != null) {
            throw new UnsupportedOperationException(
                VIEW_WRITE_REJECTION.formatted(table.getUnquotedName(), viewKind(view))
            );
        }

        return toSource(table, schema, false);
    }

    private Source toSource(Table table, Schema schema, boolean nullable) {
        return new Source(
            table.getAlias() == null
                ? table.getUnquotedName()
                : table.getAlias().getUnquotedName(),
            sourceSqlName(table),
            findSourceTable(schema, table),
            false,
            nullable
        );
    }

    /**
     * The name PostgreSQL stores for the name one {@code FROM} item exposes,
     * which is its alias where it writes one and its own name otherwise. The
     * qualifier reaches only the source itself, so a schema qualifier the item
     * writes is no part of it.
     */
    private String sourceSqlName(Table table) {
        return table.getAlias() == null
            ? SchemaNamespace.storedName(table.getName())
            : SchemaNamespace.storedName(table.getAlias().getName());
    }

    /**
     * The modeled table one query source names. A query group models one
     * namespace, so an unqualified name and a name qualified with the modeled
     * schema are the same table, and a name qualified with any other schema
     * names a table the group models nowhere, which is reported from the
     * qualifier alone before any lookup.
     *
     * <p>A source that names a recorded view is resolved as that view before
     * this lookup, and a write's target that names one is rejected before it,
     * so a name that reaches here and names no modeled table names nothing the
     * schema holds at all.
     */
    private org.sqlcj.schema.Table findSourceTable(Schema schema, Table table) {
        SchemaNamespace namespace = new SchemaNamespace(schema.namespace());

        if (!namespace.isModeled(table)) {
            String declaredSchema = MultiPartName.unquote(table.getSchemaName());

            throw new IllegalArgumentException(
                OTHER_SCHEMA_SOURCE_REJECTION.formatted(
                    namespace.declaredName(table),
                    declaredSchema,
                    namespace.name(),
                    declaredSchema
                )
            );
        }

        String tableName = table.getUnquotedName();

        return findTable(schema, tableName)
            .orElseThrow(() -> unmodeledSource(schema, tableName));
    }

    /**
     * The failure of a source that names no modeled table, which suggests the
     * modeled table closest to the written name.
     */
    private IllegalArgumentException unmodeledSource(Schema schema, String tableName) {
        return new IllegalArgumentException(
            "Table not found in schema: "
                + tableName
                + suggestion(tableName, tableNames(schema))
        );
    }

    /**
     * Accepts a bare {@code JOIN} or explicit {@code INNER JOIN}, a
     * {@code LEFT}, {@code RIGHT}, or {@code FULL} join in its bare or
     * {@code OUTER} spelling, each of them {@code NATURAL} or with a
     * {@code USING} list, and a {@code CROSS JOIN}, of one table or derived
     * source. {@link Join#isInnerJoin()} and {@link Join#isLeft()} also report
     * shapes that this subset excludes, so every excluded modifier is rejected
     * explicitly.
     *
     * <p>The join kind is classified before its source, so a modifier outside
     * the subset is reported whatever the source it joins.
     *
     * <p>A {@code LEFT} or {@code FULL} join reads its own joined source as a
     * row that may be absent, so that source is resolved as null-extended, and
     * a {@code LATERAL} joined source reads the {@code lateral} level its
     * clause states.
     */
    private Source requireSupportedJoin(
        Join join,
        String unsupportedSourceRejection,
        Analysis analysis,
        List<Source> commonTableExpressions,
        Scope lateral
    ) {
        requireSupportedJoinKind(join);

        return resolveSource(
            join.getFromItem(),
            unsupportedSourceRejection,
            analysis,
            commonTableExpressions,
            join.isLeft() || join.isFull(),
            lateral
        );
    }

    /**
     * Rejects every join kind outside the supported subset, and then each
     * combination of a supported kind with a condition PostgreSQL does not
     * give it although the parser reads it. A {@code NATURAL CROSS JOIN},
     * which is no supported kind at all, keeps the general rejection.
     */
    private void requireSupportedJoinKind(Join join) {
        if (
            !isSupportedInnerJoin(join)
                && !isSupportedOuterJoin(join)
                && !isSupportedCrossJoin(join)
        ) {
            throw new UnsupportedOperationException(
                "Only INNER, LEFT, RIGHT, FULL, and CROSS JOIN clauses are supported."
            );
        }

        requireNoCrossJoinUsingList(join);
        requireNoCrossJoinCondition(join);
        requireNoNaturalJoinCondition(join);
    }

    /**
     * Rejects a {@code USING} list on a {@code CROSS JOIN}, which PostgreSQL
     * does not have at all although the parser reads it, naming the join that
     * merges join columns.
     */
    private void requireNoCrossJoinUsingList(Join join) {
        if (join.isCross() && !join.getUsingColumns().isEmpty()) {
            throw new UnsupportedOperationException(CROSS_JOIN_USING_REJECTION);
        }
    }

    /**
     * Rejects a condition on a {@code CROSS JOIN}, which PostgreSQL does not
     * have at all although the parser reads it, naming the join that carries
     * one.
     */
    private void requireNoCrossJoinCondition(Join join) {
        if (join.isCross() && !join.getOnExpressions().isEmpty()) {
            throw new UnsupportedOperationException(
                "CROSS JOIN takes no ON condition; write JOIN ... ON."
            );
        }
    }

    /**
     * Rejects an {@code ON} condition and a {@code USING} list on a
     * {@code NATURAL} join, which PostgreSQL does not have although the parser
     * reads both, because {@code NATURAL} states the join columns itself.
     */
    private void requireNoNaturalJoinCondition(Join join) {
        if (
            join.isNatural()
                && (!join.getOnExpressions().isEmpty() || !join.getUsingColumns().isEmpty())
        ) {
            throw new UnsupportedOperationException(NATURAL_JOIN_CONDITION_REJECTION);
        }
    }

    /**
     * Reports a bare {@code JOIN}, an explicit {@code INNER JOIN}, and a bare
     * {@code NATURAL JOIN}, which PostgreSQL joins as an inner join and the
     * parser reports as a {@code NATURAL} join of no named side and no inner
     * keyword at all, so it is recognized here explicitly.
     */
    private boolean isSupportedInnerJoin(Join join) {
        return (join.isInnerJoin() || join.isNatural())
            && !join.isOuter()
            && !join.isLeft()
            && !join.isRight()
            && !join.isFull()
            && !join.isCross()
            && hasNoOtherJoinModifier(join);
    }

    /**
     * Reports a {@code LEFT}, {@code RIGHT}, or {@code FULL} join, whose
     * {@code OUTER} spelling also sets the {@code OUTER} keyword. Exactly one
     * side must be named, so the bare {@code OUTER JOIN}, which names none, is
     * excluded, as is every other qualifier.
     */
    private boolean isSupportedOuterJoin(Join join) {
        return namedOuterSides(join) == 1
            && !join.isInner()
            && !join.isCross()
            && hasNoOtherJoinModifier(join);
    }

    /** The number of outer join sides one join names. */
    private int namedOuterSides(Join join) {
        return (join.isLeft() ? 1 : 0)
            + (join.isRight() ? 1 : 0)
            + (join.isFull() ? 1 : 0);
    }

    /**
     * Reports a {@code CROSS JOIN}, which joins every row of its sources.
     * {@code NATURAL CROSS JOIN}, which PostgreSQL does not have although the
     * parser reads it, is excluded, because a natural join merges the columns
     * a cross join keeps apart.
     */
    private boolean isSupportedCrossJoin(Join join) {
        return join.isCross()
            && !join.isInner()
            && !join.isOuter()
            && !join.isNatural()
            && namedOuterSides(join) == 0
            && hasNoOtherJoinModifier(join);
    }

    private boolean hasNoOtherJoinModifier(Join join) {
        return !join.isSimple()
            && !join.isSemi()
            && !join.isApply()
            && !join.isStraight()
            && !join.isGlobal()
            && !join.isWindowJoin()
            && join.getJoinHint() == null;
    }

    /**
     * The single {@code ON} condition of one join, which may be any boolean
     * expression. The parser reports no condition for a join written without
     * one and two conditions for the nested {@code JOIN a JOIN b ON ... ON ...}
     * form, neither of which states one condition for the joined source.
     */
    private Expression requireJoinCondition(Join join) {
        Collection<Expression> onExpressions = join.getOnExpressions();

        if (onExpressions.size() != 1) {
            throw new UnsupportedOperationException(
                "A join requires one ON condition."
            );
        }

        return onExpressions.iterator().next();
    }

    /**
     * Resolves every column reference an {@code ON} condition makes against the
     * sources of its own join group up to and including its own joined source,
     * as PostgreSQL scopes a join condition, so a reference to a later source
     * or to another group of the same level is an unknown qualifier, and then
     * against each enclosing level. A nested {@code SELECT} is not analyzed
     * here, which the visitor's
     * default traversal already leaves unentered, so a subquery of the
     * condition is analyzed by the predicate rules alone.
     *
     * <p>A {@code trim} written without characters, such as
     * {@code trim(BOTH FROM name)}, has no characters expression, which the
     * adapter's own traversal dereferences, so the walk stops at such a call
     * rather than descending into it.
     */
    private void resolveJoinConditionColumns(Expression condition, Scope scope) {
        condition.accept(
            new ExpressionVisitorAdapter<Void>() {

                @Override
                public <S> Void visit(net.sf.jsqlparser.schema.Column column, S context) {
                    resolveColumn(column, scope);

                    return null;
                }

                @Override
                public <S> Void visit(TrimFunction trim, S context) {
                    return trim.getExpression() == null
                        ? null
                        : super.visit(trim, context);
                }
            }
        );
    }

    /**
     * Reports the column one expression reads directly, and {@code null} when
     * the expression reads none. A reference written with a subscript or a
     * slice, such as {@code labels[1]} or {@code labels[1:2]}, reads one
     * element or one range of the column's array value rather than the column,
     * so it states neither the type nor the name of the column it reads, and
     * every rule that takes a type or a name from a column leaves it to the
     * rules of any other expression.
     */
    net.sf.jsqlparser.schema.Column directColumn(Expression expression) {
        return expression instanceof net.sf.jsqlparser.schema.Column column
            && column.getArrayConstructor() == null
                ? column
                : null;
    }

    /**
     * Resolves one column reference against the levels in scope, innermost
     * first, as PostgreSQL resolves a reference inside a subquery.
     *
     * <p>A qualified reference resolves through the innermost source of that
     * exposed name. An unqualified reference resolves in the innermost level
     * that has the column and is ambiguous only within that level, so the same
     * column name in an enclosing level never makes a reference ambiguous. A
     * reference no level has names every visible source, from the innermost
     * level outward, and suggests the column of them closest to the written
     * name.
     *
     * <p>A name a join merged away from its source is exposed by the merged
     * source of that source's group alone, so an unqualified reference to it
     * reads the merged column and a qualified one still reads the source's own
     * column. The merged source exposes no name of its own, so no qualifier
     * reaches it, and an ambiguity it takes part in names it by its group's
     * joined sources.
     */
    ResolvedColumn resolveColumn(net.sf.jsqlparser.schema.Column column, Scope scope) {
        String columnName = column.getUnquotedColumnName();
        String qualifier = qualifier(column);

        if (qualifier != null) {
            Source source = findSource(scope, qualifier);

            return new ResolvedColumn(source, findColumn(source.table(), columnName));
        }

        for (Scope level = scope; level != null; level = level.enclosing()) {
            List<Source> matches = level.sources().stream()
                .filter(source -> exposes(source, columnName))
                .toList();

            if (matches.size() > 1) {
                throw new IllegalArgumentException(
                    "Ambiguous column reference '%s' in sources: %s"
                        .formatted(columnName, sourceNames(matches))
                );
            }

            if (!matches.isEmpty()) {
                Source source = matches.get(0);

                return new ResolvedColumn(source, findColumn(source.table(), columnName));
            }
        }

        List<Source> visible = scope.visibleSources();

        throw new IllegalArgumentException(
            "Column not found in sources %s: %s%s".formatted(
                sourceNames(visible),
                columnName,
                suggestion(columnName, columnNames(visible))
            )
        );
    }

    /**
     * Resolves one column reference as {@link #resolveColumn} does, and reports
     * no column instead of a diagnostic wherever that resolution fails: an
     * unknown qualifier, a column the qualified source does not have, an
     * ambiguous reference, and a reference no level has alike. It is read where
     * a reference states a fact of its own, such as the nullness of a cast
     * operand, which leaves a reference it cannot resolve to the database.
     */
    Optional<ResolvedColumn> lookupColumn(
        net.sf.jsqlparser.schema.Column column,
        Scope scope
    ) {
        String columnName = column.getUnquotedColumnName();
        String qualifier = qualifier(column);

        if (qualifier != null) {
            return lookupSource(scope, qualifier)
                .flatMap(source -> resolvedColumn(source, columnName));
        }

        for (Scope level = scope; level != null; level = level.enclosing()) {
            List<Source> matches = level.sources().stream()
                .filter(source -> exposes(source, columnName))
                .toList();

            if (matches.size() > 1) {
                return Optional.empty();
            }

            if (!matches.isEmpty()) {
                return resolvedColumn(matches.get(0), columnName);
            }
        }

        return Optional.empty();
    }

    /** One named column of one source, and none where the source has none. */
    private Optional<ResolvedColumn> resolvedColumn(Source source, String columnName) {
        return lookupColumn(source.table(), columnName)
            .map(column -> new ResolvedColumn(source, column));
    }

    String qualifier(net.sf.jsqlparser.schema.Column column) {
        Table table = column.getTable();

        return table == null || table.getName() == null
            ? null
            : table.getUnquotedName();
    }

    Source findSource(List<Source> sources, String name) {
        return lookupSource(sources, name)
            .orElseThrow(() -> unknownSourceQualifier(name));
    }

    /**
     * The innermost source of one exposed name, which is the source a qualified
     * reference reads: a source of the level the reference stands in before one
     * of an enclosing level.
     */
    Source findSource(Scope scope, String name) {
        return lookupSource(scope, name)
            .orElseThrow(() -> unknownSourceQualifier(name));
    }

    /**
     * The innermost source of one exposed name, and none where no level in
     * scope exposes it.
     */
    Optional<Source> lookupSource(Scope scope, String name) {
        for (Scope level = scope; level != null; level = level.enclosing()) {
            Optional<Source> source = lookupSource(level.sources(), name);

            if (source.isPresent()) {
                return source;
            }
        }

        return Optional.empty();
    }

    /**
     * The first source of one list that exposes the given name. A join group's
     * merged source exposes no name of its own, because its merged columns
     * belong to no one of its sources, so no qualifier resolves to it.
     */
    Optional<Source> lookupSource(List<Source> sources, String name) {
        return sources.stream()
            .filter(source -> !source.merged())
            .filter(source -> source.name().equalsIgnoreCase(name))
            .findFirst();
    }

    /**
     * Reports whether one source exposes the given column name to an
     * unqualified reference, which a name a join merged away from it is read
     * through its group's merged source instead of.
     */
    boolean exposes(Source source, String columnName) {
        return !source.mergesAway(columnName)
            && lookupColumn(source.table(), columnName).isPresent();
    }

    private IllegalArgumentException unknownSourceQualifier(String name) {
        return new IllegalArgumentException("Unknown source qualifier in query: " + name);
    }

    private String sourceNames(List<Source> sources) {
        return sources.stream()
            .map(Source::name)
            .collect(Collectors.joining(", "));
    }

    /**
     * The sentence a diagnostic that names an unknown table or column appends
     * when one of the names in reach is close enough to the written one, and
     * the empty text when none is.
     */
    private String suggestion(String written, List<String> candidates) {
        return Suggestions.closest(written, candidates)
            .map(closest -> ". Did you mean '" + closest + "'?")
            .orElse("");
    }

    /** The modeled tables of the schema, in declaration order. */
    private List<String> tableNames(Schema schema) {
        return schema.tables().stream()
            .map(org.sqlcj.schema.Table::name)
            .toList();
    }

    /** The columns of one table, in column order. */
    private List<String> columnNames(org.sqlcj.schema.Table table) {
        return table.columns().stream()
            .map(org.sqlcj.schema.Column::name)
            .toList();
    }

    /**
     * The columns of the given sources, in their order, each source's in column
     * order. A source no column may be read through contributes none.
     */
    private List<String> columnNames(List<Source> sources) {
        return sources.stream()
            .filter(source -> source.table() != null)
            .flatMap(source -> columnNames(source.table()).stream())
            .toList();
    }

    /** The modeled table of one name, which names are matched case-insensitively. */
    private Optional<org.sqlcj.schema.Table> findTable(Schema schema, String tableName) {
        return schema.tables().stream()
            .filter(table -> table.name().equalsIgnoreCase(tableName))
            .findFirst();
    }

    /**
     * The columns one source exposes in its own order, each nullable when the
     * source is null-extended, which is the expansion of a wildcard over that
     * source.
     */
    List<org.sqlcj.schema.Column> resolveAllColumns(Source source) {
        return resolveSourceColumns(source, source.nullable());
    }

    /**
     * The columns of one source's table in schema order, each with the
     * nullability the schema declares, which are the columns of the row record
     * a wildcard of that source embeds. A null-extended row is absent or complete
     * as a whole, and the component holding it carries that, so the row's own
     * columns keep their declaration and one table's row record stays the same
     * record in every query that embeds or returns it.
     */
    List<org.sqlcj.schema.Column> resolveRowColumns(Source source) {
        return resolveSourceColumns(source, false);
    }

    /**
     * The columns of one source's table in schema order, each nullable when the
     * schema declares it so or when {@code nullableSource} forces it.
     */
    private List<org.sqlcj.schema.Column> resolveSourceColumns(
        Source source,
        boolean nullableSource
    ) {
        return resolveWildcardColumns(source.table().columns(), nullableSource);
    }

    /**
     * The given columns of one source a wildcard expands to, resolved as
     * {@link #resolveColumns} resolves them, after rejecting the first column
     * the schema recorded without a mapped type. The query names no column of
     * its own there, so the rejection names the one the wildcard selected and
     * the fix of listing the columns instead.
     */
    List<org.sqlcj.schema.Column> resolveWildcardColumns(
        List<org.sqlcj.schema.Column> columns,
        boolean nullableSource
    ) {
        for (org.sqlcj.schema.Column column : columns) {
            if (column.type() == null) {
                throw new UnsupportedOperationException(
                    WILDCARD_COLUMN_TYPE_REJECTION
                        .formatted(column.name(), column.unsupportedType())
                );
            }
        }

        return resolveColumns(columns, nullableSource);
    }

    /**
     * The given columns of one source, each nullable when its own declaration
     * states it so or when {@code nullableSource} forces it.
     */
    private List<org.sqlcj.schema.Column> resolveColumns(
        List<org.sqlcj.schema.Column> columns,
        boolean nullableSource
    ) {
        return columns.stream()
            .map(
                column -> new org.sqlcj.schema.Column(
                    column.name(),
                    requireSupportedType(column),
                    column.nullable() || nullableSource,
                    null,
                    column.enumType(),
                    column.array(),
                    column.blankPadded(),
                    column.sqlName()
                )
            )
            .toList();
    }

    /**
     * Reports the analyzed type of a schema column. A column the schema
     * recorded without a mapped type fails here, in the query that reads,
     * binds, or expands it, rather than when the schema is parsed.
     */
    ColumnType requireSupportedType(org.sqlcj.schema.Column column) {
        if (column.type() == null) {
            throw new UnsupportedOperationException(
                "Column '%s' has unsupported type %s"
                    .formatted(column.name(), column.unsupportedType())
            );
        }

        return column.type();
    }

    org.sqlcj.schema.Column findColumn(org.sqlcj.schema.Table table, String columnName) {
        return lookupColumn(table, columnName)
            .orElseThrow(
                () -> new IllegalArgumentException(
                    "Column not found in table "
                        + table.name()
                        + ": "
                        + columnName
                        + suggestion(columnName, columnNames(table))
                )
            );
    }

    private Optional<org.sqlcj.schema.Column> lookupColumn(org.sqlcj.schema.Table table, String columnName) {
        return table.columns().stream()
            .filter(column -> column.name().equalsIgnoreCase(columnName))
            .findFirst();
    }
}
