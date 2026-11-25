package org.sqlcj.generator;

import org.sqlcj.analysis.QueryColumn;
import org.sqlcj.analysis.QueryModel;
import org.sqlcj.parser.QueryType;
import org.sqlcj.schema.ColumnType;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The helpers the generated-source classes of the package share: the shape of
 * one query's result and parameters, the Java type one column or parameter is
 * read and bound as, the column limit and the indentation measured against it,
 * and the string literal and the null check generated code carries. It reads
 * the generator it belongs to for the names of the package's generated types.
 */
final class GeneratorSupport {

    /**
     * The column a generated line may reach, which is the repository's own
     * formatter limit and an IDE's default right margin. It is fixed, because
     * sqlcj writes one output style, and it is read from the generated text
     * alone, so the output stays deterministic.
     */
    private static final int LINE_LIMIT = 120;

    /** The indentation one wrapped declaration gives each of its parameters. */
    private static final int PARAMETER_INDENT = 4;

    /** The generator these generated sources belong to. */
    private final JavaCodeGenerator generator;

    GeneratorSupport(JavaCodeGenerator generator) {
        this.generator = generator;
    }

    boolean hasResult(QueryModel query) {
        return query.type() == QueryType.ONE
            || query.type() == QueryType.OPTIONAL
            || query.type() == QueryType.MANY;
    }

    boolean isOptional(QueryModel query) {
        return query.type() == QueryType.OPTIONAL;
    }

    private boolean isExec(QueryModel query) {
        return query.type() == QueryType.EXEC;
    }

    boolean isBatchExec(QueryModel query) {
        return query.type() == QueryType.BATCH_EXEC;
    }

    /**
     * A query that generates a nested {@code Params} record of its parameters:
     * a {@code :batchexec} query, whose method takes one per row, and a query
     * whose header carries the {@code params} option, whose method takes one.
     */
    boolean hasParamsRecord(QueryModel query) {
        return isBatchExec(query) || query.parameterRecord();
    }

    /**
     * A parameter record with at least one component nests a builder; a record
     * of no component, which only a batch query that binds no parameter
     * generates, has no component for a builder to set.
     */
    boolean hasParamsBuilder(QueryModel query) {
        return hasParamsRecord(query) && !query.parameters().isEmpty();
    }

    /** A write query has no generated result record. */
    boolean hasResultType(QueryModel query) {
        return !isExec(query) && !isBatchExec(query);
    }

    /**
     * A query that returns one complete table row uses the package's row record
     * and the repository's row mapper instead of generating its own.
     */
    boolean returnsSharedRow(QueryModel query) {
        return query.rowTable() != null;
    }

    /**
     * A query whose result is exactly one plain column that cannot be NULL
     * returns that column's value, so it generates no result record of its own.
     * A plain column is one that holds no embedded row, an enum and an array
     * column included.
     */
    boolean returnsValue(QueryModel query) {
        if (!hasResult(query) || returnsSharedRow(query) || query.columns().size() != 1) {
            return false;
        }

        QueryColumn column = query.columns().get(0);

        return !column.row() && !column.nullable();
    }

    /**
     * The Java type of the value one value-returning query reads, which is
     * non-null, because only a column that cannot be NULL returns its value.
     */
    String valueType(QueryModel query, NameScope scope) {
        QueryColumn column = query.columns().get(0);

        return javaType(column.type(), column.enumType(), column.array(), false, scope);
    }

    /**
     * The fully qualified Java type one mapped column type is read and bound
     * as, which the file naming it resolves to the name it writes. A
     * {@code BYTEA} is a primitive array, which belongs to no package.
     */
    String javaTypeQualifiedName(ColumnType type) {
        return switch (type) {
            case INTEGER -> "java.lang.Integer";
            case BIGINT -> "java.lang.Long";
            case SMALLINT -> "java.lang.Short";
            case BOOLEAN -> "java.lang.Boolean";
            case VARCHAR, TEXT, JSON, JSONB, CITEXT, INET, CIDR, MACADDR, MACADDR8 -> JavaCodeGenerator.STRING;
            case DATE -> "java.time.LocalDate";
            case TIME -> "java.time.LocalTime";
            case TIMESTAMP -> "java.time.LocalDateTime";
            case TIMESTAMP_WITH_TIME_ZONE -> "java.time.OffsetDateTime";
            case DECIMAL -> "java.math.BigDecimal";
            case REAL -> "java.lang.Float";
            case DOUBLE_PRECISION -> "java.lang.Double";
            case UUID -> "java.util.UUID";
            case BYTEA -> "byte[]";
            case INTERVAL -> JavaCodeGenerator.SQL_INTERVAL;
            case ENUM -> throw new IllegalArgumentException(
                "An enum column has no mapped Java type; its generated enum type is named by the generator"
            );
        };
    }

    /**
     * Indents one member of a generated type, leaving a blank line inside it
     * blank rather than indented, so the generated source carries no trailing
     * whitespace. A line of a generated SQL text block never ends in a space or
     * a tab, because the escaping writes a line's last one as {@code \s} or
     * {@code \t}, so stripping each line leaves every generated string exact.
     */
    String indent(String text) {
        return text.indent(4)
            .lines()
            .map(String::stripTrailing)
            .collect(Collectors.joining("\n"))
            .stripTrailing();
    }

    /**
     * The Java type of one column or parameter, which is the generated enum of
     * its enum type for an enum and the mapped type of its column type
     * otherwise, each written by the name the file naming it resolves. An array
     * column or parameter is a {@code List} of that type, whose element is
     * always nullable, because a SQL array may hold a {@code NULL} element. The
     * type itself states its nullness when {@code nullable}.
     */
    String javaType(
        ColumnType type,
        String enumType,
        boolean array,
        boolean nullable,
        NameScope scope
    ) {
        String elementType = type == ColumnType.ENUM
            ? generator.enumTypeQualifiedName(enumType)
            : javaTypeQualifiedName(type);

        if (array) {
            return (nullable ? nullable(JavaCodeGenerator.LIST, scope) : scope.type(JavaCodeGenerator.LIST))
                + "<"
                + nullable(elementType, scope)
                + ">";
        }

        return nullable
            ? nullable(elementType, scope)
            : scope.type(elementType);
    }

    /**
     * Writes one type as a nullable one, with the annotation directly before
     * the simple-name segment it qualifies. An array type carries it before its
     * brackets, as in {@code byte @Nullable []}, which states that the array
     * itself may be null rather than its elements.
     */
    String nullable(String qualifiedName, NameScope scope) {
        return scope.annotated(qualifiedName, scope.annotation(JavaCodeGenerator.NULLABLE));
    }

    /**
     * Reports whether one generated line fits the limit, counted from the
     * file's margin with the indentation its type gives the member it belongs
     * to, the indentation of the line inside that member, and the line's own
     * trailing punctuation included.
     */
    boolean fits(int indent, String line) {
        return JavaCodeGenerator.MEMBER_INDENT + indent + line.length() <= LINE_LIMIT;
    }

    /**
     * Writes the declaration of one generated method, which stays on one line
     * when that line fits the limit and otherwise writes each parameter, with
     * its annotation, on a line of its own, one level past the declaration,
     * with the closing parenthesis and the opening brace on a line of their own
     * at the declaration's column, the layout a generated record already uses.
     * A declaration without parameters has nothing to wrap. A single parameter
     * that alone passes the limit stays on its line.
     */
    String generateMethodDeclaration(String signature, List<String> parameters) {
        String oneLine = parameters.stream()
            .collect(Collectors.joining(", ", signature + "(", ") {"));

        if (parameters.isEmpty() || fits(0, oneLine)) {
            return oneLine;
        }

        return signature
            + "(\n"
            + parameters.stream()
                .map(parameter -> " ".repeat(PARAMETER_INDENT) + parameter)
                .collect(Collectors.joining(",\n"))
            + "\n) {";
    }

    /**
     * The failure of one non-null parameter of a query, which names the
     * parameter, the query, and the repository, so that the failure says which
     * argument of which call was {@code null}. A component of the query's
     * parameter record carries the same failure, because the record holds the
     * arguments the positional method takes.
     */
    String parameterNullMessage(
        String parameterName,
        String queryName,
        String repositoryClassName
    ) {
        return "Parameter '%s' of query '%s' in %s must not be null"
            .formatted(parameterName, queryName, repositoryClassName);
    }

    /**
     * Writes one null check of the value {@code name} reads, carrying
     * {@code message} as its failure. A check that does not fit the limit
     * writes its two arguments one per line, one call continuation past the
     * line that opens the call, with the closing parenthesis at that line's
     * column, the layout a wrapped bound-argument list already uses.
     *
     * <p>{@code indent} is the indentation of the line inside the member it
     * stands in, and {@code enclosing} the indentation that member itself
     * carries inside the repository beyond one level, so that a check of a type
     * nested deeper is measured where it is written.
     */
    String generateCheck(
        String name,
        String message,
        int indent,
        int enclosing,
        NameScope scope
    ) {
        String margin = " ".repeat(indent);
        String literal = generateStringLiteral(message);
        String call = "%s.requireNonNull(".formatted(scope.type(JavaCodeGenerator.OBJECTS));
        String oneLine = call + name + ", " + literal + ");";

        if (fits(enclosing + indent, oneLine)) {
            return margin + oneLine;
        }

        String argumentMargin = " ".repeat(indent + JavaCodeGenerator.CONTINUATION_INDENT);

        return margin + call + "\n"
            + argumentMargin + name + ",\n"
            + argumentMargin + literal + "\n"
            + margin + ");";
    }

    /**
     * Renders the repository and query identity an executor call reports in its
     * failures as a Java string literal.
     */
    String generateStringLiteral(String value) {
        return "\""
            + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
            + "\"";
    }

    /**
     * Reports whether a type's Java text is bound and read as text the database
     * types itself rather than through the JDBC type of {@code String}.
     */
    boolean isUntypedText(ColumnType type) {
        return type == ColumnType.JSON
            || type == ColumnType.JSONB
            || type == ColumnType.CITEXT
            || type == ColumnType.INET
            || type == ColumnType.CIDR
            || type == ColumnType.MACADDR
            || type == ColumnType.MACADDR8;
    }
}
