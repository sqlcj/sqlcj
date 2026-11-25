package org.sqlcj.analysis;

import net.sf.jsqlparser.statement.select.ExceptOp;
import net.sf.jsqlparser.statement.select.IntersectOp;
import net.sf.jsqlparser.statement.select.MinusOp;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperation;
import net.sf.jsqlparser.statement.select.SetOperationList;
import org.sqlcj.schema.ColumnType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Resolves the set operations of a query and the result columns they
 * combine: it analyzes each branch as a query of its own, pairs their
 * columns by position, resolves the type and the nullability of each result
 * column, and states the rejection of a set operation outside the supported
 * subset. It reads the analyzer it belongs to for the facts the rest of one
 * query's analysis resolves.
 */
final class SetOperationResolver {

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

    /** The analysis whose query these set operations are resolved for. */
    private final QueryAnalyzer analyzer;

    SetOperationResolver(QueryAnalyzer analyzer) {
        this.analyzer = analyzer;
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
    AnalyzedSelect analyzeSetOperation(
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

        analyzer.placeholderResolver.resolvePaginationParameters(setOperation, analysis.placeholders());

        return new AnalyzedSelect(
            branches.get(0).table(),
            new ResolvedProjection(columns, analyzer.projectionResolver.toQueryColumns(columns)),
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
    AnalyzedSelect analyzeSetOperationBranch(
        Select branch,
        Analysis analysis,
        List<Source> commonTableExpressions,
        boolean namesResultColumns,
        int listedColumns,
        Scope enclosing
    ) {
        List<Select> selects = analyzer.parenthesisLevels(branch);

        List<Source> branchCommonTableExpressions = analyzer.analyzeCommonTableExpressions(
            selects,
            analysis,
            commonTableExpressions
        );

        Select enclosed = selects.get(selects.size() - 1);

        if (enclosed instanceof PlainSelect plainSelect) {
            return analyzer.analyzePlainSelect(
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

        throw new UnsupportedOperationException(QueryAnalyzer.UNSUPPORTED_STATEMENT_REJECTION);
    }

    /**
     * Requires every operator of one set operation to be an operator
     * PostgreSQL has, which {@code MINUS} is not, so the difference is written
     * as PostgreSQL spells it.
     */
    void requireSupportedSetOperators(SetOperationList setOperation) {
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
    List<org.sqlcj.schema.Column> resolveSetOperationColumns(
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
    void requireSameSetOperationColumnCounts(List<AnalyzedSelect> branches) {
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
    org.sqlcj.schema.Column resolveSetOperationColumnType(
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
            org.sqlcj.schema.Column numeric = widerResultType(QueryAnalyzer.NUMERIC_WIDENING, resolved, column);

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
    boolean isSameResultType(
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
    void requireResultColumnOrdering(
        SetOperationList setOperation,
        List<org.sqlcj.schema.Column> columns
    ) {
        List<OrderByElement> orderByElements = setOperation.getOrderByElements();

        if (orderByElements == null) {
            return;
        }

        for (OrderByElement orderByElement : orderByElements) {
            net.sf.jsqlparser.schema.Column column = analyzer.sources.directColumn(orderByElement.getExpression());

            if (column == null) {
                continue;
            }

            boolean named = analyzer.sources.qualifier(column) == null
                && columns.stream().anyMatch(
                    resultColumn -> resultColumn.name()
                        .equalsIgnoreCase(column.getUnquotedColumnName())
                );

            if (!named) {
                throw new UnsupportedOperationException(
                    SET_OPERATION_ORDER_BY_REJECTION.formatted(
                        analyzer.written(orderByElement),
                        columns.get(0).name()
                    )
                );
            }
        }
    }
}
