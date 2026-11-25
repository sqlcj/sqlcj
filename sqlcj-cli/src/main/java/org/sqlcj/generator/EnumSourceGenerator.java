package org.sqlcj.generator;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Generates the Java enum one recorded enum type becomes: one constant per
 * label in label order, each carrying the label PostgreSQL stores, and the
 * reverse lookup a generated read resolves a stored label with. It reads the
 * generator it belongs to for the helpers the generated source shares.
 */
final class EnumSourceGenerator {

    /** The runtime failure one unmapped enum label is reported with. */
    private static final String UNMAPPABLE_VALUE_EXCEPTION = "org.sqlcj.runtime.UnmappableValueException";

    /** The generator these generated sources belong to. */
    private final JavaCodeGenerator generator;

    EnumSourceGenerator(JavaCodeGenerator generator) {
        this.generator = generator;
    }

    /**
     * Generates one Java enum, holding one constant per label in label order,
     * each carrying the label PostgreSQL stores. The constant list ends with
     * {@code ;} even when the enum type declares no label, so the generated
     * source compiles either way.
     */
    String generateEnum(SharedEnum sharedEnum, NameScope scope) {
        List<String> members = List.of(
            generateEnumConstants(sharedEnum),
            "private final %s value;".formatted(scope.type(JavaCodeGenerator.STRING)),
            generateEnumConstructor(sharedEnum, scope),
            generateEnumValueMethod(scope),
            generateFromValueMethod(sharedEnum, scope)
        );

        return """
            public enum %s {

            %s
            }
            """
            .formatted(
                sharedEnum.typeName(),
                members.stream()
                    .map(generator.support::indent)
                    .collect(Collectors.joining("\n\n"))
            );
    }

    private String generateEnumConstants(SharedEnum sharedEnum) {
        return IntStream.range(0, sharedEnum.labels().size())
            .mapToObj(
                index -> sharedEnum.constantNames().get(index)
                    + "("
                    + generator.support.generateStringLiteral(sharedEnum.labels().get(index))
                    + ")"
            )
            .collect(Collectors.joining(",\n"))
            + ";";
    }

    private String generateEnumConstructor(SharedEnum sharedEnum, NameScope scope) {
        return """
            %s(%s value) {
                this.value = value;
            }
            """
            .formatted(sharedEnum.typeName(), scope.type(JavaCodeGenerator.STRING));
    }

    private String generateEnumValueMethod(NameScope scope) {
        return """
            public %s value() {
                return value;
            }
            """
            .formatted(scope.type(JavaCodeGenerator.STRING));
    }

    /**
     * Generates the reverse lookup a generated read uses: {@code null} for a
     * SQL {@code NULL}, and a failure for a value the generated enum does not
     * hold, which means the database has a label the schema source does not
     * declare.
     */
    private String generateFromValueMethod(SharedEnum sharedEnum, NameScope scope) {
        return """
            %s
                if (value == null) {
                    return null;
                }

                for (%s constant : values()) {
                    if (constant.value.equals(value)) {
                        return constant;
                    }
                }

            %s
            }
            """
            .formatted(
                generator.support.generateMethodDeclaration(
                    "public static %s %s fromValue"
                        .formatted(scope.annotation(JavaCodeGenerator.NULLABLE), sharedEnum.typeName()),
                    List.of(
                        scope.annotated(JavaCodeGenerator.STRING, scope.annotation(JavaCodeGenerator.NULLABLE))
                            + " value"
                    )
                ),
                sharedEnum.typeName(),
                generateUnknownValueFailure(sharedEnum, JavaCodeGenerator.BODY_INDENT, scope)
            );
    }

    /**
     * Writes the failure of a value the generated enum does not hold, which
     * carries the PostgreSQL type and the value itself so that the runtime
     * reports both beside the query that read them. A statement that does not
     * fit the limit writes its arguments one per line, one call continuation
     * past the line that opens the call, with the closing parenthesis at that
     * line's column, the layout a wrapped bound-argument list already uses.
     */
    private String generateUnknownValueFailure(
        SharedEnum sharedEnum,
        int indent,
        NameScope scope
    ) {
        String margin = " ".repeat(indent);
        String call = "throw new %s(".formatted(scope.type(UNMAPPABLE_VALUE_EXCEPTION));
        String message = generator.support.generateStringLiteral(
            "Unknown value for enum type " + sharedEnum.enumName() + ": "
        ) + " + value";
        String type = generator.support.generateStringLiteral(sharedEnum.enumName());
        String oneLine = call + message + ", " + type + ", value, null);";

        if (generator.support.fits(indent, oneLine)) {
            return margin + oneLine;
        }

        String argumentMargin = " ".repeat(indent + JavaCodeGenerator.CONTINUATION_INDENT);

        return margin + call + "\n"
            + argumentMargin + message + ",\n"
            + argumentMargin + type + ",\n"
            + argumentMargin + "value,\n"
            + argumentMargin + "null\n"
            + margin + ");";
    }
}
