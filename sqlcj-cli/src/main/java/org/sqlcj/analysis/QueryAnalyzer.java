package org.sqlcj.analysis;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.schema.MultiPartName;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.ParenthesedStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.delete.ParenthesedDelete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.insert.ParenthesedInsert;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperation;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.UnionOp;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.ParenthesedUpdate;
import net.sf.jsqlparser.statement.update.Update;
import org.sqlcj.parser.Query;
import org.sqlcj.parser.QueryType;
import org.sqlcj.schema.ColumnType;
import org.sqlcj.schema.Schema;
import org.sqlcj.schema.parser.ColumnTypeMapping;
import org.sqlcj.schema.parser.SchemaNamespace;
import org.sqlcj.sql.ParsedSql;
import org.sqlcj.sql.WildcardOccurrence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class QueryAnalyzer {

    static final String ANONYMOUS_PARAMETER_REJECTION = """
        Anonymous '?' parameters are not supported; use an indexed placeholder such as $1""";

    /**
     * The rejection of a placeholder written with a colon or ampersand that is
     * not a supported named placeholder, which is written with the placeholder
     * as the query spells it.
     */
    static final String NAMED_PLACEHOLDER_REJECTION = """
        Named placeholder '%s' is not supported; write an unquoted name of letters, digits, and \
        underscores directly after ':', such as :userId""";

    /** The rejection of a statement kind outside the supported subset. */
    static final String UNSUPPORTED_STATEMENT_REJECTION = """
        Only SELECT, INSERT, UPDATE, and DELETE statements are supported.""";

    /**
     * The rejection of a catalogue function projection without a result alias,
     * which names one result component as an aggregate's does. Both the rejected
     * item and the example are the call as the query writes it, so the named fix
     * is the query's own call followed by an alias the user writes.
     */
    static final String FUNCTION_ALIAS_REJECTION = """
        %s requires a result alias, such as %s AS <alias>.""";

    /**
     * The rejection of a top-level {@code SELECT} that projects a call of a
     * built-in function PostgreSQL declares as returning {@code void}, which no
     * result column holds. An {@code :exec} query is a write, so the form that
     * works is a read of the call as text: both the rejected item and the
     * example are the call as the query writes it, under the query's own alias
     * or {@code <alias>} where it writes none.
     */
    private static final String VOID_CALL_REJECTION = """
        %s returns void, which no result column holds; declare the query :one and select the \
        call as text, such as %s::text AS %s""";

    /**
     * The rejection of a column list that names more columns than its
     * {@code SELECT} returns, so the names past the last returned column rename
     * no column at all.
     */
    private static final String DERIVED_SOURCE_COLUMN_LIST_COUNT_REJECTION = """
        The column list of %s names %d columns, but its SELECT returns only %d.""";

    /**
     * The rejection of a read of a recursive {@code WITH} clause outside its
     * own recursive term, which is the one branch of its body that may read
     * it, as PostgreSQL requires of a recursive query.
     */
    private static final String RECURSIVE_SELF_REFERENCE_REJECTION = """
        WITH RECURSIVE %s may read itself only as a FROM or JOIN source of the branch after \
        its last UNION or UNION ALL.""";

    /**
     * The rejection of a read of a {@code WITH} clause of a recursive list that
     * is declared after the clause reading it, which PostgreSQL resolves and
     * this subset does not.
     */
    private static final String FORWARD_WITH_REFERENCE_REJECTION = """
        WITH clause %s is read before its declaration; declare it before the clause that \
        reads it.""";

    /**
     * The rejection of a {@code SEARCH} or {@code CYCLE} clause, which adds
     * columns to a {@code WITH} clause's rows that its own {@code SELECT} does
     * not state.
     */
    private static final String WITH_SEARCH_OR_CYCLE_REJECTION = """
        SEARCH and CYCLE clauses are not supported.""";

    /**
     * The rejection of one column of a recursive {@code WITH} clause whose two
     * terms resolve to a type the first term does not state, which PostgreSQL
     * requires the first term to state, so the cast belongs there.
     */
    private static final String RECURSIVE_COLUMN_TYPE_REJECTION = """
        Recursive WITH clause %s column %s has type %s in its first term but %s overall; \
        cast it in the first term, such as %s::%s""";

    /** The rejection of a {@code WITH} body that writes rows. */
    private static final String DATA_MODIFYING_WITH_REJECTION = """
        A data-modifying statement in WITH is not supported; run it as a query of its own.""";

    /** The character that opens a supported named placeholder. */
    static final String NAME_PREFIX = ":";

    /**
     * The character that separates the group of a parameter group placeholder
     * from its field, both in the placeholder and in the logical parameter name
     * the SQL parser compiled it under.
     */
    static final String GROUP_SEPARATOR = ".";

    /**
     * The scalar numeric types a set operation resolves to the widest of,
     * narrowest first, which is the type PostgreSQL 16 resolves each pair of
     * them to. They are also the column types arithmetic types a placeholder
     * written beside them from.
     */
    static final List<ColumnType> NUMERIC_WIDENING = List.of(
        ColumnType.SMALLINT,
        ColumnType.INTEGER,
        ColumnType.BIGINT,
        ColumnType.DECIMAL,
        ColumnType.REAL,
        ColumnType.DOUBLE_PRECISION
    );

    /**
     * The built-in functions PostgreSQL 16 declares as returning {@code void},
     * which no result column of a projection holds. Each name is unqualified
     * and in lower case, as {@link #builtinFunction} reports one.
     */
    private static final Set<String> VOID_FUNCTIONS = Set.of(
        "pg_advisory_lock",
        "pg_advisory_lock_shared",
        "pg_advisory_xact_lock",
        "pg_advisory_xact_lock_shared",
        "pg_advisory_unlock_all",
        "pg_notify",
        "pg_sleep"
    );

    /** Resolves the type a cast states, as a schema column declares its own. */
    final ColumnTypeMapping columnTypeMapping = new ColumnTypeMapping();

    /** The built-in functions whose projected result needs no cast. */
    final FunctionCatalogue functionCatalogue = new FunctionCatalogue();

    /** Resolves the sources of a query and the columns read through them. */
    final SourceResolver sources = new SourceResolver(this);

    /** Resolves the placeholders of a query and the parameters they bind. */
    final PlaceholderResolver placeholderResolver = new PlaceholderResolver(this);

    /** Resolves the projection of a query and the result columns it selects. */
    final ProjectionResolver projectionResolver = new ProjectionResolver(this);

    /** Resolves the set operations of a query and the columns they combine. */
    final SetOperationResolver setOperationResolver = new SetOperationResolver(this);

    /** Resolves the writes of a query and the result columns they return. */
    final WriteResolver writeResolver = new WriteResolver(this);

    public QueryModel analyze(Query query, ParsedSql parsedSql, Schema schema) {
        placeholderResolver.requireSupportedPlaceholders(parsedSql);
        placeholderResolver.requireNoParameterGroupInBatch(query, parsedSql);
        placeholderResolver.requireUsableParameterRecord(query, parsedSql);

        Statement statement = parsedSql.statement();

        if (statement instanceof Select select) {
            return analyzeSelectStatement(query, parsedSql, select, schema);
        }

        if (statement instanceof Insert insert) {
            return writeResolver.analyzeInsert(query, parsedSql, insert, schema);
        }

        if (statement instanceof Update update) {
            return writeResolver.analyzeUpdate(query, parsedSql, update, schema);
        }

        if (statement instanceof Delete delete) {
            return writeResolver.analyzeDelete(query, parsedSql, delete, schema);
        }

        throw new UnsupportedOperationException(UNSUPPORTED_STATEMENT_REJECTION);
    }

    /**
     * Analyzes a {@code SELECT} statement, which is classified by the query it
     * writes inside its parentheses, at any depth, so a parenthesized query is
     * the query it encloses: a plain {@code SELECT} or a set operation of
     * {@code SELECT}s. The analyzed read is the top-level statement, so the
     * annotation and the query model are decided here, while the sources, the
     * result columns, the row table, and the clause placeholders follow the
     * rules every analyzed query shares.
     *
     * <p>A projected {@code void} call is rejected before the annotation, so
     * the diagnostic names the read that works under every annotation alike.
     */
    private QueryModel analyzeSelectStatement(
        Query query,
        ParsedSql parsedSql,
        Select select,
        Schema schema
    ) {
        List<Select> selects = parenthesisLevels(select);

        if (selects.get(selects.size() - 1) instanceof SetOperationList setOperation) {
            return analyzeSetOperationStatement(query, parsedSql, selects, setOperation, schema);
        }

        PlainSelect plainSelect = requirePlainSelect(selects);

        requireNonVoidProjection(plainSelect);

        requireResultQueryType(query);

        Analysis analysis = new Analysis(parsedSql, schema, placeholderResolver.toPlaceholders(parsedSql, schema));

        return toReadQueryModel(
            query,
            analysis,
            analyzePlainSelect(
                plainSelect,
                analysis,
                analyzeCommonTableExpressions(selects, analysis, List.of()),
                true,
                0,
                null
            )
        );
    }

    /**
     * Rejects a top-level {@code SELECT} whose projection writes a call of a
     * built-in function PostgreSQL declares as returning {@code void}, which no
     * result column holds, naming the first such item in the order the
     * projection writes them. The rejection states the read that works, so it
     * runs before the annotation check and before any source or projection
     * resolves, under every annotation and whether or not the statement writes
     * {@code WITH} or {@code FROM}.
     *
     * <p>Only a call the projection writes directly, unqualified and unquoted,
     * is named here: a cast of such a call is the form this rejection names,
     * and a call nested in another expression, a qualified call, and a quoted
     * call keep the diagnostics their own forms state. A set operation, a
     * {@code WITH} body, and a subquery keep theirs too, because this rule
     * reads the top-level statement's own projection alone.
     */
    private void requireNonVoidProjection(PlainSelect plainSelect) {
        for (SelectItem<?> selectItem : plainSelect.getSelectItems()) {
            if (
                !(selectItem.getExpression() instanceof Function function)
                    || !VOID_FUNCTIONS.contains(builtinFunction(function))
            ) {
                continue;
            }

            String call = written(function);

            throw new UnsupportedOperationException(
                VOID_CALL_REJECTION.formatted(call, call, projectionResolver.hintAlias(selectItem.getAlias()))
            );
        }
    }

    /**
     * The built-in function one call names, in lower case, for a rule here to
     * recognize by name: an unquoted, unqualified name compared ignoring case.
     * A qualified call reports the empty string, and a quoted call reports its
     * name with the SQL identifier delimiters the parser keeps, so neither
     * names a built-in of any such rule.
     */
    String builtinFunction(Function function) {
        List<String> name = function.getMultipartName();

        return name == null || name.size() != 1
            ? ""
            : name.get(0).toLowerCase(Locale.ROOT);
    }

    /**
     * Analyzes a top-level set operation: every branch is analyzed as a query
     * of its own against the common table expressions each parenthesis level of
     * the statement declares, which every branch sees, and the result is the
     * branches' columns resolved into one projection.
     */
    private QueryModel analyzeSetOperationStatement(
        Query query,
        ParsedSql parsedSql,
        List<Select> selects,
        SetOperationList setOperation,
        Schema schema
    ) {
        requireResultQueryType(query);

        Analysis analysis = new Analysis(parsedSql, schema, placeholderResolver.toPlaceholders(parsedSql, schema));

        AnalyzedSelect analyzed = setOperationResolver.analyzeSetOperation(
            setOperation,
            analysis,
            analyzeCommonTableExpressions(selects, analysis, List.of()),
            true,
            0,
            null
        );

        return toReadQueryModel(query, analysis, analyzed);
    }

    /**
     * The analyzed model of one read, which returns the shared row record of
     * the table its analysis reports and otherwise the result components of its
     * own projection.
     */
    private QueryModel toReadQueryModel(
        Query query,
        Analysis analysis,
        AnalyzedSelect analyzed
    ) {
        String rowTable = analyzed.rowTable();

        return toQueryModel(
            query,
            analysis,
            analyzed.table(),
            rowTable == null
                ? analyzed.projection().resultColumns()
                : projectionResolver.toQueryColumns(analyzed.columns()),
            rowTable
        );
    }

    /**
     * Analyzes one nested {@code SELECT} by the rules of a top-level read: it
     * is classified inside its own parentheses, its own {@code WITH} list is
     * analyzed beside the common table expressions of the queries around it,
     * and its sources, result columns, and clause placeholders are resolved as
     * a top-level read's are. Its annotation, its row table, and the query
     * model stay the top-level statement's own facts.
     *
     * <p>A set operation is analyzed as a top-level one is, so the columns a
     * {@code WITH} body or a subquery source exposes are the columns the set
     * operation rules resolve from its branches.
     *
     * <p>{@code listedColumns} is the number of leading columns a column list
     * on the derived source renames, which are the positions the body itself
     * need not name, and {@code 0} where the derived source writes no list.
     *
     * <p>{@code enclosing} is the level this query's columns and placeholders
     * resolve through after its own, which a {@code LATERAL} source's body
     * receives and every other nested query receives as {@code null}. A
     * {@code WITH} list declared inside this query keeps its own scope either
     * way, so its bodies see no enclosing level.
     */
    AnalyzedSelect analyzeNestedSelect(
        Select select,
        Analysis analysis,
        List<Source> commonTableExpressions,
        int listedColumns,
        Scope enclosing
    ) {
        List<Select> selects = parenthesisLevels(select);

        if (selects.get(selects.size() - 1) instanceof SetOperationList setOperation) {
            return setOperationResolver.analyzeSetOperation(
                setOperation,
                analysis,
                analyzeCommonTableExpressions(selects, analysis, commonTableExpressions),
                true,
                listedColumns,
                enclosing
            );
        }

        PlainSelect plainSelect = requirePlainSelect(selects);

        return analyzePlainSelect(
            plainSelect,
            analysis,
            analyzeCommonTableExpressions(selects, analysis, commonTableExpressions),
            true,
            listedColumns,
            enclosing
        );
    }

    /**
     * The {@code SELECT} of each parenthesis level of one query, outermost
     * first, because a parenthesized query is the query it encloses, at any
     * depth, and each level may declare a {@code WITH} list of its own.
     */
    List<Select> parenthesisLevels(Select select) {
        List<Select> selects = new ArrayList<>();
        Select enclosed = select;

        selects.add(enclosed);

        while (enclosed instanceof ParenthesedSelect parenthesed) {
            enclosed = parenthesed.getSelect();

            selects.add(enclosed);
        }

        return selects;
    }

    /**
     * Requires the query a {@code SELECT} encloses to be a single plain
     * {@code SELECT}, which every caller classifies a set operation of its own
     * before. A query form that is neither, such as a {@code VALUES} or
     * {@code TABLE} statement, states no supported query at all, so it is
     * rejected before the annotation is checked and before any {@code WITH}
     * body is analyzed.
     */
    PlainSelect requirePlainSelect(List<Select> selects) {
        Select enclosed = selects.get(selects.size() - 1);

        if (enclosed instanceof PlainSelect plainSelect) {
            return plainSelect;
        }

        throw new UnsupportedOperationException(UNSUPPORTED_STATEMENT_REJECTION);
    }

    /**
     * Analyzes one plain {@code SELECT} against the common table expressions in
     * scope: its sources, its result columns, the parameters its join
     * conditions, {@code WHERE}, and pagination clauses type, and the table
     * whose complete row it returns.
     *
     * <p>{@code namesResultColumns} states whether this {@code SELECT} names
     * the result columns, which every read does and which only the leftmost
     * branch of a set operation does. {@code listedColumns} is the number of
     * leading columns a column list on the derived source this {@code SELECT}
     * is the body of renames, which are the positions this projection need not
     * name, and {@code 0} elsewhere.
     *
     * <p>This {@code SELECT} may omit {@code FROM}, which reads no source at
     * all, so it names no source and returns no table's complete row.
     *
     * <p>{@code enclosing} is the level this {@code SELECT} resolves through
     * after its own, which only the body of a {@code LATERAL} source receives
     * and which is {@code null} for every other read.
     */
    AnalyzedSelect analyzePlainSelect(
        PlainSelect plainSelect,
        Analysis analysis,
        List<Source> commonTableExpressions,
        boolean namesResultColumns,
        int listedColumns,
        Scope enclosing
    ) {
        List<Source> sources = this.sources.resolvePlainSelectSources(
            plainSelect,
            analysis,
            commonTableExpressions,
            enclosing
        );

        Scope scope = new Scope(sources, commonTableExpressions, enclosing);

        ResolvedProjection projection = projectionResolver.resolveProjection(
            plainSelect,
            analysis,
            scope,
            namesResultColumns,
            listedColumns
        );

        placeholderResolver.resolveBindingParameters(plainSelect, scope, analysis);

        if (sources.isEmpty()) {
            return new AnalyzedSelect(null, projection, null);
        }

        return new AnalyzedSelect(
            declaredSourceName(plainSelect.getFromItem(), sources.get(0)),
            projection,
            projectionResolver.resolveSelectRowTable(plainSelect, sources)
        );
    }

    /**
     * Analyzes one subquery of a predicate, which is the level inside the level
     * its predicate stands in, against the common table expressions in scope
     * around the predicate.
     */
    void analyzePredicateSubquery(
        ParenthesedSelect subquery,
        Scope enclosing,
        Analysis analysis
    ) {
        analyzePredicateQuery(subquery, enclosing.commonTableExpressions(), enclosing, analysis);
    }

    /**
     * Analyzes the query of one predicate subquery, which is classified inside
     * its own parentheses as a top-level read is: its own {@code WITH} list is
     * analyzed beside the common table expressions in scope around it, and its
     * sources, its join conditions, and its clause placeholders resolve through
     * its own level and then each enclosing one.
     *
     * <p>Its {@code SELECT} list states no result column of the analyzed query,
     * because the predicate reads whether a row exists or compares one value,
     * so a direct column there only resolves, whether or not the schema maps its
     * type, and every other item reaches the database as written.
     *
     * <p>A set operation there states no result column either, so each of its
     * branches is analyzed by these same rules, against the common table
     * expressions the list declares and the same enclosing level, while
     * PostgreSQL checks that the branches pair their columns. Only the
     * operators and the {@code LIMIT} and {@code OFFSET} paging the list's own
     * result are resolved here, because no analyzed column reads that result.
     */
    private void analyzePredicateQuery(
        Select select,
        List<Source> commonTableExpressionsInScope,
        Scope enclosing,
        Analysis analysis
    ) {
        List<Select> selects = parenthesisLevels(select);

        if (selects.get(selects.size() - 1) instanceof SetOperationList setOperation) {
            setOperationResolver.requireSupportedSetOperators(setOperation);

            List<Source> listCommonTableExpressions = analyzeCommonTableExpressions(
                selects,
                analysis,
                commonTableExpressionsInScope
            );

            for (Select branch : setOperation.getSelects()) {
                analyzePredicateQuery(branch, listCommonTableExpressions, enclosing, analysis);
            }

            placeholderResolver.resolvePaginationParameters(setOperation, analysis.placeholders());

            return;
        }

        PlainSelect plainSelect = requirePlainSelect(selects);

        List<Source> commonTableExpressions = analyzeCommonTableExpressions(
            selects,
            analysis,
            commonTableExpressionsInScope
        );

        Scope scope = new Scope(
            sources.resolvePlainSelectSources(plainSelect, analysis, commonTableExpressions, enclosing),
            commonTableExpressions,
            enclosing
        );

        resolvePredicateSubqueryColumns(plainSelect, scope);
        placeholderResolver.resolveBindingParameters(plainSelect, scope, analysis);
    }

    /**
     * Resolves the direct columns of a predicate subquery's {@code SELECT}
     * list, which reach the database as written like every other item there but
     * name a column of the sources in scope.
     */
    private void resolvePredicateSubqueryColumns(PlainSelect plainSelect, Scope scope) {
        for (SelectItem<?> selectItem : plainSelect.getSelectItems()) {
            net.sf.jsqlparser.schema.Column selectedColumn = sources.directColumn(selectItem.getExpression());

            if (selectedColumn != null) {
                sources.resolveColumn(selectedColumn, scope);
            }
        }
    }

    /**
     * The name the query writes for its first source, which the analyzed model
     * carries as the query's table: the table's own name as the query spells
     * it, and the exposed name of a derived source, which names no table.
     *
     * <p>A parenthesized group that opens the {@code FROM} list is read as the
     * same joins unparenthesized, so the first source is the one that group
     * encloses, at any depth of leading groups.
     */
    private String declaredSourceName(FromItem fromItem, Source source) {
        if (source.derived()) {
            return source.name();
        }

        FromItem leading = fromItem;

        while (leading instanceof ParenthesedFromItem group && group.getAlias() == null) {
            leading = group.getFromItem();
        }

        return ((Table) leading).getUnquotedName();
    }

    /**
     * Analyzes the {@code WITH} items each parenthesis level of one query
     * declares, in the order the query writes them, and reports the derived
     * sources they declare after the ones the queries around it declared.
     *
     * <p>Every body is analyzed, whether or not a source reads it, so its
     * placeholders are typed by its own clause rules. A body sees the common
     * table expressions written before it and those of every enclosing query,
     * so a name declared here reads the schema table of that name inside its
     * own body.
     */
    List<Source> analyzeCommonTableExpressions(
        List<Select> selects,
        Analysis analysis,
        List<Source> commonTableExpressions
    ) {
        List<Source> scope = new ArrayList<>(commonTableExpressions);

        for (Select select : selects) {
            List<WithItem<?>> withItems = select.getWithItemsList();

            if (withItems == null) {
                continue;
            }

            analyzeWithItems(withItems, analysis, scope);
        }

        return List.copyOf(scope);
    }

    /**
     * Analyzes the {@code WITH} items of one statement, which are the only ones
     * a write declares, and reports the derived sources they declare, which are
     * in scope of the predicates of that write.
     */
    List<Source> analyzeCommonTableExpressions(
        List<WithItem<?>> withItems,
        Analysis analysis
    ) {
        if (withItems == null) {
            return List.of();
        }

        List<Source> scope = new ArrayList<>();

        analyzeWithItems(withItems, analysis, scope);

        return List.copyOf(scope);
    }

    /**
     * Analyzes one {@code WITH} list, in the order the query writes it, adding
     * the derived source each item declares to the scope the items after it
     * see.
     *
     * <p>{@code RECURSIVE} covers the whole list, as it does in PostgreSQL, so
     * a list any item of which the parser reports as recursive is a recursive
     * list. In one, an item's own name and the name of every item declared
     * after it stand in that item's own scope as unreadable names, because a
     * source there may read neither: its own name only its recursive term may
     * read, and a name declared later this subset does not resolve at all. A
     * {@code WITH} list nested inside the body still declares that name anew,
     * so it shadows the unreadable one as it shadows any other.
     */
    private void analyzeWithItems(
        List<WithItem<?>> withItems,
        Analysis analysis,
        List<Source> scope
    ) {
        boolean recursive = withItems.stream().anyMatch(WithItem::isRecursive);

        for (int index = 0; index < withItems.size(); index++) {
            WithItem<?> withItem = withItems.get(index);

            scope.add(
                analyzeCommonTableExpression(
                    withItem,
                    analysis,
                    recursive
                        ? withUnreadableNames(scope, withItems, index)
                        : List.copyOf(scope),
                    recursive
                )
            );
        }
    }

    /**
     * The scope one item of a recursive {@code WITH} list is analyzed in: the
     * clauses declared before it, followed by one unreadable name per name no
     * source of its body may read — its own, which only its recursive term may
     * read, and the name of every item declared after it.
     */
    private List<Source> withUnreadableNames(
        List<Source> scope,
        List<WithItem<?>> withItems,
        int index
    ) {
        List<Source> names = new ArrayList<>(scope);

        names.add(
            Source.unreadable(
                withItems.get(index).getUnquotedAliasName(),
                RECURSIVE_SELF_REFERENCE_REJECTION
            )
        );

        for (int later = index + 1; later < withItems.size(); later++) {
            names.add(
                Source.unreadable(
                    withItems.get(later).getUnquotedAliasName(),
                    FORWARD_WITH_REFERENCE_REJECTION
                )
            );
        }

        return List.copyOf(names);
    }

    /**
     * Analyzes one {@code WITH} item into the derived source it declares, whose
     * columns are its body's result columns, renamed by the item's column list
     * where it writes one. A data-modifying body writes rows, which this subset
     * does not model.
     *
     * <p>The number of names the list writes is the number of leading columns
     * the body itself need not name, so it is known before the body is
     * analyzed, while the renaming itself reads the columns that analysis
     * resolves.
     *
     * <p>An item of a recursive list whose body states the recursive form is
     * analyzed as a recursive clause; every other item, in a recursive list or
     * not, is analyzed as the query its body states.
     */
    private Source analyzeCommonTableExpression(
        WithItem<?> withItem,
        Analysis analysis,
        List<Source> commonTableExpressions,
        boolean recursiveList
    ) {
        requireNoSearchOrCycleClause(withItem);

        Select body = requireSelectBody(withItem);
        String name = withItem.getUnquotedAliasName();
        String sqlName = SchemaNamespace.storedName(withItem.getAliasName());
        List<SelectItem<?>> columnList = withItem.getWithItemList();
        int listedColumns = columnList == null ? 0 : columnList.size();

        List<Select> levels = parenthesisLevels(body);

        if (recursiveList && readsItselfRecursively(levels, name)) {
            return analyzeRecursiveCommonTableExpression(
                name,
                sqlName,
                columnList,
                levels,
                analysis,
                commonTableExpressions
            );
        }

        AnalyzedSelect analyzed = analyzeNestedSelect(
            body,
            analysis,
            commonTableExpressions,
            listedColumns,
            null
        );

        return sources.toDerivedSource(
            name,
            sqlName,
            renamedDerivedColumns(
                name,
                declaredColumnListNames(columnList),
                analyzed.columns()
            ),
            false
        );
    }

    /**
     * Rejects a {@code SEARCH} or a {@code CYCLE} clause on a {@code WITH}
     * item, recursive or not, because each adds a column to that clause's rows
     * that its own {@code SELECT} does not state.
     */
    private void requireNoSearchOrCycleClause(WithItem<?> withItem) {
        if (withItem.getSearchClause() != null || withItem.getCycleClause() != null) {
            throw new UnsupportedOperationException(WITH_SEARCH_OR_CYCLE_REJECTION);
        }
    }

    /**
     * Reports whether one item of a recursive {@code WITH} list states the
     * recursive form PostgreSQL defines: its body, inside its parentheses, is a
     * set operation whose last operator is {@code UNION} or {@code UNION ALL},
     * and whose last branch reads the clause itself as a {@code FROM} or
     * {@code JOIN} source.
     *
     * <p>Only an unqualified name of that branch's own sources reads the
     * clause, as a source names a clause in scope, so a schema-qualified name
     * and a read at any other depth state no recursive term; a body that is not
     * this form is analyzed as the query it states, where the item's own name
     * is unreadable.
     */
    private boolean readsItselfRecursively(List<Select> levels, String name) {
        if (!(levels.get(levels.size() - 1) instanceof SetOperationList setOperation)) {
            return false;
        }

        List<SetOperation> operators = setOperation.getOperations();

        if (!(operators.get(operators.size() - 1) instanceof UnionOp)) {
            return false;
        }

        List<Select> branches = setOperation.getSelects();
        List<Select> branchLevels = parenthesisLevels(branches.get(branches.size() - 1));

        if (!(branchLevels.get(branchLevels.size() - 1) instanceof PlainSelect plainSelect)) {
            return false;
        }

        if (namesSource(plainSelect.getFromItem(), name)) {
            return true;
        }

        List<Join> joins = plainSelect.getJoins();

        if (joins == null) {
            return false;
        }

        return joins.stream().anyMatch(join -> namesSource(join.getFromItem(), name));
    }

    /**
     * Reports whether one source names the given {@code WITH} clause, which an
     * unqualified table name does, compared ignoring case as a source resolves
     * a clause in scope.
     */
    private boolean namesSource(FromItem fromItem, String name) {
        return fromItem instanceof Table table
            && table.getSchemaName() == null
            && table.getUnquotedName().equalsIgnoreCase(name);
    }

    /**
     * Analyzes one recursive {@code WITH} item into the derived source it
     * declares.
     *
     * <p>The branches before the last are the clause's first term, analyzed by
     * the set operation rules against the scope around the clause, where its
     * own name is unreadable. Their columns, renamed by the item's column list,
     * are the columns the clause exposes, so the recursive term — the last
     * branch — reads them through a provisional source of that name appended to
     * the scope.
     *
     * <p>Each column of the recursive term must resolve to the type the first
     * term states, as PostgreSQL requires, so the cast that states one type for
     * both terms belongs to the first term. A column is nullable when either
     * term's is, and a column the recursive term alone makes nullable makes
     * every column of the clause nullable, because the recursive term read them
     * before that was known.
     *
     * <p>The body's own {@code ORDER BY}, {@code LIMIT}, and {@code OFFSET} are
     * the list's own clauses, resolved as they are for any set operation.
     */
    private Source analyzeRecursiveCommonTableExpression(
        String name,
        String sqlName,
        List<SelectItem<?>> columnList,
        List<Select> levels,
        Analysis analysis,
        List<Source> commonTableExpressions
    ) {
        SetOperationList setOperation = (SetOperationList) levels.get(levels.size() - 1);

        setOperationResolver.requireSupportedSetOperators(setOperation);

        List<Source> scope = analyzeCommonTableExpressions(levels, analysis, commonTableExpressions);

        List<Select> branches = setOperation.getSelects();
        int last = branches.size() - 1;

        List<AnalyzedSelect> firstTerm = new ArrayList<>();

        for (int index = 0; index < last; index++) {
            firstTerm.add(
                setOperationResolver.analyzeSetOperationBranch(
                    branches.get(index),
                    analysis,
                    scope,
                    index == 0,
                    index == 0 && columnList != null ? columnList.size() : 0,
                    null
                )
            );
        }

        List<org.sqlcj.schema.Column> firstColumns = renamedDerivedColumns(
            name,
            declaredColumnListNames(columnList),
            firstTerm.size() == 1
                ? firstTerm.get(0).columns()
                : setOperationResolver.resolveSetOperationColumns(
                    firstTerm,
                    setOperation.getOperations().subList(0, last - 1)
                )
        );

        List<Source> recursiveScope = new ArrayList<>(scope);

        recursiveScope.add(sources.toDerivedSource(name, sqlName, firstColumns, false));

        AnalyzedSelect recursiveTerm = setOperationResolver.analyzeSetOperationBranch(
            branches.get(last),
            analysis,
            List.copyOf(recursiveScope),
            false,
            0,
            null
        );

        List<AnalyzedSelect> allBranches = new ArrayList<>(firstTerm);

        allBranches.add(recursiveTerm);

        setOperationResolver.requireSameSetOperationColumnCounts(allBranches);

        List<org.sqlcj.schema.Column> columns = resolveRecursiveColumns(
            name,
            firstColumns,
            recursiveTerm.columns()
        );

        setOperationResolver.requireResultColumnOrdering(setOperation, columns);

        placeholderResolver.resolvePaginationParameters(setOperation, analysis.placeholders());

        return sources.toDerivedSource(name, sqlName, columns, false);
    }

    /**
     * Resolves the columns of a recursive {@code WITH} clause from its first
     * term's columns and its recursive term's: each column keeps the first
     * term's name and type, which the recursive term's column must resolve to,
     * and is nullable when either term's is or when the recursive term alone
     * makes any column nullable.
     */
    private List<org.sqlcj.schema.Column> resolveRecursiveColumns(
        String name,
        List<org.sqlcj.schema.Column> firstTerm,
        List<org.sqlcj.schema.Column> recursiveTerm
    ) {
        boolean allNullable = false;

        for (int index = 0; index < firstTerm.size(); index++) {
            org.sqlcj.schema.Column first = firstTerm.get(index);
            org.sqlcj.schema.Column recursive = recursiveTerm.get(index);

            requireRecursiveColumnType(name, first, recursive);

            allNullable = allNullable || (recursive.nullable() && !first.nullable());
        }

        List<org.sqlcj.schema.Column> columns = new ArrayList<>();

        for (int index = 0; index < firstTerm.size(); index++) {
            org.sqlcj.schema.Column first = firstTerm.get(index);

            columns.add(
                new org.sqlcj.schema.Column(
                    first.name(),
                    first.type(),
                    allNullable || first.nullable() || recursiveTerm.get(index).nullable(),
                    null,
                    first.enumType(),
                    first.array(),
                    first.blankPadded(),
                    first.sqlName()
                )
            );
        }

        return List.copyOf(columns);
    }

    /**
     * Requires the type both terms of a recursive {@code WITH} clause resolve
     * one column to, by the set operation rules, to be the type its first term
     * states, which is the type PostgreSQL gives that column, naming the cast
     * the first term writes otherwise.
     */
    private void requireRecursiveColumnType(
        String name,
        org.sqlcj.schema.Column first,
        org.sqlcj.schema.Column recursive
    ) {
        org.sqlcj.schema.Column resolved = setOperationResolver
            .resolveSetOperationColumnType(first, first, recursive);

        if (setOperationResolver.isSameResultType(resolved, first)) {
            return;
        }

        throw new UnsupportedOperationException(
            RECURSIVE_COLUMN_TYPE_REJECTION.formatted(
                name,
                first.name(),
                setOperationResolver.sqlType(first),
                setOperationResolver.sqlType(resolved),
                first.name(),
                setOperationResolver.sqlType(resolved)
            )
        );
    }

    /**
     * The columns of one derived source under the names a column list on it
     * gives them: each name renames the column of its own position, keeping
     * that column's type, enum type, array spelling, blank-padded spelling, and
     * nullability, because the list names columns and states nothing about
     * them. The columns past a shorter list keep the names their own
     * {@code SELECT} states, and a list is rejected where it names more columns
     * than that {@code SELECT} returns at all.
     *
     * <p>The renamed columns are the ones the distinct-name rule of a derived
     * source then reads, so a list may resolve a name its {@code SELECT}
     * repeats, and may repeat one itself.
     *
     * <p>{@code declaredNames} are the names the list writes, SQL identifier
     * delimiters included, and no name at all where the derived source writes
     * no list.
     */
    List<org.sqlcj.schema.Column> renamedDerivedColumns(
        String name,
        List<String> declaredNames,
        List<org.sqlcj.schema.Column> columns
    ) {
        if (declaredNames.isEmpty()) {
            return columns;
        }

        if (declaredNames.size() > columns.size()) {
            throw new UnsupportedOperationException(
                DERIVED_SOURCE_COLUMN_LIST_COUNT_REJECTION.formatted(
                    name,
                    declaredNames.size(),
                    columns.size()
                )
            );
        }

        List<org.sqlcj.schema.Column> renamed = new ArrayList<>(columns);

        for (int index = 0; index < declaredNames.size(); index++) {
            org.sqlcj.schema.Column column = columns.get(index);
            String declaredName = declaredNames.get(index);

            renamed.set(
                index,
                new org.sqlcj.schema.Column(
                    MultiPartName.unquote(declaredName),
                    column.type(),
                    column.nullable(),
                    column.unsupportedType(),
                    column.enumType(),
                    column.array(),
                    column.blankPadded(),
                    SchemaNamespace.storedName(declaredName)
                )
            );
        }

        return List.copyOf(renamed);
    }

    /**
     * The names one column list on a derived source writes, as the query writes
     * them, and no name at all where the source writes no list.
     */
    private List<String> declaredColumnListNames(List<SelectItem<?>> columnList) {
        if (columnList == null) {
            return List.of();
        }

        return columnList.stream()
            .map(item -> ((net.sf.jsqlparser.schema.Column) item.getExpression()).getColumnName())
            .toList();
    }

    /**
     * The {@code SELECT} one {@code WITH} item states inside its parentheses. A
     * body that writes rows is rejected from the parsed statement kind, because
     * reading such a body as a {@code SELECT} is an error of the parser's own
     * model, and every other body states no query at all.
     */
    private Select requireSelectBody(WithItem<?> withItem) {
        ParenthesedStatement statement = withItem.getParenthesedStatement();

        if (statement instanceof ParenthesedSelect select) {
            return select.getSelect();
        }

        if (
            statement instanceof ParenthesedInsert
                || statement instanceof ParenthesedUpdate
                || statement instanceof ParenthesedDelete
        ) {
            throw new UnsupportedOperationException(DATA_MODIFYING_WITH_REJECTION);
        }

        throw new UnsupportedOperationException(UNSUPPORTED_STATEMENT_REJECTION);
    }

    private void requireResultQueryType(Query query) {
        if (!producesResult(query)) {
            throw new UnsupportedOperationException(
                "SELECT queries must be declared as :one, :optional, or :many"
            );
        }
    }

    /** The annotations that declare a row-producing result shape. */
    boolean producesResult(Query query) {
        return query.type() == QueryType.ONE
            || query.type() == QueryType.OPTIONAL
            || query.type() == QueryType.MANY;
    }

    /**
     * One expression as the query writes it, on one line: the parser's own
     * spelling of the expression, with every run of whitespace written as one
     * space, so a diagnostic naming an expression written across lines stays
     * one line.
     */
    String written(Expression expression) {
        return written(expression.toString());
    }

    /** One ordering item as the query writes it, which a diagnostic names. */
    String written(OrderByElement orderByElement) {
        return written(orderByElement.toString());
    }

    private String written(String sql) {
        return sql
            .replaceAll("\\s+", " ")
            .trim();
    }

    /**
     * Builds the analyzed model from the parameter occurrences resolved in
     * textual order, keeping that order for JDBC binding and exposing one
     * logical parameter per placeholder index.
     */
    QueryModel toQueryModel(
        Query query,
        Analysis analysis,
        String tableName,
        List<QueryColumn> columns,
        String rowTable
    ) {
        ParsedSql parsedSql = analysis.parsedSql();

        ResolvedOccurrences resolved = placeholderResolver.resolveOccurrences(parsedSql, analysis.placeholders());

        List<QueryParameter> occurrences = resolved.occurrences();

        List<Integer> bindingParameterIndexes = placeholderResolver.requireAccountedOccurrences(parsedSql, occurrences);

        List<String> names = parsedSql.parameters().names();

        List<QueryParameter> parameters = placeholderResolver.toParameters(occurrences, names);

        return new QueryModel(
            query.name(),
            query.type(),
            tableName,
            toExecutableSql(parsedSql, resolved, analysis.wildcards()),
            bindingParameterIndexes,
            columns,
            parameters,
            rowTable,
            query.comment(),
            query.writtenSql(),
            query.line(),
            query.parameterRecord(),
            toPlaceholders(parameters, names)
        );
    }

    /**
     * Writes the placeholder of each logical parameter of one query as the
     * query source spells it, in logical parameter order, so that the generated
     * documentation names the placeholder a method parameter or record
     * component binds.
     */
    private List<String> toPlaceholders(List<QueryParameter> parameters, List<String> names) {
        return parameters.stream()
            .map(parameter -> placeholderResolver.describePlaceholder(parameter.index(), names))
            .toList();
    }

    /**
     * The JDBC-executable SQL of one analyzed query, which is the compiled SQL
     * with each wildcard this analysis expanded replaced by the columns it
     * selects and with {@code ::<type>} written directly after the {@code ?} of
     * each occurrence typed from another occurrence of its parameter, so that
     * PostgreSQL reads the columns this analysis resolved and types the bound
     * value where the query itself states no type.
     *
     * <p>Every other character stays as the compiler wrote it, so a query that
     * expands no wildcard and whose every occurrence a clause or a cast types
     * executes its compiled SQL unchanged, and a wildcard no clause expanded
     * stays as the query writes it.
     *
     * <p>The whole wildcard item is replaced, its alias included, because
     * PostgreSQL ignores an alias on a wildcard and would read one written
     * after a column list as the name of its last column.
     */
    private String toExecutableSql(
        ParsedSql parsedSql,
        ResolvedOccurrences resolved,
        Wildcards wildcards
    ) {
        String sql = parsedSql.parameters().executableSql();

        List<WrittenSpan> spans = writtenSpans(parsedSql, resolved, wildcards);

        if (spans.isEmpty()) {
            return sql;
        }

        StringBuilder executableSql = new StringBuilder();
        int copied = 0;

        for (WrittenSpan span : spans) {
            executableSql
                .append(sql, copied, span.begin())
                .append(span.text());

            copied = span.end();
        }

        return executableSql
            .append(sql, copied, sql.length())
            .toString();
    }

    /**
     * One span of the compiled SQL the executable SQL writes its own text at: a
     * type hint, which stands directly after one {@code ?} and replaces no
     * character of it, and the columns of one expanded wildcard, which replace
     * the whole wildcard item.
     */
    private record WrittenSpan(int begin, int end, String text) {
    }

    /**
     * The spans the executable SQL of one query writes, in ascending order of
     * where they stand in the compiled SQL, which is the order they are written
     * in. A type hint and a wildcard never share a position, because a
     * wildcard item spells no placeholder.
     */
    private List<WrittenSpan> writtenSpans(
        ParsedSql parsedSql,
        ResolvedOccurrences resolved,
        Wildcards wildcards
    ) {
        List<Integer> offsets = parsedSql.parameters().offsets();
        List<WrittenSpan> spans = new ArrayList<>();

        for (int position : resolved.writtenTypes()) {
            int marker = offsets.get(position) + 1;

            spans.add(
                new WrittenSpan(
                    marker,
                    marker,
                    "::" + sqlTypeName(resolved.occurrences().get(position))
                )
            );
        }

        for (WildcardOccurrence occurrence : parsedSql.wildcards()) {
            String columnList = wildcards.columnList(occurrence.item());

            if (columnList != null) {
                spans.add(
                    new WrittenSpan(occurrence.begin(), occurrence.end(), columnList)
                );
            }
        }

        spans.sort(Comparator.comparingInt(WrittenSpan::begin));

        return spans;
    }

    /**
     * The PostgreSQL name of one parameter's type, which the executable SQL
     * writes as a cast at an occurrence typed from another occurrence. It is
     * the name PostgreSQL gives the type the runtime binds the parameter as, a
     * blank-padded character type's own {@code bpchar} included, an enum type's
     * declared name, and {@code []} after the element name of an array.
     */
    private String sqlTypeName(QueryParameter parameter) {
        String name = parameter.blankPadded() && parameter.type() == ColumnType.VARCHAR
            ? "bpchar"
            : scalarSqlTypeName(parameter);

        return parameter.array()
            ? name + "[]"
            : name;
    }

    private String scalarSqlTypeName(QueryParameter parameter) {
        return switch (parameter.type()) {
            case INTEGER -> "int4";
            case BIGINT -> "int8";
            case SMALLINT -> "int2";
            case BOOLEAN -> "bool";
            case VARCHAR -> "varchar";
            case TEXT -> "text";
            case DATE -> "date";
            case TIME -> "time";
            case TIMESTAMP -> "timestamp";
            case TIMESTAMP_WITH_TIME_ZONE -> "timestamptz";
            case DECIMAL -> "numeric";
            case REAL -> "float4";
            case DOUBLE_PRECISION -> "float8";
            case UUID -> "uuid";
            case BYTEA -> "bytea";
            case JSON -> "json";
            case JSONB -> "jsonb";
            case CITEXT -> "citext";
            case INET -> "inet";
            case CIDR -> "cidr";
            case MACADDR -> "macaddr";
            case MACADDR8 -> "macaddr8";
            case INTERVAL -> "interval";
            case ENUM -> parameter.enumType();
        };
    }
}
