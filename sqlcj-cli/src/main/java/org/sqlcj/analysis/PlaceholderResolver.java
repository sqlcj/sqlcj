package org.sqlcj.analysis;

import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.JsonExpression;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.operators.arithmetic.Addition;
import net.sf.jsqlparser.expression.operators.arithmetic.Division;
import net.sf.jsqlparser.expression.operators.arithmetic.Multiplication;
import net.sf.jsqlparser.expression.operators.arithmetic.Subtraction;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsDistinctExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.JsonOperator;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.expression.operators.relational.RegExpMatchOperator;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.Offset;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.sqlcj.parser.Query;
import org.sqlcj.parser.QueryType;
import org.sqlcj.schema.ColumnType;
import org.sqlcj.schema.Schema;
import org.sqlcj.schema.parser.ColumnTypeMapping;
import org.sqlcj.sql.ParsedSql;
import org.sqlcj.sql.PlaceholderOccurrence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves the placeholders one query binds: it checks the placeholder forms
 * the query writes, types the placeholders of each clause against the columns
 * and casts around them, resolves each placeholder occurrence to its logical
 * parameter, and assembles the parameters of the analyzed query. It reads the
 * analyzer it belongs to for the facts the rest of one query's analysis
 * resolves.
 */
final class PlaceholderResolver {

    private static final String MIXED_PLACEHOLDER_REJECTION = """
        Indexed '$N' and named ':name' placeholders must not be mixed in one query""";

    /**
     * The rejection of a parameter group placeholder in a {@code :batchexec}
     * query, which is written with the placeholder as the query spells it and
     * with the plain named placeholder of its field.
     */
    private static final String BATCH_PARAMETER_GROUP_REJECTION = """
        A :batchexec query cannot use the parameter group placeholder %s, because a batch already \
        takes one record per row; write a plain named placeholder, such as %s""";

    /**
     * The rejection of a parameter group placeholder in a query that carries the
     * {@code params} option, which is written with the placeholder as the query
     * spells it and with the plain named placeholder of its field.
     */
    private static final String PARAMETER_RECORD_GROUP_REJECTION = """
        A query with the option 'params' cannot use the parameter group placeholder %s, because \
        its parameters are already one record; write a plain named placeholder, such as %s""";

    /**
     * The rejection of the {@code params} option on a query that binds no
     * parameter, which has no parameter to make a record of.
     */
    private static final String PARAMETER_RECORD_WITHOUT_PARAMETER_REJECTION = """
        The query option 'params' has no effect on a query that binds no parameter; remove it""";

    /**
     * The rejection of a placeholder that no clause of the query types and no
     * cast states the type of, which names the placeholder as the query spells
     * it and where the query writes it. The suggested cast names no type of its
     * own and writes {@code <type>} for the user to fill in, because sqlcj
     * infers no type for such a placeholder and must not name a wrong one.
     */
    private static final String UNTYPED_PLACEHOLDER_REJECTION = """
        Cannot infer the type of %s in %s; state it with a cast, such as %s::<type>""";

    /** The parameter name of a {@code LIMIT} row count placeholder. */
    private static final String LIMIT_PARAMETER_NAME = "limit";

    /** The parameter name of an {@code OFFSET} value placeholder. */
    private static final String OFFSET_PARAMETER_NAME = "offset";

    /**
     * The prefix of the name an indexed placeholder takes where no column of
     * the query names it, which a cast types wherever it appears.
     */
    private static final String INDEXED_PARAMETER_NAME_PREFIX = "param";

    /**
     * The JSONB key-existence operator that tests one key, whose right operand
     * is a single key rather than a list of them.
     */
    private static final String JSON_KEY_OPERATOR = "?";

    /**
     * The JSONB key-existence operators, written as the parser reports them:
     * {@code ?} tests one key, {@code ?|} tests any of a list of keys, and
     * {@code ?&} tests all of them.
     */
    private static final List<String> JSON_KEY_OPERATORS = List.of(JSON_KEY_OPERATOR, "?|", "?&");

    /**
     * The JSON path operators whose result is {@code text}, written as the
     * parser reports them: {@code ->>} reads one key or one element and
     * {@code #>>} reads one path. Both always return {@code text}, so a
     * placeholder compared with such a result needs no cast, while {@code ->}
     * and {@code #>} return {@code json} or {@code jsonb} and type nothing.
     */
    private static final List<String> JSON_TEXT_OPERATORS = List.of("->>", "#>>");

    /**
     * Compares parameter occurrences by their Java type, which decides whether
     * a repeated placeholder index can share one generated parameter.
     */
    private final ParameterGroups parameterGroups = new ParameterGroups();

    /** The analysis whose query these placeholders are resolved for. */
    private final QueryAnalyzer analyzer;

    PlaceholderResolver(QueryAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    /**
     * Resolves the parameter occurrences of one query in the textual order of
     * the placeholder occurrences the SQL parser reported, which is the JDBC
     * binding order of the generated {@code ?} positions.
     *
     * <p>An occurrence a clause of the query typed is that clause's parameter.
     * An occurrence no clause typed is typed by the cast it is the direct
     * operand of, wherever in the statement it stands, and is named after its
     * own placeholder. An occurrence neither of them typed, of a parameter
     * neither of them typed anywhere in the query, is typed by the catalogue
     * call it is a direct argument of, where that position states a placeholder
     * type, and is named after its own placeholder as a cast-typed one is; and
     * an occurrence of a parameter no such position typed either is typed by
     * the aggregate a {@code HAVING} comparison compares it with, and is named
     * after that aggregate's own column.
     * Every remaining occurrence takes the parameter of the first occurrence of
     * its own logical parameter, in textual order, that one of the four typed,
     * wherever both stand, and sqlcj writes that parameter's type at it. A
     * logical parameter no occurrence types has no type to write, so the first
     * occurrence of such a parameter, in textual order, is rejected naming
     * where the query writes it.
     *
     * <p>An occurrence the query tests for null is nullable wherever its
     * parameter's type comes from, because the test gives a {@code null} bound
     * at it a meaning, so the guard of a criteria query is nullable both where
     * it states its own cast and where another occurrence types it.
     */
    ResolvedOccurrences resolveOccurrences(ParsedSql parsedSql, Placeholders placeholders) {
        List<PlaceholderOccurrence> reported = parsedSql.placeholders();
        List<QueryParameter> occurrences = new ArrayList<>(Collections.nCopies(reported.size(), null));
        Map<Integer, QueryParameter> firstTyped = new LinkedHashMap<>();

        for (int position = 0; position < reported.size(); position++) {
            PlaceholderOccurrence occurrence = reported.get(position);
            QueryParameter typed = placeholders.typed(occurrence.placeholder());

            if (typed == null) {
                CastPlaceholder cast = placeholders.ofCast(occurrence.cast());

                typed = cast == null
                    ? null
                    : toCastParameter(cast, indexedParameterName(cast.placeholder()), false);
            }

            if (typed != null) {
                occurrences.set(position, typed);
                firstTyped.putIfAbsent(typed.index(), typed);
            }
        }

        placeholders.requireReportedOccurrences();

        typeCallArguments(reported, placeholders, occurrences, firstTyped);
        typeHavingComparisons(reported, placeholders, occurrences, firstTyped);

        List<Integer> writtenTypes = new ArrayList<>();

        for (int position = 0; position < reported.size(); position++) {
            if (occurrences.get(position) != null) {
                continue;
            }

            PlaceholderOccurrence occurrence = reported.get(position);
            QueryParameter parameter = firstTyped.get(
                placeholders.of(occurrence.placeholder()).index()
            );

            if (parameter == null) {
                throw untypedPlaceholder(occurrence);
            }

            occurrences.set(position, parameter);
            writtenTypes.add(position);
        }

        for (int position = 0; position < reported.size(); position++) {
            if (reported.get(position).nullTested()) {
                occurrences.set(position, occurrences.get(position).asNullable());
            }
        }

        return new ResolvedOccurrences(occurrences, writtenTypes);
    }

    /**
     * Types each remaining occurrence that is a direct argument of a catalogue
     * call at a position that states a placeholder type, which PostgreSQL 16
     * infers there, so such an occurrence executes as a bare {@code ?}.
     *
     * <p>A call types a parameter only where no clause and no cast typed any
     * occurrence of it, so a query that compiles without this rule keeps the
     * parameter it has: the occurrence of such a parameter takes that
     * parameter, as it does today, with its type written at it. The first
     * occurrence typed here is the one a later uncast occurrence elsewhere
     * takes, and two positions of one parameter that state different types are
     * rejected as conflicting, as two clause-typed occurrences are.
     */
    private void typeCallArguments(
        List<PlaceholderOccurrence> reported,
        Placeholders placeholders,
        List<QueryParameter> occurrences,
        Map<Integer, QueryParameter> firstTyped
    ) {
        Set<Integer> stated = new HashSet<>(firstTyped.keySet());

        for (int position = 0; position < reported.size(); position++) {
            if (occurrences.get(position) != null) {
                continue;
            }

            PlaceholderOccurrence occurrence = reported.get(position);
            Placeholder placeholder = placeholders.of(occurrence.placeholder());

            if (stated.contains(placeholder.index())) {
                continue;
            }

            ColumnType type = argumentPlaceholderType(occurrence);

            if (type == null) {
                continue;
            }

            QueryParameter typed = toParameter(
                placeholder,
                indexedParameterName(placeholder),
                type,
                null,
                false,
                false,
                false
            );

            occurrences.set(position, typed);
            firstTyped.putIfAbsent(placeholder.index(), typed);
        }
    }

    /**
     * Types each remaining occurrence one {@code HAVING} comparison typed from
     * the aggregate it compares, which executes as a bare {@code ?}, because
     * the comparison itself states the type to PostgreSQL.
     *
     * <p>Such a comparison types a parameter only where no clause, no cast, and
     * no catalogue argument position typed any occurrence of it, so it is read
     * after every one of them and a query that compiles without this rule keeps
     * the parameter it has: the occurrence standing in {@code HAVING} takes that
     * parameter, as it does today, with its type written at it. The first
     * comparison in textual order is the one a later uncast occurrence
     * elsewhere takes, and two comparisons of one parameter that state
     * different types are rejected as conflicting, as two catalogue positions
     * are.
     */
    private void typeHavingComparisons(
        List<PlaceholderOccurrence> reported,
        Placeholders placeholders,
        List<QueryParameter> occurrences,
        Map<Integer, QueryParameter> firstTyped
    ) {
        Set<Integer> stated = new HashSet<>(firstTyped.keySet());

        for (int position = 0; position < reported.size(); position++) {
            if (occurrences.get(position) != null) {
                continue;
            }

            PlaceholderOccurrence occurrence = reported.get(position);
            Placeholder placeholder = placeholders.of(occurrence.placeholder());

            if (stated.contains(placeholder.index())) {
                continue;
            }

            QueryParameter typed = placeholders.deferred(occurrence.placeholder());

            if (typed == null) {
                continue;
            }

            occurrences.set(position, typed);
            firstTyped.putIfAbsent(placeholder.index(), typed);
        }
    }

    /**
     * The type the catalogue states for one occurrence at its own argument
     * position, and {@code null} for an occurrence that is the direct argument
     * of no call, of a call the catalogue does not hold or does not read, such
     * as a qualified or a {@code DISTINCT} one, and of a position that states
     * no placeholder type.
     */
    private ColumnType argumentPlaceholderType(PlaceholderOccurrence occurrence) {
        if (occurrence.call() == null) {
            return null;
        }

        CatalogueCall call = analyzer.projectionResolver.catalogueCall(occurrence.call());

        return call == null
            ? null
            : analyzer.functionCatalogue.placeholderType(
                call.name(),
                call.form(),
                call.arguments().size(),
                occurrence.argument()
            );
    }

    /**
     * The rejection of one placeholder occurrence no clause of the query typed
     * and no cast states the type of, which the SQL parser reported together
     * with where the query writes it.
     */
    UnsupportedOperationException untypedPlaceholder(PlaceholderOccurrence occurrence) {
        String spelled = spelledPlaceholder(occurrence.placeholder());

        return new UnsupportedOperationException(
            UNTYPED_PLACEHOLDER_REJECTION.formatted(spelled, occurrence.enclosing(), spelled)
        );
    }

    /**
     * One placeholder expression as the query spells it, which is its own name
     * for a named placeholder and its index for an indexed one. Every other
     * placeholder form is already rejected before a placeholder is spelled.
     */
    private String spelledPlaceholder(Expression placeholder) {
        if (placeholder instanceof JdbcNamedParameter named) {
            return QueryAnalyzer.NAME_PREFIX + named.getName();
        }

        return "$" + ((JdbcParameter) placeholder).getIndex();
    }

    /**
     * The occurrence the SQL parser reported for one placeholder expression of
     * the parsed source, which every placeholder of that source has.
     */
    PlaceholderOccurrence occurrenceOf(ParsedSql parsedSql, Expression placeholder) {
        return parsedSql.placeholders().stream()
            .filter(occurrence -> occurrence.placeholder() == placeholder)
            .findFirst()
            .orElseThrow(
                () -> new IllegalStateException(
                    "A placeholder is not a reported placeholder occurrence."
                )
            );
    }

    /**
     * Requires that the occurrences resolved against the schema are exactly the
     * placeholder tokens the SQL parser reported, in the same textual order, so
     * that every executable {@code ?} position has one typed binding source.
     */
    List<Integer> requireAccountedOccurrences(ParsedSql parsedSql, List<QueryParameter> occurrences) {
        List<Integer> analyzed = occurrences.stream()
            .map(QueryParameter::index)
            .toList();

        List<Integer> compiled = parsedSql.parameters().indexes();
        List<String> names = parsedSql.parameters().names();

        if (!analyzed.equals(compiled)) {
            throw new UnsupportedOperationException(
                "SQL placeholders %s are not the analyzed parameters %s; a placeholder is in an unsupported location"
                    .formatted(
                        describePlaceholders(compiled, names),
                        describePlaceholders(analyzed, names)
                    )
            );
        }

        return analyzed;
    }

    /**
     * Writes a list of logical parameter numbers as the query spells its
     * placeholders, which is {@code :name} for a named query and the number
     * itself for an indexed one.
     */
    private String describePlaceholders(List<Integer> indexes, List<String> names) {
        if (names.isEmpty()) {
            return indexes.toString();
        }

        return indexes.stream()
            .map(index -> describePlaceholder(index, names))
            .collect(Collectors.joining(", ", "[", "]"));
    }

    /** Writes one logical parameter as the query spells its placeholder. */
    String describePlaceholder(int index, List<String> names) {
        return names.isEmpty()
            ? "$" + index
            : QueryAnalyzer.NAME_PREFIX + names.get(index - 1);
    }

    /**
     * Retains one parameter per placeholder index in logical index order. A
     * repeated index keeps the name and type of its first occurrence and is
     * accepted only when every occurrence resolves to the same Java type. It is
     * nullable when any of its occurrences is, because a {@code null} argument
     * is bound at every position of the index and one position giving it a
     * meaning is enough.
     */
    List<QueryParameter> toParameters(List<QueryParameter> occurrences, List<String> names) {
        Map<Integer, QueryParameter> parametersByIndex = new LinkedHashMap<>();

        for (QueryParameter occurrence : occurrences) {
            QueryParameter parameter = parametersByIndex.putIfAbsent(occurrence.index(), occurrence);

            if (parameter != null) {
                requireSameParameterType(parameter, occurrence, names);

                if (occurrence.nullable()) {
                    parametersByIndex.put(parameter.index(), parameter.asNullable());
                }
            }
        }

        List<QueryParameter> parameters = parametersByIndex.values().stream()
            .sorted(Comparator.comparingInt(QueryParameter::index))
            .toList();

        requireContiguousIndexes(parameters);

        return parameters;
    }

    /**
     * Requires two occurrences of one placeholder index to have the same type.
     * An enum occurrence is the same type only as an occurrence of the same
     * enum type, because each enum type generates a Java type of its own; every
     * other occurrence is compared by the Java type it resolves to. An array
     * occurrence is a list of its element's Java type, so it is never the same
     * type as an occurrence of that element.
     */
    private void requireSameParameterType(
        QueryParameter parameter,
        QueryParameter occurrence,
        List<String> names
    ) {
        if (parameterGroups.isSameType(parameter, occurrence)) {
            return;
        }

        if (!names.isEmpty()) {
            throw new UnsupportedOperationException(
                "Placeholder %s has conflicting types: %s and %s"
                    .formatted(
                        describePlaceholder(parameter.index(), names),
                        parameterGroups.describeType(parameter),
                        parameterGroups.describeType(occurrence)
                    )
            );
        }

        throw new UnsupportedOperationException(
            "Placeholder $%d has conflicting types: %s from '%s' and %s from '%s'"
                .formatted(
                    parameter.index(),
                    parameterGroups.describeType(parameter),
                    parameter.name(),
                    parameterGroups.describeType(occurrence),
                    occurrence.name()
                )
        );
    }

    private void requireContiguousIndexes(List<QueryParameter> parameters) {
        for (int index = 0; index < parameters.size(); index++) {
            if (parameters.get(index).index() != index + 1) {
                throw new UnsupportedOperationException(
                    "Placeholder indexes must start at $1 without gaps, but were %s"
                        .formatted(
                            parameters.stream()
                                .map(QueryParameter::index)
                                .toList()
                        )
                );
            }
        }
    }

    /**
     * Resolves the parameters the clauses of a read type over its whole level,
     * which are the {@code WHERE} parameters, the {@code HAVING} parameters,
     * and the pagination parameters. Each join condition types its own
     * placeholders over its join group where its sources are resolved, and
     * binding order comes from the textual placeholder order the parser
     * reported, not from the order the clauses are analyzed in.
     */
    void resolveBindingParameters(
        PlainSelect plainSelect,
        Scope scope,
        Analysis analysis
    ) {
        if (plainSelect.getWhere() != null) {
            resolveParameters(
                plainSelect.getWhere(),
                scope,
                analysis
            );
        }

        if (plainSelect.getHaving() != null) {
            resolveHavingParameters(
                plainSelect.getHaving(),
                scope,
                analysis
            );
        }

        resolvePaginationParameters(plainSelect, analysis.placeholders());
    }

    /**
     * Resolves the parameters a {@code HAVING} predicate types from the
     * aggregates it compares, over the level the predicate stands in. The rules
     * descend through {@code AND}, {@code OR}, {@code NOT}, and parentheses as
     * the predicate rules of a {@code WHERE} clause do, and every other
     * expression of the clause reaches the database as written, so PostgreSQL
     * rather than sqlcj checks it.
     */
    private void resolveHavingParameters(
        Expression expression,
        Scope scope,
        Analysis analysis
    ) {
        if (expression instanceof AndExpression and) {
            resolveHavingParameters(and.getLeftExpression(), scope, analysis);
            resolveHavingParameters(and.getRightExpression(), scope, analysis);
            return;
        }

        if (expression instanceof OrExpression or) {
            resolveHavingParameters(or.getLeftExpression(), scope, analysis);
            resolveHavingParameters(or.getRightExpression(), scope, analysis);
            return;
        }

        if (expression instanceof NotExpression not) {
            resolveHavingParameters(not.getExpression(), scope, analysis);
            return;
        }

        if (expression instanceof ParenthesedExpressionList<?> parentheses) {
            for (Expression nestedExpression : parentheses) {
                resolveHavingParameters(nestedExpression, scope, analysis);
            }
            return;
        }

        if (
            expression instanceof ComparisonOperator comparison
                && isColumnTypedComparison(comparison)
        ) {
            resolveHavingComparison(comparison, scope, analysis);
        }
    }

    /**
     * Resolves the parameter one {@code HAVING} comparison types, which is an
     * uncast placeholder written on either side of a typed aggregate: it takes
     * that aggregate's type and its column name, as a placeholder compared with
     * a column does, and is non-null for the same reason. The six comparisons
     * {@link #isColumnTypedComparison} names that the parser reports as
     * comparison operators compare one aggregated value this way; every other
     * operator of the clause states no such value.
     *
     * <p>The parameter is deferred rather than recorded, because a clause or a
     * cast elsewhere in the query may state the type sqlcj writes at every
     * occurrence of it, as a catalogue argument position is deferred.
     */
    private void resolveHavingComparison(
        ComparisonOperator comparison,
        Scope scope,
        Analysis analysis
    ) {
        Placeholders placeholders = analysis.placeholders();

        Expression left = comparison.getLeftExpression();
        Expression right = comparison.getRightExpression();

        Placeholder leftPlaceholder = placeholders.of(left);

        if (leftPlaceholder != null) {
            deferHavingAggregateParameter(leftPlaceholder, right, scope, placeholders);
            return;
        }

        Placeholder rightPlaceholder = placeholders.of(right);

        if (rightPlaceholder != null) {
            deferHavingAggregateParameter(rightPlaceholder, left, scope, placeholders);
        }
    }

    /**
     * Defers the parameter of one uncast placeholder compared with an
     * aggregate in {@code HAVING}, where that aggregate is one the projection
     * rules type a result column of. An aggregate they type none of — a
     * {@code MIN} or {@code MAX} of a type PostgreSQL defines neither for, an
     * {@code array_agg}, a grouped column, and arithmetic — states no type, so
     * such a placeholder keeps needing a cast.
     */
    private void deferHavingAggregateParameter(
        Placeholder placeholder,
        Expression aggregate,
        Scope scope,
        Placeholders placeholders
    ) {
        org.sqlcj.schema.Column column = havingAggregateColumn(aggregate, scope);

        if (column == null) {
            return;
        }

        placeholders.recordDeferred(
            placeholder,
            toParameter(
                placeholder,
                column.name(),
                column.type(),
                column.enumType(),
                column.array(),
                column.blankPadded(),
                false
            )
        );
    }

    /**
     * The result column one compared aggregate of a {@code HAVING} predicate
     * states, and {@code null} for every expression the projection rules type
     * none of.
     *
     * <p>The aggregate's own references are read as the operand of a cast is,
     * not as a projection's are: the clause reaches the database as written, so
     * nothing in it is required to resolve. A reference the level in scope has
     * states its column, while one no source of it exposes — a whole-row
     * reference such as {@code count(u)}, a system column such as
     * {@code count(xmin)}, an ambiguous reference, and the qualifier of
     * {@code count(x.*)} no source exposes — states no type, as a column the
     * schema recorded without a mapped type does. Such a comparison therefore
     * types nothing rather than rejecting a query PostgreSQL itself accepts.
     *
     * <p>The aggregate is read without an alias and as a projection that names
     * its result columns reads it, so the column it states carries the name
     * PostgreSQL gives it, which an indexed placeholder takes as its parameter
     * name.
     */
    private org.sqlcj.schema.Column havingAggregateColumn(Expression aggregate, Scope scope) {
        if (aggregate instanceof Function function) {
            return analyzer.projectionResolver.leavesAggregateUntyped(function, scope, false)
                ? null
                : analyzer.projectionResolver.aggregateColumn(function, null, scope, true);
        }

        if (aggregate instanceof AnalyticExpression analytic) {
            return leavesFilteredCountUntyped(analytic, scope)
                ? null
                : analyzer.projectionResolver.filteredCountColumn(analytic, null, scope, true);
        }

        return null;
    }

    /**
     * Reports whether the filtered-{@code COUNT} rule states no type for one
     * compared analytic expression, which is a count of a reference the level
     * in scope does not have, read as {@link ProjectionResolver#leavesAggregateUntyped} reads the
     * same reference of a plain call written where nothing must resolve.
     *
     * <p>A {@code COUNT} never reads its argument's type, so only the reference
     * decides here, and an argument that is no reference at all reaches the
     * database as written, as a plain {@code COUNT}'s does.
     */
    private boolean leavesFilteredCountUntyped(AnalyticExpression analytic, Scope scope) {
        Expression argument = analytic.getExpression();

        if (argument instanceof AllTableColumns qualified) {
            return analyzer.sources.lookupSource(scope, qualified.getTable().getUnquotedName()).isEmpty();
        }

        net.sf.jsqlparser.schema.Column column = analyzer.sources.directColumn(argument);

        return column != null && analyzer.sources.lookupColumn(column, scope).isEmpty();
    }

    /**
     * Resolves the pagination parameters, which are the {@code LIMIT} row count
     * and the {@code OFFSET} value. Either value binds one parameter named after
     * its own clause, or after the placeholder when the placeholder is named.
     * The parser stores {@code OFFSET a LIMIT b} exactly like
     * {@code LIMIT b OFFSET a}, so the two bind in the order the parser reported
     * their placeholders, not in the order they are resolved here.
     *
     * <p>The clauses page a plain {@code SELECT} and the result of a set
     * operation by the same rules, so either query states them.
     */
    void resolvePaginationParameters(Select select, Placeholders placeholders) {
        Offset offset = select.getOffset();

        resolvePaginationValue(
            resolveLimitRowCount(select.getLimit()),
            LIMIT_PARAMETER_NAME,
            placeholders
        );

        resolvePaginationValue(
            offset == null
                ? null
                : offset.getOffset(),
            OFFSET_PARAMETER_NAME,
            placeholders
        );
    }

    /**
     * Resolves one pagination value: a placeholder, which counts rows and is
     * therefore an {@code INTEGER}, or a cast placeholder, which states its own
     * type and keeps its clause's name. Every other value reaches the database
     * as written.
     */
    private void resolvePaginationValue(
        Expression value,
        String name,
        Placeholders placeholders
    ) {
        Placeholder placeholder = placeholders.of(value);

        if (placeholder != null) {
            addParameter(placeholder, name, ColumnType.INTEGER, placeholders);

            return;
        }

        CastPlaceholder cast = placeholders.ofCast(value);

        if (cast != null) {
            addCastParameter(cast, name, placeholders);
        }
    }

    /**
     * Reports the analyzed row count of a {@code LIMIT} clause, which is absent
     * when the clause carries an offset of its own, as in the {@code LIMIT a, b}
     * form.
     */
    private Expression resolveLimitRowCount(Limit limit) {
        return limit == null || limit.getOffset() != null
            ? null
            : limit.getRowCount();
    }

    /**
     * Resolves the parameters one predicate types, over the level the predicate
     * stands in. A negated predicate states the same operands as the predicate
     * it negates, so the rules descend through {@code NOT} as they do through
     * {@code AND}, {@code OR}, and parentheses.
     *
     * <p>A subquery of {@code EXISTS}, which {@code NOT EXISTS} negates, is
     * analyzed as the level inside this one.
     */
    void resolveParameters(
        Expression expression,
        Scope scope,
        Analysis analysis
    ) {
        if (expression instanceof AndExpression and) {
            resolveParameters(and.getLeftExpression(), scope, analysis);
            resolveParameters(and.getRightExpression(), scope, analysis);
            return;
        }

        if (expression instanceof OrExpression or) {
            resolveParameters(or.getLeftExpression(), scope, analysis);
            resolveParameters(or.getRightExpression(), scope, analysis);
            return;
        }

        if (expression instanceof NotExpression not) {
            resolveParameters(not.getExpression(), scope, analysis);
            return;
        }

        if (expression instanceof ParenthesedExpressionList<?> parentheses) {
            for (Expression nestedExpression : parentheses) {
                resolveParameters(
                    nestedExpression,
                    scope,
                    analysis
                );
            }
            return;
        }

        if (expression instanceof ExistsExpression exists) {
            if (exists.getRightExpression() instanceof ParenthesedSelect subquery) {
                analyzer.analyzePredicateSubquery(subquery, scope, analysis);
            }

            return;
        }

        if (expression instanceof InExpression in) {
            resolveInExpression(in, scope, analysis);
            return;
        }

        if (expression instanceof LikeExpression like) {
            resolveLikeExpression(like, scope, analysis);
            return;
        }

        if (expression instanceof IsNullExpression isNull) {
            resolveIsNullExpression(isNull, scope);
            return;
        }

        if (expression instanceof Between between) {
            resolveBetweenExpression(between, scope, analysis);
            return;
        }

        if (
            expression instanceof JsonOperator json
                && JSON_KEY_OPERATORS.contains(json.getStringExpression())
        ) {
            resolveJsonKeyExistence(json, scope, analysis);
            return;
        }

        if (expression instanceof ComparisonOperator comparison) {
            resolveParameterComparison(comparison, scope, analysis);
            return;
        }

        if (expression instanceof IsDistinctExpression isDistinct) {
            resolveParameterComparison(isDistinct, scope, analysis);
            return;
        }

        if (expression instanceof RegExpMatchOperator match) {
            resolveParameterComparison(match, scope, analysis);
        }
    }

    /**
     * Resolves one JSONB key-existence predicate, written {@code ?},
     * {@code ?|}, or {@code ?&}. A direct column on either side resolves
     * against the level the predicate stands in, as a comparison's operand
     * does.
     *
     * <p>PostgreSQL fixes the key type of each operator, so an uncast
     * placeholder written as the right operand binds {@code text} for
     * {@code ?} and {@code text[]} for {@code ?|} and {@code ?&}, named after
     * itself when named and after its own index otherwise. Every other operand
     * keeps the rules of the expression it is, so a cast placeholder states its
     * own type.
     */
    private void resolveJsonKeyExistence(
        JsonOperator json,
        Scope scope,
        Analysis analysis
    ) {
        Placeholders placeholders = analysis.placeholders();

        net.sf.jsqlparser.schema.Column leftColumn = analyzer.sources.directColumn(json.getLeftExpression());

        if (leftColumn != null) {
            analyzer.sources.resolveColumn(leftColumn, scope);
        }

        Expression right = json.getRightExpression();
        net.sf.jsqlparser.schema.Column rightColumn = analyzer.sources.directColumn(right);

        if (rightColumn != null) {
            analyzer.sources.resolveColumn(rightColumn, scope);
        }

        Placeholder placeholder = placeholders.of(right);

        if (placeholder != null) {
            addParameter(
                placeholder,
                indexedParameterName(placeholder),
                ColumnType.TEXT,
                null,
                !JSON_KEY_OPERATOR.equals(json.getStringExpression()),
                false,
                placeholders
            );
        }
    }

    /**
     * Resolves one {@code IN} or {@code NOT IN} predicate. A tested direct
     * column resolves against the level the predicate stands in, and a
     * membership query is analyzed as the level inside it, whatever the tested
     * value is, because the query states its own rows rather than values of
     * that column.
     */
    private void resolveInExpression(InExpression in, Scope scope, Analysis analysis) {
        net.sf.jsqlparser.schema.Column column = analyzer.sources.directColumn(in.getLeftExpression());

        if (in.getRightExpression() instanceof ParenthesedSelect subquery) {
            if (column != null) {
                analyzer.sources.resolveColumn(column, scope);
            }

            analyzer.analyzePredicateSubquery(subquery, scope, analysis);

            return;
        }

        if (column == null) {
            return;
        }

        org.sqlcj.schema.Column schemaColumn = analyzer.sources.resolveColumn(column, scope).column();

        Expression rightExpression = in.getRightExpression();

        if (rightExpression instanceof ExpressionList<?> expressionList) {
            for (Expression expression : expressionList) {
                resolveColumnValue(expression, schemaColumn, analysis.placeholders());
            }

            return;
        }

        resolveInExpression(
            rightExpression,
            schemaColumn,
            scope,
            analysis
        );
    }

    private void resolveInExpression(
        Expression expression,
        org.sqlcj.schema.Column schemaColumn,
        Scope scope,
        Analysis analysis
    ) {
        Placeholders placeholders = analysis.placeholders();

        Placeholder placeholder = placeholders.of(expression);

        if (placeholder != null) {
            addParameter(placeholder, schemaColumn, placeholders);
            return;
        }

        if (expression instanceof AndExpression and) {
            resolveInExpression(
                and.getLeftExpression(),
                schemaColumn,
                scope,
                analysis
            );

            resolveInExpression(
                and.getRightExpression(),
                schemaColumn,
                scope,
                analysis
            );

            return;
        }

        if (expression instanceof OrExpression or) {
            resolveInExpression(
                or.getLeftExpression(),
                schemaColumn,
                scope,
                analysis
            );

            resolveInExpression(
                or.getRightExpression(),
                schemaColumn,
                scope,
                analysis
            );

            return;
        }

        if (expression instanceof ParenthesedExpressionList<?> expressionList) {
            for (Expression nestedExpression : expressionList) {
                Placeholder nested = placeholders.of(nestedExpression);

                if (nested != null) {
                    addParameter(nested, schemaColumn, placeholders);
                } else {
                    resolveParameters(
                        nestedExpression,
                        scope,
                        analysis
                    );
                }
            }

            return;
        }

        CastPlaceholder cast = placeholders.ofCast(expression);

        if (cast != null) {
            addCastParameter(cast, schemaColumn.name(), placeholders);
        }
    }

    /**
     * Resolves the pattern parameter of {@code <column> LIKE $N} or
     * {@code <column> ILIKE $N}, typed from the tested text column. A pattern
     * that binds no placeholder reaches the database as written, so a literal
     * pattern stays unanalyzed.
     *
     * <p>A cast pattern states its own type, so the pattern shape and the
     * tested column's type restrict only an uncast pattern, which takes the
     * tested column's type. A cast pattern keeps the tested column's name only
     * in the shape that accepts an uncast pattern, because only there would an
     * uncast pattern be named after that column; in every other shape it is
     * named after its own placeholder.
     */
    private void resolveLikeExpression(
        LikeExpression like,
        Scope scope,
        Analysis analysis
    ) {
        Placeholders placeholders = analysis.placeholders();

        Expression left = like.getLeftExpression();
        Expression right = like.getRightExpression();

        if (placeholders.of(left) != null) {
            throw new UnsupportedOperationException(
                "A LIKE placeholder must be the pattern, not the tested value."
            );
        }

        Placeholder pattern = placeholders.of(right);

        if (pattern != null) {
            requireSupportedLikePattern(like);

            net.sf.jsqlparser.schema.Column testedColumn = analyzer.sources.directColumn(left);

            if (testedColumn == null) {
                return;
            }

            org.sqlcj.schema.Column schemaColumn = analyzer.sources.resolveColumn(testedColumn, scope).column();

            analyzer.sources.requireSupportedType(schemaColumn);

            addParameter(
                pattern,
                requireTextColumn(schemaColumn),
                placeholders
            );

            return;
        }

        CastPlaceholder castPattern = placeholders.ofCast(right);
        net.sf.jsqlparser.schema.Column testedColumn = analyzer.sources.directColumn(left);

        if (
            castPattern != null
                && unsupportedLikePatternReason(like) == null
                && testedColumn != null
        ) {
            addCastParameter(
                castPattern,
                analyzer.sources.resolveColumn(testedColumn, scope).column().name(),
                placeholders
            );
        }
    }

    /**
     * Requires the exact {@code LIKE}/{@code ILIKE} pattern shape this subset
     * types, so a related keyword or modifier is never analyzed as a plain
     * pattern match.
     */
    private void requireSupportedLikePattern(LikeExpression like) {
        String reason = unsupportedLikePatternReason(like);

        if (reason != null) {
            throw new UnsupportedOperationException(reason);
        }
    }

    /**
     * Reports why a {@code LIKE} expression is not the pattern shape this
     * subset types, and {@code null} when it is that shape. The reason is the
     * rejection of an uncast pattern placeholder, and it is also what decides
     * whether a cast pattern is named after the tested column.
     */
    private String unsupportedLikePatternReason(LikeExpression like) {
        if (like.isNot()) {
            return "A negated LIKE pattern placeholder is not supported.";
        }

        LikeExpression.KeyWord keyword = like.getLikeKeyWord();

        if (keyword != LikeExpression.KeyWord.LIKE && keyword != LikeExpression.KeyWord.ILIKE) {
            return "Only LIKE and ILIKE pattern placeholders are supported, but was: " + keyword;
        }

        if (like.getEscape() != null) {
            return "A LIKE pattern placeholder must not have an ESCAPE clause.";
        }

        if (like.isUseBinary()) {
            return "A binary LIKE pattern placeholder is not supported.";
        }

        return null;
    }

    /**
     * Requires a text column, because the pattern parameter takes the tested
     * column's type and only a character type makes that type a pattern.
     * {@code citext} is one, so a pattern of one matches case-insensitively
     * as PostgreSQL's own {@code citext} {@code LIKE} does.
     */
    private org.sqlcj.schema.Column requireTextColumn(org.sqlcj.schema.Column column) {
        boolean text = column.type() == ColumnType.VARCHAR
            || column.type() == ColumnType.TEXT
            || column.type() == ColumnType.CITEXT;

        if (!text || column.array()) {
            throw new UnsupportedOperationException(
                ("A LIKE pattern placeholder requires a VARCHAR, TEXT, or CITEXT column,"
                    + " but %s is %s.")
                    .formatted(
                        column.name(),
                        column.array()
                            ? column.type() + "[]"
                            : column.type()
                    )
            );
        }

        return column;
    }

    /**
     * Resolves the tested column of {@code IS NULL} and {@code IS NOT NULL}
     * against the query analyzer.sources. The predicate names no parameter after its
     * tested value, so a tested value that is not a direct column is left to
     * the cast that types its own placeholder.
     */
    private void resolveIsNullExpression(IsNullExpression isNull, Scope scope) {
        Expression tested = isNull.getLeftExpression();

        if (tested instanceof net.sf.jsqlparser.schema.Column column) {
            analyzer.sources.resolveColumn(column, scope);
        }
    }

    /**
     * Resolves the bounds of {@code <column> BETWEEN $a AND $b} and its
     * negation, typing each placeholder bound from the tested column and adding
     * the start bound before the end bound. A bound that binds no placeholder
     * reaches the database as written, so a range of literal bounds stays
     * unanalyzed.
     */
    private void resolveBetweenExpression(
        Between between,
        Scope scope,
        Analysis analysis
    ) {
        Placeholders placeholders = analysis.placeholders();

        Expression left = between.getLeftExpression();
        Expression start = between.getBetweenExpressionStart();
        Expression end = between.getBetweenExpressionEnd();

        if (placeholders.of(left) != null) {
            return;
        }

        net.sf.jsqlparser.schema.Column testedColumn = analyzer.sources.directColumn(left);

        if (testedColumn != null && (bindsValue(start, placeholders) || bindsValue(end, placeholders))) {
            org.sqlcj.schema.Column schemaColumn = analyzer.sources.resolveColumn(testedColumn, scope).column();

            resolveColumnValue(start, schemaColumn, placeholders);
            resolveColumnValue(end, schemaColumn, placeholders);
        }
    }

    /**
     * Resolves the operands of one comparison, which names a parameter after
     * the column it compares. A placeholder compared with a column takes that
     * column's type, and a cast placeholder states its own type and keeps the
     * column's name. A placeholder inside the {@code = ANY(p)} form takes a
     * list of the compared column's values. Every other operand contributes
     * only the parameters its own cast placeholders state.
     *
     * <p>An operand that is a subquery states its own value rather than a
     * column's, so it is analyzed as the level inside the one the comparison
     * stands in and types no parameter of this comparison.
     *
     * <p>Arithmetic inside either operand types the placeholders it writes
     * beside a numeric column, which the comparison's own rules never reach.
     *
     * <p>{@code IS [NOT] DISTINCT FROM} and the regular-expression matches
     * {@code ~}, {@code ~*}, {@code !~}, and {@code !~*}, which the parser
     * reports as plain binary expressions rather than comparisons, compare one
     * column value as a comparison does and so are resolved by these same
     * rules.
     */
    private void resolveParameterComparison(
        BinaryExpression comparison,
        Scope scope,
        Analysis analysis
    ) {
        Placeholders placeholders = analysis.placeholders();

        Expression left = comparison.getLeftExpression();
        Expression right = comparison.getRightExpression();

        resolveArithmeticParameters(left, scope, placeholders);
        resolveArithmeticParameters(right, scope, placeholders);

        if (resolveComparisonSubqueries(left, right, scope, analysis)) {
            return;
        }

        Placeholder leftPlaceholder = placeholders.of(left);
        Placeholder rightPlaceholder = placeholders.of(right);

        net.sf.jsqlparser.schema.Column leftColumn = analyzer.sources.directColumn(left);
        net.sf.jsqlparser.schema.Column rightColumn = analyzer.sources.directColumn(right);

        if (leftColumn != null && rightPlaceholder != null) {
            addParameter(
                rightPlaceholder,
                analyzer.sources.resolveColumn(leftColumn, scope).column(),
                placeholders
            );
            return;
        }

        if (leftPlaceholder != null && rightColumn != null) {
            addParameter(
                leftPlaceholder,
                analyzer.sources.resolveColumn(rightColumn, scope).column(),
                placeholders
            );
            return;
        }

        if (comparison instanceof EqualsTo && leftColumn != null) {
            Expression listArgument = anyListArgument(right, placeholders);

            if (listArgument != null) {
                resolveAnyListParameter(listArgument, leftColumn, scope, placeholders);
                return;
            }
        }

        if (isColumnTypedComparison(comparison)) {
            if (rightPlaceholder != null && readsJsonText(left)) {
                addJsonTextParameter(rightPlaceholder, placeholders);
                return;
            }

            if (leftPlaceholder != null && readsJsonText(right)) {
                addJsonTextParameter(leftPlaceholder, placeholders);
                return;
            }

            if (leftColumn != null) {
                CastPlaceholder cast = placeholders.ofCast(right);

                if (cast != null) {
                    addCastParameter(
                        cast,
                        analyzer.sources.resolveColumn(leftColumn, scope).column().name(),
                        placeholders
                    );
                }
            } else if (rightColumn != null) {
                CastPlaceholder cast = placeholders.ofCast(left);

                if (cast != null) {
                    addCastParameter(
                        cast,
                        analyzer.sources.resolveColumn(rightColumn, scope).column().name(),
                        placeholders
                    );
                }
            }
        }
    }

    /**
     * Records the parameter of one uncast placeholder compared with the
     * {@code text} result of a JSON path, which binds {@code text} named after
     * itself when it is named and after its own index otherwise, as a key of
     * the key-existence operators does.
     */
    private void addJsonTextParameter(Placeholder placeholder, Placeholders placeholders) {
        addParameter(
            placeholder,
            indexedParameterName(placeholder),
            ColumnType.TEXT,
            placeholders
        );
    }

    /**
     * Reports whether one operand is a JSON path whose written result is
     * {@code text}, which is a path whose last step is {@code ->>} or
     * {@code #>>}. A path whose last step is {@code ->} or {@code #>} returns
     * {@code json} or {@code jsonb}, and a parenthesized path is another
     * expression, so neither types a placeholder written beside it.
     */
    boolean readsJsonText(Expression operand) {
        if (!(operand instanceof JsonExpression json)) {
            return false;
        }

        String operator = lastJsonOperator(json);

        return operator != null && JSON_TEXT_OPERATORS.contains(operator);
    }

    /**
     * The operator of the last step of one JSON path, and {@code null} for a
     * path that writes no step. The parser nests a chain of steps into the key
     * of the step before it, so {@code meta -> 'a' ->> 'b'} is one {@code ->}
     * step whose key is the path {@code 'a' ->> 'b'}, and the last step of the
     * written path is the last step of that key.
     */
    private String lastJsonOperator(JsonExpression json) {
        List<Map.Entry<Expression, String>> steps = json.getIdentList();

        if (steps.isEmpty()) {
            return null;
        }

        Map.Entry<Expression, String> last = steps.get(steps.size() - 1);

        if (last.getKey() instanceof JsonExpression nested) {
            String nestedOperator = lastJsonOperator(nested);

            if (nestedOperator != null) {
                return nestedOperator;
            }
        }

        return last.getValue();
    }

    /**
     * Resolves the parameters arithmetic types inside one expression of a
     * clause that reads a row. A placeholder written as a direct operand of
     * {@code +}, {@code -}, {@code *}, or {@code /} beside a direct numeric
     * column takes that column's type and name, as PostgreSQL types such a
     * placeholder from the other operand, and a named placeholder keeps its
     * own name. The rule reads through nested arithmetic and through a pair of
     * parentheses around one expression, so {@code visits + :a + 1} and
     * {@code (score + :b) * 2} type their placeholders as the innermost
     * operator writes them.
     *
     * <p>A direct placeholder or cast operand of the clause itself is none of
     * these nodes, so the clause's own rules keep typing it. Every other
     * operand, among them a signed placeholder, a function argument, and a
     * placeholder written beside a computed expression, states no column, so it
     * keeps needing a cast.
     */
    void resolveArithmeticParameters(
        Expression expression,
        Scope scope,
        Placeholders placeholders
    ) {
        if (expression instanceof ParenthesedExpressionList<?> parentheses && parentheses.size() == 1) {
            resolveArithmeticParameters(parentheses.get(0), scope, placeholders);

            return;
        }

        if (!(expression instanceof BinaryExpression arithmetic) || !isArithmetic(arithmetic)) {
            return;
        }

        Expression left = arithmetic.getLeftExpression();
        Expression right = arithmetic.getRightExpression();

        resolveArithmeticOperand(left, right, scope, placeholders);
        resolveArithmeticOperand(right, left, scope, placeholders);

        resolveArithmeticParameters(left, scope, placeholders);
        resolveArithmeticParameters(right, scope, placeholders);
    }

    /**
     * Reports whether one binary expression is an arithmetic operator that
     * types a placeholder from the column it is written beside. {@code %} is
     * none of them, because the decision names exactly the four operators
     * PostgreSQL was probed with.
     */
    private boolean isArithmetic(BinaryExpression expression) {
        return expression instanceof Addition
            || expression instanceof Subtraction
            || expression instanceof Multiplication
            || expression instanceof Division;
    }

    /**
     * Types one operand of an arithmetic operator from the operand written
     * beside it. The column is resolved only where the operand is an uncast
     * placeholder, so arithmetic that binds nothing keeps reading its columns
     * where the clause's own rules read them, and a column of an unmapped, a
     * date, time, or interval, or an array type types nothing, which leaves the
     * placeholder to the cast that states its type.
     */
    private void resolveArithmeticOperand(
        Expression operand,
        Expression sibling,
        Scope scope,
        Placeholders placeholders
    ) {
        Placeholder placeholder = placeholders.of(operand);

        if (placeholder == null) {
            return;
        }

        net.sf.jsqlparser.schema.Column siblingColumn = analyzer.sources.directColumn(sibling);

        if (siblingColumn == null) {
            return;
        }

        org.sqlcj.schema.Column column = analyzer.sources.resolveColumn(siblingColumn, scope).column();

        if (
            column.array()
                || column.type() == null
                || !QueryAnalyzer.NUMERIC_WIDENING.contains(column.type())
        ) {
            return;
        }

        addParameter(placeholder, column, placeholders);
    }

    /**
     * Analyzes each operand of one comparison that is a subquery, as the level
     * inside the one the comparison stands in, and reports whether the
     * comparison compares one. No parameter rule of a comparison reads such an
     * operand, so a comparison with one types no parameter of its own and
     * leaves a placeholder beside it to the cast that states its type.
     */
    private boolean resolveComparisonSubqueries(
        Expression left,
        Expression right,
        Scope scope,
        Analysis analysis
    ) {
        boolean subquery = false;

        if (left instanceof ParenthesedSelect leftSubquery) {
            analyzer.analyzePredicateSubquery(leftSubquery, scope, analysis);

            subquery = true;
        }

        if (right instanceof ParenthesedSelect rightSubquery) {
            analyzer.analyzePredicateSubquery(rightSubquery, scope, analysis);

            subquery = true;
        }

        return subquery;
    }

    /**
     * Reports whether a comparison is one of the supported predicate
     * comparisons, whose operand is a value of the compared column's type and
     * therefore names its parameter after that column. Another operator the
     * parser reports as a comparison, such as the array overlap {@code &&},
     * compares something other than one column value, so a cast placeholder
     * there is named after itself.
     *
     * <p>{@code IS [NOT] DISTINCT FROM} compares one column value as
     * {@code =} does, apart from its reading of {@code NULL}, and each
     * regular-expression match compares one column value with a pattern of the
     * column's own type, so all five are column-typed comparisons.
     */
    private boolean isColumnTypedComparison(BinaryExpression comparison) {
        return comparison instanceof EqualsTo
            || comparison instanceof NotEqualsTo
            || comparison instanceof GreaterThan
            || comparison instanceof GreaterThanEquals
            || comparison instanceof MinorThan
            || comparison instanceof MinorThanEquals
            || comparison instanceof IsDistinctExpression
            || comparison instanceof RegExpMatchOperator;
    }

    /**
     * The argument of the {@code ANY(p)} operand whose parameter is the list a
     * compared column's value is looked up in, and {@code null} when the
     * expression is not that operand. The operand is an unquoted, unqualified,
     * case-insensitive {@code ANY} with no modifier and with exactly one
     * argument that is itself one parameter's value, which is a placeholder or
     * a cast of one.
     *
     * <p>Every other spelling, modifier, and argument, such as a quoted
     * {@code "ANY"}, {@code pg_catalog.any}, an array constructor, and a
     * literal, is another expression, which binds no list here.
     */
    private Expression anyListArgument(Expression expression, Placeholders placeholders) {
        if (!(expression instanceof Function function)) {
            return null;
        }

        List<String> name = function.getMultipartName();

        if (name == null || name.size() != 1 || !name.get(0).equalsIgnoreCase("any")) {
            return null;
        }

        if (function.isDistinct() || function.getOrderByElements() != null) {
            return null;
        }

        ExpressionList<?> arguments = function.getParameters();

        if (arguments == null || arguments.size() != 1) {
            return null;
        }

        Expression argument = arguments.get(0);

        return bindsValue(argument, placeholders)
            ? argument
            : null;
    }

    /**
     * Resolves the list parameter of {@code <column> = ANY(p)}, which is named
     * after the compared column and is an array of that column's type, so that
     * the generated method takes one list and the runtime binds it as one
     * server array. A null list and an empty list match no row, as PostgreSQL
     * compares a value with no element of either.
     *
     * <p>A cast argument states its own type, as a cast placeholder does
     * anywhere else, and keeps the column's name, so only the column is
     * resolved for it.
     */
    private void resolveAnyListParameter(
        Expression argument,
        net.sf.jsqlparser.schema.Column column,
        Scope scope,
        Placeholders placeholders
    ) {
        org.sqlcj.schema.Column schemaColumn = analyzer.sources.resolveColumn(column, scope).column();
        Placeholder placeholder = placeholders.of(argument);

        if (placeholder == null) {
            addCastParameter(
                placeholders.ofCast(argument),
                schemaColumn.name(),
                placeholders
            );
            return;
        }

        ColumnType type = analyzer.sources.requireSupportedType(schemaColumn);

        requireArrayElementColumn(schemaColumn);

        addParameter(
            placeholder,
            schemaColumn.name(),
            type,
            schemaColumn.enumType(),
            true,
            schemaColumn.blankPadded(),
            placeholders
        );
    }

    /**
     * Requires a column an array of whose values sqlcj binds, because the list
     * parameter of {@code = ANY} takes the compared column's type as its
     * element type. An array column's own values are not elements of its type,
     * and the element types the schema maps no array of have no array binding.
     */
    private void requireArrayElementColumn(org.sqlcj.schema.Column column) {
        if (column.array() || !analyzer.columnTypeMapping.isArrayElementType(column.type())) {
            throw new UnsupportedOperationException(
                ("An = ANY placeholder requires a non-array column of a type other than BYTEA, "
                    + "JSON, and JSONB, but %s is %s.")
                    .formatted(
                        column.name(),
                        column.array()
                            ? column.type() + "[]"
                            : column.type()
                    )
            );
        }
    }

    /**
     * Resolves one value of a clause that names its parameter after a column: a
     * placeholder typed from the column, a cast placeholder that states its own
     * type and keeps the column's name, or an expression that is neither, whose
     * own cast placeholders are typed where they stand. A {@code null} bound
     * there has no meaning of its own, as a compared value and an item of an
     * insert's {@code SELECT} are.
     */
    void resolveColumnValue(
        Expression value,
        org.sqlcj.schema.Column column,
        Placeholders placeholders
    ) {
        resolveColumnValue(value, column, placeholders, false);
    }

    /**
     * Resolves one value of a clause that names its parameter after a column,
     * stating whether a {@code null} bound there has a meaning, which it has
     * where the value is written into a nullable column and stores SQL
     * {@code NULL} in it.
     */
    void resolveColumnValue(
        Expression value,
        org.sqlcj.schema.Column column,
        Placeholders placeholders,
        boolean nullable
    ) {
        Placeholder placeholder = placeholders.of(value);

        if (placeholder != null) {
            addParameter(placeholder, column, placeholders, nullable);
            return;
        }

        CastPlaceholder cast = placeholders.ofCast(value);

        if (cast != null) {
            addCastParameter(cast, column.name(), placeholders, nullable);
        }
    }

    /**
     * Reports whether an expression is itself the value of one parameter, which
     * is a placeholder or a cast of one.
     */
    private boolean bindsValue(Expression value, Placeholders placeholders) {
        return placeholders.of(value) != null || placeholders.ofCast(value) != null;
    }

    /**
     * The name an indexed placeholder takes where no column of the query names
     * it, which is the placeholder's own index. A named placeholder states its
     * name itself, so this name is used only for an indexed one.
     */
    private String indexedParameterName(Placeholder placeholder) {
        return INDEXED_PARAMETER_NAME_PREFIX + placeholder.index();
    }

    /**
     * Records one parameter occurrence a cast types in the clause that names
     * it, which names it after the column whose value it is.
     */
    private void addCastParameter(
        CastPlaceholder cast,
        String name,
        Placeholders placeholders
    ) {
        addCastParameter(cast, name, placeholders, false);
    }

    private void addCastParameter(
        CastPlaceholder cast,
        String name,
        Placeholders placeholders,
        boolean nullable
    ) {
        placeholders.record(cast.placeholder(), toCastParameter(cast, name, nullable));
    }

    /** One parameter occurrence a cast types, under the given name. */
    private QueryParameter toCastParameter(
        CastPlaceholder cast,
        String name,
        boolean nullable
    ) {
        ColumnTypeMapping.MappedType type = cast.type();

        return toParameter(
            cast.placeholder(),
            name,
            type.type(),
            type.enumType(),
            type.array(),
            type.blankPadded(),
            nullable
        );
    }

    private void addParameter(
        Placeholder placeholder,
        org.sqlcj.schema.Column column,
        Placeholders placeholders
    ) {
        addParameter(placeholder, column, placeholders, false);
    }

    private void addParameter(
        Placeholder placeholder,
        org.sqlcj.schema.Column column,
        Placeholders placeholders,
        boolean nullable
    ) {
        addParameter(
            placeholder,
            column.name(),
            analyzer.sources.requireSupportedType(column),
            column.enumType(),
            column.array(),
            column.blankPadded(),
            nullable,
            placeholders
        );
    }

    private void addParameter(
        Placeholder placeholder,
        String name,
        ColumnType type,
        Placeholders placeholders
    ) {
        addParameter(placeholder, name, type, null, false, false, placeholders);
    }

    /** Records one parameter occurrence a clause of the query typed. */
    private void addParameter(
        Placeholder placeholder,
        String name,
        ColumnType type,
        String enumType,
        boolean array,
        boolean blankPadded,
        Placeholders placeholders
    ) {
        addParameter(placeholder, name, type, enumType, array, blankPadded, false, placeholders);
    }

    /**
     * Records one parameter occurrence a clause of the query typed, stating
     * whether a {@code null} bound at it has a meaning of its own.
     */
    private void addParameter(
        Placeholder placeholder,
        String name,
        ColumnType type,
        String enumType,
        boolean array,
        boolean blankPadded,
        boolean nullable,
        Placeholders placeholders
    ) {
        placeholders.record(
            placeholder,
            toParameter(placeholder, name, type, enumType, array, blankPadded, nullable)
        );
    }

    /**
     * One parameter occurrence, named after its placeholder when the placeholder
     * is named, and otherwise after the column or clause it belongs to, and
     * carrying the parameter group a group placeholder names.
     */
    private QueryParameter toParameter(
        Placeholder placeholder,
        String name,
        ColumnType type,
        String enumType,
        boolean array,
        boolean blankPadded,
        boolean nullable
    ) {
        return new QueryParameter(
            placeholder.index(),
            placeholder.nameOr(name),
            type,
            enumType,
            array,
            blankPadded,
            nullable,
            placeholder.group()
        );
    }

    /**
     * Rejects the placeholder forms an analyzed query must not contain
     * anywhere in its SQL source, including a clause this analyzer does not
     * traverse: an anonymous placeholder, which has no parameter to bind, a
     * named placeholder the compiler did not replace, which the executable SQL
     * would otherwise keep, and indexed and named placeholders in one query,
     * whose parameter order would follow two rules at once.
     */
    void requireSupportedPlaceholders(ParsedSql parsedSql) {
        if (parsedSql.parameters().hasAnonymousParameter()) {
            throw new UnsupportedOperationException(QueryAnalyzer.ANONYMOUS_PARAMETER_REJECTION);
        }

        List<String> uncompiled = parsedSql.parameters().uncompiledPlaceholders();

        if (!uncompiled.isEmpty()) {
            throw new UnsupportedOperationException(
                QueryAnalyzer.NAMED_PLACEHOLDER_REJECTION.formatted(uncompiled.get(0))
            );
        }

        if (parsedSql.parameters().hasPositionalParameter() && !parsedSql.parameters().names().isEmpty()) {
            throw new UnsupportedOperationException(MIXED_PLACEHOLDER_REJECTION);
        }
    }

    /**
     * Rejects a parameter group placeholder in a {@code :batchexec} query,
     * whose method already takes one generated record per row, so a group would
     * be a record inside that record. The first group placeholder of the source
     * is named, with the plain placeholder to write instead.
     */
    void requireNoParameterGroupInBatch(Query query, ParsedSql parsedSql) {
        if (query.type() != QueryType.BATCH_EXEC) {
            return;
        }

        for (String name : parsedSql.parameters().names()) {
            int separator = name.indexOf(QueryAnalyzer.GROUP_SEPARATOR);

            if (separator >= 0) {
                throw new UnsupportedOperationException(
                    BATCH_PARAMETER_GROUP_REJECTION.formatted(
                        QueryAnalyzer.NAME_PREFIX + name,
                        QueryAnalyzer.NAME_PREFIX + name.substring(separator + QueryAnalyzer.GROUP_SEPARATOR.length())
                    )
                );
            }
        }
    }

    /**
     * Rejects the {@code params} option where sqlcj cannot honor it: on a query
     * that uses a parameter group, whose record would stand beside the one the
     * option asks for, naming the first group placeholder of the source with
     * the plain placeholder to write instead, and on a query that binds no
     * parameter, which has no parameter to make a record of.
     */
    void requireUsableParameterRecord(Query query, ParsedSql parsedSql) {
        if (!query.parameterRecord()) {
            return;
        }

        for (String name : parsedSql.parameters().names()) {
            int separator = name.indexOf(QueryAnalyzer.GROUP_SEPARATOR);

            if (separator >= 0) {
                throw new UnsupportedOperationException(
                    PARAMETER_RECORD_GROUP_REJECTION.formatted(
                        QueryAnalyzer.NAME_PREFIX + name,
                        QueryAnalyzer.NAME_PREFIX + name.substring(separator + QueryAnalyzer.GROUP_SEPARATOR.length())
                    )
                );
            }
        }

        if (parsedSql.parameters().indexes().isEmpty()) {
            throw new UnsupportedOperationException(
                PARAMETER_RECORD_WITHOUT_PARAMETER_REJECTION
            );
        }
    }

    /**
     * The compiled placeholders of one query, against which every analyzed
     * placeholder occurrence resolves to its logical parameter.
     */
    Placeholders toPlaceholders(ParsedSql parsedSql, Schema schema) {
        return new Placeholders(
            parsedSql.parameters().names(),
            schema,
            analyzer.columnTypeMapping
        );
    }
}
