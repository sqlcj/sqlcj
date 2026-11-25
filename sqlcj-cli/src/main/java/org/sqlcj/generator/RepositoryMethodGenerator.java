package org.sqlcj.generator;

import org.sqlcj.analysis.QueryModel;
import org.sqlcj.analysis.QueryParameter;
import org.sqlcj.schema.ColumnType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Generates the method of one query: its declaration and the null checks its
 * body runs first, the executor call it makes, the SQL text block that call
 * executes, the arguments it binds in the textual order of their placeholders,
 * and the type it returns. It reads the generator it belongs to for the helpers
 * the generated source shares and for the names of the package's generated
 * types.
 */
final class RepositoryMethodGenerator {

    /**
     * The runtime wrapper one value the database types from its own context is
     * bound through.
     */
    private static final String UNTYPED_TEXT = "org.sqlcj.runtime.UntypedText";

    private static final String OPTIONAL = "java.util.Optional";

    private static final String ARRAYS = "java.util.Arrays";

    private static final String COLLECTIONS = "java.util.Collections";

    private static final String OBJECT = "java.lang.Object";

    private static final String UNSUPPORTED_OPERATION_EXCEPTION = "java.lang.UnsupportedOperationException";

    /**
     * The indentation, inside the member a generated method is, of the executor
     * arguments of its call, which is where its SQL text block starts.
     */
    private static final String SQL_INDENT = " ".repeat(12);

    /**
     * The indentation one wrapped bound-argument list gives each of its
     * arguments, one call continuation past the line that opens the list.
     */
    private static final int ARGUMENT_INDENT = SQL_INDENT.length() + 8;

    /** The generator these generated sources belong to. */
    private final JavaCodeGenerator generator;

    RepositoryMethodGenerator(JavaCodeGenerator generator) {
        this.generator = generator;
    }

    /**
     * Writes the parameters one generated method takes, in order of first
     * occurrence: the value of each plain placeholder, typed and null-marked as
     * its own use states, and the record of each parameter group the query
     * uses, which the method always requires. A query with the {@code params}
     * option takes its own parameter record instead, which the method requires
     * as well.
     */
    private List<String> generateMethodParameters(
        QueryModel query,
        JavaNames.QueryNames names,
        NameScope scope
    ) {
        return names.methodParameters().stream()
            .map(methodParameter -> generateMethodParameter(query, names, methodParameter, scope))
            .toList();
    }

    private String generateMethodParameter(
        QueryModel query,
        JavaNames.QueryNames names,
        JavaNames.MethodParameter methodParameter,
        NameScope scope
    ) {
        if (query.parameterRecord()) {
            return names.paramsTypeName() + " " + methodParameter.name();
        }

        if (methodParameter.group() != null) {
            return methodParameter.group().typeName() + " " + methodParameter.name();
        }

        QueryParameter parameter = query.parameters().get(methodParameter.parameter());

        return generator.support.javaType(
            parameter.type(),
            parameter.enumType(),
            parameter.array(),
            parameter.nullable(),
            scope
        )
            + " "
            + methodParameter.name();
    }

    /**
     * The Java expression each logical parameter of one query is bound from, in
     * logical parameter order: the method parameter of a plain placeholder, and
     * the component of its group's record for a group field, read through the
     * method parameter that holds the record. A query with the {@code params}
     * option reads every one of them from its own parameter record, as a batch
     * row reads them.
     */
    private List<String> generateValueNames(QueryModel query, JavaNames.QueryNames names) {
        if (query.parameterRecord()) {
            return names.parameterNames().stream()
                .map(name -> JavaCodeGenerator.PARAMS_NAME + "." + name + "()")
                .toList();
        }

        Map<String, String> groupNames = new LinkedHashMap<>();

        for (JavaNames.MethodParameter methodParameter : names.methodParameters()) {
            if (methodParameter.group() != null) {
                groupNames.put(
                    methodParameter.group().groupName(),
                    methodParameter.name()
                );
            }
        }

        return IntStream.range(0, query.parameters().size())
            .mapToObj(index -> {
                QueryParameter parameter = query.parameters().get(index);
                String name = names.parameterNames().get(index);

                return parameter.group() == null
                    ? name
                    : groupNames.get(parameter.group()) + "." + name + "()";
            })
            .toList();
    }

    /**
     * Writes the declaration of one generated method together with the null
     * checks its body runs before anything else, separated from the statement
     * that follows by a blank line. A method whose every parameter is nullable
     * writes its declaration alone.
     */
    private String generateCheckedDeclaration(
        QueryModel query,
        String repositoryClassName,
        JavaNames.QueryNames names,
        NameScope scope
    ) {
        String declaration = generator.support.generateMethodDeclaration(
            "public %s %s".formatted(generateReturnType(query, names, scope), names.methodName()),
            generateMethodParameters(query, names, scope)
        );

        List<String> checks = generateParameterNullChecks(
            query,
            repositoryClassName,
            names,
            JavaCodeGenerator.BODY_INDENT,
            scope
        );

        return checks.isEmpty()
            ? declaration
            : declaration + "\n" + String.join("\n", checks) + "\n";
    }

    /**
     * Writes one null check per parameter of one generated method whose
     * argument must not be {@code null}, in the order the method takes them, so
     * that such an argument fails where the caller passes it rather than as an
     * empty result or a constraint violation. A nullable plain parameter is
     * checked nowhere, because the query gives its {@code null} a meaning,
     * while a group record and the query's own parameter record are always
     * checked; the components inside them are checked by the record itself.
     */
    private List<String> generateParameterNullChecks(
        QueryModel query,
        String repositoryClassName,
        JavaNames.QueryNames names,
        int indent,
        NameScope scope
    ) {
        return names.methodParameters().stream()
            .filter(
                methodParameter -> query.parameterRecord()
                    || methodParameter.group() != null
                    || !query.parameters().get(methodParameter.parameter()).nullable()
            )
            .map(
                methodParameter -> generateNullCheck(
                    methodParameter.name(),
                    query,
                    repositoryClassName,
                    indent,
                    scope
                )
            )
            .toList();
    }

    /**
     * Writes one null check, which names the parameter, the query, and the
     * repository, so that the failure says which argument of which call was
     * {@code null}.
     */
    private String generateNullCheck(
        String parameterName,
        QueryModel query,
        String repositoryClassName,
        int indent,
        NameScope scope
    ) {
        return generator.support.generateCheck(
            parameterName,
            generator.support.parameterNullMessage(parameterName, query.name(), repositoryClassName),
            indent,
            0,
            scope
        );
    }

    String generateMethod(
        QueryModel query,
        String repositoryClassName,
        JavaNames.QueryNames names,
        NameScope scope
    ) {
        return switch (query.type()) {
            case ONE, OPTIONAL, MANY -> generateQueryMethod(
                query,
                repositoryClassName,
                names,
                scope
            );
            case EXEC -> generateExecMethod(query, repositoryClassName, names, scope);
            case BATCH_EXEC -> generateBatchExecMethod(query, repositoryClassName, names, scope);
            case EXEC_RESULT, BATCH_MANY, BATCH_ONE -> generateUnsupportedMethod(
                query,
                names,
                scope
            );
        };
    }

    private String generateQueryMethod(
        QueryModel query,
        String repositoryClassName,
        JavaNames.QueryNames names,
        NameScope scope
    ) {
        return """
            %s
                return executor.%s(
                        %s,
                        %s,
                        %s,
                        %s,
                        %s
                );
            }
            """
            .formatted(
                generateCheckedDeclaration(query, repositoryClassName, names, scope),
                generateExecutorMethod(query),
                generator.support.generateStringLiteral(repositoryClassName),
                generator.support.generateStringLiteral(query.name()),
                generateSql(query, repositoryClassName, names.methodName()),
                generateParameterList(query, generateValueNames(query, names), "", ",", scope),
                names.rowMapperName()
            );
    }

    private String generateExecMethod(
        QueryModel query,
        String repositoryClassName,
        JavaNames.QueryNames names,
        NameScope scope
    ) {
        return """
            %s
                return executor.execute(
                        %s,
                        %s,
                        %s,
                        %s
                );
            }
            """
            .formatted(
                generateCheckedDeclaration(query, repositoryClassName, names, scope),
                generator.support.generateStringLiteral(repositoryClassName),
                generator.support.generateStringLiteral(query.name()),
                generateSql(query, repositoryClassName, names.methodName()),
                generateParameterList(query, generateValueNames(query, names), "", "", scope)
            );
    }

    /**
     * Generates the method of one batch query, which takes one list of its
     * parameter record and returns the count the driver reported for each of
     * its rows, in the order of the list.
     */
    private String generateBatchExecMethod(
        QueryModel query,
        String repositoryClassName,
        JavaNames.QueryNames names,
        NameScope scope
    ) {
        return """
            %s
                return executor.executeBatch(
                        %s,
                        %s,
                        %s,
                        %s
                );
            }
            """
            .formatted(
                generator.support.generateMethodDeclaration(
                    "public %s %s"
                        .formatted(generateReturnType(query, names, scope), names.methodName()),
                    List.of(
                        "%s<%s> %s"
                            .formatted(
                                scope.type(JavaCodeGenerator.LIST), names.paramsTypeName(), JavaCodeGenerator.BATCH_NAME
                            )
                    )
                ),
                generator.support.generateStringLiteral(repositoryClassName),
                generator.support.generateStringLiteral(query.name()),
                generateSql(query, repositoryClassName, names.methodName()),
                generateBatchArgumentLists(query, names, scope)
            );
    }

    /**
     * Renders the argument lists of one batch, one per row of the list the
     * method takes and in its order, each reading the components of that row.
     * A query that binds no parameter reads no component, so it rejects a null
     * row explicitly to fail before the executor is called.
     */
    private String generateBatchArgumentLists(
        QueryModel query,
        JavaNames.QueryNames names,
        NameScope scope
    ) {
        String prefix = "%s.stream()%s.map(%s -> "
            .formatted(
                JavaCodeGenerator.BATCH_NAME,
                query.bindingParameterIndexes().isEmpty()
                    ? ".map(%s::requireNonNull)".formatted(scope.type(JavaCodeGenerator.OBJECTS))
                    : "",
                JavaCodeGenerator.PARAMS_NAME
            );
        String suffix = ").toList()";

        return prefix
            + generateParameterList(
                query,
                names.parameterNames().stream()
                    .map(name -> JavaCodeGenerator.PARAMS_NAME + "." + name + "()")
                    .toList(),
                prefix,
                suffix,
                scope
            )
            + suffix;
    }

    private String generateUnsupportedMethod(
        QueryModel query,
        JavaNames.QueryNames names,
        NameScope scope
    ) {
        return """
            %s
                throw new %s("Not implemented");
            }
            """
            .formatted(
                generator.support.generateMethodDeclaration(
                    "public %s %s"
                        .formatted(generateReturnType(query, names, scope), names.methodName()),
                    generateMethodParameters(query, names, scope)
                ),
                scope.type(UNSUPPORTED_OPERATION_EXCEPTION)
            );
    }

    private String generateExecutorMethod(QueryModel query) {
        return switch (query.type()) {
            case ONE -> "queryOne";
            case OPTIONAL -> "queryOptional";
            case MANY -> "queryMany";
            default -> throw new IllegalStateException(
                "Query type does not produce a result: " + query.type()
            );
        };
    }

    /**
     * Renders the statement a generated method executes as a text block: a
     * block comment holding {@code sqlcj} and the repository class and method
     * names, as in {@code sqlcj AuthorRepository.getAuthor}, on a line of its
     * own, then the analyzed SQL, so the compiled string is that comment line,
     * a line break, and the analyzed SQL character for character. PostgreSQL
     * carries the comment into its log and {@code pg_stat_activity}, and a
     * {@code QueryExecutor} decorator reads it, so a running or logged
     * statement leads back to the generated method. Both names are Java
     * identifiers, so neither can end or nest the comment and neither needs
     * escaping.
     */
    private String generateSql(QueryModel query, String repositoryClassName, String methodName) {
        return "\"\"\"\n"
            + indentSql(
                "/* sqlcj %s.%s */\n".formatted(repositoryClassName, methodName)
                    + escapeTextBlock(query.executableSql())
            )
            + "\"\"\"";
    }

    /**
     * Indents the comment line and the escaped SQL of a generated method to the
     * column its opening delimiter and the other executor arguments start at,
     * writing a blank line empty. The line the closing delimiter ends is
     * indented with the others, including the empty line SQL ending in a line
     * terminator leaves, because the compiler reads that line's indentation as
     * well.
     *
     * <p>The comment line and the analyzed SQL never start with whitespace, so
     * the indentation the compiler strips as incidental is exactly this one and
     * the compiled string holds the comment line and the analyzed SQL character
     * for character.
     */
    private String indentSql(String sql) {
        String[] lines = sql.split("\n", -1);

        return IntStream.range(0, lines.length)
            .mapToObj(
                index -> lines[index].isEmpty() && index < lines.length - 1
                    ? ""
                    : SQL_INDENT + lines[index]
            )
            .collect(Collectors.joining("\n"));
    }

    /**
     * Escapes the executable SQL rendered inside the generated text block so
     * that the compiled string is exactly the analyzed SQL, including
     * backslashes, embedded text-block delimiters, and line-trailing
     * whitespace.
     */
    private String escapeTextBlock(String sql) {
        StringBuilder builder = new StringBuilder();
        int quotes = 0;

        for (int index = 0; index < sql.length(); index++) {
            char character = sql.charAt(index);
            boolean lineEnd = isLineEnd(sql, index);

            if (character == '"') {
                quotes++;

                if (quotes == 3 || index + 1 == sql.length()) {
                    builder.append("\\\"");
                    quotes = 0;
                    continue;
                }

                builder.append('"');
                continue;
            }

            quotes = 0;

            switch (character) {
                case '\\' -> builder.append("\\\\");
                case '\r' -> builder.append("\\r");
                case ' ' -> builder.append(lineEnd ? "\\s" : " ");
                case '\t' -> builder.append(lineEnd ? "\\t" : "\t");
                default -> builder.append(character);
            }
        }

        return builder.toString();
    }

    private boolean isLineEnd(String sql, int index) {
        if (index + 1 == sql.length()) {
            return true;
        }

        char next = sql.charAt(index + 1);

        return next == '\n' || next == '\r';
    }

    /**
     * Renders the executor arguments in the textual order of the JDBC
     * {@code ?} positions, reading each logical parameter from the Java
     * expression {@code valueNames} holds for it in logical order, which is the
     * method parameter of a single execution and a component of the parameter
     * record of a batched one.
     *
     * <p>A list of no binding position is built with
     * {@code Collections.emptyList}, of one with
     * {@code Collections.singletonList}, and of two or more with
     * {@code Arrays.asList}. Each of the three accepts a null
     * argument, unlike {@code List.of}, so a null parameter can be bound.
     *
     * <p>A list of two or more arguments states its element type rather than
     * leaving it to be inferred, so that the list's type follows from the
     * arguments' nullness alone and not from the Java types they happen to
     * have: {@code Object} when no argument can be null, and a nullable
     * {@code Object} when one can. Only a parameter bound as its own value can
     * be null, because every wrapper the generator writes around an argument
     * is itself non-null. An inferred element type is the least upper bound of
     * the argument types, which for several non-null arguments of different
     * types is an intersection type that NullAway 0.14.3 cannot annotate.
     *
     * <p>A list of at least one argument whose line would pass the limit writes
     * each argument on a line of its own, one call continuation past the line
     * that opens the list, with the closing parenthesis at that line's column.
     * {@code prefix} and {@code suffix} are the text the caller writes before
     * and after the list on that line, so the decision counts the whole line.
     * A single argument that alone passes the limit stays on its line.
     */
    private String generateParameterList(
        QueryModel query,
        List<String> valueNames,
        String prefix,
        String suffix,
        NameScope scope
    ) {
        if (query.bindingParameterIndexes().isEmpty()) {
            return "%s.emptyList()".formatted(scope.type(COLLECTIONS));
        }

        Map<Integer, String> argumentsByIndex = generateArgumentsByIndex(query, valueNames, scope);
        List<String> arguments = query.bindingParameterIndexes().stream()
            .map(argumentsByIndex::get)
            .toList();

        String factory = arguments.size() == 1
            ? "%s.singletonList".formatted(scope.type(COLLECTIONS))
            : "%s.<%s>asList"
                .formatted(
                    scope.type(ARRAYS),
                    bindsNull(query, valueNames, scope)
                        ? generator.support.nullable(OBJECT, scope)
                        : scope.type(OBJECT)
                );
        String oneLine = arguments.stream()
            .collect(Collectors.joining(", ", factory + "(", ")"));

        if (generator.support.fits(SQL_INDENT.length(), prefix + oneLine + suffix)) {
            return oneLine;
        }

        return factory
            + "(\n"
            + arguments.stream()
                .map(argument -> " ".repeat(ARGUMENT_INDENT) + argument)
                .collect(Collectors.joining(",\n"))
            + "\n"
            + SQL_INDENT
            + ")";
    }

    /**
     * Reports whether one bound-argument list can hold a null, which only a
     * nullable parameter the list binds as its own value can.
     */
    private boolean bindsNull(QueryModel query, List<String> valueNames, NameScope scope) {
        return IntStream.range(0, query.parameters().size())
            .anyMatch(index -> {
                QueryParameter parameter = query.parameters().get(index);
                String name = valueNames.get(index);

                return parameter.nullable()
                    && generateArgument(parameter, name, scope).equals(name);
            });
    }

    /**
     * Renders the executor argument of each logical parameter, keyed by the
     * placeholder index it is bound at, so that a repeated index renders the
     * same argument at each of its binding positions.
     */
    private Map<Integer, String> generateArgumentsByIndex(
        QueryModel query,
        List<String> valueNames,
        NameScope scope
    ) {
        Map<Integer, String> argumentsByIndex = new LinkedHashMap<>();

        for (int index = 0; index < query.parameters().size(); index++) {
            QueryParameter parameter = query.parameters().get(index);

            argumentsByIndex.put(
                parameter.index(),
                generateArgument(parameter, valueNames.get(index), scope)
            );
        }

        return argumentsByIndex;
    }

    /**
     * Renders one executor argument. A parameter of a type the database types
     * itself from text — {@code JSON}, {@code JSONB}, {@code citext}, and the
     * network address types — is wrapped in the runtime's {@code UntypedText}
     * so that the runtime binds its text without a declared SQL type and the
     * database types it from the context of its placeholder. An enum parameter
     * is wrapped the same way, around the label of the generated constant,
     * because PostgreSQL rejects a label bound as {@code varchar} where an enum
     * is expected. An {@code INTERVAL} parameter is wrapped the same way,
     * around the text its runtime value writes. An array parameter is wrapped
     * in the runtime's {@code SqlArray} with the element type's PostgreSQL
     * name, so that the runtime binds the list as a server array.
     */
    private String generateArgument(QueryParameter parameter, String name, NameScope scope) {
        if (parameter.array()) {
            return generateArrayArgument(parameter, name, scope);
        }

        if (parameter.type() == ColumnType.ENUM) {
            return generateUntypedTextArgument(parameter, name, "value", scope);
        }

        if (parameter.type() == ColumnType.INTERVAL) {
            return generateUntypedTextArgument(parameter, name, "format", scope);
        }

        if (generator.support.isUntypedText(parameter.type())) {
            return "new " + scope.type(UNTYPED_TEXT) + "(" + name + ")";
        }

        return name;
    }

    /**
     * Renders one executor argument the runtime binds as the text a method of
     * its Java value writes. A nullable parameter writes that text only for a
     * value, because its {@code null} is bound as a SQL {@code NULL}, while a
     * non-null one reads the value directly, so the generated body carries no
     * test a null checker reads as always false.
     */
    private String generateUntypedTextArgument(
        QueryParameter parameter,
        String name,
        String method,
        NameScope scope
    ) {
        if (!parameter.nullable()) {
            return "new %s(%s.%s())".formatted(scope.type(UNTYPED_TEXT), name, method);
        }

        return "new %s(%s == null ? null : %s.%s())"
            .formatted(scope.type(UNTYPED_TEXT), name, name, method);
    }

    /**
     * Renders one array executor argument, which carries the element type's
     * PostgreSQL name beside the list. An array of an enum carries the labels
     * of its constants instead of the constants themselves, because the runtime
     * binds the values the database stores, and an array of {@code INTERVAL}
     * carries the text of its values for the same reason.
     */
    private String generateArrayArgument(QueryParameter parameter, String name, NameScope scope) {
        if (parameter.type() == ColumnType.ENUM) {
            return "%s.of(%s, %s, %s::value)"
                .formatted(
                    scope.type(JavaCodeGenerator.SQL_ARRAY),
                    generator.support.generateStringLiteral(parameter.enumType()),
                    name,
                    scope.type(generator.enumTypeQualifiedName(parameter.enumType()))
                );
        }

        if (parameter.type() == ColumnType.INTERVAL) {
            return "%s.of(%s, %s, %s::format)"
                .formatted(
                    scope.type(JavaCodeGenerator.SQL_ARRAY),
                    generator.support.generateStringLiteral(elementTypeName(parameter)),
                    name,
                    scope.type(JavaCodeGenerator.SQL_INTERVAL)
                );
        }

        return "new %s(%s, %s)"
            .formatted(
                scope.type(JavaCodeGenerator.SQL_ARRAY),
                generator.support.generateStringLiteral(elementTypeName(parameter)),
                name
            );
    }

    /**
     * The PostgreSQL name of one array element type, which is the name
     * {@code Connection.createArrayOf} resolves the array type from. Only the
     * element types sqlcj maps an array of have one. A blank-padded character
     * element is named {@code bpchar} rather than {@code varchar}, because
     * PostgreSQL compares a {@code bpchar} array only with another one.
     */
    private String elementTypeName(QueryParameter parameter) {
        if (parameter.blankPadded() && parameter.type() == ColumnType.VARCHAR) {
            return "bpchar";
        }

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
            case CITEXT -> "citext";
            case INET -> "inet";
            case CIDR -> "cidr";
            case MACADDR -> "macaddr";
            case MACADDR8 -> "macaddr8";
            case INTERVAL -> "interval";
            default -> throw new IllegalStateException(
                "Column type has no array element type: " + parameter.type()
            );
        };
    }

    /**
     * The return type of one generated method. A query returning one complete
     * table row returns the package's row record, which another file declares
     * and this one therefore names through its scope; every other result type
     * is a record the repository declares itself.
     */
    private String generateReturnType(
        QueryModel query,
        JavaNames.QueryNames names,
        NameScope scope
    ) {
        String resultType;

        if (generator.support.returnsValue(query)) {
            resultType = generator.support.valueType(query, scope);
        } else if (generator.support.returnsSharedRow(query)) {
            resultType = scope.type(generator.rowTypeQualifiedName(names.resultTypeName()));
        } else {
            resultType = names.resultTypeName();
        }

        return switch (query.type()) {
            case ONE -> resultType;
            case OPTIONAL -> scope.type(OPTIONAL) + "<" + resultType + ">";
            case MANY -> scope.type(JavaCodeGenerator.LIST) + "<" + resultType + ">";
            case EXEC -> "int";
            case BATCH_EXEC -> "int[]";
            case EXEC_RESULT -> "void";
            case BATCH_MANY -> "void";
            case BATCH_ONE -> "void";
        };
    }
}
