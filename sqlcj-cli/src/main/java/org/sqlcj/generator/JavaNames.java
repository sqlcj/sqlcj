package org.sqlcj.generator;

import org.sqlcj.analysis.QueryColumn;
import org.sqlcj.analysis.QueryGroupModel;
import org.sqlcj.analysis.QueryModel;
import org.sqlcj.analysis.QueryParameter;
import org.sqlcj.parser.QueryType;

import javax.lang.model.SourceVersion;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Resolves the Java identifiers of one generated repository.
 *
 * <p>Every generated name is derived from its SQL spelling — the configured
 * group name, a query name, a column name or projection alias, or a parameter's
 * column name — with one deterministic, locale-independent rule set: the SQL
 * name is split into words at every character that is not a letter or a digit,
 * a word written without a lower-case letter is lower-cased so that {@code ID}
 * becomes {@code Id}, and the words are joined as upper camel case for a type
 * and lower camel case for a method, record component, or parameter.
 *
 * <p>Names that collide inside the generated parameter list or result record are
 * disambiguated in their existing SQL order.
 *
 * <p>A query that returns one complete table row is named after that table
 * instead of after itself, so every such query of one group shares one row
 * record and one row mapper. A result that embeds one table's row holds that
 * same record as one component, named after the source's exposed name, and
 * reads it inside its own mapper rather than through a mapper field.
 *
 * <p>Two queries of one group that would generate the same method are rejected
 * instead of being disambiguated, because a repository method is a name the
 * application calls. Two queries whose nested result types differ only by case
 * are rejected for the same reason: their class files share one path on a
 * case-insensitive filesystem. Two tables whose row types are equal ignoring
 * case are rejected on the same grounds.
 *
 * <p>One parameter group is one record of the repository, named after the
 * group and defined by the first query that uses it. A group record takes part
 * in the same collision rules: it is rejected when its name is equal ignoring
 * case to the repository class, to a row record the repository imports, to
 * another query's result or parameter record, or to another group's record, and
 * when it would be named after the builder every group record nests.
 */
final class JavaNames {

    private static final SourceVersion SOURCE_VERSION = SourceVersion.RELEASE_17;

    private static final String REPOSITORY_SUFFIX = "Repository";

    private static final String RESULT_SUFFIX = "Result";

    private static final String PARAMS_SUFFIX = "Params";

    private static final String ROW_SUFFIX = "Row";

    private static final String MAPPER_SUFFIX = "Mapper";

    private static final String ROW_MAPPER_SUFFIX = ROW_SUFFIX + MAPPER_SUFFIX;

    /** The nested builder every parameter group's record declares. */
    static final String BUILDER_TYPE_NAME = "Builder";

    /** The static method of a group record that opens its builder. */
    static final String BUILDER_METHOD_NAME = "builder";

    /** The method of a group record's builder that builds the record. */
    static final String BUILD_METHOD_NAME = "build";

    /**
     * The one parameter the method of a query with the {@code params} option
     * takes, which holds that query's parameter record.
     */
    static final String PARAMETER_RECORD_NAME = "params";

    /**
     * Inherited {@link Object} method names that a generated method or record
     * component cannot use.
     */
    private static final Set<String> RESERVED_MEMBER_NAMES = Set.of(
        "clone",
        "finalize",
        "getClass",
        "hashCode",
        "notify",
        "notifyAll",
        "toString",
        "wait"
    );

    /**
     * The member names a component of a parameter group's record cannot use,
     * which are the inherited ones beside the two its own builder takes: a
     * component {@code builder} would clash with the static {@code builder()},
     * and a setter {@code build(value)} would read as {@code build()} itself.
     */
    private static final Set<String> RESERVED_GROUP_MEMBER_NAMES = reservedGroupMemberNames();

    /** Generator-owned name referenced by every generated method body. */
    private static final String EXECUTOR_NAME = "executor";

    private final String repositoryClassName;
    private final List<RowNames> rows;
    private final List<GroupNames> groups;
    private final List<QueryNames> queries;

    private JavaNames(
        String repositoryClassName,
        List<RowNames> rows,
        List<GroupNames> groups,
        List<QueryNames> queries
    ) {
        this.repositoryClassName = repositoryClassName;
        this.rows = rows;
        this.groups = groups;
        this.queries = queries;
    }

    static JavaNames of(QueryGroupModel group) {
        String repositoryClassName = upperCamelCase(group.name(), null) + REPOSITORY_SUFFIX;

        List<String> resultTypeNames = group.queries().stream()
            .map(query -> upperCamelCase(query.name(), null) + RESULT_SUFFIX)
            .toList();

        List<String> paramsTypeNames = group.queries().stream()
            .map(query -> upperCamelCase(query.name(), null) + PARAMS_SUFFIX)
            .toList();

        List<String> methodNames = group.queries().stream()
            .map(query -> methodName(query.name()))
            .toList();

        rejectDuplicateMethodNames(group, methodNames);
        rejectResultTypeNamesDifferingOnlyByCase(group, resultTypeNames);

        Map<String, RowNames> rowsByTable = resolveRows(group, methodNames);

        Map<String, GroupNames> groupsByName = resolveGroups(
            group,
            repositoryClassName,
            resultTypeNames,
            paramsTypeNames,
            rowsByTable.values()
        );

        Set<String> reservedParameterNames = reservedParameterNames(
            methodNames,
            rowsByTable.values()
        );

        List<QueryNames> queries = new ArrayList<>(group.queries().size());

        for (int index = 0; index < group.queries().size(); index++) {
            QueryModel query = group.queries().get(index);
            String methodName = methodNames.get(index);
            RowNames row = rowsByTable.get(query.rowTable());

            List<MethodParameter> methodParameters = resolveMethodParameters(
                query,
                groupsByName,
                reservedParameterNames(query, reservedParameterNames)
            );

            queries.add(
                new QueryNames(
                    row == null ? resultTypeNames.get(index) : row.typeName(),
                    paramsTypeNames.get(index),
                    methodName,
                    row == null ? methodName + ROW_MAPPER_SUFFIX : row.mapperName(),
                    resolveParameterNames(query, groupsByName, methodParameters),
                    resolveNames(
                        query.columns().stream()
                            .map(QueryColumn::name)
                            .toList(),
                        RESERVED_MEMBER_NAMES,
                        query.name()
                    ),
                    methodParameters(query, methodParameters)
                )
            );
        }

        return new JavaNames(
            repositoryClassName,
            List.copyOf(rowsByTable.values()),
            List.copyOf(groupsByName.values()),
            List.copyOf(queries)
        );
    }

    /**
     * The Java type generated for one enum type, which is the upper camel form
     * of its PostgreSQL name, as in {@code stage_setting} to
     * {@code StageSetting}.
     */
    static String enumTypeName(String enumName) {
        return upperCamelCase(enumName, null);
    }

    /**
     * The Java type generated for one table's shared row record, which is the
     * upper camel form of its table name followed by {@code Row}, as in
     * {@code tasks} to {@code TasksRow}.
     */
    static String rowTypeName(String tableName) {
        return upperCamelCase(tableName, null) + ROW_SUFFIX;
    }

    /**
     * The constants generated for the labels of one enum type, in label order.
     * A constant is the label's words, upper-cased and joined with {@code _},
     * so {@code in progress} and {@code in-progress} both become
     * {@code IN_PROGRESS}, and {@code InProgress}, which is one word, becomes
     * {@code INPROGRESS}.
     *
     * <p>A label with no letter and no digit generates no constant, and two
     * labels that generate one constant are rejected, because a generated
     * constant is a name the application reads.
     */
    static List<String> enumConstantNames(String enumName, List<String> labels) {
        Map<String, String> labelsByConstant = new LinkedHashMap<>();
        List<String> constants = new ArrayList<>(labels.size());

        for (String label : labels) {
            String constant = enumConstantName(enumName, label);
            String previous = labelsByConstant.putIfAbsent(constant, label);

            if (previous != null) {
                throw new IllegalArgumentException(
                    "Labels '%s' and '%s' of enum type '%s' generate the same constant %s"
                        .formatted(previous, label, enumName, constant)
                );
            }

            constants.add(constant);
        }

        return List.copyOf(constants);
    }

    private static String enumConstantName(String enumName, String label) {
        List<String> words = splitWords(label);

        if (words.isEmpty()) {
            throw new IllegalArgumentException(
                "Label '%s' of enum type '%s' has no letter or digit to generate a Java constant from"
                    .formatted(label, enumName)
            );
        }

        return startIdentifier(
            words.stream()
                .map(word -> word.toUpperCase(Locale.ROOT))
                .collect(Collectors.joining("_"))
        );
    }

    String repositoryClassName() {
        return repositoryClassName;
    }

    /**
     * The shared row records of this repository, in the order the queries first
     * use them.
     */
    List<RowNames> rows() {
        return rows;
    }

    /**
     * The parameter group records of this repository, in the order the queries
     * first use them.
     */
    List<GroupNames> groups() {
        return groups;
    }

    List<QueryNames> queries() {
        return queries;
    }

    /**
     * The resolved Java identifiers of one generated repository method. A query
     * that returns one complete table row names the repository's shared row
     * record and row mapper instead of a record of its own.
     *
     * @param paramsTypeName  the nested record whose components are this
     *                        query's parameters, which a batch query takes one
     *                        list of and a query with the {@code params} option
     *                        takes one of
     * @param parameterNames  the Java name of each parameter of the query, in
     *                        logical parameter order, which is the method
     *                        parameter of a plain placeholder and the record
     *                        component of a group field
     * @param methodParameters
     *                        the parameters the generated method takes, in
     *                        order of first occurrence
     */
    record QueryNames(
        String resultTypeName,
        String paramsTypeName,
        String methodName,
        String rowMapperName,
        List<String> parameterNames,
        List<String> componentNames,
        List<MethodParameter> methodParameters
    ) {
    }

    /**
     * One parameter of a generated method: the value of one plain placeholder,
     * the record of one parameter group, or the query's own parameter record.
     *
     * @param name      the resolved Java name of the parameter
     * @param parameter the logical parameter it binds, as an index into the
     *                  query's parameters, and {@code -1} for a group and for
     *                  the query's parameter record, whose fields and
     *                  components are logical parameters of their own
     * @param group     the group record it holds, and {@code null} for a plain
     *                  parameter and for the query's parameter record
     */
    record MethodParameter(String name, int parameter, GroupNames group) {
    }

    /**
     * The resolved Java identifiers of one parameter group's record, which the
     * first query of the repository that uses the group defines.
     *
     * @param groupName      the group as the query source spells it
     * @param typeName       the record the repository declares for the group
     * @param fields         the analyzed parameter of each field, in the
     *                       defining query's order of first occurrence
     * @param componentNames the record component of each field, in that order
     */
    record GroupNames(
        String groupName,
        String typeName,
        List<QueryParameter> fields,
        List<String> componentNames
    ) {

        /** The component one field of this group is read from. */
        String componentName(String field) {
            for (int index = 0; index < fields.size(); index++) {
                if (fields.get(index).name().equals(field)) {
                    return componentNames.get(index);
                }
            }

            throw new IllegalStateException(
                "Parameter group '%s' has no field '%s'.".formatted(groupName, field)
            );
        }
    }

    /**
     * The resolved Java identifiers of one shared row record.
     *
     * @param tableName     the schema's declared name of the table the row
     *                      belongs to
     * @param mapperName    the row mapper field of this repository, and
     *                      {@code null} for a row no query of the group returns
     *                      whole, which is read inside another result's mapper
     * @param columns       the analyzed columns of the row, in schema order
     * @param returnedWhole whether one query of the group returns this row as
     *                      its whole result
     */
    record RowNames(
        String tableName,
        String typeName,
        String mapperName,
        List<QueryColumn> columns,
        List<String> componentNames,
        boolean returnedWhole
    ) {
    }

    /**
     * Resolves one record per parameter group the queries of one repository
     * use, keyed by the group as the SQL spells it and ordered by first use,
     * which is query order and then parameter order. Each record's components
     * are the fields of the first query that uses the group, in that query's
     * order of first occurrence.
     */
    private static Map<String, GroupNames> resolveGroups(
        QueryGroupModel group,
        String repositoryClassName,
        List<String> resultTypeNames,
        List<String> paramsTypeNames,
        Collection<RowNames> rows
    ) {
        Map<String, GroupNames> groups = new LinkedHashMap<>();
        Map<String, String> typesByPortabilityKey = repositoryTypes(
            group,
            repositoryClassName,
            resultTypeNames,
            paramsTypeNames,
            rows
        );

        for (QueryModel query : group.queries()) {
            for (Map.Entry<String, List<QueryParameter>> use : groupFields(query).entrySet()) {
                if (groups.containsKey(use.getKey())) {
                    continue;
                }

                String groupName = use.getKey();
                String typeName = upperCamelCase(groupName, query.name());

                rejectGroupTypeName(typesByPortabilityKey, groupName, typeName);

                groups.put(
                    groupName,
                    new GroupNames(
                        groupName,
                        typeName,
                        use.getValue(),
                        resolveNames(
                            use.getValue().stream()
                                .map(QueryParameter::name)
                                .toList(),
                            RESERVED_GROUP_MEMBER_NAMES,
                            query.name()
                        )
                    )
                );
            }
        }

        return groups;
    }

    /**
     * The fields of each parameter group one query uses, keyed by the group,
     * with both the groups and the fields of each group in the query's order of
     * first occurrence. A field the query writes twice is one logical
     * parameter, so it stands once.
     */
    private static Map<String, List<QueryParameter>> groupFields(QueryModel query) {
        Map<String, List<QueryParameter>> fields = new LinkedHashMap<>();

        for (QueryParameter parameter : query.parameters()) {
            if (parameter.group() != null) {
                fields
                    .computeIfAbsent(parameter.group(), group -> new ArrayList<>())
                    .add(parameter);
            }
        }

        return fields;
    }

    /**
     * The types one repository names beside its group records, each described
     * for a collision diagnostic and keyed by the name a case-insensitive
     * filesystem compares its class file by. The shared row records are named
     * too, because the repository imports them, and a nested record of that
     * name would shadow the import inside every member that reads the row.
     */
    private static Map<String, String> repositoryTypes(
        QueryGroupModel group,
        String repositoryClassName,
        List<String> resultTypeNames,
        List<String> paramsTypeNames,
        Collection<RowNames> rows
    ) {
        Map<String, String> types = new LinkedHashMap<>();

        types.put(
            repositoryClassName.toLowerCase(Locale.ROOT),
            "the repository class " + repositoryClassName
        );

        for (RowNames row : rows) {
            types.putIfAbsent(
                row.typeName().toLowerCase(Locale.ROOT),
                "the row record %s of table '%s'".formatted(row.typeName(), row.tableName())
            );
        }

        for (int index = 0; index < group.queries().size(); index++) {
            QueryModel query = group.queries().get(index);

            types.putIfAbsent(
                resultTypeNames.get(index).toLowerCase(Locale.ROOT),
                "the result record %s of query '%s'"
                    .formatted(resultTypeNames.get(index), query.name())
            );

            if (hasParamsRecord(query)) {
                types.putIfAbsent(
                    paramsTypeNames.get(index).toLowerCase(Locale.ROOT),
                    "the parameter record %s of query '%s'"
                        .formatted(paramsTypeNames.get(index), query.name())
                );
            }
        }

        return types;
    }

    /**
     * Rejects a group record the repository cannot declare: one whose name is
     * equal ignoring case to another type the repository names, because the
     * compiled class files of two declared types are one path on a
     * case-insensitive filesystem and a record of an imported type's name
     * shadows that import, and one named after the builder every group record
     * nests, which a nested type cannot share its enclosing record's name with.
     */
    private static void rejectGroupTypeName(
        Map<String, String> typesByPortabilityKey,
        String groupName,
        String typeName
    ) {
        if (BUILDER_TYPE_NAME.equals(typeName)) {
            throw new IllegalArgumentException(
                ("Parameter group '%s' generates the record %s, "
                    + "which every group record's builder is named; rename the group")
                    .formatted(groupName, BUILDER_TYPE_NAME)
            );
        }

        String previous = typesByPortabilityKey.putIfAbsent(
            typeName.toLowerCase(Locale.ROOT),
            "the record %s of parameter group '%s'".formatted(typeName, groupName)
        );

        if (previous != null) {
            throw new IllegalArgumentException(
                ("Parameter group '%s' generates the record %s, "
                    + "which is equal ignoring case to %s; rename the group")
                    .formatted(groupName, typeName, previous)
            );
        }
    }

    /**
     * Resolves the parameters one generated method takes, in order of first
     * occurrence: one per plain placeholder, named after it, and one per
     * parameter group, named after the group and standing at its first field.
     */
    private static List<MethodParameter> resolveMethodParameters(
        QueryModel query,
        Map<String, GroupNames> groups,
        Set<String> reserved
    ) {
        List<String> sqlNames = new ArrayList<>();
        List<Integer> parameters = new ArrayList<>();
        List<GroupNames> parameterGroups = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();

        for (int index = 0; index < query.parameters().size(); index++) {
            QueryParameter parameter = query.parameters().get(index);

            if (parameter.group() == null) {
                sqlNames.add(parameter.name());
                parameters.add(index);
                parameterGroups.add(null);

                continue;
            }

            if (used.add(parameter.group())) {
                sqlNames.add(parameter.group());
                parameters.add(-1);
                parameterGroups.add(groups.get(parameter.group()));
            }
        }

        List<String> names = resolveNames(sqlNames, reserved, query.name());

        return IntStream.range(0, names.size())
            .mapToObj(
                index -> new MethodParameter(
                    names.get(index),
                    parameters.get(index),
                    parameterGroups.get(index)
                )
            )
            .toList();
    }

    /**
     * The parameters one generated method takes. A query with the
     * {@code params} option takes one parameter named after the option, which
     * holds its {@code Params} record, whatever its own placeholders are named,
     * while every other query takes the parameters its placeholders resolve to.
     */
    private static List<MethodParameter> methodParameters(
        QueryModel query,
        List<MethodParameter> resolved
    ) {
        return query.parameterRecord()
            ? List.of(new MethodParameter(PARAMETER_RECORD_NAME, -1, null))
            : resolved;
    }

    /**
     * The Java name of each parameter of one query, in logical parameter order:
     * the method parameter of a plain placeholder, and the component its group
     * record reads a group field from.
     */
    private static List<String> resolveParameterNames(
        QueryModel query,
        Map<String, GroupNames> groups,
        List<MethodParameter> methodParameters
    ) {
        Map<Integer, String> namesByParameter = new LinkedHashMap<>();

        for (MethodParameter methodParameter : methodParameters) {
            if (methodParameter.group() == null) {
                namesByParameter.put(methodParameter.parameter(), methodParameter.name());
            }
        }

        return IntStream.range(0, query.parameters().size())
            .mapToObj(index -> {
                QueryParameter parameter = query.parameters().get(index);

                return parameter.group() == null
                    ? namesByParameter.get(index)
                    : groups.get(parameter.group()).componentName(parameter.name());
            })
            .toList();
    }

    private static Set<String> reservedGroupMemberNames() {
        Set<String> reserved = new LinkedHashSet<>(RESERVED_MEMBER_NAMES);

        reserved.add(BUILD_METHOD_NAME);
        reserved.add(BUILDER_METHOD_NAME);

        return Set.copyOf(reserved);
    }

    /**
     * Resolves one row record per table whose complete row the group returns or
     * embeds, keyed by the schema table name and ordered by first use, which is
     * query order and then component order.
     */
    private static Map<String, RowNames> resolveRows(QueryGroupModel group, List<String> methodNames) {
        RowResolution resolution = new RowResolution(
            new LinkedHashMap<>(),
            new HashMap<>(),
            wholeRowTables(group),
            queryRowMapperNames(group, methodNames),
            new HashSet<>()
        );

        for (QueryModel query : group.queries()) {
            if (query.rowTable() != null) {
                resolution.add(query.rowTable(), query.columns(), query.name());

                continue;
            }

            for (QueryColumn column : query.columns()) {
                if (column.row()) {
                    resolution.add(column.rowTable(), column.rowColumns(), query.name());
                }
            }
        }

        return resolution.rowsByTable();
    }

    /** The tables whose complete row one query of the group returns. */
    private static Set<String> wholeRowTables(QueryGroupModel group) {
        return group.queries().stream()
            .map(QueryModel::rowTable)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * The state of resolving one group's row records: the rows resolved so far,
     * the row types recorded for portability, the tables the group returns
     * whole, and the row mapper names a mapper field yields to and has used.
     */
    private record RowResolution(
        Map<String, RowNames> rowsByTable,
        Map<String, GeneratedRowType> rowTypesByPortabilityKey,
        Set<String> wholeRowTables,
        Set<String> queryRowMapperNames,
        Set<String> usedRowMapperNames
    ) {

        /**
         * Resolves the row record of one table from its first use, which is
         * where its columns and its component names come from. A row no query
         * returns whole generates no mapper field, so it takes no mapper name.
         */
        private void add(String table, List<QueryColumn> columns, String queryName) {
            if (rowsByTable.containsKey(table)) {
                return;
            }

            String typeName = upperCamelCase(table, queryName) + ROW_SUFFIX;

            rejectRowTypeNameEqualIgnoringCase(rowTypesByPortabilityKey, table, typeName);

            boolean returnedWhole = wholeRowTables.contains(table);

            rowsByTable.put(
                table,
                new RowNames(
                    table,
                    typeName,
                    returnedWhole
                        ? resolveName(
                            decapitalize(typeName) + MAPPER_SUFFIX,
                            false,
                            queryRowMapperNames,
                            usedRowMapperNames
                        )
                        : null,
                    columns,
                    resolveNames(
                        columns.stream()
                            .map(QueryColumn::name)
                            .toList(),
                        RESERVED_MEMBER_NAMES,
                        queryName
                    ),
                    returnedWhole
                )
            );
        }
    }

    /**
     * Rejects two tables of one group whose row types are equal ignoring case,
     * because the compiled class files of those types are one path on a
     * case-insensitive filesystem and would overwrite each other.
     */
    private static void rejectRowTypeNameEqualIgnoringCase(
        Map<String, GeneratedRowType> rowTypesByPortabilityKey,
        String tableName,
        String typeName
    ) {
        GeneratedRowType previous = rowTypesByPortabilityKey.putIfAbsent(
            typeName.toLowerCase(Locale.ROOT),
            new GeneratedRowType(tableName, typeName)
        );

        if (previous != null) {
            throw new IllegalArgumentException(
                "Tables '%s' and '%s' generate row types that are equal ignoring case: %s and %s"
                    .formatted(
                        previous.tableName(),
                        tableName,
                        previous.typeName(),
                        typeName
                    )
            );
        }
    }

    /** One generated row type and the table that generated it. */
    private record GeneratedRowType(String tableName, String typeName) {
    }

    /** The row mapper names of the queries that still generate their own. */
    private static Set<String> queryRowMapperNames(QueryGroupModel group, List<String> methodNames) {
        Set<String> names = new LinkedHashSet<>();

        for (int index = 0; index < group.queries().size(); index++) {
            QueryModel query = group.queries().get(index);

            if (query.rowTable() == null && generatesRowMapper(query)) {
                names.add(methodNames.get(index) + ROW_MAPPER_SUFFIX);
            }
        }

        return names;
    }

    private static boolean generatesRowMapper(QueryModel query) {
        return query.type() == QueryType.ONE
            || query.type() == QueryType.OPTIONAL
            || query.type() == QueryType.MANY;
    }

    /**
     * Rejects two queries of one group that generate the same method, naming
     * both queries so the query source can be corrected.
     */
    private static void rejectDuplicateMethodNames(QueryGroupModel group, List<String> methodNames) {
        Map<String, String> queryNamesByMethodName = new HashMap<>();

        for (int index = 0; index < methodNames.size(); index++) {
            String methodName = methodNames.get(index);
            String queryName = group.queries().get(index).name();
            String previous = queryNamesByMethodName.putIfAbsent(methodName, queryName);

            if (previous != null) {
                throw new IllegalArgumentException(
                    "Queries '%s' and '%s' generate the same repository method '%s'"
                        .formatted(previous, queryName, methodName)
                );
            }
        }
    }

    /**
     * Rejects two queries of one group whose nested result types differ only by
     * case, because the compiled class files of those types are one path on a
     * case-insensitive filesystem and would overwrite each other.
     */
    private static void rejectResultTypeNamesDifferingOnlyByCase(
        QueryGroupModel group,
        List<String> resultTypeNames
    ) {
        Map<String, GeneratedResultType> resultTypesByPortabilityKey = new HashMap<>();

        for (int index = 0; index < resultTypeNames.size(); index++) {
            String resultTypeName = resultTypeNames.get(index);
            String queryName = group.queries().get(index).name();

            GeneratedResultType previous = resultTypesByPortabilityKey.putIfAbsent(
                resultTypeName.toLowerCase(Locale.ROOT),
                new GeneratedResultType(queryName, resultTypeName)
            );

            if (previous != null) {
                throw new IllegalArgumentException(
                    "Queries '%s' and '%s' generate result types that differ only by case: %s and %s"
                        .formatted(
                            previous.queryName(),
                            queryName,
                            previous.resultTypeName(),
                            resultTypeName
                        )
                );
            }
        }
    }

    /** One generated nested result type and the query that generated it. */
    private record GeneratedResultType(String queryName, String resultTypeName) {
    }

    /**
     * A method parameter must not shadow a generated name the method body
     * reads, which is the executor field and the repository's row mappers.
     */
    private static Set<String> reservedParameterNames(
        List<String> methodNames,
        Collection<RowNames> rows
    ) {
        Set<String> reserved = new LinkedHashSet<>();

        reserved.add(EXECUTOR_NAME);

        methodNames.stream()
            .map(methodName -> methodName + ROW_MAPPER_SUFFIX)
            .forEach(reserved::add);

        rows.stream()
            .map(RowNames::mapperName)
            .filter(Objects::nonNull)
            .forEach(reserved::add);

        return reserved;
    }

    /**
     * The names one query's parameters avoid. The parameters of a query that
     * generates a {@code Params} record are that record's components, so they
     * avoid the names a component of a record with a builder cannot use — the
     * inherited {@link Object} member names and the two the builder takes —
     * beside the generated names a method parameter avoids.
     */
    private static Set<String> reservedParameterNames(
        QueryModel query,
        Set<String> reservedParameterNames
    ) {
        if (!hasParamsRecord(query)) {
            return reservedParameterNames;
        }

        Set<String> reserved = new LinkedHashSet<>(reservedParameterNames);

        reserved.addAll(RESERVED_GROUP_MEMBER_NAMES);

        return reserved;
    }

    /**
     * Whether one query generates a {@code Params} record of its parameters: a
     * {@code :batchexec} query, which takes one per row, and a query whose
     * header carries the {@code params} option.
     */
    private static boolean hasParamsRecord(QueryModel query) {
        return query.type() == QueryType.BATCH_EXEC || query.parameterRecord();
    }

    private static String methodName(String queryName) {
        String name = lowerCamelCase(queryName, null);

        while (isKeyword(name) || RESERVED_MEMBER_NAMES.contains(name)) {
            name += "_";
        }

        return name;
    }

    /**
     * Resolves one generated namespace, keeping the declared order and using
     * stable {@code name1}, {@code name2}, ... suffixes for names that repeat
     * or are reserved.
     */
    private static List<String> resolveNames(
        List<String> sqlNames,
        Set<String> reserved,
        String queryName
    ) {
        List<String> bases = sqlNames.stream()
            .map(sqlName -> memberName(sqlName, queryName))
            .toList();

        Map<String, Long> occurrences = bases.stream()
            .collect(
                Collectors.groupingBy(
                    Function.identity(),
                    Collectors.counting()
                )
            );

        Set<String> used = new HashSet<>();
        List<String> names = new ArrayList<>(bases.size());

        for (String base : bases) {
            names.add(
                resolveName(
                    base,
                    occurrences.get(base) > 1,
                    reserved,
                    used
                )
            );
        }

        return List.copyOf(names);
    }

    private static String resolveName(
        String base,
        boolean repeated,
        Set<String> reserved,
        Set<String> used
    ) {
        if (!repeated && !reserved.contains(base) && !used.contains(base)) {
            used.add(base);
            return base;
        }

        int suffix = 1;
        String candidate = base + suffix;

        while (reserved.contains(candidate) || used.contains(candidate)) {
            suffix++;
            candidate = base + suffix;
        }

        used.add(candidate);

        return candidate;
    }

    /**
     * Names one record component or method parameter after its SQL name,
     * keeping a Java keyword or literal usable by suffixing {@code _}.
     */
    private static String memberName(String sqlName, String queryName) {
        String name = lowerCamelCase(sqlName, queryName);

        return isKeyword(name) ? name + "_" : name;
    }

    /**
     * Joins the words of a SQL name in upper camel case, as in
     * {@code get_author} to {@code GetAuthor} and {@code user_ID} to
     * {@code UserId}.
     */
    private static String upperCamelCase(String sqlName, String queryName) {
        StringBuilder builder = new StringBuilder();

        for (String word : words(sqlName, queryName)) {
            int first = word.codePointAt(0);

            builder
                .appendCodePoint(Character.toUpperCase(first))
                .append(word.substring(Character.charCount(first)));
        }

        return startIdentifier(builder.toString());
    }

    /**
     * Joins the words of a SQL name in lower camel case, as in
     * {@code created_at} to {@code createdAt} and {@code GetAuthor} to
     * {@code getAuthor}.
     */
    private static String lowerCamelCase(String sqlName, String queryName) {
        return decapitalize(upperCamelCase(sqlName, queryName));
    }

    /**
     * Lower-cases the leading upper-case run of an upper-camel-case name. A run
     * of two or more letters that is followed by a lower-case letter keeps its
     * last letter upper-case, because that letter starts the next word:
     * {@code HTTPStatus} becomes {@code httpStatus} while {@code HTTP2Status}
     * becomes {@code http2Status}.
     */
    private static String decapitalize(String name) {
        int end = 0;
        int letters = 0;

        while (end < name.length() && Character.isUpperCase(name.codePointAt(end))) {
            end += Character.charCount(name.codePointAt(end));
            letters++;
        }

        if (letters == 0) {
            return name;
        }

        boolean startsNextWord = letters > 1
            && end < name.length()
            && Character.isLowerCase(name.codePointAt(end));

        int lowerCaseEnd = startsNextWord
            ? name.offsetByCodePoints(end, -1)
            : end;

        return name.substring(0, lowerCaseEnd).toLowerCase(Locale.ROOT)
            + name.substring(lowerCaseEnd);
    }

    /**
     * Splits a SQL name into the words every generated name is built from. A
     * word ends at every character that is not a letter or a digit, and a word
     * written without a lower-case letter is lower-cased so that an acronym
     * such as {@code ID} or {@code URL} becomes one ordinary word.
     */
    private static List<String> words(String sqlName, String queryName) {
        List<String> words = splitWords(sqlName);

        if (words.isEmpty()) {
            throw new IllegalArgumentException(
                queryName == null
                    ? "SQL name '%s' has no letter or digit to generate a Java name from"
                        .formatted(sqlName)
                    : "SQL name '%s' of query '%s' has no letter or digit to generate a Java name from"
                        .formatted(sqlName, queryName)
            );
        }

        return words;
    }

    /**
     * Splits a SQL name into its words, which is empty for a name with no
     * letter and no digit at all.
     */
    private static List<String> splitWords(String sqlName) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        int index = 0;

        while (index < sqlName.length()) {
            int codePoint = sqlName.codePointAt(index);
            index += Character.charCount(codePoint);

            if (Character.isLetterOrDigit(codePoint)) {
                word.appendCodePoint(codePoint);
                continue;
            }

            addWord(words, word);
        }

        addWord(words, word);

        return words;
    }

    private static void addWord(List<String> words, StringBuilder word) {
        if (word.isEmpty()) {
            return;
        }

        String value = word.toString();

        words.add(
            value.codePoints().anyMatch(Character::isLowerCase)
                ? value
                : value.toLowerCase(Locale.ROOT)
        );

        word.setLength(0);
    }

    /** A name built from a digit-initial SQL name cannot start an identifier. */
    private static String startIdentifier(String name) {
        return Character.isDigit(name.codePointAt(0))
            ? "_" + name
            : name;
    }

    private static boolean isKeyword(String name) {
        return SourceVersion.isKeyword(name, SOURCE_VERSION);
    }
}
