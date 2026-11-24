package org.sqlcj.analysis;

import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import org.sqlcj.schema.Schema;
import org.sqlcj.schema.parser.ColumnTypeMapping;
import org.sqlcj.schema.parser.SchemaNamespace;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The compiled placeholders of one query and the parameter occurrences its
 * clauses typed, each recorded against the placeholder expression it
 * resolves. The textual order of the occurrences is the order the parser
 * reported them in, not the order the clauses were analyzed in.
 */
final class Placeholders {

    /** The compiled placeholder names in logical parameter order. */
    private final List<String> names;

    /** The schema whose declared enum types a cast may name. */
    private final Schema schema;

    /** Resolves the type a cast states. */
    private final ColumnTypeMapping columnTypeMapping;

    /** The occurrences a clause typed, by their placeholder expression. */
    private final Map<Expression, QueryParameter> typedOccurrences = new IdentityHashMap<>();

    /**
     * The occurrences a clause typed only where no other clause and no cast
     * states a type for their parameter, by their placeholder expression,
     * which are the occurrences a {@code HAVING} comparison types from the
     * aggregate it compares.
     */
    private final Map<Expression, QueryParameter> deferredOccurrences = new IdentityHashMap<>();

    Placeholders(
        List<String> names,
        Schema schema,
        ColumnTypeMapping columnTypeMapping
    ) {
        this.names = names;
        this.schema = schema;
        this.columnTypeMapping = columnTypeMapping;
    }

    /**
     * Reports the placeholder an expression is, and {@code null} when the
     * expression is absent or binds none. A named placeholder the compiler
     * did not replace is rejected as the query spells it, which keeps every
     * accepted occurrence bound even though such a placeholder is normally
     * already rejected from the compiler's report.
     *
     * <p>The compiled name of a parameter group placeholder is its group
     * and its field separated by a {@code .}, so the parameter it binds is
     * named after the field and carries the group.
     */
    Placeholder of(Expression expression) {
        if (expression instanceof JdbcParameter parameter) {
            if (!parameter.isUseFixedIndex()) {
                throw new UnsupportedOperationException(QueryAnalyzer.ANONYMOUS_PARAMETER_REJECTION);
            }

            return new Placeholder(expression, parameter.getIndex(), null, null);
        }

        if (expression instanceof JdbcNamedParameter named) {
            int index = requireCompiledName(named);
            String name = named.getName();
            int separator = name.indexOf(QueryAnalyzer.GROUP_SEPARATOR);

            return separator < 0
                ? new Placeholder(expression, index, name, null)
                : new Placeholder(
                    expression,
                    index,
                    name.substring(separator + QueryAnalyzer.GROUP_SEPARATOR.length()),
                    name.substring(0, separator)
                );
        }

        return null;
    }

    /**
     * Reports the placeholder a cast types, which is the placeholder the
     * cast states as its direct operand, and {@code null} when the
     * expression is not such a cast. A cast that declares a row type
     * instead of a type name states no column type, so it types no
     * placeholder.
     *
     * <p>The cast states the parameter's type, so a type the schema's
     * column type mapping does not map is rejected here, where the
     * placeholder it would have typed is known.
     */
    CastPlaceholder ofCast(Expression expression) {
        if (!(expression instanceof CastExpression cast) || cast.getColDataType() == null) {
            return null;
        }

        Placeholder placeholder = of(cast.getLeftExpression());

        if (placeholder == null) {
            return null;
        }

        ColumnTypeMapping.MappedType type = columnTypeMapping.map(
            cast.getColDataType(),
            schema.enums(),
            schema.domains(),
            new SchemaNamespace(schema.namespace())
        );

        if (!type.mapped()) {
            throw new UnsupportedOperationException(
                "Placeholder %s has unsupported cast type %s"
                    .formatted(placeholder.spelled(), type.unsupportedType())
            );
        }

        return new CastPlaceholder(placeholder, type);
    }

    private int requireCompiledName(JdbcNamedParameter named) {
        int index = QueryAnalyzer.NAME_PREFIX.equals(named.getParameterCharacter())
            ? names.indexOf(named.getName())
            : -1;

        if (index < 0) {
            throw new UnsupportedOperationException(
                QueryAnalyzer.NAMED_PLACEHOLDER_REJECTION.formatted(
                    named.getParameterCharacter() + named.getName()
                )
            );
        }

        return index + 1;
    }

    /**
     * Records the parameter occurrence a clause of the query typed for one
     * placeholder. Each placeholder occurrence stands in one clause, so
     * typing one twice is a defect of this analyzer.
     */
    void record(Placeholder placeholder, QueryParameter occurrence) {
        if (typedOccurrences.put(placeholder.expression(), occurrence) != null) {
            throw new IllegalStateException(
                "Placeholder %s was typed twice.".formatted(placeholder.spelled())
            );
        }
    }

    /**
     * Records the parameter occurrence one {@code HAVING} comparison typed
     * from the aggregate it compares, which types its parameter only where
     * no other clause and no cast states a type for it. Each placeholder
     * occurrence stands in one comparison, so typing one twice is a defect
     * of this analyzer.
     */
    void recordDeferred(Placeholder placeholder, QueryParameter occurrence) {
        if (deferredOccurrences.put(placeholder.expression(), occurrence) != null) {
            throw new IllegalStateException(
                "Placeholder %s was typed twice in HAVING.".formatted(placeholder.spelled())
            );
        }
    }

    /**
     * Takes the occurrence a clause typed for one placeholder, and
     * {@code null} when no clause typed it.
     */
    QueryParameter typed(Expression placeholder) {
        return typedOccurrences.remove(placeholder);
    }

    /**
     * The occurrence a {@code HAVING} comparison typed for one placeholder,
     * and {@code null} when none did. A deferred occurrence is read where
     * no clause and no cast typed the parameter, so one the parameter does
     * not need is left unread.
     */
    QueryParameter deferred(Expression placeholder) {
        return deferredOccurrences.get(placeholder);
    }

    /**
     * Requires that every occurrence a clause typed was one of the
     * placeholder occurrences the parser reported, so that no analyzed
     * parameter is left out of the binding order.
     */
    void requireReportedOccurrences() {
        if (!typedOccurrences.isEmpty()) {
            throw new IllegalStateException(
                "A typed placeholder is not a reported placeholder occurrence."
            );
        }
    }
}
