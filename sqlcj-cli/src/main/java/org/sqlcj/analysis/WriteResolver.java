package org.sqlcj.analysis;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.MultiPartName;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.ReturningClause;
import net.sf.jsqlparser.statement.create.table.Index;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.ConflictActionType;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.insert.InsertConflictAction;
import net.sf.jsqlparser.statement.insert.InsertConflictTarget;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;
import org.sqlcj.parser.Query;
import org.sqlcj.parser.QueryType;
import org.sqlcj.schema.Schema;
import org.sqlcj.schema.parser.SchemaNamespace;
import org.sqlcj.sql.ParsedSql;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the writes of a query: it analyzes an {@code INSERT}, an
 * {@code UPDATE}, and a {@code DELETE}, resolves the rows and the assigned
 * values they write and the conflict clause of an insert, names the result
 * columns a {@code RETURNING} clause returns, and states the rejection of a
 * write outside the supported subset. It reads the analyzer it belongs to
 * for the facts the rest of one query's analysis resolves.
 */
final class WriteResolver {

    /**
     * The rejection of a returned item that is no column of the write's
     * sources, no wildcard of its target, and no cast, which is written as the
     * query spells it. A cast states the type of such an item, exactly as it
     * states the type of a computed projection, so the rejection names that
     * fix: the query's own expression, cast to a type the user writes in place
     * of {@code <type>}, under the query's own alias or {@code <alias>} where
     * it writes none.
     */
    private static final String COMPUTED_RETURNING_REJECTION = """
        Cannot infer the type of %s in RETURNING; state it with a cast and an alias, \
        such as %s::<type> AS %s""";

    /**
     * The rejection of an aliased returned wildcard, which is written as the
     * query spells the wildcard, because an alias names one result component
     * and a wildcard returns a row of them.
     */
    private static final String RETURNING_WILDCARD_ALIAS_REJECTION = """
        RETURNING %s must not be aliased.""";

    /**
     * The rejection of a modified returned wildcard, which is written as the
     * query spells the wildcard, because a modifier changes which columns the
     * wildcard returns.
     */
    private static final String RETURNING_WILDCARD_MODIFIER_REJECTION = """
        RETURNING %s must not be modified.""";

    /**
     * The rejection of a bare {@code *} on a joined write, which PostgreSQL
     * returns the columns of every source for, while the only complete row such
     * a write returns here is its target's.
     */
    private static final String JOINED_RETURNING_ALL_REJECTION = """
        RETURNING * is not supported with UPDATE ... FROM or DELETE ... USING; \
        write RETURNING %s.* for the target's row.""";

    /**
     * The rejection of a returned wildcard of a source other than the write
     * target, whose complete row the write does not return, named by the source
     * the qualifier exposes.
     */
    private static final String OTHER_SOURCE_RETURNING_WILDCARD_REJECTION = """
        RETURNING %s.* is not supported; return the columns of %s by name.""";

    /**
     * The rejection of an {@code UPDATE} that names several targets, whose
     * updated rows this subset does not model, naming the clause that adds a
     * source instead.
     */
    private static final String MULTI_TABLE_UPDATE_REJECTION = """
        UPDATE of several tables is not supported; write the other sources in UPDATE ... FROM.""";

    /**
     * The rejection of a joined {@code DELETE}, whose deleted rows this subset
     * does not model, naming the clause that adds a source instead.
     */
    private static final String JOINED_DELETE_REJECTION = """
        A joined DELETE is not supported; write the other sources in DELETE ... USING.""";

    /**
     * The rejection of an {@code INSERT} whose rows come from neither
     * parenthesized {@code VALUES} rows nor a {@code SELECT}, which are the
     * row sources this subset models.
     */
    private static final String INSERT_ROW_SOURCE_REJECTION = """
        INSERT requires parenthesized VALUES rows or a SELECT.""";

    /**
     * The rejection of a set operation written as the row source of an
     * {@code INSERT}, which states no projection the written columns pair with,
     * naming the rewrite that reads the same rows and is analyzed: a
     * {@code SELECT} from the set operation as a subquery source.
     */
    private static final String INSERT_SET_OPERATION_REJECTION = """
        A set operation as the row source of INSERT is not supported; select from it as a \
        subquery, such as SELECT * FROM (SELECT ... UNION SELECT ...) AS combined.""";

    /**
     * The rejection of a {@code VALUES} row or an insert {@code SELECT} that
     * writes a number of values other than the number of written columns.
     */
    private static final String INSERT_COUNT_REJECTION = """
        INSERT column and value counts must match.""";

    /**
     * The qualifier an {@code ON CONFLICT DO UPDATE} assignment writes to read
     * a column of the row the insert proposed, compared ignoring case as
     * PostgreSQL resolves it.
     */
    private static final String EXCLUDED_QUALIFIER = "EXCLUDED";

    /** The analysis whose query these writes are resolved for. */
    private final QueryAnalyzer analyzer;

    WriteResolver(QueryAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    QueryModel analyzeInsert(Query query, ParsedSql parsedSql, Insert insert, Schema schema) {
        ReturningClause returningClause = insert.getReturningClause();

        requireWriteQueryType(query, returningClause);

        Table table = insert.getTable();
        Source source = analyzer.sources.toSource(table, schema);

        Analysis analysis = new Analysis(
            parsedSql, schema, analyzer.placeholderResolver.toPlaceholders(parsedSql, schema)
        );

        List<Source> commonTableExpressions = analyzer.analyzeCommonTableExpressions(
            insert.getWithItemsList(),
            analysis
        );

        resolveInsertRows(insert, source.table(), commonTableExpressions, analysis);

        List<QueryColumn> columns = List.of();
        String rowTable = null;

        if (returningClause != null) {
            columns = resolveReturningColumns(
                returningClause,
                List.of(source),
                schema,
                analysis.wildcards()
            );
            rowTable = resolveReturningRowTable(returningClause, List.of(source));
        }

        resolveConflictParameters(insert, source, commonTableExpressions, analysis);

        return analyzer.toQueryModel(
            query,
            analysis,
            table.getUnquotedName(),
            columns,
            rowTable
        );
    }

    QueryModel analyzeUpdate(Query query, ParsedSql parsedSql, Update update, Schema schema) {
        ReturningClause returningClause = update.getReturningClause();

        requireWriteQueryType(query, returningClause);
        requireSupportedUpdateSources(update);

        Table table = update.getTable();
        Source target = analyzer.sources.toSource(table, schema);

        Placeholders placeholders = analyzer.placeholderResolver.toPlaceholders(parsedSql, schema);
        Analysis analysis = new Analysis(parsedSql, schema, placeholders);

        List<Source> commonTableExpressions = analyzer.analyzeCommonTableExpressions(
            update.getWithItemsList(),
            analysis
        );

        List<Source> sources = analyzer.sources.resolveWriteSources(
            target,
            update.getFromItem() == null
                ? List.of()
                : List.of(update.getFromItem()),
            update.getJoins(),
            analysis,
            commonTableExpressions
        );

        List<QueryColumn> columns = List.of();
        String rowTable = null;

        if (returningClause != null) {
            columns = resolveReturningColumns(
                returningClause,
                sources,
                schema,
                analysis.wildcards()
            );
            rowTable = resolveReturningRowTable(returningClause, sources);
        }

        Scope scope = Scope.of(sources, commonTableExpressions);

        resolveUpdateSetParameters(update.getUpdateSets(), target.table(), scope, placeholders);

        if (update.getWhere() != null) {
            analyzer.placeholderResolver.resolveParameters(update.getWhere(), scope, analysis);
        }

        return analyzer.toQueryModel(
            query,
            analysis,
            table.getUnquotedName(),
            columns,
            rowTable
        );
    }

    QueryModel analyzeDelete(Query query, ParsedSql parsedSql, Delete delete, Schema schema) {
        ReturningClause returningClause = delete.getReturningClause();

        requireWriteQueryType(query, returningClause);
        requireSupportedDeleteSources(delete);

        Table table = delete.getTable();
        Source target = analyzer.sources.toSource(table, schema);

        Analysis analysis = new Analysis(
            parsedSql, schema, analyzer.placeholderResolver.toPlaceholders(parsedSql, schema)
        );

        List<Source> commonTableExpressions = analyzer.analyzeCommonTableExpressions(
            delete.getWithItemsList(),
            analysis
        );

        List<Source> sources = analyzer.sources.resolveWriteSources(
            target,
            delete.getUsingFromItemList(),
            null,
            analysis,
            commonTableExpressions
        );

        List<QueryColumn> columns = List.of();
        String rowTable = null;

        if (returningClause != null) {
            columns = resolveReturningColumns(
                returningClause,
                sources,
                schema,
                analysis.wildcards()
            );
            rowTable = resolveReturningRowTable(returningClause, sources);
        }

        if (delete.getWhere() != null) {
            analyzer.placeholderResolver.resolveParameters(
                delete.getWhere(),
                Scope.of(sources, commonTableExpressions),
                analysis
            );
        }

        return analyzer.toQueryModel(
            query,
            analysis,
            table.getUnquotedName(),
            columns,
            rowTable
        );
    }

    /**
     * Requires the annotation that matches the write shape: a write without
     * {@code RETURNING} reports an affected-row count, once or once per batched
     * row, and a returning write produces rows like a read.
     */
    private void requireWriteQueryType(Query query, ReturningClause returningClause) {
        if (returningClause == null) {
            if (query.type() != QueryType.EXEC && query.type() != QueryType.BATCH_EXEC) {
                throw new UnsupportedOperationException(
                    "Write queries without RETURNING must be declared as :exec or :batchexec"
                );
            }

            return;
        }

        if (query.type() == QueryType.BATCH_EXEC) {
            throw new UnsupportedOperationException(
                "Write queries declared as :batchexec cannot use RETURNING, because a batch returns"
                    + " no rows; remove RETURNING or declare the query as :one, :optional, or :many"
            );
        }

        if (!analyzer.producesResult(query)) {
            throw new UnsupportedOperationException(
                "Write queries with RETURNING must be declared as :one, :optional, or :many"
            );
        }
    }

    /**
     * Resolves the returned columns in declared order against the write's
     * sources, the target first.
     *
     * <p>A clause that is exactly a wildcard of the target returns that table's
     * complete row, so it resolves to the row's own columns. A wildcard written
     * beside another item is one embedded row of the target in its own
     * position.
     */
    private List<QueryColumn> resolveReturningColumns(
        ReturningClause returningClause,
        List<Source> sources,
        Schema schema,
        Wildcards wildcards
    ) {
        if (
            returningClause.getKeyword() != ReturningClause.Keyword.RETURNING
                || returningClause.getDataItems() != null
        ) {
            throw new UnsupportedOperationException(
                "Only a RETURNING clause without data items is supported."
            );
        }

        boolean wholeRow = resolveReturningRowTable(returningClause, sources) != null;

        List<QueryColumn> columns = new ArrayList<>();

        for (SelectItem<?> returningItem : returningClause) {
            columns.addAll(
                resolveReturningItem(
                    returningItem,
                    sources,
                    schema,
                    !wholeRow,
                    wildcards
                )
            );
        }

        return List.copyOf(columns);
    }

    /**
     * Reports the table whose complete row a returning write returns, which is
     * the write target when the clause is exactly a wildcard of the target, and
     * {@code null} for a returned column list. The name is the schema's own
     * spelling of the table, so every query returning that row shares one row
     * identity.
     *
     * <p>A bare {@code *} is such a wildcard only on a single-table write,
     * because a joined write returns the columns of every source for it and is
     * rejected where the returned columns are resolved.
     */
    private String resolveReturningRowTable(ReturningClause returningClause, List<Source> sources) {
        if (returningClause.size() != 1) {
            return null;
        }

        Expression expression = returningClause.get(0).getExpression();
        Source target = sources.get(0);

        if (expression instanceof AllTableColumns qualified) {
            return analyzer.sources.lookupSource(sources, returningQualifier(qualified))
                .filter(source -> source == target)
                .map(source -> source.table().name())
                .orElse(null);
        }

        return expression instanceof AllColumns && sources.size() == 1
            ? target.table().name()
            : null;
    }

    /**
     * Resolves one returned item, which is a wildcard of the write target, a
     * direct column of one of the write's sources, or an aliased cast, whose
     * alias names its result component. Every other computed item, a
     * subscripted reference included, has no type to generate, so it is
     * rejected naming the cast that states one.
     *
     * <p>A returned column of a null-extended source is nullable even when its
     * schema declaration is not, because an unmatched row reads it as
     * {@code NULL}.
     *
     * <p>A returned cast is typed and its nullness read by the rule a cast
     * projection carries, against the write's sources. {@code RETURNING} names
     * one result column per item and accepts no aggregate, so the alias is
     * required here, where the cast as the query writes it names the fix,
     * rather than by that rule, whose example names an aggregate.
     */
    private List<QueryColumn> resolveReturningItem(
        SelectItem<?> returningItem,
        List<Source> sources,
        Schema schema,
        boolean embedRow,
        Wildcards wildcards
    ) {
        Expression expression = returningItem.getExpression();
        Alias alias = returningItem.getAlias();

        if (expression instanceof AllColumns allColumns) {
            return resolveReturningWildcard(
                returningItem,
                allColumns,
                alias,
                sources,
                embedRow,
                wildcards
            );
        }

        net.sf.jsqlparser.schema.Column returnedColumn = analyzer.sources.directColumn(expression);

        if (returnedColumn != null) {
            ResolvedColumn resolved = analyzer.sources.resolveColumn(returnedColumn, Scope.of(sources));
            org.sqlcj.schema.Column schemaColumn = resolved.column();

            return List.of(
                new QueryColumn(
                    analyzer.projectionResolver.selectedColumnName(alias, schemaColumn),
                    analyzer.sources.requireSupportedType(schemaColumn),
                    schemaColumn.nullable() || resolved.source().nullable(),
                    schemaColumn.enumType(),
                    schemaColumn.array()
                )
            );
        }

        if (expression instanceof CastExpression cast && cast.getColDataType() != null) {
            if (alias == null) {
                throw new UnsupportedOperationException(
                    QueryAnalyzer.FUNCTION_ALIAS_REJECTION.formatted(analyzer.written(cast), analyzer.written(cast))
                );
            }

            return List.of(
                analyzer.projectionResolver
                    .toQueryColumn(analyzer.projectionResolver.castColumn(cast, alias, schema, Scope.of(sources), true))
            );
        }

        throw new UnsupportedOperationException(
            COMPUTED_RETURNING_REJECTION.formatted(
                analyzer.written(expression),
                analyzer.projectionResolver.castOperand(expression),
                analyzer.projectionResolver.hintAlias(alias)
            )
        );
    }

    /**
     * Resolves one returned wildcard, which returns the complete row of the
     * write target: a bare {@code *} on a single-table write, and
     * {@code target.*} on any write. The columns of another source are
     * returnable by name, and a bare {@code *} on a joined write would return
     * every source's columns, so each is rejected naming what to write instead.
     *
     * <p>A wildcard written beside another returned item is one embedded row of
     * the target, named after the name the target exposes, and a clause that is
     * exactly the wildcard resolves to the row's own columns.
     *
     * <p>An alias names one result component and a modifier changes which
     * columns the wildcard returns, so neither is supported on a bare or a
     * qualified wildcard.
     */
    private List<QueryColumn> resolveReturningWildcard(
        SelectItem<?> returningItem,
        AllColumns allColumns,
        Alias alias,
        List<Source> sources,
        boolean embedRow,
        Wildcards wildcards
    ) {
        String written = writtenReturningWildcard(allColumns);

        if (alias != null) {
            throw new UnsupportedOperationException(
                RETURNING_WILDCARD_ALIAS_REJECTION.formatted(written)
            );
        }

        if (allColumns.getExceptColumns() != null || allColumns.getReplaceExpressions() != null) {
            throw new UnsupportedOperationException(
                RETURNING_WILDCARD_MODIFIER_REJECTION.formatted(written)
            );
        }

        Source target = sources.get(0);

        if (!(allColumns instanceof AllTableColumns qualified)) {
            if (sources.size() > 1) {
                throw new UnsupportedOperationException(
                    JOINED_RETURNING_ALL_REJECTION.formatted(target.name())
                );
            }

            List<QueryColumn> columns = returningWildcardColumns(target, embedRow);

            analyzer.projectionResolver.recordWildcardColumns(wildcards, returningItem, null, sources);

            return columns;
        }

        Source source = analyzer.sources.findSource(sources, returningQualifier(qualified));

        if (source != target) {
            throw new UnsupportedOperationException(
                OTHER_SOURCE_RETURNING_WILDCARD_REJECTION.formatted(source.name(), source.name())
            );
        }

        List<QueryColumn> columns = returningWildcardColumns(target, embedRow);

        analyzer.projectionResolver.recordWildcardColumns(wildcards, returningItem, target, sources);

        return columns;
    }

    /**
     * The returned components of one wildcard of the write target, which are
     * one embedded row of the target beside another returned item and the
     * target row's own columns when the clause is exactly the wildcard.
     */
    private List<QueryColumn> returningWildcardColumns(Source target, boolean embedRow) {
        List<QueryColumn> columns = analyzer.projectionResolver
            .toQueryColumns(analyzer.sources.resolveAllColumns(target));

        return embedRow
            ? List.of(QueryColumn.row(target.name(), target.rowTable(), columns))
            : columns;
    }

    /**
     * The exposed name a qualified returned wildcard names, which the parser
     * reports as the wildcard's own table, and as a returning qualifier for the
     * {@code old} and {@code new} row aliases of a write.
     */
    private String returningQualifier(AllTableColumns allTableColumns) {
        Table table = allTableColumns.getTable();

        return table == null
            ? allTableColumns.getReturningQualifier()
            : table.getUnquotedName();
    }

    /**
     * One returned wildcard as the query writes it, which a diagnostic names,
     * without any modifier the wildcard carries.
     */
    private String writtenReturningWildcard(AllColumns allColumns) {
        return allColumns instanceof AllTableColumns qualified
            ? returningQualifier(qualified) + ".*"
            : "*";
    }

    /**
     * Rejects an {@code UPDATE} that names several tables before {@code SET},
     * whose updated rows this subset does not model, on an {@code :exec} and on
     * a returning update alike. The parser reports both the comma-separated and
     * the joined spelling of that form as the joins of the target, so a source
     * written in {@code FROM}, which this subset models, is not one of them.
     */
    private void requireSupportedUpdateSources(Update update) {
        if (update.getStartJoins() != null) {
            throw new UnsupportedOperationException(MULTI_TABLE_UPDATE_REJECTION);
        }
    }

    /**
     * Rejects a joined {@code DELETE}, whose deleted rows this subset does not
     * model, on an {@code :exec} and on a returning delete alike. The parser
     * reports a {@code USING} source, which this subset models, as a source of
     * its own clause rather than as a join.
     */
    private void requireSupportedDeleteSources(Delete delete) {
        if (delete.getJoins() != null) {
            throw new UnsupportedOperationException(JOINED_DELETE_REJECTION);
        }
    }

    /**
     * Resolves the rows an {@code INSERT} writes against its explicit column
     * list, which pairs with the written values by position and is also their
     * textual order.
     *
     * <p>The row source is classified inside its parentheses, at any depth, as
     * a top-level {@code SELECT} is, so {@code VALUES} rows written inside
     * parentheses are those rows and a parenthesized query is the query it
     * encloses. A column list with no row source, as in {@code DEFAULT VALUES},
     * states no row of this subset at all.
     *
     * <p>A set operation pairs its own branches' columns by position, which the
     * written column list does not take part in, so it is rejected with the
     * rewrite that reads its rows as a subquery source instead.
     *
     * <p>Every parenthesis level of the row source may declare a {@code WITH}
     * list of its own, which is analyzed beside the insert's own clauses so its
     * placeholders are typed from its columns. A {@code VALUES} source reads no
     * source, so the derived sources such a list declares are read by nothing.
     */
    private void resolveInsertRows(
        Insert insert,
        org.sqlcj.schema.Table table,
        List<Source> insertCommonTableExpressions,
        Analysis analysis
    ) {
        ExpressionList<net.sf.jsqlparser.schema.Column> columns = insert.getColumns();

        if (columns == null || columns.isEmpty()) {
            throw new UnsupportedOperationException("INSERT requires an explicit column list.");
        }

        Select rowSource = insert.getSelect();

        if (rowSource == null) {
            throw new UnsupportedOperationException(INSERT_ROW_SOURCE_REJECTION);
        }

        List<Select> selects = analyzer.parenthesisLevels(rowSource);

        if (selects.get(selects.size() - 1) instanceof Values values) {
            analyzer.analyzeCommonTableExpressions(selects, analysis, insertCommonTableExpressions);
            resolveInsertValueRows(values, columns, table, analysis.placeholders());

            return;
        }

        if (selects.get(selects.size() - 1) instanceof SetOperationList) {
            throw new UnsupportedOperationException(INSERT_SET_OPERATION_REJECTION);
        }

        PlainSelect plainSelect = analyzer.requirePlainSelect(selects);

        resolveInsertSelect(
            plainSelect,
            columns,
            table,
            analyzer.analyzeCommonTableExpressions(selects, analysis, insertCommonTableExpressions),
            analysis
        );
    }

    /**
     * Resolves the values of every {@code VALUES} row against the column list,
     * which pairs with each row by position. The parser reports one row as the
     * parenthesized list of its values and several rows as a list of such
     * rows, so an element that is not a parenthesized row states no row.
     */
    private void resolveInsertValueRows(
        Values values,
        ExpressionList<net.sf.jsqlparser.schema.Column> columns,
        org.sqlcj.schema.Table table,
        Placeholders placeholders
    ) {
        ExpressionList<?> rows = values.getExpressions();

        if (rows instanceof ParenthesedExpressionList<?> singleRow) {
            resolveInsertValueRow(singleRow, columns, table, placeholders);

            return;
        }

        for (Expression element : rows) {
            if (!(element instanceof ParenthesedExpressionList<?> row)) {
                throw new UnsupportedOperationException(INSERT_ROW_SOURCE_REJECTION);
            }

            resolveInsertValueRow(row, columns, table, placeholders);
        }
    }

    /**
     * Resolves one {@code VALUES} row, each of whose values is typed from the
     * column it is written into. A value that binds no placeholder reaches the
     * database as written, so only its target column must exist, and a
     * placeholder repeated across rows is one parameter bound at each of its
     * positions.
     *
     * <p>A target written with a subscript inserts into one element of its
     * array column, so its value is not typed from that column, which must
     * still exist.
     *
     * <p>A value written into a nullable column stores SQL {@code NULL} there
     * for a {@code null} argument, so such a parameter is nullable, whatever
     * default the column declares, while one written into a {@code NOT NULL}
     * column is not.
     */
    private void resolveInsertValueRow(
        ParenthesedExpressionList<?> row,
        ExpressionList<net.sf.jsqlparser.schema.Column> columns,
        org.sqlcj.schema.Table table,
        Placeholders placeholders
    ) {
        if (row.size() != columns.size()) {
            throw new UnsupportedOperationException(INSERT_COUNT_REJECTION);
        }

        for (int index = 0; index < columns.size(); index++) {
            net.sf.jsqlparser.schema.Column target = columns.get(index);
            org.sqlcj.schema.Column column = analyzer.sources.findColumn(table, target.getUnquotedColumnName());

            if (analyzer.sources.directColumn(target) != null) {
                analyzer.placeholderResolver
                    .resolveColumnValue(row.get(index), column, placeholders, column.nullable());
            }
        }
    }

    /**
     * Resolves the {@code SELECT} an insert reads its rows from, which is
     * analyzed by the rules of a nested read: its sources resolve against the
     * common table expressions in scope, and its join conditions, {@code WHERE},
     * and pagination clauses type their own placeholders. It sees no source of
     * the insert, as PostgreSQL scopes it, and it may omit {@code FROM}, which
     * states one row of values and no source at all.
     *
     * <p>Its items pair with the column list by position and must match its
     * count. An item that is a placeholder, or a cast of one, is the value of
     * its target column, so it is typed as a written value is; a direct column
     * only resolves against the select's own sources, whether or not the schema
     * maps its type, because it binds nothing; and every other item reaches the
     * database as written.
     */
    private void resolveInsertSelect(
        PlainSelect plainSelect,
        ExpressionList<net.sf.jsqlparser.schema.Column> columns,
        org.sqlcj.schema.Table table,
        List<Source> commonTableExpressions,
        Analysis analysis
    ) {
        List<Source> sources = plainSelect.getFromItem() == null
            ? List.of()
            : analyzer.sources.resolveSources(plainSelect, analysis, commonTableExpressions, null);

        Scope scope = Scope.of(sources, commonTableExpressions);

        List<Expression> items = resolveInsertSelectItems(plainSelect, sources, analysis);

        if (items.size() != columns.size()) {
            throw new UnsupportedOperationException(INSERT_COUNT_REJECTION);
        }

        for (int index = 0; index < columns.size(); index++) {
            net.sf.jsqlparser.schema.Column target = columns.get(index);

            resolveInsertSelectItem(
                items.get(index),
                target,
                analyzer.sources.findColumn(table, target.getUnquotedColumnName()),
                scope,
                analysis.placeholders()
            );
        }

        analyzer.placeholderResolver.resolveBindingParameters(plainSelect, scope, analysis);
    }

    /**
     * The items an insert's {@code SELECT} writes by position, where each
     * column a wildcard expands to is one item of its own, reported as
     * {@code null} because it states a column of a source rather than a
     * written value. A bare {@code *} expands the columns of the level's join
     * groups, a group that merges columns listing its merged columns in place
     * of the columns they merge, and {@code qualifier.*} the columns of that
     * one source, neither of which needs a mapped type, because nothing binds
     * them.
     */
    private List<Expression> resolveInsertSelectItems(
        PlainSelect plainSelect,
        List<Source> sources,
        Analysis analysis
    ) {
        List<Expression> items = new ArrayList<>();

        for (SelectItem<?> selectItem : plainSelect.getSelectItems()) {
            Expression expression = selectItem.getExpression();

            if (expression instanceof AllTableColumns allTableColumns) {
                Source qualified = analyzer.sources.findSource(
                    sources,
                    allTableColumns.getTable().getUnquotedName()
                );

                addWildcardItems(items, qualified);

                analyzer.projectionResolver.recordWildcardColumns(
                    analysis.wildcards(),
                    selectItem,
                    qualified,
                    sources
                );

                continue;
            }

            if (expression instanceof AllColumns) {
                for (WildcardSource wildcardSource : analyzer.projectionResolver.bareWildcardSources(sources)) {
                    addWildcardItems(items, wildcardSource.columns().size());
                }

                analyzer.projectionResolver.recordWildcardColumns(analysis.wildcards(), selectItem, null, sources);

                continue;
            }

            items.add(expression);
        }

        return items;
    }

    /** One wildcard item per column of the source the wildcard expands over. */
    private void addWildcardItems(List<Expression> items, Source source) {
        addWildcardItems(items, source.table().columns().size());
    }

    /** One wildcard item per column one wildcard expands to. */
    private void addWildcardItems(List<Expression> items, int columns) {
        for (int index = 0; index < columns; index++) {
            items.add(null);
        }
    }

    /**
     * Resolves one item of an insert's {@code SELECT} as the value of the
     * column it is written into. A wildcard's column states a column of a
     * source rather than a value, so it types nothing, and neither does a
     * direct column, which only resolves, nor a value of a subscripted target,
     * whose array column must still exist.
     */
    private void resolveInsertSelectItem(
        Expression item,
        net.sf.jsqlparser.schema.Column target,
        org.sqlcj.schema.Column column,
        Scope scope,
        Placeholders placeholders
    ) {
        if (item == null) {
            return;
        }

        net.sf.jsqlparser.schema.Column selectedColumn = analyzer.sources.directColumn(item);

        if (selectedColumn != null) {
            analyzer.sources.resolveColumn(selectedColumn, scope);

            return;
        }

        if (analyzer.sources.directColumn(target) != null) {
            analyzer.placeholderResolver.resolveColumnValue(item, column, placeholders);
        }
    }

    /**
     * Resolves the assignment parameters of an {@code UPDATE} or of an
     * {@code ON CONFLICT DO UPDATE} action in source order, which precedes any
     * parameter in the {@code WHERE} expression. An assigned value that binds
     * no placeholder reaches the database as written, so only its target
     * column must exist.
     *
     * <p>A target written with a subscript assigns one element of its array
     * column, so its value is not typed from that column, which must still
     * exist.
     *
     * <p>Arithmetic inside an assigned value types the placeholders it writes
     * beside a numeric column of the level the assignment's own clause reads,
     * which is the level its {@code WHERE} expression reads.
     *
     * <p>A value assigned to a nullable column stores SQL {@code NULL} there
     * for a {@code null} argument, so such a parameter is nullable, whatever
     * default the column declares, while one assigned to a {@code NOT NULL}
     * column is not.
     */
    private void resolveUpdateSetParameters(
        List<UpdateSet> updateSets,
        org.sqlcj.schema.Table table,
        Scope scope,
        Placeholders placeholders
    ) {
        for (UpdateSet updateSet : updateSets) {
            if (updateSet.getColumns().size() != 1 || updateSet.getValues().size() != 1) {
                throw new UnsupportedOperationException(
                    "UPDATE assignments must set one column at a time."
                );
            }

            net.sf.jsqlparser.schema.Column target = updateSet.getColumn(0);
            org.sqlcj.schema.Column column = analyzer.sources.findColumn(table, target.getUnquotedColumnName());

            if (analyzer.sources.directColumn(target) != null) {
                analyzer.placeholderResolver.resolveColumnValue(
                    updateSet.getValue(0),
                    column,
                    placeholders,
                    column.nullable()
                );
            }

            analyzer.placeholderResolver.resolveArithmeticParameters(updateSet.getValue(0), scope, placeholders);
        }
    }

    /**
     * Resolves the conflict clause of an insert, whose placeholders are written
     * after the {@code VALUES} placeholders and therefore bind after them. A
     * {@code DO UPDATE ... WHERE} predicate reads both the stored row and the
     * proposed one, so its placeholders follow the {@code WHERE} rules over the
     * inserted table and {@code EXCLUDED} as two sources of the same columns,
     * which makes an unqualified column of either one ambiguous, as PostgreSQL
     * also reports it. An assigned value reads the same two sources, so
     * arithmetic in it types a placeholder from a qualified column of either
     * one.
     *
     * <p>{@code DO NOTHING} decides the conflict by itself, so it needs no
     * target, while {@code DO UPDATE} must name the rows it updates.
     */
    private void resolveConflictParameters(
        Insert insert,
        Source source,
        List<Source> commonTableExpressions,
        Analysis analysis
    ) {
        InsertConflictTarget target = insert.getConflictTarget();
        InsertConflictAction action = insert.getConflictAction();

        if (target == null && action == null) {
            return;
        }

        boolean doUpdate = action != null
            && action.getConflictActionType() == ConflictActionType.DO_UPDATE;

        if (target != null) {
            resolveConflictTarget(target, source, commonTableExpressions, analysis);
        } else if (doUpdate) {
            throw new UnsupportedOperationException(
                "ON CONFLICT DO UPDATE requires a conflict target,"
                    + " such as ON CONFLICT (id) or ON CONFLICT ON CONSTRAINT name."
            );
        }

        if (!doUpdate) {
            return;
        }

        List<UpdateSet> updateSets = action.getUpdateSets();

        Scope scope = Scope.of(
            List.of(
                source,
                new Source(
                    EXCLUDED_QUALIFIER,
                    SchemaNamespace.storedName(EXCLUDED_QUALIFIER),
                    source.table(),
                    false,
                    false
                )
            ),
            commonTableExpressions
        );

        resolveUpdateSetParameters(updateSets, source.table(), scope, analysis.placeholders());
        resolveExcludedColumns(updateSets, source.table());

        if (action.getWhereExpression() != null) {
            analyzer.placeholderResolver.resolveParameters(action.getWhereExpression(), scope, analysis);
        }
    }

    /**
     * Resolves the conflict target against the inserted table. Each element of
     * a target of plain column names is a column of that table, and the
     * target's predicate reads that table alone, under the name it exposes. A named
     * constraint, like the unique index a column-name target matches, is
     * resolved by PostgreSQL. An element that is an expression or carries a
     * collation or an operator class decides the conflict by something the
     * analyzed model does not describe.
     */
    private void resolveConflictTarget(
        InsertConflictTarget target,
        Source source,
        List<Source> commonTableExpressions,
        Analysis analysis
    ) {
        if (target.getConstraintName() != null) {
            return;
        }

        boolean columnNames = target.getIndexElements().stream().noneMatch(
            element -> element.isExpression()
                || element.getCollation() != null
                || element.getOperatorClass() != null
        );

        if (!columnNames) {
            throw new UnsupportedOperationException(
                "ON CONFLICT requires a target of column names, such as ON CONFLICT (id)."
            );
        }

        for (Index.ColumnParams element : target.getIndexElements()) {
            analyzer.sources.findColumn(source.table(), MultiPartName.unquote(element.getColumnName()));
        }

        if (target.getWhereExpression() != null) {
            analyzer.placeholderResolver.resolveParameters(
                target.getWhereExpression(),
                Scope.of(List.of(source), commonTableExpressions),
                analysis
            );
        }
    }

    /**
     * Resolves the column each {@code DO UPDATE} value that is exactly
     * {@code EXCLUDED.column} reads from the row the insert proposed, which is
     * a column of the inserted table. A value that merely contains such a
     * reference is not analyzed, as no other computed write value is.
     */
    private void resolveExcludedColumns(
        List<UpdateSet> updateSets,
        org.sqlcj.schema.Table table
    ) {
        for (UpdateSet updateSet : updateSets) {
            if (
                updateSet.getValue(0) instanceof net.sf.jsqlparser.schema.Column column
                    && EXCLUDED_QUALIFIER.equalsIgnoreCase(analyzer.sources.qualifier(column))
            ) {
                analyzer.sources.findColumn(table, column.getUnquotedColumnName());
            }
        }
    }
}
