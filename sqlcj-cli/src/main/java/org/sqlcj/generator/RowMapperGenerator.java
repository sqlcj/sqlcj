package org.sqlcj.generator;

import org.sqlcj.analysis.QueryColumn;
import org.sqlcj.analysis.QueryModel;
import org.sqlcj.schema.ColumnType;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Generates the row mappers one repository reads its results with: the mapper
 * of a whole record, the mapper of a single value, the inline construction of
 * an embedded row, and the read of each result column by its projection
 * position. It reads the generator it belongs to for the helpers the generated
 * source shares and for the names of the package's generated types.
 */
final class RowMapperGenerator {

    /**
     * The runtime class every read of a component or single-column value the
     * model states non-null goes through.
     */
    private static final String NOT_NULL_COLUMN = "org.sqlcj.runtime.NotNullColumn";

    /** The runtime interface every generated row mapper implements. */
    private static final String ROW_MAPPER = "org.sqlcj.runtime.RowMapper";

    /** The runtime class one embedded nullable row's absence is read with. */
    private static final String SQL_ROW = "org.sqlcj.runtime.SqlRow";

    /**
     * The indentation, inside the member a generated row mapper is, of the
     * constructor arguments it reads, one level past its {@code resultSet ->}
     * line.
     */
    private static final int MAPPING_INDENT = 16;

    /**
     * The indentation one wrapped value row mapper gives its mapping, one call
     * continuation past its field, as the whole-row mapper's lambda line is.
     */
    private static final int MAPPER_INDENT = 8;

    /** The generator these generated sources belong to. */
    private final JavaCodeGenerator generator;

    RowMapperGenerator(JavaCodeGenerator generator) {
        this.generator = generator;
    }

    /**
     * Generates the row mapper of one value-returning query, which reads the
     * single column of its result as the value the method returns. A mapper
     * whose line would pass the limit continues its mapping on the next line,
     * one call continuation past its field, as the whole-row mapper's lambda
     * line is.
     */
    String generateValueRowMapper(
        QueryModel query,
        String mapperName,
        NameScope scope
    ) {
        String field = "private static final %s<%s> %s ="
            .formatted(scope.type(ROW_MAPPER), generator.support.valueType(query, scope), mapperName);
        String mapping = "resultSet -> %s;"
            .formatted(generateResultMapping(query.columns().get(0), 1, scope));

        if (generator.support.fits(0, field + " " + mapping)) {
            return field + " " + mapping + "\n";
        }

        return field + "\n" + " ".repeat(MAPPER_INDENT) + mapping + "\n";
    }

    String generateRowMapper(
        String typeName,
        String mapperName,
        List<QueryColumn> columns,
        NameScope scope
    ) {
        return """
            private static final %s<%s> %s =
                    resultSet -> new %s(
            %s
                    );
            """
            .formatted(
                scope.type(ROW_MAPPER),
                typeName,
                mapperName,
                typeName,
                generateResultMappings(columns, scope)
            );
    }

    /**
     * Reads each result column by its one-based projection position so that
     * identically named columns from different sources stay distinct. A
     * component holding one table's complete row reads that table's columns at
     * the consecutive positions the wildcard selected them at, so the read
     * order stays the selected-column order.
     */
    private String generateResultMappings(List<QueryColumn> columns, NameScope scope) {
        List<String> mappings = new ArrayList<>(columns.size());
        int position = 1;

        for (QueryColumn column : columns) {
            if (column.row()) {
                mappings.add(indentMapping(generateRowMapping(column, position, scope)));
                position += column.rowColumns().size();

                continue;
            }

            mappings.add(indentMapping(generateResultMapping(column, position, scope)));
            position++;
        }

        return String.join(",\n", mappings);
    }

    /**
     * Indents one argument of a generated row mapper, keeping the relative
     * indentation of an embedded row's own lines.
     */
    private String indentMapping(String mapping) {
        return mapping
            .indent(MAPPING_INDENT)
            .stripTrailing();
    }

    /**
     * Builds one embedded row inline from the positions its own columns were
     * selected at, so that the result's mapper reads every column of the result
     * in one pass.
     *
     * <p>A nullable row, which a left-joined source contributes, is {@code null}
     * when every column of its own range reads SQL {@code NULL}, which the
     * runtime's {@code SqlRow} reports over that range alone.
     */
    private String generateRowMapping(QueryColumn column, int firstPosition, NameScope scope) {
        List<QueryColumn> columns = column.rowColumns();

        String components = IntStream.range(0, columns.size())
            .mapToObj(
                index -> generateResultMapping(columns.get(index), firstPosition + index, scope)
                    .indent(8)
                    .stripTrailing()
            )
            .collect(Collectors.joining(",\n"));

        String construction = "new %s(\n%s\n)"
            .formatted(
                scope.type(generator.rowTypeQualifiedName(JavaNames.rowTypeName(column.rowTable()))),
                components
            );

        if (!column.nullable()) {
            return construction;
        }

        return """
            %s.isNull(resultSet, %d, %d)
                ? null
                : %s"""
            .formatted(
                scope.type(SQL_ROW),
                firstPosition,
                firstPosition + columns.size() - 1,
                construction.indent(4).strip()
            );
    }

    /**
     * A {@code JSON}, {@code citext}, or network address column is read with
     * {@code getString}, because a driver reports it as a type of its own for
     * which {@code getObject(position, String.class)}
     * is not defined. An enum column reads its label the same way and resolves
     * it to the generated constant, and an {@code INTERVAL} column reads
     * PostgreSQL's text the same way and parses it; an array column reads its
     * elements through the runtime's {@code SqlArray}, which reads each element
     * as a scalar of the element type is read; every other column is read as
     * its mapped Java type.
     *
     * <p>A column the model states non-null is read through the runtime's
     * {@code NotNullColumn} instead, which reads the same value and fails where
     * the database returns SQL {@code NULL} all the same, so that the read
     * never contradicts the nullness the generated API declares.
     */
    private String generateResultMapping(QueryColumn column, int position, NameScope scope) {
        if (column.array()) {
            return generateArrayMapping(column, position, scope);
        }

        if (column.type() == ColumnType.ENUM) {
            String enumTypeName = scope.type(generator.enumTypeQualifiedName(column.enumType()));

            return column.nullable()
                ? "%s.fromValue(resultSet.getString(%d))".formatted(enumTypeName, position)
                : "%s.getString(resultSet, %d, %s::fromValue)"
                    .formatted(scope.type(NOT_NULL_COLUMN), position, enumTypeName);
        }

        if (generator.support.isUntypedText(column.type())) {
            return column.nullable()
                ? "resultSet.getString(%d)".formatted(position)
                : "%s.getString(resultSet, %d)"
                    .formatted(scope.type(NOT_NULL_COLUMN), position);
        }

        if (column.type() == ColumnType.INTERVAL) {
            return column.nullable()
                ? "%s.parse(resultSet.getString(%d))"
                    .formatted(scope.type(JavaCodeGenerator.SQL_INTERVAL), position)
                : "%s.getString(resultSet, %d, %s::parse)"
                    .formatted(
                        scope.type(NOT_NULL_COLUMN),
                        position,
                        scope.type(JavaCodeGenerator.SQL_INTERVAL)
                    );
        }

        String javaType = scope.type(generator.support.javaTypeQualifiedName(column.type()));

        return column.nullable()
            ? "resultSet.getObject(%d, %s.class)".formatted(position, javaType)
            : "%s.getObject(resultSet, %d, %s.class)"
                .formatted(scope.type(NOT_NULL_COLUMN), position, javaType);
    }

    /**
     * Reads one array column as a list of its element type. An array of an enum
     * reads each label and resolves it to the generated constant, and an array
     * of {@code INTERVAL} reads each element's text and parses it. An array
     * column the model states non-null reads the same elements through the
     * runtime's {@code NotNullColumn}, which fails where the column itself is
     * SQL {@code NULL}; a {@code NULL} element stays a {@code null} element
     * either way.
     */
    private String generateArrayMapping(QueryColumn column, int position, NameScope scope) {
        String reader = column.nullable()
            ? scope.type(JavaCodeGenerator.SQL_ARRAY)
            : scope.type(NOT_NULL_COLUMN);

        if (column.type() == ColumnType.ENUM) {
            return "%s.getList(resultSet, %d, %s.class, %s::fromValue)"
                .formatted(
                    reader,
                    position,
                    scope.type(JavaCodeGenerator.STRING),
                    scope.type(generator.enumTypeQualifiedName(column.enumType()))
                );
        }

        if (column.type() == ColumnType.INTERVAL) {
            return "%s.getList(resultSet, %d, %s.class, %s::parse)"
                .formatted(
                    reader, position, scope.type(JavaCodeGenerator.STRING), scope.type(JavaCodeGenerator.SQL_INTERVAL)
                );
        }

        return "%s.getList(resultSet, %d, %s.class)"
            .formatted(reader, position, scope.type(generator.support.javaTypeQualifiedName(column.type())));
    }
}
