package org.sqlcj.generator;

import org.sqlcj.analysis.QueryColumn;
import org.sqlcj.analysis.QueryModel;
import org.sqlcj.analysis.QueryParameter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Generates the records the package declares: the result and row records in
 * selected-column order, the parameter record of one query and the record of
 * one parameter group in logical parameter order, the null check of each of
 * their non-null components, and the builder those records nest. It reads the
 * generator it belongs to for the helpers the generated source shares and for
 * the names of the package's generated types.
 */
final class RecordGenerator {

    /**
     * The indentation a member of a parameter record carries inside the
     * repository, beside the indentation of its own lines, which is the one
     * level the record itself is written at.
     */
    private static final int RECORD_MEMBER_INDENT = JavaCodeGenerator.MEMBER_INDENT;

    /**
     * The indentation a member of a parameter record's builder carries inside
     * the repository, which is the record's level and the builder's own.
     */
    private static final int BUILDER_MEMBER_INDENT = JavaCodeGenerator.MEMBER_INDENT * 2;

    /** The generator these generated sources belong to. */
    private final JavaCodeGenerator generator;

    RecordGenerator(JavaCodeGenerator generator) {
        this.generator = generator;
    }

    /** Generates one result or row record in selected-column order. */
    String generateRecord(
        String typeName,
        List<QueryColumn> columns,
        List<String> componentNames,
        NameScope scope
    ) {
        return """
            public record %s(
            %s
            ) {
            }
            """
            .formatted(
                typeName,
                generateResultComponents(columns, componentNames, scope)
            );
    }

    /**
     * Generates the parameter record of one query that has one, in logical
     * parameter order, whose components are typed exactly as the method
     * parameters of a single execution of the same query are. A query that
     * binds no parameter generates a record without components, written on one
     * line so that the generated source carries no blank line of its own and
     * without a builder, which would name nothing.
     *
     * <p>A record with components is written in the shape every generated
     * parameter record has, so it checks each of its non-null components and
     * nests a builder, and its failures name the parameter, the query, and the
     * repository, as the generated method's own check does.
     */
    String generateParamsRecord(
        QueryModel query,
        String repositoryClassName,
        JavaNames.QueryNames names,
        NameScope scope
    ) {
        if (query.parameters().isEmpty()) {
            return """
                public record %s() {
                }
                """
                .formatted(names.paramsTypeName());
        }

        return generateParameterRecord(
            paramsRecord(query, repositoryClassName, names),
            scope
        );
    }

    /**
     * The record one query's parameters are passed as, whose failures name the
     * parameter, the query, and the repository, so that a query switched to the
     * {@code params} option fails for a missing value with the message its
     * positional method gave.
     */
    private ParameterRecord paramsRecord(
        QueryModel query,
        String repositoryClassName,
        JavaNames.QueryNames names
    ) {
        return new ParameterRecord(
            names.paramsTypeName(),
            query.parameters(),
            names.parameterNames(),
            name -> generator.support.parameterNullMessage(name, query.name(), repositoryClassName)
        );
    }

    /**
     * The record one parameter group is passed as, whose failures name the
     * field and the record.
     */
    ParameterRecord groupRecord(
        JavaNames.GroupNames group,
        String repositoryClassName
    ) {
        return new ParameterRecord(
            group.typeName(),
            group.fields(),
            group.componentNames(),
            name -> fieldNullMessage(group, repositoryClassName, name)
        );
    }

    /**
     * One generated record built from query parameters: the record of one
     * parameter group and the {@code <QueryName>Params} record of one query.
     *
     * @param typeName       the record the repository declares
     * @param fields         the analyzed parameter of each component, in
     *                       component order
     * @param componentNames the component of each of them, in that order
     * @param nullMessage    the failure a {@code null} component is rejected
     *                       with, by component name
     */
    private record ParameterRecord(
        String typeName,
        List<QueryParameter> fields,
        List<String> componentNames,
        Function<String, String> nullMessage
    ) {
    }

    /**
     * Generates the components of one record built from parameters, in the
     * order they are given, each typed as the method parameter of a single
     * execution of the same value is.
     */
    private String generateParameterComponents(
        List<QueryParameter> parameters,
        List<String> componentNames,
        NameScope scope
    ) {
        return IntStream.range(0, parameters.size())
            .mapToObj(index -> {
                QueryParameter parameter = parameters.get(index);

                return "%s %s"
                    .formatted(
                        generator.support.javaType(
                            parameter.type(),
                            parameter.enumType(),
                            parameter.array(),
                            parameter.nullable(),
                            scope
                        ),
                        componentNames.get(index)
                    )
                    .indent(4)
                    .stripTrailing();
            })
            .collect(Collectors.joining(",\n"));
    }

    /**
     * Generates one record built from query parameters, whose components are
     * its fields in the order they are given, each typed and null-marked as the
     * method parameter of a plain placeholder of that use is.
     *
     * <p>The record checks each of its non-null components wherever it is
     * constructed, so a missing value fails where the caller builds the record
     * rather than where a query binds it, and it nests the builder its static
     * {@code builder()} opens, whose setters carry the component names. A
     * record whose every component is nullable declares no constructor of its
     * own.
     */
    String generateParameterRecord(ParameterRecord record, NameScope scope) {
        List<String> members = List.of(
            generateParameterRecordConstructor(record, scope),
            generateBuilderMethod(),
            generateRecordBuilder(record, scope)
        );

        return """
            public record %s(
            %s
            ) {

            %s
            }
            """
            .formatted(
                record.typeName(),
                generateParameterComponents(record.fields(), record.componentNames(), scope),
                members.stream()
                    .filter(member -> !member.isBlank())
                    .map(generator.support::indent)
                    .collect(Collectors.joining("\n\n"))
            );
    }

    /**
     * Generates the compact constructor of one parameter record, which runs one
     * null check per non-null component in component order, and nothing at all
     * when the record has no non-null component.
     */
    private String generateParameterRecordConstructor(ParameterRecord record, NameScope scope) {
        List<String> checks = generateComponentNullChecks(
            record,
            RECORD_MEMBER_INDENT,
            scope
        );

        if (checks.isEmpty()) {
            return "";
        }

        return """
            public %s {
            %s
            }
            """
            .formatted(record.typeName(), String.join("\n", checks));
    }

    private String generateBuilderMethod() {
        return """
            public static %s %s() {
                return new %s();
            }
            """
            .formatted(
                JavaNames.BUILDER_TYPE_NAME,
                JavaNames.BUILDER_METHOD_NAME,
                JavaNames.BUILDER_TYPE_NAME
            );
    }

    /**
     * Generates the builder of one parameter record: one nullable field per
     * component, a private constructor the record's {@code builder()} calls,
     * one setter per component, named after it and taking the component's own
     * type, and a {@code build()} that constructs the record, checking each
     * non-null component so that a component left unset fails by name.
     */
    private String generateRecordBuilder(ParameterRecord record, NameScope scope) {
        List<String> members = new ArrayList<>();

        members.add(generateBuilderFields(record, scope));
        members.add(generateBuilderConstructor());

        for (int index = 0; index < record.fields().size(); index++) {
            members.add(generateBuilderSetter(record, index, scope));
        }

        members.add(generateBuildMethod(record, scope));

        return """
            public static final class %s {

            %s
            }
            """
            .formatted(
                JavaNames.BUILDER_TYPE_NAME,
                members.stream()
                    .map(generator.support::indent)
                    .collect(Collectors.joining("\n\n"))
            );
    }

    /**
     * Generates the fields of one parameter record's builder, which are
     * nullable whatever their component states, because a field is unset until
     * a setter writes it.
     */
    private String generateBuilderFields(ParameterRecord record, NameScope scope) {
        return IntStream.range(0, record.fields().size())
            .mapToObj(
                index -> "private %s %s;"
                    .formatted(
                        nullableFieldType(record.fields().get(index), scope),
                        record.componentNames().get(index)
                    )
            )
            .collect(Collectors.joining("\n"));
    }

    private String generateBuilderConstructor() {
        return """
            private %s() {
            }
            """
            .formatted(JavaNames.BUILDER_TYPE_NAME);
    }

    private String generateBuilderSetter(
        ParameterRecord record,
        int index,
        NameScope scope
    ) {
        QueryParameter field = record.fields().get(index);
        String name = record.componentNames().get(index);

        return """
            public %s %s(%s %s) {
                this.%s = %s;

                return this;
            }
            """
            .formatted(
                JavaNames.BUILDER_TYPE_NAME,
                name,
                generator.support.javaType(
                    field.type(),
                    field.enumType(),
                    field.array(),
                    field.nullable(),
                    scope
                ),
                name,
                name,
                name
            );
    }

    /**
     * Generates the {@code build()} of one parameter record's builder, which
     * constructs the record from its fields, checking each non-null component
     * where it is read so that the construction states the nullness the record
     * declares. A construction whose line would pass the limit writes its
     * arguments one per line, one call continuation past the line that opens
     * the call, the layout a wrapped bound-argument list already uses.
     */
    private String generateBuildMethod(ParameterRecord record, NameScope scope) {
        return """
            public %s %s() {
            %s
            }
            """
            .formatted(
                record.typeName(),
                JavaNames.BUILD_METHOD_NAME,
                generateConstruction(record, scope)
            );
    }

    /**
     * Writes the construction one {@code build()} returns, on one line when
     * that line fits the limit and otherwise with each argument on a line of
     * its own, one call continuation past the line that opens the call, with
     * the closing parenthesis at that line's column.
     */
    private String generateConstruction(ParameterRecord record, NameScope scope) {
        String margin = " ".repeat(JavaCodeGenerator.BODY_INDENT);
        String call = "return new %s(".formatted(record.typeName());
        String oneLine = IntStream.range(0, record.fields().size())
            .mapToObj(index -> generateComponentArgument(record, index, scope))
            .collect(Collectors.joining(", ", call, ");"));

        if (generator.support.fits(BUILDER_MEMBER_INDENT + JavaCodeGenerator.BODY_INDENT, oneLine)) {
            return margin + oneLine;
        }

        return margin + call + "\n"
            + IntStream.range(0, record.fields().size())
                .mapToObj(index -> generateWrappedComponentArgument(record, index, scope))
                .collect(Collectors.joining(",\n"))
            + "\n"
            + margin
            + ");";
    }

    /**
     * The argument one {@code build()} constructs its record from: the builder
     * field itself for a nullable component, and the checked field for a
     * non-null one, so that a component left unset fails by name.
     */
    private String generateComponentArgument(
        ParameterRecord record,
        int index,
        NameScope scope
    ) {
        String name = record.componentNames().get(index);

        return record.fields().get(index).nullable()
            ? name
            : "%s.requireNonNull(%s, %s)"
                .formatted(
                    scope.type(JavaCodeGenerator.OBJECTS),
                    name,
                    generator.support.generateStringLiteral(record.nullMessage().apply(name))
                );
    }

    /**
     * Writes one argument of a wrapped construction at its own column. A check
     * whose line would pass the limit writes its two arguments one per line,
     * one call continuation past the line that opens the check, with the
     * closing parenthesis at that line's column, the layout a wrapped null
     * check already uses. A field read as itself cannot be broken further.
     */
    private String generateWrappedComponentArgument(
        ParameterRecord record,
        int index,
        NameScope scope
    ) {
        int indent = JavaCodeGenerator.BODY_INDENT + JavaCodeGenerator.CONTINUATION_INDENT;
        String margin = " ".repeat(indent);
        String argument = generateComponentArgument(record, index, scope);

        if (
            record.fields().get(index).nullable()
                || generator.support.fits(BUILDER_MEMBER_INDENT + indent, argument + ",")
        ) {
            return margin + argument;
        }

        String name = record.componentNames().get(index);
        String literal = generator.support.generateStringLiteral(record.nullMessage().apply(name));
        String argumentMargin = " ".repeat(indent + JavaCodeGenerator.CONTINUATION_INDENT);

        return margin + "%s.requireNonNull(".formatted(scope.type(JavaCodeGenerator.OBJECTS)) + "\n"
            + argumentMargin + name + ",\n"
            + argumentMargin + literal + "\n"
            + margin + ")";
    }

    /**
     * The type one builder field carries, which is its component's type written
     * nullable, because a field the caller has not set is {@code null}.
     */
    private String nullableFieldType(QueryParameter field, NameScope scope) {
        return generator.support.javaType(field.type(), field.enumType(), field.array(), true, scope);
    }

    /**
     * Writes one null check per non-null component of one parameter record, in
     * component order and each indented inside the member it stands in, so that
     * a component left unset fails by the record's own failure.
     */
    private List<String> generateComponentNullChecks(
        ParameterRecord record,
        int enclosing,
        NameScope scope
    ) {
        return IntStream.range(0, record.fields().size())
            .filter(index -> !record.fields().get(index).nullable())
            .mapToObj(index -> record.componentNames().get(index))
            .map(
                name -> generator.support.generateCheck(
                    name,
                    record.nullMessage().apply(name),
                    JavaCodeGenerator.BODY_INDENT,
                    enclosing,
                    scope
                )
            )
            .toList();
    }

    /**
     * The failure of one non-null field of a group record, which names the
     * field and the record, so that the failure says which field of which
     * record of which repository was {@code null}.
     */
    private String fieldNullMessage(
        JavaNames.GroupNames group,
        String repositoryClassName,
        String componentName
    ) {
        return "Field '%s' of %s.%s must not be null"
            .formatted(componentName, repositoryClassName, group.typeName());
    }

    private String generateResultComponents(
        List<QueryColumn> columns,
        List<String> componentNames,
        NameScope scope
    ) {
        return IntStream.range(0, columns.size())
            .mapToObj(
                index -> generateResultComponent(
                    columns.get(index),
                    componentNames.get(index),
                    scope
                )
            )
            .collect(Collectors.joining(",\n"));
    }

    /**
     * Generates one record component, whose type is the package's row record
     * for a component holding one table's complete row.
     */
    private String generateResultComponent(QueryColumn column, String name, NameScope scope) {
        String type = column.row()
            ? rowComponentType(column, scope)
            : generator.support.javaType(column.type(), column.enumType(), column.array(), column.nullable(), scope);

        return "%s %s"
            .formatted(type, name)
            .indent(4)
            .stripTrailing();
    }

    /**
     * The type of one component holding another table's complete row, which is
     * that table's row record, nullable exactly when a join null-extends the
     * source the row stands for.
     */
    private String rowComponentType(QueryColumn column, NameScope scope) {
        String rowType = generator.rowTypeQualifiedName(JavaNames.rowTypeName(column.rowTable()));

        return column.nullable()
            ? generator.support.nullable(rowType, scope)
            : scope.type(rowType);
    }
}
