package org.sqlcj.analysis;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.AnalyticType;
import net.sf.jsqlparser.expression.BooleanValue;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.JsonExpression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.OracleNamedFunctionParameter;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.TrimFunction;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;
import org.sqlcj.schema.ColumnType;
import org.sqlcj.schema.Schema;
import org.sqlcj.schema.parser.ColumnTypeMapping;
import org.sqlcj.schema.parser.SchemaNamespace;
import org.sqlcj.sql.PlaceholderOccurrence;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves the projection of a query and the result columns it selects: it
 * expands the wildcards of a projection, types each selected item, an
 * aggregate, a catalogue call, and a cast included, and names the result
 * columns. It reads the analyzer it belongs to for the facts the rest of one
 * query's analysis resolves.
 */
final class ProjectionResolver {

    /**
     * The rejection of a projected expression that is neither a column, a
     * wildcard, a typed aggregate, a cast, nor an {@code EXISTS} of a
     * subquery, which is written as the query spells it. An aggregate outside
     * the typed forms, such as one of an argument or a column type PostgreSQL
     * defines no such aggregate for, is one of them, and so is an
     * {@code EXISTS} the parser reports around anything but the subquery
     * itself, such as {@code NOT EXISTS (...)} or {@code EXISTS (...)::boolean}.
     * A cast states the type of such a projection, so the rejection names that
     * fix: the query's own expression, cast to a type the user writes in place
     * of {@code <type>}, under the query's own alias or {@code <alias>} where
     * it writes none. sqlcj types no result expression, so the example names no
     * type of its own rather than one the expression may not have.
     */
    private static final String COMPUTED_PROJECTION_REJECTION = """
        Cannot infer the type of %s in SELECT; state it with a cast and an alias, \
        such as %s::<type> AS %s""";

    /**
     * The rejection of a typed aggregate projection whose own column name,
     * which is the name PostgreSQL gives it, another result column of the same
     * projection already takes, because such a name names no component of its
     * own. Both the rejected item and the example are the aggregate as the
     * query writes it, so the named fix is the query's own aggregate followed
     * by an alias.
     */
    private static final String AGGREGATE_ALIAS_REJECTION = """
        %s requires a result alias, such as %s AS total.""";

    /**
     * The rejection of an {@code EXISTS} projection without a result alias,
     * which names one result component. The item itself names no column, and
     * the computed-projection rejection would name a cast instead of the alias
     * this item needs, so the named fix is an {@code EXISTS} followed by one.
     */
    private static final String EXISTS_ALIAS_REJECTION = """
        An EXISTS projection requires a result alias, such as EXISTS (SELECT 1 FROM ...) AS present.""";

    /**
     * The rejection of a bare {@code *} that lists a merged column of a
     * {@code USING} or {@code NATURAL} join another source of the same
     * {@code FROM} clause also has a column of that name in. The merged column
     * belongs to no one source, so the executed statement can only write it
     * unqualified, which PostgreSQL would read as an ambiguous reference.
     */
    private static final String AMBIGUOUS_MERGED_WILDCARD_COLUMN_REJECTION = """
        A bare * cannot list the merged column '%s' of a USING or NATURAL join, because another \
        source of the same FROM clause also has a column '%s'; list the columns the query needs \
        instead.""";

    /**
     * The scalar types PostgreSQL 16 defines no {@code MIN} and no {@code MAX}
     * for: the types it orders no two values of, and {@code macaddr} and
     * {@code macaddr8}, which it orders without defining the aggregates. An
     * array of such a type is ordered, so a column of one is excluded only
     * where it is scalar.
     */
    private static final Set<ColumnType> UNORDERED_TYPES = Set.of(
        ColumnType.BOOLEAN,
        ColumnType.UUID,
        ColumnType.BYTEA,
        ColumnType.JSON,
        ColumnType.JSONB,
        ColumnType.MACADDR,
        ColumnType.MACADDR8
    );

    /**
     * The scalar types whose {@code MIN} and {@code MAX} PostgreSQL 16 defines
     * over a type other than the argument's own. A type this table does not
     * name keeps its own type, as an array of any ordered type does.
     */
    private static final Map<ColumnType, ColumnType> EXTREMUM_TYPES = Map.of(
        ColumnType.CIDR, ColumnType.INET
    );

    /**
     * The type of a {@code SUM} of each scalar type PostgreSQL 16 defines one
     * for, which widens an integral type so that the sum of many rows fits, and
     * keeps {@code INTERVAL}, whose sum PostgreSQL defines as well. A type this
     * table does not name has no sum, an array included.
     */
    private static final Map<ColumnType, ColumnType> SUM_TYPES = Map.of(
        ColumnType.SMALLINT, ColumnType.BIGINT,
        ColumnType.INTEGER, ColumnType.BIGINT,
        ColumnType.BIGINT, ColumnType.DECIMAL,
        ColumnType.DECIMAL, ColumnType.DECIMAL,
        ColumnType.REAL, ColumnType.REAL,
        ColumnType.DOUBLE_PRECISION, ColumnType.DOUBLE_PRECISION,
        ColumnType.INTERVAL, ColumnType.INTERVAL
    );

    /**
     * The type of an {@code AVG} of each scalar type PostgreSQL 16 defines one
     * for, which is exact for an exact input and floating for a floating one,
     * and keeps {@code INTERVAL}, whose average PostgreSQL defines as well. A
     * type this table does not name has no average, an array included.
     */
    private static final Map<ColumnType, ColumnType> AVERAGE_TYPES = Map.of(
        ColumnType.SMALLINT, ColumnType.DECIMAL,
        ColumnType.INTEGER, ColumnType.DECIMAL,
        ColumnType.BIGINT, ColumnType.DECIMAL,
        ColumnType.DECIMAL, ColumnType.DECIMAL,
        ColumnType.REAL, ColumnType.DOUBLE_PRECISION,
        ColumnType.DOUBLE_PRECISION, ColumnType.DOUBLE_PRECISION,
        ColumnType.INTERVAL, ColumnType.INTERVAL
    );

    /** The analysis whose query this projection is resolved for. */
    private final QueryAnalyzer analyzer;

    ProjectionResolver(QueryAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    /**
     * Resolves the selected columns in declared order, expanding {@code *}
     * across the query sources in their declared order and
     * {@code qualifier.*} across one source, each in schema column order. A
     * column of a null-extended source is nullable even when its schema
     * declaration is not, because an unmatched row reads it as {@code NULL}.
     *
     * <p>An aliased scalar count and an aliased cast each resolve to one column
     * in their own position, so either may stand beside any other item.
     *
     * <p>A bare placeholder projection is a placeholder a cast makes a
     * supported result column, so it is rejected as an untyped placeholder
     * rather than as an unsupported result expression.
     *
     * <p>A subscripted reference projects an element rather than the column, so
     * it is rejected as any other computed item is, and a cast states its type.
     *
     * <p>Beside the expanded columns, which are the columns a derived source
     * exposes, the projection carries the result components of a top-level
     * read, where a wildcard of a schema table is one embedded row rather than
     * that table's own columns, nullable when its source is null-extended. A
     * bare {@code *} over a join group that merges columns lists that group's
     * columns flat instead, as PostgreSQL lists them, so a merged column
     * stands once and in its own position in both.
     *
     * <p>A cast and an {@code EXISTS} of a subquery name their column with an
     * alias, which is required only of a projection that names the result
     * columns, so a branch of a set operation other than the leftmost may
     * write either of them without one. A column list on the derived source
     * this projection is the body of names its own {@code listedColumns}
     * leading columns, so neither needs an alias in one of those positions
     * either.
     *
     * <p>An aggregate needs no alias at all, because it takes the name
     * PostgreSQL gives its column. That name is checked once every item has
     * resolved, so an aggregate whose name another column of this projection
     * takes, a column a wildcard expanded included, is rejected naming the
     * first such aggregate the projection writes.
     */
    ResolvedProjection resolveProjection(
        PlainSelect plainSelect,
        Analysis analysis,
        Scope scope,
        boolean namesResultColumns,
        int listedColumns
    ) {
        List<Source> sources = scope.sources();

        List<org.sqlcj.schema.Column> columns = new ArrayList<>();
        List<QueryColumn> resultColumns = new ArrayList<>();
        List<ImplicitAggregate> implicitAggregates = new ArrayList<>();

        for (SelectItem<?> selectItem : plainSelect.getSelectItems()) {
            Expression expression = selectItem.getExpression();

            if (expression instanceof AllTableColumns allTableColumns) {
                Source qualified = analyzer.sources.findSource(
                    sources,
                    allTableColumns.getTable().getUnquotedName()
                );

                addWildcardColumns(columns, resultColumns, qualified);

                recordWildcardColumns(
                    analysis.wildcards(),
                    selectItem,
                    qualified,
                    sources
                );

                continue;
            }

            if (expression instanceof AllColumns) {
                for (WildcardSource wildcardSource : bareWildcardSources(sources)) {
                    if (wildcardSource.flat()) {
                        List<org.sqlcj.schema.Column> flatColumns = analyzer.sources.resolveWildcardColumns(
                            wildcardSource.columns(),
                            wildcardSource.source().nullable()
                        );

                        columns.addAll(flatColumns);
                        resultColumns.addAll(toQueryColumns(flatColumns));

                        continue;
                    }

                    addWildcardColumns(columns, resultColumns, wildcardSource.source());
                }

                recordWildcardColumns(analysis.wildcards(), selectItem, null, sources);

                continue;
            }

            org.sqlcj.schema.Column column = resolveColumnItem(
                selectItem,
                analysis,
                scope,
                namesResultColumns && columns.size() >= listedColumns,
                implicitAggregates
            );

            columns.add(column);
            resultColumns.add(toQueryColumn(column));
        }

        requireDistinctAggregateNames(columns, implicitAggregates);

        return new ResolvedProjection(columns, resultColumns);
    }

    /**
     * Requires every aggregate that took the name PostgreSQL gives its column
     * to be the only result column of this projection under that name,
     * compared ignoring case as a derived source's column names are, because a
     * name another column already takes names no component of its own. Every
     * column the projection resolved is compared, a column a wildcard expanded
     * included, and the aggregates are checked in the order the projection
     * writes them, so the first one whose name is taken is the one an alias is
     * asked for.
     */
    private void requireDistinctAggregateNames(
        List<org.sqlcj.schema.Column> columns,
        List<ImplicitAggregate> implicitAggregates
    ) {
        for (ImplicitAggregate aggregate : implicitAggregates) {
            long named = columns.stream()
                .filter(column -> column.name().equalsIgnoreCase(aggregate.name()))
                .count();

            if (named > 1) {
                throw new UnsupportedOperationException(
                    AGGREGATE_ALIAS_REJECTION.formatted(
                        aggregate.written(),
                        aggregate.written()
                    )
                );
            }
        }
    }

    /**
     * The sources a bare {@code *} expands, in the order PostgreSQL lists
     * their columns: every join group of the level in the order the query
     * writes it, and within a group that merges columns its merged columns
     * first and then the columns each of its sources still exposes, in the
     * order the group joins them. A group that merges none expands each of its
     * sources as it is written.
     *
     * <p>A group's merged source stands right after the group's first source,
     * and every other source of that group after it, so the sources a merging
     * group expands are the merged source's own and the ones the level lists
     * beside it.
     */
    List<WildcardSource> bareWildcardSources(List<Source> sources) {
        List<WildcardSource> wildcardSources = new ArrayList<>();

        for (int index = 0; index < sources.size(); index++) {
            Source source = sources.get(index);

            Source merged = index + 1 < sources.size() && sources.get(index + 1).merged()
                ? sources.get(index + 1)
                : null;

            if (merged == null) {
                wildcardSources.add(WildcardSource.of(source));

                continue;
            }

            wildcardSources.add(WildcardSource.flat(merged, merged.table().columns()));

            for (String name : merged.groupSources()) {
                Source groupSource = analyzer.sources.findSource(sources, name);

                wildcardSources.add(
                    WildcardSource.flat(groupSource, analyzer.sources.exposedColumns(groupSource))
                );
            }

            index += merged.groupSources().size();
        }

        return List.copyOf(wildcardSources);
    }

    /**
     * Records the columns one analyzed wildcard selects, written as the
     * executed statement lists them, so that the statement reads the columns
     * this analysis resolved and a column the database gains later shifts no
     * value the generated mapper reads.
     *
     * <p>{@code qualified} is the source a {@code qualifier.*} names, every
     * column of whose own table it lists under that qualifier, the names a join
     * merged away from the source included, because PostgreSQL exposes each of
     * them there. A bare {@code *} is {@code null} instead and lists the
     * columns of its level's sources exactly as {@link #bareWildcardSources}
     * expands them: a join group's merged columns unqualified, because they
     * belong to no one source of the group, and every other column under the
     * name its own source exposes.
     *
     * <p>Every name is written as a delimited identifier, so a stored name of
     * any spelling reads the column it names and no keyword is read as one.
     */
    void recordWildcardColumns(
        Wildcards wildcards,
        SelectItem<?> selectItem,
        Source qualified,
        List<Source> sources
    ) {
        List<String> columns = new ArrayList<>();

        if (qualified != null) {
            for (org.sqlcj.schema.Column column : qualified.table().columns()) {
                columns.add(qualifiedColumn(qualified, column));
            }
        } else {
            for (WildcardSource wildcardSource : bareWildcardSources(sources)) {
                for (org.sqlcj.schema.Column column : wildcardSource.columns()) {
                    columns.add(
                        wildcardSource.source().merged()
                            ? mergedWildcardColumn(column, sources)
                            : qualifiedColumn(wildcardSource.source(), column)
                    );
                }
            }
        }

        wildcards.record(selectItem, String.join(", ", columns));
    }

    /**
     * One column a wildcard lists, written under the name its source exposes,
     * which is the only name a qualified reference reads it through.
     */
    private String qualifiedColumn(Source source, org.sqlcj.schema.Column column) {
        return delimited(source.sqlName()) + "." + delimited(column.sqlName());
    }

    /**
     * One merged column a bare {@code *} lists, written unqualified, because
     * the join merged it away from every source of its group.
     *
     * <p>Another source of the same {@code FROM} clause may have a column of
     * that name, which PostgreSQL would read the unqualified name as ambiguous
     * between, so such a wildcard is rejected naming the fix of listing the
     * columns the query needs.
     */
    private String mergedWildcardColumn(
        org.sqlcj.schema.Column column,
        List<Source> sources
    ) {
        boolean ambiguous = sources.stream()
            .filter(source -> analyzer.sources.exposes(source, column.name()))
            .count() > 1;

        if (ambiguous) {
            throw new UnsupportedOperationException(
                AMBIGUOUS_MERGED_WILDCARD_COLUMN_REJECTION.formatted(column.name(), column.name())
            );
        }

        return delimited(column.sqlName());
    }

    /**
     * One name PostgreSQL stores, written as a delimited SQL identifier, with
     * each quote of the name itself doubled.
     */
    private String delimited(String storedName) {
        return '"' + storedName.replace("\"", "\"\"") + '"';
    }

    /**
     * Adds one wildcard of one source to the expanded columns and to the result
     * components: the source's columns in schema order, and one embedded row of
     * the source's table in its own position.
     *
     * <p>The embedded row holds the table's own columns with their declared
     * nullability, and the component itself is nullable when the source is
     * null-extended, which is where the absence of the whole row is recorded.
     * The expanded columns stay the columns a derived source exposes, so a
     * null-extended source still exposes every one of them as nullable.
     *
     * <p>A derived source has no row record, so it contributes its expanded
     * columns as result components too.
     */
    private void addWildcardColumns(
        List<org.sqlcj.schema.Column> columns,
        List<QueryColumn> resultColumns,
        Source source
    ) {
        List<org.sqlcj.schema.Column> sourceColumns = analyzer.sources.resolveAllColumns(source);

        columns.addAll(sourceColumns);

        if (embedsRow(source)) {
            resultColumns.add(
                QueryColumn.row(
                    source.name(),
                    source.nullable(),
                    source.rowTable(),
                    toQueryColumns(analyzer.sources.resolveRowColumns(source))
                )
            );

            return;
        }

        resultColumns.addAll(toQueryColumns(sourceColumns));
    }

    /**
     * Reports whether a wildcard of one source selects the complete row of a
     * table the package generates a row record for, which a derived source has
     * none of.
     */
    private boolean embedsRow(Source source) {
        return !source.derived();
    }

    /**
     * Reports whether a wildcard of one source returns the shared row record
     * itself rather than embedding it, which a null-extended source does not,
     * because its row may be absent and a result of its own records that.
     */
    private boolean returnsRow(Source source) {
        return embedsRow(source) && !source.nullable();
    }

    /**
     * Resolves one projection item that is not a wildcard into its single
     * column: a direct column, an aggregate this subset types, an aliased call
     * the function catalogue types, an aliased cast, an aliased
     * {@code EXISTS} of a subquery, or an aliased JSON path that reads
     * {@code text}. Every other item is a computed projection, which a cast
     * states the type of.
     *
     * <p>A {@code COUNT} written with {@code FILTER (WHERE ...)}, which the
     * parser reports as a filtered analytic expression rather than a function
     * call, is the aggregate it counts as, so it is typed here beside the
     * plain call, and an {@code array_agg} of one column is the array
     * aggregate it collects, typed where no other aggregate rule types the
     * call.
     *
     * <p>A JSON path whose last step reads {@code text} is a nullable
     * {@code TEXT} column, because both text operators always return
     * {@code text}, and requires its alias exactly where a catalogue call
     * does, because PostgreSQL names such a column {@code ?column?}.
     *
     * <p>A call of the function catalogue is typed by the catalogue from the
     * facts its arguments state, so a cast is not needed; a call the catalogue
     * types no result of stays a computed projection, and a cast over such a
     * call keeps the cast's own type and nullness.
     *
     * <p>An {@code EXISTS} of a subquery is a non-null {@code BOOLEAN} column,
     * because PostgreSQL defines the predicate as {@code true} or
     * {@code false} and never {@code NULL}, and its subquery is analyzed as
     * the predicate's own subquery is, as the level inside this one, so a
     * correlated reference and a placeholder inside it resolve there. An
     * {@code EXISTS} the parser reports around anything but the subquery
     * itself, such as {@code NOT EXISTS (...)} or
     * {@code EXISTS (...)::boolean}, is not this item and stays a computed
     * projection.
     *
     * <p>A cast is typed by the cast itself and is non-null only where its
     * operand is one this analysis knows to read no {@code NULL}, which it
     * resolves a direct operand against the levels in scope for.
     *
     * <p>A catalogue call, a cast, and an {@code EXISTS} require their alias
     * only where {@code namesResultColumns} states that this item names its own
     * column, which a projection that names no result column and a position a
     * column list renames both state it does not. An aggregate takes the name
     * PostgreSQL gives its column there instead, and is recorded in
     * {@code implicitAggregates} for the projection to check that name once
     * every item of it has resolved.
     */
    private org.sqlcj.schema.Column resolveColumnItem(
        SelectItem<?> selectItem,
        Analysis analysis,
        Scope scope,
        boolean namesResultColumns,
        List<ImplicitAggregate> implicitAggregates
    ) {
        Expression expression = selectItem.getExpression();

        net.sf.jsqlparser.schema.Column selectedColumn = analyzer.sources.directColumn(expression);

        if (selectedColumn != null) {
            ResolvedColumn resolved = analyzer.sources.resolveColumn(selectedColumn, scope);
            org.sqlcj.schema.Column schemaColumn = resolved.column();

            return new org.sqlcj.schema.Column(
                selectedColumnName(selectItem.getAlias(), schemaColumn),
                analyzer.sources.requireSupportedType(schemaColumn),
                schemaColumn.nullable() || resolved.source().nullable(),
                null,
                schemaColumn.enumType(),
                schemaColumn.array(),
                schemaColumn.blankPadded(),
                selectedColumnSqlName(selectItem.getAlias(), schemaColumn)
            );
        }

        if (expression instanceof Function function) {
            org.sqlcj.schema.Column aggregate = aggregateColumn(
                function,
                selectItem.getAlias(),
                scope,
                namesResultColumns
            );

            if (aggregate == null) {
                aggregate = arrayAggregateColumn(
                    function,
                    selectItem.getAlias(),
                    scope,
                    namesResultColumns
                );
            }

            if (aggregate != null) {
                recordImplicitAggregate(
                    implicitAggregates,
                    selectItem,
                    aggregate,
                    namesResultColumns
                );

                return aggregate;
            }
        }

        if (expression instanceof AnalyticExpression analytic) {
            org.sqlcj.schema.Column filtered = filteredCountColumn(
                analytic,
                selectItem.getAlias(),
                scope,
                namesResultColumns
            );

            if (filtered != null) {
                recordImplicitAggregate(
                    implicitAggregates,
                    selectItem,
                    filtered,
                    namesResultColumns
                );

                return filtered;
            }
        }

        org.sqlcj.schema.Column catalogued = catalogueColumn(
            expression,
            selectItem.getAlias(),
            analysis,
            scope,
            namesResultColumns
        );

        if (catalogued != null) {
            return catalogued;
        }

        if (expression instanceof CastExpression cast && cast.getColDataType() != null) {
            return castColumn(
                cast,
                selectItem.getAlias(),
                analysis.schema(),
                scope,
                namesResultColumns
            );
        }

        if (
            expression instanceof ExistsExpression exists
                && exists.getRightExpression() instanceof ParenthesedSelect subquery
        ) {
            Alias alias = selectItem.getAlias();

            if (alias == null && namesResultColumns) {
                throw new UnsupportedOperationException(EXISTS_ALIAS_REJECTION);
            }

            analyzer.analyzePredicateSubquery(subquery, scope, analysis);

            return new org.sqlcj.schema.Column(
                alias == null
                    ? analyzer.written(exists)
                    : alias.getUnquotedName(),
                ColumnType.BOOLEAN,
                false,
                null,
                null,
                false,
                false,
                aliasSqlName(alias, analyzer.written(exists))
            );
        }

        if (expression instanceof JsonExpression json && analyzer.placeholderResolver.readsJsonText(json)) {
            return jsonTextColumn(json, selectItem.getAlias(), scope, namesResultColumns);
        }

        if (expression instanceof JdbcParameter || expression instanceof JdbcNamedParameter) {
            throw analyzer.placeholderResolver
                .untypedPlaceholder(analyzer.placeholderResolver.occurrenceOf(analysis.parsedSql(), expression));
        }

        throw new UnsupportedOperationException(
            COMPUTED_PROJECTION_REJECTION.formatted(
                analyzer.written(expression),
                castOperand(expression),
                hintAlias(selectItem.getAlias())
            )
        );
    }

    /**
     * Records one resolved aggregate that took the name PostgreSQL gives its
     * column, which is every typed aggregate a projection that names the
     * result columns writes no alias on, so that the projection checks that
     * name against its other result columns.
     */
    private void recordImplicitAggregate(
        List<ImplicitAggregate> implicitAggregates,
        SelectItem<?> selectItem,
        org.sqlcj.schema.Column aggregate,
        boolean namesResultColumns
    ) {
        if (selectItem.getAlias() == null && namesResultColumns) {
            implicitAggregates.add(
                new ImplicitAggregate(
                    aggregate.name(),
                    analyzer.written(selectItem.getExpression())
                )
            );
        }
    }

    /**
     * One computed projection as the cast that states its type writes its
     * operand: the expression as the query writes it, which a function call is
     * cast directly, and every other expression in parentheses, because
     * {@code ::} binds tighter than an operator the expression writes.
     */
    String castOperand(Expression expression) {
        String written = analyzer.written(expression);

        return expression instanceof Function
            ? written
            : "(" + written + ")";
    }

    /**
     * The result alias a cast hint writes, which is the alias the query writes
     * as it spells it, and {@code <alias>} for the user to fill in where the
     * query writes none.
     */
    String hintAlias(Alias alias) {
        return alias == null
            ? "<alias>"
            : alias.getName();
    }

    /**
     * Resolves one projected aggregate into its single result column, and
     * reports {@code null} for a function this rule types no result of, which
     * the computed-projection rejection then names as the query writes it.
     *
     * <p>A typed aggregate is an unqualified case-insensitive {@code COUNT},
     * {@code MIN}, {@code MAX}, {@code SUM}, or {@code AVG} of exactly one
     * argument, written with or without {@code DISTINCT} or {@code ALL}, so a
     * qualified name such as {@code pg_catalog.count(*)}, another function, and
     * the {@code FILTER} and {@code OVER} forms, which the parser reports as
     * expressions of their own, are typed by no rule here; a filtered
     * {@code COUNT} is typed by {@link #filteredCountColumn} instead.
     *
     * <p>A typed aggregate names its own result column, so it needs no alias:
     * the column takes the alias the query writes, and the name PostgreSQL
     * gives it, which is the aggregate's function name in lower case, where
     * the query writes none.
     *
     * <p>A {@code COUNT} is a non-null {@code BIGINT} of any argument, because
     * {@code count} returns {@code bigint} and counts {@code 0} rows rather
     * than none: a direct column resolves against the query's sources whether
     * or not its type is mapped, {@code COUNT(<source>.*)} resolves its
     * qualifier, and any other argument reaches the database as written. Only
     * {@code *} written with {@code DISTINCT} or {@code ALL} is left untyped,
     * because PostgreSQL's grammar admits {@code *} alone.
     *
     * <p>{@code MIN}, {@code MAX}, {@code SUM}, and {@code AVG} take one
     * direct, unsubscripted column of a mapped type and are nullable, because
     * each reads {@code NULL} over no row. {@code MIN} and {@code MAX} take the
     * column's own type, its enum type, its array spelling, and its
     * blank-padded spelling included; {@code SUM} and {@code AVG} take the type
     * PostgreSQL 16 defines for the column's scalar type. A column of any other
     * type is left untyped, because PostgreSQL defines no such aggregate for
     * it.
     */
    org.sqlcj.schema.Column aggregateColumn(
        Function function,
        Alias alias,
        Scope scope,
        boolean namesResultColumns
    ) {
        List<String> name = function.getMultipartName();
        ExpressionList<?> arguments = function.getParameters();

        if (name == null || name.size() != 1 || arguments == null || arguments.size() != 1) {
            return null;
        }

        String aggregate = name.get(0).toLowerCase(Locale.ROOT);
        Expression argument = arguments.get(0);
        String implicitName = implicitAggregateName(aggregate, function, namesResultColumns);

        if (aggregate.equals("count")) {
            return countedColumn(
                argument,
                function.isDistinct() || function.isAllColumns(),
                alias,
                implicitName,
                scope
            );
        }

        boolean extremum = aggregate.equals("min") || aggregate.equals("max");

        Map<ColumnType, ColumnType> scalarTypes = switch (aggregate) {
            case "sum" -> SUM_TYPES;
            case "avg" -> AVERAGE_TYPES;
            default -> Map.of();
        };

        if (!extremum && scalarTypes.isEmpty()) {
            return null;
        }

        net.sf.jsqlparser.schema.Column argumentColumn = analyzer.sources.directColumn(argument);

        if (argumentColumn == null) {
            return null;
        }

        org.sqlcj.schema.Column column = analyzer.sources.resolveColumn(argumentColumn, scope).column();
        ColumnType type = analyzer.sources.requireSupportedType(column);

        if (extremum) {
            return column.array() || !UNORDERED_TYPES.contains(type)
                ? new org.sqlcj.schema.Column(
                    aggregateName(alias, implicitName),
                    extremumType(type, column.array()),
                    true,
                    null,
                    column.enumType(),
                    column.array(),
                    column.blankPadded(),
                    aliasSqlName(alias, implicitName)
                )
                : null;
        }

        ColumnType aggregated = column.array()
            ? null
            : scalarTypes.get(type);

        return aggregated == null
            ? null
            : new org.sqlcj.schema.Column(
                aggregateName(alias, implicitName),
                aggregated,
                true,
                null,
                null,
                false,
                false,
                aliasSqlName(alias, implicitName)
            );
    }

    /**
     * Resolves a projected {@code array_agg} of one column into its single
     * result column, and reports {@code null} for every other call, which the
     * computed-projection rejection then names as the query writes it.
     *
     * <p>The column is a nullable one-dimensional array of the collected
     * column's own type, its enum type and its blank-padded spelling included,
     * because {@code array_agg} reads {@code NULL} over no row, and it is named
     * as every other typed aggregate is: by its alias, and by the name
     * PostgreSQL gives it, {@code array_agg}, where the query writes none.
     *
     * <p>Only an unqualified case-insensitive {@code array_agg} of exactly one
     * direct, unsubscripted, non-array column is collected, written with or
     * without {@code DISTINCT}, {@code ALL}, or an {@code ORDER BY} inside the
     * call. An array column, which PostgreSQL collects into a multidimensional
     * array, a {@code BYTEA}, {@code JSON}, or {@code JSONB} column, an array
     * of which sqlcj maps no type of, a qualified name, another argument, and
     * the {@code FILTER} and {@code OVER} forms, which the parser reports as
     * expressions of their own, stay computed projections; a column of a type
     * the schema recorded without a mapping is rejected as a {@code MIN} of one
     * is, because the result takes its type from the column.
     */
    private org.sqlcj.schema.Column arrayAggregateColumn(
        Function function,
        Alias alias,
        Scope scope,
        boolean namesResultColumns
    ) {
        List<String> name = function.getMultipartName();
        ExpressionList<?> arguments = function.getParameters();

        if (
            name == null
                || name.size() != 1
                || !name.get(0).equalsIgnoreCase("array_agg")
                || arguments == null
                || arguments.size() != 1
        ) {
            return null;
        }

        net.sf.jsqlparser.schema.Column argumentColumn = analyzer.sources.directColumn(arguments.get(0));

        if (argumentColumn == null) {
            return null;
        }

        org.sqlcj.schema.Column column = analyzer.sources.resolveColumn(argumentColumn, scope).column();
        ColumnType type = analyzer.sources.requireSupportedType(column);

        if (column.array() || !analyzer.columnTypeMapping.isArrayElementType(type)) {
            return null;
        }

        String implicitName = implicitAggregateName("array_agg", function, namesResultColumns);

        return new org.sqlcj.schema.Column(
            aggregateName(alias, implicitName),
            type,
            true,
            null,
            column.enumType(),
            true,
            column.blankPadded(),
            aliasSqlName(alias, implicitName)
        );
    }

    /**
     * The type of a {@code MIN} or {@code MAX} of one ordered column, which is
     * the column's own type except where PostgreSQL 16 defines the aggregate
     * over another type: a scalar {@code cidr} is compared as the
     * {@code inet} it converts to, so its extremum is an {@code inet}. An
     * array keeps its element type, because PostgreSQL orders the array itself.
     */
    private ColumnType extremumType(ColumnType type, boolean array) {
        return array
            ? type
            : EXTREMUM_TYPES.getOrDefault(type, type);
    }

    /**
     * Resolves a projected {@code COUNT} of one argument into its single result
     * column, and reports {@code null} for the one argument shape PostgreSQL
     * counts no rows of, a {@code *} written with {@code DISTINCT} or
     * {@code ALL}.
     *
     * <p>A direct column and the qualifier of {@code COUNT(<source>.*)} resolve
     * against the query's sources, which rejects a reference no source exposes,
     * while the type of a counted column is never read, so a column of an
     * unmapped type is counted too. Every other argument reaches the database
     * as written, as the operand of a cast projection does.
     *
     * <p>{@code quantified} states that the query writes {@code DISTINCT} or
     * {@code ALL} before the argument, and {@code implicitName} the name the
     * column takes where the query writes no alias, so a plain and a filtered
     * {@code COUNT} count one argument by the same rules.
     */
    private org.sqlcj.schema.Column countedColumn(
        Expression argument,
        boolean quantified,
        Alias alias,
        String implicitName,
        Scope scope
    ) {
        net.sf.jsqlparser.schema.Column counted = analyzer.sources.directColumn(argument);

        if (argument.getClass() == AllColumns.class) {
            if (quantified) {
                return null;
            }
        } else if (argument instanceof AllTableColumns qualified) {
            analyzer.sources.findSource(scope, qualified.getTable().getUnquotedName());
        } else if (counted != null) {
            analyzer.sources.resolveColumn(counted, scope);
        }

        return new org.sqlcj.schema.Column(
            aggregateName(alias, implicitName),
            ColumnType.BIGINT,
            false,
            null,
            null,
            false,
            false,
            aliasSqlName(alias, implicitName)
        );
    }

    /**
     * Resolves a projected {@code COUNT} written with {@code FILTER (WHERE
     * ...)}, which the parser reports as a filtered analytic expression rather
     * than a function call, into its single result column, and reports
     * {@code null} for every other analytic expression, which the
     * computed-projection rejection then names as the query writes it.
     *
     * <p>The counted rows are the ones the condition keeps, so the column is
     * the non-null {@code BIGINT} a plain {@code COUNT} is, under its argument
     * rules and under its naming, while the condition itself is no result
     * expression and reaches the database as written.
     *
     * <p>Only an unqualified case-insensitive {@code count} of exactly one
     * argument is counted here, so a qualified name such as
     * {@code pg_catalog.count(*) FILTER (...)}, a second argument, which the
     * parser reports as the offset, a {@code FILTER} on another aggregate, and
     * the {@code OVER} forms are typed by no rule here.
     */
    org.sqlcj.schema.Column filteredCountColumn(
        AnalyticExpression analytic,
        Alias alias,
        Scope scope,
        boolean namesResultColumns
    ) {
        String name = analytic.getName();
        Expression argument = analytic.getExpression();

        if (
            analytic.getType() != AnalyticType.FILTER_ONLY
                || name == null
                || !name.equalsIgnoreCase("count")
                || argument == null
                || analytic.getOffset() != null
                || analytic.getDefaultValue() != null
        ) {
            return null;
        }

        return countedColumn(
            argument,
            analytic.isDistinct() || analytic.isAllColumns(),
            alias,
            implicitAggregateName("count", analytic, namesResultColumns),
            scope
        );
    }

    /**
     * The name a typed aggregate's result column takes, which is its alias,
     * written with or without {@code AS}, and {@code implicitName} where the
     * query writes no alias at all.
     */
    private String aggregateName(Alias alias, String implicitName) {
        return alias == null
            ? implicitName
            : alias.getUnquotedName();
    }

    /**
     * The name a typed aggregate's result column takes where the query writes
     * no alias on it, which is also the name PostgreSQL stores for it, so a
     * derived source exposes the column and a wildcard of that source reads it
     * under this name.
     *
     * <p>Wherever the projection names the result columns, it is the name
     * PostgreSQL gives the column: the aggregate's own function name in lower
     * case, such as {@code count}. A projection that names no result column —
     * a branch of a set operation other than the leftmost, and a position a
     * column list on a {@code WITH} name renames — keeps the aggregate as the
     * query writes it as a name of its own.
     */
    private String implicitAggregateName(
        String aggregate,
        Expression expression,
        boolean namesResultColumns
    ) {
        return namesResultColumns
            ? aggregate
            : analyzer.written(expression);
    }

    /**
     * Resolves one projected call of the function catalogue into its single
     * result column, and reports {@code null} for every expression the
     * catalogue types no result of, which the computed-projection rejection then
     * names as the query writes it.
     *
     * <p>The call's own shape decides whether the catalogue reads it at all: a
     * plain call must be written unqualified, without {@code DISTINCT},
     * {@code ALL}, an {@code ORDER BY} inside the call, a named argument, or a
     * {@code *}, and a window call must be written with {@code OVER} and
     * without {@code FILTER} or {@code IGNORE NULLS}, so every other spelling
     * stays a computed projection. The arguments are the ones the query writes,
     * in order, which for {@code trim} are the characters and the string the
     * parser reports and for a window call are the read value, the offset, and
     * the default value.
     *
     * <p>The type is resolved before the alias is required, so a call this
     * catalogue types no result of says why rather than that it is unnamed, and
     * the alias itself is required exactly where an aggregate's is.
     */
    private org.sqlcj.schema.Column catalogueColumn(
        Expression expression,
        Alias alias,
        Analysis analysis,
        Scope scope,
        boolean namesResultColumns
    ) {
        FunctionCatalogue.Result result = catalogueResult(expression, analysis, scope);

        if (result == null) {
            return null;
        }

        String written = analyzer.written(expression);

        if (alias == null && namesResultColumns) {
            throw new UnsupportedOperationException(
                QueryAnalyzer.FUNCTION_ALIAS_REJECTION.formatted(written, written)
            );
        }

        FunctionCatalogue.ResultType type = result.type();

        return new org.sqlcj.schema.Column(
            alias == null
                ? written
                : alias.getUnquotedName(),
            type.type(),
            !result.nonNull(),
            null,
            type.enumType(),
            type.array(),
            type.blankPadded(),
            aliasSqlName(alias, written)
        );
    }

    /**
     * Resolves one projected JSON path whose last step reads {@code text} into
     * its single result column, which is a nullable {@code TEXT}, because
     * {@code ->>} and {@code #>>} always return {@code text} and either may
     * read a key the document does not hold.
     *
     * <p>A direct column the path reads resolves against the query's sources as
     * a selected column does, so a reference no source exposes is rejected,
     * while its type is not read, because PostgreSQL rather than sqlcj checks
     * that the operator applies to it. Every other base and every key reaches
     * the database as written.
     *
     * <p>PostgreSQL names such a column {@code ?column?}, so the alias is
     * required exactly where a catalogue call's is and is rejected with the
     * same diagnostic, naming the path as the query writes it.
     */
    private org.sqlcj.schema.Column jsonTextColumn(
        JsonExpression json,
        Alias alias,
        Scope scope,
        boolean namesResultColumns
    ) {
        net.sf.jsqlparser.schema.Column base = analyzer.sources.directColumn(json.getExpression());

        if (base != null) {
            analyzer.sources.resolveColumn(base, scope);
        }

        String written = analyzer.written(json);

        if (alias == null && namesResultColumns) {
            throw new UnsupportedOperationException(
                QueryAnalyzer.FUNCTION_ALIAS_REJECTION.formatted(written, written)
            );
        }

        return new org.sqlcj.schema.Column(
            alias == null
                ? written
                : alias.getUnquotedName(),
            ColumnType.TEXT,
            true,
            null,
            null,
            false,
            false,
            aliasSqlName(alias, written)
        );
    }

    /**
     * The result the catalogue states for one expression, and {@code null} for
     * an expression that writes no catalogue call and for a call the catalogue
     * types no result of.
     *
     * <p>The called name is read before any argument is, so the arguments of a
     * call the catalogue does not hold are never resolved and such a call keeps
     * the rejection it has without this catalogue.
     */
    private FunctionCatalogue.Result catalogueResult(
        Expression expression,
        Analysis analysis,
        Scope scope
    ) {
        return catalogueResult(expression, analysis, scope, true);
    }

    /**
     * The result the catalogue states for one expression, resolving its
     * arguments' references as {@code resolving} requires.
     */
    private FunctionCatalogue.Result catalogueResult(
        Expression expression,
        Analysis analysis,
        Scope scope,
        boolean resolving
    ) {
        CatalogueCall call = catalogueCall(expression);

        if (call == null || !analyzer.functionCatalogue.holds(call.name(), call.form())) {
            return null;
        }

        List<FunctionCatalogue.Argument> arguments = new ArrayList<>();

        for (Expression argument : call.arguments()) {
            arguments.add(argumentFact(argument, analysis, scope, resolving));
        }

        return analyzer.functionCatalogue.result(call.name(), call.form(), arguments);
    }

    /**
     * The call one expression writes for the catalogue to read, and
     * {@code null} for every expression that writes none.
     */
    CatalogueCall catalogueCall(Expression expression) {
        if (expression instanceof TrimFunction trim) {
            return writtenCall(
                "trim",
                FunctionCatalogue.Form.PLAIN,
                trimArguments(trim)
            );
        }

        if (expression instanceof Function function) {
            List<String> name = function.getMultipartName();

            if (
                name == null
                    || name.size() != 1
                    || function.isDistinct()
                    || function.isAllColumns()
                    || function.getOrderByElements() != null
            ) {
                return null;
            }

            return writtenCall(
                name.get(0),
                FunctionCatalogue.Form.PLAIN,
                arguments(function.getParameters())
            );
        }

        if (expression instanceof AnalyticExpression analytic) {
            String name = analytic.getName();

            if (
                analytic.getType() != AnalyticType.OVER
                    || analytic.isIgnoreNulls()
                    || analytic.isDistinct()
                    || analytic.isAllColumns()
                    || analytic.getFilterExpression() != null
                    || name == null
                    || name.indexOf('.') >= 0
            ) {
                return null;
            }

            return writtenCall(
                name,
                FunctionCatalogue.Form.WINDOW,
                windowArguments(analytic)
            );
        }

        return null;
    }

    /**
     * One call of the given arguments, and {@code null} where an argument is
     * one no catalogue call writes: a {@code *}, which PostgreSQL admits for no
     * function of the catalogue, and a named argument, which states no position.
     */
    private CatalogueCall writtenCall(
        String name,
        FunctionCatalogue.Form form,
        List<Expression> arguments
    ) {
        boolean positional = arguments.stream()
            .noneMatch(
                argument -> argument instanceof AllColumns
                    || argument instanceof OracleNamedFunctionParameter
            );

        return positional
            ? new CatalogueCall(name, form, arguments)
            : null;
    }

    /** The arguments of one plain call, in the order the query writes them. */
    private List<Expression> arguments(ExpressionList<?> parameters) {
        return parameters == null
            ? List.of()
            : new ArrayList<>(parameters);
    }

    /**
     * The arguments of one {@code trim}, which the parser reports as the trimmed
     * characters and, where the call writes {@code FROM} or a second argument,
     * the trimmed string. A call that writes only a trim specification, such as
     * {@code trim(BOTH FROM s)}, states the string alone.
     */
    private List<Expression> trimArguments(TrimFunction trim) {
        List<Expression> arguments = new ArrayList<>();

        if (trim.getExpression() != null) {
            arguments.add(trim.getExpression());
        }

        if (trim.getFromExpression() != null) {
            arguments.add(trim.getFromExpression());
        }

        return arguments;
    }

    /**
     * The arguments of one window call, which the parser reports as the read
     * value and, for {@code lag} and {@code lead}, the offset and the default
     * value beside it.
     */
    private List<Expression> windowArguments(AnalyticExpression analytic) {
        List<Expression> arguments = new ArrayList<>();

        if (analytic.getExpression() != null) {
            arguments.add(analytic.getExpression());
        }

        if (analytic.getOffset() != null) {
            arguments.add(analytic.getOffset());
        }

        if (analytic.getDefaultValue() != null) {
            arguments.add(analytic.getDefaultValue());
        }

        return arguments;
    }

    /**
     * The facts one argument of a catalogue call states: the type it has, where
     * this analysis knows one, and whether it reads no {@code NULL}.
     *
     * <p>A direct column resolves against the levels in scope exactly as a
     * selected column does, so an unknown or an ambiguous reference is rejected
     * there, and it carries its mapped type, its enum type, and its array and
     * blank-padded spellings, or no type where the schema recorded none. It
     * reads no {@code NULL} under the rule a cast operand follows: a
     * {@code NOT NULL} column of a source no join null-extends.
     *
     * <p>A cast carries the type it states, mapped as a schema column's
     * declared type is, and reads no {@code NULL} exactly where its operand
     * does by these same rules and by the rule a cast projection's operand
     * follows. The operand itself reaches the database as written, as a cast
     * projection's operand does, so it is read for its nullness alone and
     * nothing in it is required to resolve: a reference no source has, such as
     * {@code xmin}, and an aggregate of such a reference leave the cast
     * nullable rather than reject the query. A nested catalogue call and a
     * projected aggregate carry their own result and nullness, and an aggregate
     * of a column the schema recorded without a mapped type has no type here
     * rather than rejecting the query, because PostgreSQL rather than sqlcj
     * states what it returns.
     *
     * <p>A placeholder takes the type its position states, and reads no
     * {@code NULL} unless the query tests its logical parameter for null, at
     * this or at any other occurrence, because such a parameter is non-null and
     * so fails before the statement runs. An integer literal is the narrowest
     * integer type that holds it, a literal written with a decimal point or an
     * exponent is {@code numeric}, {@code TRUE} and {@code FALSE} are
     * {@code boolean}, and a string literal and {@code NULL} are untyped
     * literals, which take the type their position or the other arguments
     * state. Every other argument has no type and is read as nullable.
     */
    private FunctionCatalogue.Argument argumentFact(
        Expression argument,
        Analysis analysis,
        Scope scope
    ) {
        return argumentFact(argument, analysis, scope, true);
    }

    /**
     * The facts one argument states, resolving its references as
     * {@code resolving} requires: a directly written argument resolves them, as
     * a selected column does, and a cast operand resolves none, because the
     * cast states the type and the operand is read for its nullness alone.
     */
    private FunctionCatalogue.Argument argumentFact(
        Expression argument,
        Analysis analysis,
        Scope scope,
        boolean resolving
    ) {
        net.sf.jsqlparser.schema.Column column = analyzer.sources.directColumn(argument);

        if (column != null) {
            return columnFact(column, scope, resolving);
        }

        if (argument instanceof CastExpression cast && cast.getColDataType() != null) {
            Schema schema = analysis.schema();

            ColumnTypeMapping.MappedType type = analyzer.columnTypeMapping.map(
                cast.getColDataType(),
                schema.enums(),
                schema.domains(),
                new SchemaNamespace(schema.namespace())
            );

            return typedFact(
                type.type(),
                type.enumType(),
                type.array(),
                type.blankPadded(),
                operandReadsNoNull(cast.getLeftExpression(), analysis, scope)
            );
        }

        FunctionCatalogue.Result nested = catalogueResult(argument, analysis, scope, resolving);

        if (nested != null) {
            return FunctionCatalogue.Argument.typed(nested.type(), nested.nonNull());
        }

        if (
            argument instanceof Function function
                && !leavesAggregateUntyped(function, scope, resolving)
        ) {
            org.sqlcj.schema.Column aggregate = aggregateColumn(function, null, scope, false);

            if (aggregate != null) {
                return typedFact(
                    aggregate.type(),
                    aggregate.enumType(),
                    aggregate.array(),
                    aggregate.blankPadded(),
                    !aggregate.nullable()
                );
            }
        }

        if (argument instanceof JdbcParameter || argument instanceof JdbcNamedParameter) {
            return FunctionCatalogue.Argument.placeholder(!testsNull(argument, analysis));
        }

        return literalFact(argument);
    }

    /**
     * Reports whether the operand of one cast argument reads no {@code NULL},
     * which is the only fact that operand states, because the cast states the
     * type.
     *
     * <p>The operand reads none where these argument rules state it — a
     * placeholder whose parameter the query does not test for null, a literal
     * other than {@code NULL}, a catalogue call that reads none, and a
     * {@code NOT NULL} column of a source no join null-extends — and where the
     * rule a cast projection's operand follows states it, which adds a
     * {@code COUNT} however its own argument is written. Nothing here is
     * required to resolve, so an operand this analysis cannot read is nullable
     * rather than rejected.
     */
    private boolean operandReadsNoNull(Expression operand, Analysis analysis, Scope scope) {
        return argumentFact(operand, analysis, scope, false).nonNull()
            || readsNoNull(operand, scope);
    }

    /**
     * The facts one column reference states as an argument of a catalogue call:
     * its mapped type, its enum type, and its array and blank-padded spellings,
     * or no type where the schema recorded none, and no {@code NULL} where it is
     * a {@code NOT NULL} column of a source no join null-extends.
     *
     * <p>A directly written argument resolves as a selected column does, so an
     * unknown or an ambiguous reference is rejected there. A reference of a cast
     * operand resolves against the levels in scope where it can and states no
     * type where it cannot, because such an operand reaches the database as
     * written.
     */
    private FunctionCatalogue.Argument columnFact(
        net.sf.jsqlparser.schema.Column column,
        Scope scope,
        boolean resolving
    ) {
        Optional<ResolvedColumn> resolved = resolving
            ? Optional.of(analyzer.sources.resolveColumn(column, scope))
            : analyzer.sources.lookupColumn(column, scope);

        return resolved
            .map(
                found -> typedFact(
                    found.column().type(),
                    found.column().enumType(),
                    found.column().array(),
                    found.column().blankPadded(),
                    !found.column().nullable() && !found.source().nullable()
                )
            )
            .orElseGet(FunctionCatalogue.Argument::untyped);
    }

    /**
     * Reports whether the aggregate rule states no type for one function
     * argument that it would reject instead of typing.
     *
     * <p>An aggregate of a column the schema recorded without a mapped type
     * states no type here, because the result of the call around it is either
     * independent of the argument or left untyped, which the projection itself
     * reports; a {@code COUNT} never reads its argument's type, so it is typed
     * over such a column as it is anywhere.
     *
     * <p>Where nothing is required to resolve, which is the operand of a cast,
     * an aggregate of a reference that resolves against no source and a
     * {@code COUNT} of a qualifier no source exposes state no type either,
     * because such an operand is read for its nullness alone and is never
     * rejected. A directly written aggregate still rejects them, as it does
     * when the projection writes it on its own.
     */
    boolean leavesAggregateUntyped(Function function, Scope scope, boolean resolving) {
        List<String> name = function.getMultipartName();
        ExpressionList<?> arguments = function.getParameters();

        if (name == null || name.size() != 1 || arguments == null || arguments.size() != 1) {
            return false;
        }

        Expression argument = arguments.get(0);
        boolean counted = name.get(0).equalsIgnoreCase("count");

        if (argument instanceof AllTableColumns qualified) {
            return !resolving
                && analyzer.sources.lookupSource(scope, qualified.getTable().getUnquotedName()).isEmpty();
        }

        net.sf.jsqlparser.schema.Column column = analyzer.sources.directColumn(argument);

        if (column == null) {
            return false;
        }

        Optional<ResolvedColumn> resolved = analyzer.sources.lookupColumn(column, scope);

        return resolved.isEmpty()
            ? !resolving
            : !counted && resolved.get().column().type() == null;
    }

    /**
     * One argument of the given type and nullness, and one of no type where the
     * type is unmapped, which is read as nullable wherever the result does not
     * depend on it.
     */
    private FunctionCatalogue.Argument typedFact(
        ColumnType type,
        String enumType,
        boolean array,
        boolean blankPadded,
        boolean nonNull
    ) {
        return type == null
            ? FunctionCatalogue.Argument.untyped()
            : FunctionCatalogue.Argument.typed(
                new FunctionCatalogue.ResultType(type, enumType, array, blankPadded),
                nonNull
            );
    }

    /**
     * The facts one literal argument states, and no type for every other
     * expression. An integer literal written with a leading sign states the
     * signed value's own type, so {@code -1} is an {@code integer} as {@code 1}
     * is.
     */
    private FunctionCatalogue.Argument literalFact(Expression argument) {
        if (argument instanceof SignedExpression signed) {
            return signedFact(signed);
        }

        if (argument instanceof LongValue integer) {
            return integerFact(integer.getBigIntegerValue());
        }

        if (argument instanceof DoubleValue) {
            return typedFact(ColumnType.DECIMAL, null, false, false, true);
        }

        if (argument instanceof BooleanValue) {
            return typedFact(ColumnType.BOOLEAN, null, false, false, true);
        }

        if (argument instanceof StringValue) {
            return FunctionCatalogue.Argument.literal(true);
        }

        return argument instanceof NullValue
            ? FunctionCatalogue.Argument.literal(false)
            : FunctionCatalogue.Argument.untyped();
    }

    /** The facts one signed literal states, and no type for any other operand. */
    private FunctionCatalogue.Argument signedFact(SignedExpression signed) {
        Expression value = signed.getExpression();
        char sign = signed.getSign();

        if (sign != '-' && sign != '+') {
            return FunctionCatalogue.Argument.untyped();
        }

        if (value instanceof LongValue integer) {
            BigInteger written = integer.getBigIntegerValue();

            return integerFact(
                sign == '-'
                    ? written.negate()
                    : written
            );
        }

        return value instanceof DoubleValue
            ? typedFact(ColumnType.DECIMAL, null, false, false, true)
            : FunctionCatalogue.Argument.untyped();
    }

    /**
     * The facts one integer literal states, which is the narrowest integer type
     * PostgreSQL 16 reads it as: an {@code integer} within {@code int4}, a
     * {@code bigint} within {@code int8}, and a {@code numeric} beyond.
     */
    private FunctionCatalogue.Argument integerFact(BigInteger value) {
        ColumnType type;

        if (value.bitLength() < Integer.SIZE) {
            type = ColumnType.INTEGER;
        } else if (value.bitLength() < Long.SIZE) {
            type = ColumnType.BIGINT;
        } else {
            type = ColumnType.DECIMAL;
        }

        return typedFact(type, null, false, false, true);
    }

    /**
     * Reports whether the query tests the logical parameter of one placeholder
     * for null, at this or at any other occurrence of that parameter, which
     * gives a {@code null} bound at it a meaning and so leaves an argument
     * written as the placeholder nullable.
     */
    private boolean testsNull(Expression placeholder, Analysis analysis) {
        int index = analysis.placeholders().of(placeholder).index();

        return analysis.parsedSql().placeholders().stream()
            .filter(PlaceholderOccurrence::nullTested)
            .anyMatch(
                occurrence -> analysis.placeholders()
                    .of(occurrence.placeholder())
                    .index() == index
            );
    }

    /**
     * Resolves a cast projection into its single result column, which is named
     * after its alias and typed by the cast, as a cast types a placeholder. A
     * cast reads {@code NULL} exactly where its operand does, so the column is
     * non-null where the operand is one this analysis knows to read none and
     * nullable wherever that is unknown.
     *
     * <p>The alias is required wherever the projection names the result
     * columns, so a branch of a set operation other than the leftmost may write
     * a cast without one, which takes the cast as the query writes it as a name
     * of its own.
     *
     * <p>The operand itself reaches the database as written, as a non-binding
     * write value does, so PostgreSQL rather than sqlcj checks it. It is read
     * for its nullness alone, after the alias and the cast type are checked, so
     * no operand is rejected here and none is required to resolve.
     */
    org.sqlcj.schema.Column castColumn(
        CastExpression cast,
        Alias alias,
        Schema schema,
        Scope scope,
        boolean namesResultColumns
    ) {
        if (alias == null && namesResultColumns) {
            throw new UnsupportedOperationException(
                "A cast projection requires a result alias, such as SUM(amount)::numeric AS total."
            );
        }

        String name = alias == null
            ? analyzer.written(cast)
            : alias.getUnquotedName();

        ColumnTypeMapping.MappedType type = analyzer.columnTypeMapping.map(
            cast.getColDataType(),
            schema.enums(),
            schema.domains(),
            new SchemaNamespace(schema.namespace())
        );

        if (!type.mapped()) {
            throw new UnsupportedOperationException(
                "Result column '%s' has unsupported cast type %s"
                    .formatted(name, type.unsupportedType())
            );
        }

        return new org.sqlcj.schema.Column(
            name,
            type.type(),
            !readsNoNull(cast.getLeftExpression(), scope),
            null,
            type.enumType(),
            type.array(),
            type.blankPadded(),
            aliasSqlName(alias, name)
        );
    }

    /**
     * Reports whether one cast operand is an expression this analysis knows
     * reads no {@code NULL}, so that a cast of it is a non-null result column.
     * Every other operand, including one that resolves against no source, is
     * unknown here and leaves the cast nullable rather than rejected.
     *
     * <p>A direct column reads no {@code NULL} when it resolves through the
     * levels in scope to a non-null column of a source no join null-extends,
     * which is the rule a selected column of its own carries. Its type is never
     * read, so a column of an unmapped type states its nullness too.
     *
     * <p>An unqualified case-insensitive {@code COUNT} of one argument reads no
     * {@code NULL}, written plainly or with {@code FILTER (WHERE ...)}, which
     * the parser reports as a filtered analytic expression of no offset and no
     * default value, because {@code count} counts {@code 0} rows rather than
     * none. {@code COUNT(...) OVER (...)}, which the parser reports as a
     * windowed analytic expression, and a qualified name such as
     * {@code pg_catalog.count(*)} are typed by no rule here.
     *
     * <p>A cast of such an operand reads no {@code NULL} either, so a cast
     * chain is read through to the operand it finally casts.
     */
    private boolean readsNoNull(Expression operand, Scope scope) {
        net.sf.jsqlparser.schema.Column column = analyzer.sources.directColumn(operand);

        if (column != null) {
            return analyzer.sources.lookupColumn(column, scope)
                .filter(resolved -> !resolved.column().nullable())
                .filter(resolved -> !resolved.source().nullable())
                .isPresent();
        }

        if (operand instanceof Function function) {
            List<String> name = function.getMultipartName();
            ExpressionList<?> arguments = function.getParameters();

            return name != null
                && name.size() == 1
                && name.get(0).equalsIgnoreCase("count")
                && arguments != null
                && arguments.size() == 1;
        }

        if (operand instanceof AnalyticExpression analytic) {
            return analytic.getType() == AnalyticType.FILTER_ONLY
                && analytic.getName() != null
                && analytic.getName().equalsIgnoreCase("count")
                && analytic.getOffset() == null
                && analytic.getDefaultValue() == null;
        }

        return operand instanceof CastExpression cast
            && readsNoNull(cast.getLeftExpression(), scope);
    }

    /**
     * Reports the table whose complete row a read returns, which is the table
     * of the one source a lone wildcard covers: {@code *} over a single source,
     * and {@code qualifier.*} beside any number of other sources. Every other
     * projection returns a result of its own, so it reports {@code null}.
     *
     * <p>The name is the schema's own spelling of the table, so a query-side
     * alias or spelling never splits one row identity.
     *
     * <p>A derived source names no table, so a read over one returns a result
     * specific to its own query even when the source is named like a table, and
     * a null-extended source's row may be absent, so a lone wildcard of one
     * returns a result of its own holding that row as one nullable component.
     */
    String resolveSelectRowTable(PlainSelect plainSelect, List<Source> sources) {
        List<SelectItem<?>> selectItems = plainSelect.getSelectItems();

        if (selectItems.size() != 1) {
            return null;
        }

        Expression expression = selectItems.get(0).getExpression();

        if (expression instanceof AllTableColumns allTableColumns) {
            return wildcardRowTable(
                analyzer.sources.findSource(sources, allTableColumns.getTable().getUnquotedName())
            );
        }

        return expression instanceof AllColumns && sources.size() == 1
            ? wildcardRowTable(sources.get(0))
            : null;
    }

    /**
     * The table whose complete row a wildcard of one source returns, and
     * {@code null} for a source whose wildcard is read as a result of its own.
     */
    private String wildcardRowTable(Source source) {
        return returnsRow(source)
            ? source.rowTable()
            : null;
    }

    /**
     * The analyzed result columns of one query, which carry the facts code
     * generation reads from a selected column.
     */
    List<QueryColumn> toQueryColumns(List<org.sqlcj.schema.Column> columns) {
        return columns.stream()
            .map(this::toQueryColumn)
            .toList();
    }

    /** The analyzed result column of one selected column. */
    QueryColumn toQueryColumn(org.sqlcj.schema.Column column) {
        return new QueryColumn(
            column.name(),
            column.type(),
            column.nullable(),
            column.enumType(),
            column.array()
        );
    }

    /**
     * Names a selected direct column after its explicit alias when the
     * projection declares one, so the alias reaches Java naming. The column's
     * type still comes from the schema column, and its nullability from the
     * schema column and its source.
     */
    String selectedColumnName(Alias alias, org.sqlcj.schema.Column schemaColumn) {
        return alias == null
            ? schemaColumn.name()
            : alias.getUnquotedName();
    }

    /**
     * The name PostgreSQL stores for one selected direct column, which is the
     * explicit alias of the projection where it declares one and the stored
     * name of the schema column otherwise.
     */
    private String selectedColumnSqlName(Alias alias, org.sqlcj.schema.Column schemaColumn) {
        return aliasSqlName(alias, schemaColumn.sqlName());
    }

    /**
     * The name PostgreSQL stores for one result column an alias names, and
     * {@code storedName} for a column written without one, which only a
     * projection that names no result column may write.
     */
    private String aliasSqlName(Alias alias, String storedName) {
        return alias == null
            ? storedName
            : SchemaNamespace.storedName(alias.getName());
    }
}
