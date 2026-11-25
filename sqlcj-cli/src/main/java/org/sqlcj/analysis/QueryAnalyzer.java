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
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.MultiPartName;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.ParenthesedStatement;
import net.sf.jsqlparser.statement.ReturningClause;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.Index;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.delete.ParenthesedDelete;
import net.sf.jsqlparser.statement.insert.ConflictActionType;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.insert.InsertConflictAction;
import net.sf.jsqlparser.statement.insert.InsertConflictTarget;
import net.sf.jsqlparser.statement.insert.ParenthesedInsert;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.ExceptOp;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.IntersectOp;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.MinusOp;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperation;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.UnionOp;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.ParenthesedUpdate;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;
import org.sqlcj.parser.Query;
import org.sqlcj.parser.QueryType;
import org.sqlcj.schema.ColumnType;
import org.sqlcj.schema.Schema;
import org.sqlcj.schema.parser.ColumnTypeMapping;
import org.sqlcj.schema.parser.SchemaNamespace;
import org.sqlcj.sql.ParsedSql;
import org.sqlcj.sql.PlaceholderOccurrence;
import org.sqlcj.sql.WildcardOccurrence;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
     * The rejection of {@code MINUS}, which PostgreSQL does not have, naming
     * the operator it spells the same difference with.
     */
    private static final String MINUS_REJECTION = "MINUS is not supported; write EXCEPT.";

    /**
     * The rejection of a set operation whose branches select different numbers
     * of columns, which pair by position, naming the first branch's count and
     * the first branch that differs from it.
     */
    private static final String SET_OPERATION_COLUMN_COUNT_REJECTION = """
        Every branch of a set operation must select the same number of columns; \
        the first selects %d and branch %d selects %d.""";

    /**
     * The rejection of one set operation column whose branches state types this
     * subset resolves to no single type, which names the column, the two types
     * as PostgreSQL spells them, and the cast that states one type for every
     * branch.
     */
    private static final String SET_OPERATION_COLUMN_TYPE_REJECTION = """
        Set operation column %s has types %s and %s; cast it to one type in every branch, \
        such as %s::%s""";

    /**
     * The rejection of an {@code ORDER BY} column of a set operation's result
     * that names no result column, which PostgreSQL also restricts to an output
     * column or a position, naming the item as written and the first result
     * column.
     */
    private static final String SET_OPERATION_ORDER_BY_REJECTION = """
        ORDER BY %s must name a result column of the set operation, such as ORDER BY %s.""";

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
     * The rejection of a catalogue function projection without a result alias,
     * which names one result component as an aggregate's does. Both the rejected
     * item and the example are the call as the query writes it, so the named fix
     * is the query's own call followed by an alias the user writes.
     */
    private static final String FUNCTION_ALIAS_REJECTION = """
        %s requires a result alias, such as %s AS <alias>.""";

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

    /** The character that opens a supported named placeholder. */
    static final String NAME_PREFIX = ":";

    /**
     * The character that separates the group of a parameter group placeholder
     * from its field, both in the placeholder and in the logical parameter name
     * the SQL parser compiled it under.
     */
    static final String GROUP_SEPARATOR = ".";

    /**
     * The qualifier an {@code ON CONFLICT DO UPDATE} assignment writes to read
     * a column of the row the insert proposed, compared ignoring case as
     * PostgreSQL resolves it.
     */
    private static final String EXCLUDED_QUALIFIER = "EXCLUDED";

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
     * The scalar date and time types a set operation resolves to the widest of,
     * narrowest first, which is the type PostgreSQL 16 resolves each pair of
     * them to. {@code TIME} orders with none of them, so it resolves only with
     * itself.
     */
    private static final List<ColumnType> TEMPORAL_WIDENING = List.of(
        ColumnType.DATE,
        ColumnType.TIMESTAMP,
        ColumnType.TIMESTAMP_WITH_TIME_ZONE
    );

    /**
     * The scalar character types a set operation resolves to the first
     * branch's, blank-padded or not, which is the type PostgreSQL 16 resolves
     * each pair of them to, because each converts implicitly to the other.
     */
    private static final Set<ColumnType> CHARACTER_TYPES = Set.of(
        ColumnType.VARCHAR,
        ColumnType.TEXT
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

    public QueryModel analyze(Query query, ParsedSql parsedSql, Schema schema) {
        placeholderResolver.requireSupportedPlaceholders(parsedSql);
        placeholderResolver.requireNoParameterGroupInBatch(query, parsedSql);
        placeholderResolver.requireUsableParameterRecord(query, parsedSql);

        Statement statement = parsedSql.statement();

        if (statement instanceof Select select) {
            return analyzeSelectStatement(query, parsedSql, select, schema);
        }

        if (statement instanceof Insert insert) {
            return analyzeInsert(query, parsedSql, insert, schema);
        }

        if (statement instanceof Update update) {
            return analyzeUpdate(query, parsedSql, update, schema);
        }

        if (statement instanceof Delete delete) {
            return analyzeDelete(query, parsedSql, delete, schema);
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
                VOID_CALL_REJECTION.formatted(call, call, hintAlias(selectItem.getAlias()))
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

        AnalyzedSelect analyzed = analyzeSetOperation(
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
                : toQueryColumns(analyzed.columns()),
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
            return analyzeSetOperation(
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
    private List<Select> parenthesisLevels(Select select) {
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
    private PlainSelect requirePlainSelect(List<Select> selects) {
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
    private AnalyzedSelect analyzePlainSelect(
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

        ResolvedProjection projection = resolveProjection(
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
            resolveSelectRowTable(plainSelect, sources)
        );
    }

    /**
     * Analyzes one set operation against the common table expressions in scope,
     * which every branch sees: each branch is analyzed as a query of its own,
     * and the result is their columns resolved into one projection, paired by
     * position and named by the leftmost branch.
     *
     * <p>{@code namesResultColumns} states whether this set operation names the
     * result columns, so only the leftmost branch of the leftmost set operation
     * must name every result component of its own projection. That leftmost
     * branch is also the naming projection a column list on the derived source
     * this set operation is the body of renames the leading columns of, so only
     * it receives {@code listedColumns}.
     *
     * <p>The parser reports a flat list of branches and operators with no
     * precedence grouping, and PostgreSQL binds {@code INTERSECT} more tightly
     * than {@code UNION} and {@code EXCEPT}, so the nullability rules group the
     * operators themselves. A branch written in parentheses states its own
     * grouping, as the parser reports it.
     *
     * <p>An {@code ORDER BY} the parser reports on this list orders this list's
     * own result, so it may name only one of its result columns; the
     * {@code LIMIT} and {@code OFFSET} reported beside it page that same result
     * and are typed by the pagination rules. Written without an
     * {@code ORDER BY}, the parser reports them on the last branch instead,
     * where that branch's own pagination rules type them, while PostgreSQL
     * applies them to the list's result either way. A nested list written in
     * parentheses carries its own clauses, which are therefore resolved here
     * rather than only on the outermost list.
     *
     * <p>{@code enclosing} is the level every branch resolves through after its
     * own, which only the body of a {@code LATERAL} source receives and which
     * is {@code null} for every other set operation.
     */
    private AnalyzedSelect analyzeSetOperation(
        SetOperationList setOperation,
        Analysis analysis,
        List<Source> commonTableExpressions,
        boolean namesResultColumns,
        int listedColumns,
        Scope enclosing
    ) {
        requireSupportedSetOperators(setOperation);

        List<Select> branchSelects = setOperation.getSelects();
        List<AnalyzedSelect> branches = new ArrayList<>();

        for (int index = 0; index < branchSelects.size(); index++) {
            branches.add(
                analyzeSetOperationBranch(
                    branchSelects.get(index),
                    analysis,
                    commonTableExpressions,
                    namesResultColumns && index == 0,
                    index == 0 ? listedColumns : 0,
                    enclosing
                )
            );
        }

        List<org.sqlcj.schema.Column> columns = resolveSetOperationColumns(
            branches,
            setOperation.getOperations()
        );

        requireResultColumnOrdering(setOperation, columns);

        placeholderResolver.resolvePaginationParameters(setOperation, analysis.placeholders());

        return new AnalyzedSelect(
            branches.get(0).table(),
            new ResolvedProjection(columns, toQueryColumns(columns)),
            resolveSetOperationRowTable(branches)
        );
    }

    /**
     * Analyzes one branch of a set operation, which is classified inside its
     * own parentheses as a top-level read is: a plain {@code SELECT} follows the
     * read rules, and a set operation is analyzed as a set operation of its own.
     * Each parenthesis level of the branch may declare a {@code WITH} list,
     * which is analyzed beside the common table expressions in scope around it.
     *
     * <p>{@code listedColumns} is the number of leading columns a column list
     * renames, which this branch is given only where it is the naming
     * projection of the renamed derived source.
     *
     * <p>{@code enclosing} is the level this branch resolves through after its
     * own, which only the body of a {@code LATERAL} source receives and which
     * is {@code null} for every other branch.
     */
    private AnalyzedSelect analyzeSetOperationBranch(
        Select branch,
        Analysis analysis,
        List<Source> commonTableExpressions,
        boolean namesResultColumns,
        int listedColumns,
        Scope enclosing
    ) {
        List<Select> selects = parenthesisLevels(branch);

        List<Source> branchCommonTableExpressions = analyzeCommonTableExpressions(
            selects,
            analysis,
            commonTableExpressions
        );

        Select enclosed = selects.get(selects.size() - 1);

        if (enclosed instanceof PlainSelect plainSelect) {
            return analyzePlainSelect(
                plainSelect,
                analysis,
                branchCommonTableExpressions,
                namesResultColumns,
                listedColumns,
                enclosing
            );
        }

        if (enclosed instanceof SetOperationList nested) {
            return analyzeSetOperation(
                nested,
                analysis,
                branchCommonTableExpressions,
                namesResultColumns,
                listedColumns,
                enclosing
            );
        }

        throw new UnsupportedOperationException(UNSUPPORTED_STATEMENT_REJECTION);
    }

    /**
     * Requires every operator of one set operation to be an operator
     * PostgreSQL has, which {@code MINUS} is not, so the difference is written
     * as PostgreSQL spells it.
     */
    private void requireSupportedSetOperators(SetOperationList setOperation) {
        for (SetOperation operator : setOperation.getOperations()) {
            if (operator instanceof MinusOp) {
                throw new UnsupportedOperationException(MINUS_REJECTION);
            }
        }
    }

    /**
     * Resolves the result columns of one set operation, which pair with the
     * leftmost branch's columns by position, so every branch must select the
     * same number of them.
     */
    private List<org.sqlcj.schema.Column> resolveSetOperationColumns(
        List<AnalyzedSelect> branches,
        List<SetOperation> operators
    ) {
        requireSameSetOperationColumnCounts(branches);

        List<org.sqlcj.schema.Column> first = branches.get(0).columns();

        List<org.sqlcj.schema.Column> columns = new ArrayList<>();

        for (int index = 0; index < first.size(); index++) {
            columns.add(resolveSetOperationColumn(branches, operators, index));
        }

        return List.copyOf(columns);
    }

    /**
     * Requires every branch of a set operation to select the number of columns
     * the leftmost branch selects, which is the number of columns the result
     * has, naming the first branch that differs. A wildcard counts as the
     * columns it expands to, as PostgreSQL pairs them.
     */
    private void requireSameSetOperationColumnCounts(List<AnalyzedSelect> branches) {
        int expected = branches.get(0).columns().size();

        for (int index = 1; index < branches.size(); index++) {
            int selected = branches.get(index).columns().size();

            if (selected != expected) {
                throw new UnsupportedOperationException(
                    SET_OPERATION_COLUMN_COUNT_REJECTION.formatted(
                        expected,
                        index + 1,
                        selected
                    )
                );
            }
        }
    }

    /**
     * Resolves one result column of a set operation from the column each branch
     * selects in that position: its name is the leftmost branch's, its type is
     * the type every branch's resolves to, and its nullability follows the
     * operators.
     */
    private org.sqlcj.schema.Column resolveSetOperationColumn(
        List<AnalyzedSelect> branches,
        List<SetOperation> operators,
        int index
    ) {
        org.sqlcj.schema.Column first = branches.get(0).columns().get(index);
        org.sqlcj.schema.Column resolved = first;

        List<Boolean> nullability = new ArrayList<>();

        nullability.add(first.nullable());

        for (int branch = 1; branch < branches.size(); branch++) {
            org.sqlcj.schema.Column column = branches.get(branch).columns().get(index);

            resolved = resolveSetOperationColumnType(first, resolved, column);

            nullability.add(column.nullable());
        }

        return new org.sqlcj.schema.Column(
            first.name(),
            resolved.type(),
            resolveSetOperationNullability(nullability, operators),
            null,
            resolved.enumType(),
            resolved.array(),
            resolved.blankPadded(),
            first.sqlName()
        );
    }

    /**
     * Resolves the type of one result column over one more branch, which is the
     * type the common type rules resolve the two to.
     *
     * <p>A pair those rules resolve to no single type is rejected naming the
     * cast that states one type for every branch, because an inferred type that
     * is not PostgreSQL's own would model the result wrongly. The diagnostic
     * names the first branch's type and the conflicting branch's, which are the
     * types the query writes.
     */
    private org.sqlcj.schema.Column resolveSetOperationColumnType(
        org.sqlcj.schema.Column first,
        org.sqlcj.schema.Column resolved,
        org.sqlcj.schema.Column column
    ) {
        org.sqlcj.schema.Column common = resolveCommonColumnType(resolved, column);

        if (common != null) {
            return common;
        }

        throw new UnsupportedOperationException(
            SET_OPERATION_COLUMN_TYPE_REJECTION.formatted(
                first.name(),
                sqlType(first),
                sqlType(column),
                first.name(),
                sqlType(first)
            )
        );
    }

    /**
     * The type two columns of one result resolve to, which is the type
     * PostgreSQL 16 resolves the two to wherever this subset states it exactly:
     *
     * <ul>
     *   <li>one identical type, its enum type, its array spelling, and its
     *       blank-padded spelling included, resolves to itself;</li>
     *   <li>two scalar numeric types resolve to the wider;</li>
     *   <li>two scalar date and time types resolve to the wider;</li>
     *   <li>two scalar character types resolve to the first column's, because
     *       each converts implicitly to the other.</li>
     * </ul>
     *
     * <p>Every other pair resolves to {@code null}, which each caller — a set
     * operation's column, a recursive {@code WITH} clause's column, and a
     * join's merged column — rejects with the fix its own clause takes.
     */
    org.sqlcj.schema.Column resolveCommonColumnType(
        org.sqlcj.schema.Column resolved,
        org.sqlcj.schema.Column column
    ) {
        if (isSameResultType(resolved, column)) {
            return resolved;
        }

        if (isScalarResultType(resolved) && isScalarResultType(column)) {
            org.sqlcj.schema.Column numeric = widerResultType(NUMERIC_WIDENING, resolved, column);

            if (numeric != null) {
                return numeric;
            }

            org.sqlcj.schema.Column temporal = widerResultType(TEMPORAL_WIDENING, resolved, column);

            if (temporal != null) {
                return temporal;
            }

            if (
                CHARACTER_TYPES.contains(resolved.type())
                    && CHARACTER_TYPES.contains(column.type())
            ) {
                return resolved;
            }
        }

        return null;
    }

    /**
     * Reports whether two selected columns state one type, which is the same
     * {@link ColumnType} with the same enum type, the same array spelling, and
     * the same blank-padded spelling, because each of them decides the column's
     * Java type or the name PostgreSQL binds it under.
     */
    private boolean isSameResultType(
        org.sqlcj.schema.Column resolved,
        org.sqlcj.schema.Column column
    ) {
        if (
            resolved.type() != column.type()
                || resolved.array() != column.array()
                || resolved.blankPadded() != column.blankPadded()
        ) {
            return false;
        }

        return resolved.enumType() == null
            ? column.enumType() == null
            : resolved.enumType().equalsIgnoreCase(column.enumType());
    }

    /**
     * Reports whether a selected column is of a scalar type the widening rules
     * read, which an array and a value of a declared enum type are not: an array
     * is ordered by its elements rather than converted, and each enum type is a
     * type of its own.
     */
    private boolean isScalarResultType(org.sqlcj.schema.Column column) {
        return !column.array() && column.type() != ColumnType.ENUM;
    }

    /**
     * The wider of two scalar types of one widening order, and {@code null}
     * when either type is outside that order, which leaves the pair to the
     * remaining rules.
     */
    private org.sqlcj.schema.Column widerResultType(
        List<ColumnType> widening,
        org.sqlcj.schema.Column resolved,
        org.sqlcj.schema.Column column
    ) {
        int resolvedWidth = widening.indexOf(resolved.type());
        int columnWidth = widening.indexOf(column.type());

        if (resolvedWidth < 0 || columnWidth < 0) {
            return null;
        }

        return resolvedWidth >= columnWidth
            ? resolved
            : column;
    }

    /**
     * Resolves the nullability of one result column from each branch's, in
     * branch order, and the operators between them.
     *
     * <p>{@code INTERSECT} binds more tightly than {@code UNION} and
     * {@code EXCEPT}, as it does in PostgreSQL, so consecutive
     * {@code INTERSECT} branches form one group, which reads {@code NULL} only
     * where every branch in it does. The groups then fold left to right: a
     * {@code UNION} reads {@code NULL} where either side does, and an
     * {@code EXCEPT} returns rows of its left side alone, so it keeps that
     * side's nullability.
     */
    private boolean resolveSetOperationNullability(
        List<Boolean> branchNullability,
        List<SetOperation> operators
    ) {
        List<Boolean> groups = new ArrayList<>();
        List<SetOperation> groupOperators = new ArrayList<>();

        boolean group = branchNullability.get(0);

        for (int index = 0; index < operators.size(); index++) {
            boolean next = branchNullability.get(index + 1);

            if (operators.get(index) instanceof IntersectOp) {
                group = group && next;

                continue;
            }

            groups.add(group);
            groupOperators.add(operators.get(index));

            group = next;
        }

        groups.add(group);

        boolean nullable = groups.get(0);

        for (int index = 0; index < groupOperators.size(); index++) {
            nullable = groupOperators.get(index) instanceof ExceptOp
                ? nullable
                : nullable || groups.get(index + 1);
        }

        return nullable;
    }

    /**
     * Reports the table whose complete row a set operation returns, which is
     * the table every branch returns whole, and {@code null} when any branch
     * returns a result of its own, because a set operation's column belongs to
     * no one source.
     */
    private String resolveSetOperationRowTable(List<AnalyzedSelect> branches) {
        String rowTable = branches.get(0).rowTable();

        if (rowTable == null) {
            return null;
        }

        for (AnalyzedSelect branch : branches) {
            if (!rowTable.equals(branch.rowTable())) {
                return null;
            }
        }

        return rowTable;
    }

    /**
     * One type as PostgreSQL spells it, which a set operation's type diagnostic
     * names: the declared name of an enum type, and the {@code []} suffix of an
     * array of either.
     */
    String sqlType(org.sqlcj.schema.Column column) {
        String name = switch (column.type()) {
            case SMALLINT -> "smallint";
            case INTEGER -> "integer";
            case BIGINT -> "bigint";
            case DECIMAL -> "numeric";
            case REAL -> "real";
            case DOUBLE_PRECISION -> "double precision";
            case BOOLEAN -> "boolean";
            case VARCHAR -> column.blankPadded()
                ? "bpchar"
                : "varchar";
            case TEXT -> "text";
            case DATE -> "date";
            case TIME -> "time";
            case TIMESTAMP -> "timestamp";
            case TIMESTAMP_WITH_TIME_ZONE -> "timestamptz";
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
            case ENUM -> column.enumType();
        };

        return column.array()
            ? name + "[]"
            : name;
    }

    /**
     * Requires every direct column of a set operation's {@code ORDER BY} to
     * name one result column, unqualified and compared ignoring case, as
     * PostgreSQL restricts an ordering of a set operation's result to an output
     * column or a position. Every other item, a position included, reaches the
     * database as written, as an ordering item of a plain read does.
     */
    private void requireResultColumnOrdering(
        SetOperationList setOperation,
        List<org.sqlcj.schema.Column> columns
    ) {
        List<OrderByElement> orderByElements = setOperation.getOrderByElements();

        if (orderByElements == null) {
            return;
        }

        for (OrderByElement orderByElement : orderByElements) {
            net.sf.jsqlparser.schema.Column column = sources.directColumn(orderByElement.getExpression());

            if (column == null) {
                continue;
            }

            boolean named = sources.qualifier(column) == null
                && columns.stream().anyMatch(
                    resultColumn -> resultColumn.name()
                        .equalsIgnoreCase(column.getUnquotedColumnName())
                );

            if (!named) {
                throw new UnsupportedOperationException(
                    SET_OPERATION_ORDER_BY_REJECTION.formatted(
                        written(orderByElement),
                        columns.get(0).name()
                    )
                );
            }
        }
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
            requireSupportedSetOperators(setOperation);

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
    private List<Source> analyzeCommonTableExpressions(
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
    private List<Source> analyzeCommonTableExpressions(
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

        requireSupportedSetOperators(setOperation);

        List<Source> scope = analyzeCommonTableExpressions(levels, analysis, commonTableExpressions);

        List<Select> branches = setOperation.getSelects();
        int last = branches.size() - 1;

        List<AnalyzedSelect> firstTerm = new ArrayList<>();

        for (int index = 0; index < last; index++) {
            firstTerm.add(
                analyzeSetOperationBranch(
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
                : resolveSetOperationColumns(
                    firstTerm,
                    setOperation.getOperations().subList(0, last - 1)
                )
        );

        List<Source> recursiveScope = new ArrayList<>(scope);

        recursiveScope.add(sources.toDerivedSource(name, sqlName, firstColumns, false));

        AnalyzedSelect recursiveTerm = analyzeSetOperationBranch(
            branches.get(last),
            analysis,
            List.copyOf(recursiveScope),
            false,
            0,
            null
        );

        List<AnalyzedSelect> allBranches = new ArrayList<>(firstTerm);

        allBranches.add(recursiveTerm);

        requireSameSetOperationColumnCounts(allBranches);

        List<org.sqlcj.schema.Column> columns = resolveRecursiveColumns(
            name,
            firstColumns,
            recursiveTerm.columns()
        );

        requireResultColumnOrdering(setOperation, columns);

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
        org.sqlcj.schema.Column resolved = resolveSetOperationColumnType(first, first, recursive);

        if (isSameResultType(resolved, first)) {
            return;
        }

        throw new UnsupportedOperationException(
            RECURSIVE_COLUMN_TYPE_REJECTION.formatted(
                name,
                first.name(),
                sqlType(first),
                sqlType(resolved),
                first.name(),
                sqlType(resolved)
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

    private QueryModel analyzeInsert(Query query, ParsedSql parsedSql, Insert insert, Schema schema) {
        ReturningClause returningClause = insert.getReturningClause();

        requireWriteQueryType(query, returningClause);

        Table table = insert.getTable();
        Source source = sources.toSource(table, schema);

        Analysis analysis = new Analysis(parsedSql, schema, placeholderResolver.toPlaceholders(parsedSql, schema));

        List<Source> commonTableExpressions = analyzeCommonTableExpressions(
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

        return toQueryModel(
            query,
            analysis,
            table.getUnquotedName(),
            columns,
            rowTable
        );
    }

    private QueryModel analyzeUpdate(Query query, ParsedSql parsedSql, Update update, Schema schema) {
        ReturningClause returningClause = update.getReturningClause();

        requireWriteQueryType(query, returningClause);
        requireSupportedUpdateSources(update);

        Table table = update.getTable();
        Source target = this.sources.toSource(table, schema);

        Placeholders placeholders = placeholderResolver.toPlaceholders(parsedSql, schema);
        Analysis analysis = new Analysis(parsedSql, schema, placeholders);

        List<Source> commonTableExpressions = analyzeCommonTableExpressions(
            update.getWithItemsList(),
            analysis
        );

        List<Source> sources = this.sources.resolveWriteSources(
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
            placeholderResolver.resolveParameters(update.getWhere(), scope, analysis);
        }

        return toQueryModel(
            query,
            analysis,
            table.getUnquotedName(),
            columns,
            rowTable
        );
    }

    private QueryModel analyzeDelete(Query query, ParsedSql parsedSql, Delete delete, Schema schema) {
        ReturningClause returningClause = delete.getReturningClause();

        requireWriteQueryType(query, returningClause);
        requireSupportedDeleteSources(delete);

        Table table = delete.getTable();
        Source target = this.sources.toSource(table, schema);

        Analysis analysis = new Analysis(parsedSql, schema, placeholderResolver.toPlaceholders(parsedSql, schema));

        List<Source> commonTableExpressions = analyzeCommonTableExpressions(
            delete.getWithItemsList(),
            analysis
        );

        List<Source> sources = this.sources.resolveWriteSources(
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
            placeholderResolver.resolveParameters(
                delete.getWhere(),
                Scope.of(sources, commonTableExpressions),
                analysis
            );
        }

        return toQueryModel(
            query,
            analysis,
            table.getUnquotedName(),
            columns,
            rowTable
        );
    }

    private void requireResultQueryType(Query query) {
        if (!producesResult(query)) {
            throw new UnsupportedOperationException(
                "SELECT queries must be declared as :one, :optional, or :many"
            );
        }
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

        if (!producesResult(query)) {
            throw new UnsupportedOperationException(
                "Write queries with RETURNING must be declared as :one, :optional, or :many"
            );
        }
    }

    /** The annotations that declare a row-producing result shape. */
    private boolean producesResult(Query query) {
        return query.type() == QueryType.ONE
            || query.type() == QueryType.OPTIONAL
            || query.type() == QueryType.MANY;
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
            return this.sources.lookupSource(sources, returningQualifier(qualified))
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

        net.sf.jsqlparser.schema.Column returnedColumn = this.sources.directColumn(expression);

        if (returnedColumn != null) {
            ResolvedColumn resolved = this.sources.resolveColumn(returnedColumn, Scope.of(sources));
            org.sqlcj.schema.Column schemaColumn = resolved.column();

            return List.of(
                new QueryColumn(
                    selectedColumnName(alias, schemaColumn),
                    this.sources.requireSupportedType(schemaColumn),
                    schemaColumn.nullable() || resolved.source().nullable(),
                    schemaColumn.enumType(),
                    schemaColumn.array()
                )
            );
        }

        if (expression instanceof CastExpression cast && cast.getColDataType() != null) {
            if (alias == null) {
                throw new UnsupportedOperationException(
                    FUNCTION_ALIAS_REJECTION.formatted(written(cast), written(cast))
                );
            }

            return List.of(
                toQueryColumn(castColumn(cast, alias, schema, Scope.of(sources), true))
            );
        }

        throw new UnsupportedOperationException(
            COMPUTED_RETURNING_REJECTION.formatted(
                written(expression),
                castOperand(expression),
                hintAlias(alias)
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

            recordWildcardColumns(wildcards, returningItem, null, sources);

            return columns;
        }

        Source source = this.sources.findSource(sources, returningQualifier(qualified));

        if (source != target) {
            throw new UnsupportedOperationException(
                OTHER_SOURCE_RETURNING_WILDCARD_REJECTION.formatted(source.name(), source.name())
            );
        }

        List<QueryColumn> columns = returningWildcardColumns(target, embedRow);

        recordWildcardColumns(wildcards, returningItem, target, sources);

        return columns;
    }

    /**
     * The returned components of one wildcard of the write target, which are
     * one embedded row of the target beside another returned item and the
     * target row's own columns when the clause is exactly the wildcard.
     */
    private List<QueryColumn> returningWildcardColumns(Source target, boolean embedRow) {
        List<QueryColumn> columns = toQueryColumns(sources.resolveAllColumns(target));

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
     * One expression as the query writes it, on one line: the parser's own
     * spelling of the expression, with every run of whitespace written as one
     * space, so a diagnostic naming an expression written across lines stays
     * one line.
     */
    private String written(Expression expression) {
        return written(expression.toString());
    }

    /** One ordering item as the query writes it, which a diagnostic names. */
    private String written(OrderByElement orderByElement) {
        return written(orderByElement.toString());
    }

    private String written(String sql) {
        return sql
            .replaceAll("\\s+", " ")
            .trim();
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

        List<Select> selects = parenthesisLevels(rowSource);

        if (selects.get(selects.size() - 1) instanceof Values values) {
            analyzeCommonTableExpressions(selects, analysis, insertCommonTableExpressions);
            resolveInsertValueRows(values, columns, table, analysis.placeholders());

            return;
        }

        if (selects.get(selects.size() - 1) instanceof SetOperationList) {
            throw new UnsupportedOperationException(INSERT_SET_OPERATION_REJECTION);
        }

        PlainSelect plainSelect = requirePlainSelect(selects);

        resolveInsertSelect(
            plainSelect,
            columns,
            table,
            analyzeCommonTableExpressions(selects, analysis, insertCommonTableExpressions),
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
            org.sqlcj.schema.Column column = sources.findColumn(table, target.getUnquotedColumnName());

            if (sources.directColumn(target) != null) {
                placeholderResolver.resolveColumnValue(row.get(index), column, placeholders, column.nullable());
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
            : this.sources.resolveSources(plainSelect, analysis, commonTableExpressions, null);

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
                this.sources.findColumn(table, target.getUnquotedColumnName()),
                scope,
                analysis.placeholders()
            );
        }

        placeholderResolver.resolveBindingParameters(plainSelect, scope, analysis);
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
                Source qualified = this.sources.findSource(
                    sources,
                    allTableColumns.getTable().getUnquotedName()
                );

                addWildcardItems(items, qualified);

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
                    addWildcardItems(items, wildcardSource.columns().size());
                }

                recordWildcardColumns(analysis.wildcards(), selectItem, null, sources);

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

        net.sf.jsqlparser.schema.Column selectedColumn = sources.directColumn(item);

        if (selectedColumn != null) {
            sources.resolveColumn(selectedColumn, scope);

            return;
        }

        if (sources.directColumn(target) != null) {
            placeholderResolver.resolveColumnValue(item, column, placeholders);
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
            org.sqlcj.schema.Column column = sources.findColumn(table, target.getUnquotedColumnName());

            if (sources.directColumn(target) != null) {
                placeholderResolver.resolveColumnValue(
                    updateSet.getValue(0),
                    column,
                    placeholders,
                    column.nullable()
                );
            }

            placeholderResolver.resolveArithmeticParameters(updateSet.getValue(0), scope, placeholders);
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
            placeholderResolver.resolveParameters(action.getWhereExpression(), scope, analysis);
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
            sources.findColumn(source.table(), MultiPartName.unquote(element.getColumnName()));
        }

        if (target.getWhereExpression() != null) {
            placeholderResolver.resolveParameters(
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
                    && EXCLUDED_QUALIFIER.equalsIgnoreCase(sources.qualifier(column))
            ) {
                sources.findColumn(table, column.getUnquotedColumnName());
            }
        }
    }

    /**
     * Builds the analyzed model from the parameter occurrences resolved in
     * textual order, keeping that order for JDBC binding and exposing one
     * logical parameter per placeholder index.
     */
    private QueryModel toQueryModel(
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
    private ResolvedProjection resolveProjection(
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
                Source qualified = this.sources.findSource(
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
                        List<org.sqlcj.schema.Column> flatColumns = this.sources.resolveWildcardColumns(
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
    private List<WildcardSource> bareWildcardSources(List<Source> sources) {
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
                Source groupSource = this.sources.findSource(sources, name);

                wildcardSources.add(
                    WildcardSource.flat(groupSource, this.sources.exposedColumns(groupSource))
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
    private void recordWildcardColumns(
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
            .filter(source -> this.sources.exposes(source, column.name()))
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
        List<org.sqlcj.schema.Column> sourceColumns = sources.resolveAllColumns(source);

        columns.addAll(sourceColumns);

        if (embedsRow(source)) {
            resultColumns.add(
                QueryColumn.row(
                    source.name(),
                    source.nullable(),
                    source.rowTable(),
                    toQueryColumns(sources.resolveRowColumns(source))
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

        net.sf.jsqlparser.schema.Column selectedColumn = sources.directColumn(expression);

        if (selectedColumn != null) {
            ResolvedColumn resolved = sources.resolveColumn(selectedColumn, scope);
            org.sqlcj.schema.Column schemaColumn = resolved.column();

            return new org.sqlcj.schema.Column(
                selectedColumnName(selectItem.getAlias(), schemaColumn),
                sources.requireSupportedType(schemaColumn),
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

            analyzePredicateSubquery(subquery, scope, analysis);

            return new org.sqlcj.schema.Column(
                alias == null
                    ? written(exists)
                    : alias.getUnquotedName(),
                ColumnType.BOOLEAN,
                false,
                null,
                null,
                false,
                false,
                aliasSqlName(alias, written(exists))
            );
        }

        if (expression instanceof JsonExpression json && placeholderResolver.readsJsonText(json)) {
            return jsonTextColumn(json, selectItem.getAlias(), scope, namesResultColumns);
        }

        if (expression instanceof JdbcParameter || expression instanceof JdbcNamedParameter) {
            throw placeholderResolver
                .untypedPlaceholder(placeholderResolver.occurrenceOf(analysis.parsedSql(), expression));
        }

        throw new UnsupportedOperationException(
            COMPUTED_PROJECTION_REJECTION.formatted(
                written(expression),
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
                    written(selectItem.getExpression())
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
    private String castOperand(Expression expression) {
        String written = written(expression);

        return expression instanceof Function
            ? written
            : "(" + written + ")";
    }

    /**
     * The result alias a cast hint writes, which is the alias the query writes
     * as it spells it, and {@code <alias>} for the user to fill in where the
     * query writes none.
     */
    private String hintAlias(Alias alias) {
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

        net.sf.jsqlparser.schema.Column argumentColumn = sources.directColumn(argument);

        if (argumentColumn == null) {
            return null;
        }

        org.sqlcj.schema.Column column = sources.resolveColumn(argumentColumn, scope).column();
        ColumnType type = sources.requireSupportedType(column);

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

        net.sf.jsqlparser.schema.Column argumentColumn = sources.directColumn(arguments.get(0));

        if (argumentColumn == null) {
            return null;
        }

        org.sqlcj.schema.Column column = sources.resolveColumn(argumentColumn, scope).column();
        ColumnType type = sources.requireSupportedType(column);

        if (column.array() || !columnTypeMapping.isArrayElementType(type)) {
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
        net.sf.jsqlparser.schema.Column counted = sources.directColumn(argument);

        if (argument.getClass() == AllColumns.class) {
            if (quantified) {
                return null;
            }
        } else if (argument instanceof AllTableColumns qualified) {
            sources.findSource(scope, qualified.getTable().getUnquotedName());
        } else if (counted != null) {
            sources.resolveColumn(counted, scope);
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
            : written(expression);
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

        String written = written(expression);

        if (alias == null && namesResultColumns) {
            throw new UnsupportedOperationException(
                FUNCTION_ALIAS_REJECTION.formatted(written, written)
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
        net.sf.jsqlparser.schema.Column base = sources.directColumn(json.getExpression());

        if (base != null) {
            sources.resolveColumn(base, scope);
        }

        String written = written(json);

        if (alias == null && namesResultColumns) {
            throw new UnsupportedOperationException(
                FUNCTION_ALIAS_REJECTION.formatted(written, written)
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

        if (call == null || !functionCatalogue.holds(call.name(), call.form())) {
            return null;
        }

        List<FunctionCatalogue.Argument> arguments = new ArrayList<>();

        for (Expression argument : call.arguments()) {
            arguments.add(argumentFact(argument, analysis, scope, resolving));
        }

        return functionCatalogue.result(call.name(), call.form(), arguments);
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
        net.sf.jsqlparser.schema.Column column = sources.directColumn(argument);

        if (column != null) {
            return columnFact(column, scope, resolving);
        }

        if (argument instanceof CastExpression cast && cast.getColDataType() != null) {
            Schema schema = analysis.schema();

            ColumnTypeMapping.MappedType type = columnTypeMapping.map(
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
            ? Optional.of(sources.resolveColumn(column, scope))
            : sources.lookupColumn(column, scope);

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
                && sources.lookupSource(scope, qualified.getTable().getUnquotedName()).isEmpty();
        }

        net.sf.jsqlparser.schema.Column column = sources.directColumn(argument);

        if (column == null) {
            return false;
        }

        Optional<ResolvedColumn> resolved = sources.lookupColumn(column, scope);

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
    private org.sqlcj.schema.Column castColumn(
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
            ? written(cast)
            : alias.getUnquotedName();

        ColumnTypeMapping.MappedType type = columnTypeMapping.map(
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
        net.sf.jsqlparser.schema.Column column = sources.directColumn(operand);

        if (column != null) {
            return sources.lookupColumn(column, scope)
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
    private String resolveSelectRowTable(PlainSelect plainSelect, List<Source> sources) {
        List<SelectItem<?>> selectItems = plainSelect.getSelectItems();

        if (selectItems.size() != 1) {
            return null;
        }

        Expression expression = selectItems.get(0).getExpression();

        if (expression instanceof AllTableColumns allTableColumns) {
            return wildcardRowTable(
                this.sources.findSource(sources, allTableColumns.getTable().getUnquotedName())
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
    private List<QueryColumn> toQueryColumns(List<org.sqlcj.schema.Column> columns) {
        return columns.stream()
            .map(this::toQueryColumn)
            .toList();
    }

    /** The analyzed result column of one selected column. */
    private QueryColumn toQueryColumn(org.sqlcj.schema.Column column) {
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
    private String selectedColumnName(Alias alias, org.sqlcj.schema.Column schemaColumn) {
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
