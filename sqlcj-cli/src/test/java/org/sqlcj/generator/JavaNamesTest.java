package org.sqlcj.generator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.sqlcj.analysis.QueryColumn;
import org.sqlcj.analysis.QueryGroupModel;
import org.sqlcj.analysis.QueryModel;
import org.sqlcj.analysis.QueryParameter;
import org.sqlcj.parser.QueryType;
import org.sqlcj.schema.ColumnType;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JavaNamesTest {

    private static final String SQL = "SELECT 1";

    @ParameterizedTest
    @CsvSource(
        {
            "Author, AuthorRepository",
            "author_admin, AuthorAdminRepository"
        }
    )
    void shouldNameRepositoryAfterConfiguredGroupName(String groupName, String expectedClassName) {
        JavaNames names = names(groupName, "GetAuthor");

        assertEquals(expectedClassName, names.repositoryClassName());
    }

    @Test
    void shouldNameResultTypeAndRowMapperAfterQueryName() {
        JavaNames.QueryNames names = queryNames("GetUser");

        assertEquals("GetUserResult", names.resultTypeName());
        assertEquals("getUser", names.methodName());
        assertEquals("getUserRowMapper", names.rowMapperName());
    }

    @Test
    void shouldKeepKeywordMethodNameSafe() {
        JavaNames.QueryNames names = queryNames("Class");

        assertEquals("ClassResult", names.resultTypeName());
        assertEquals("class_", names.methodName());
    }

    @ParameterizedTest
    @CsvSource(
        {
            "authors, AuthorsResult",
            "get_author, GetAuthorResult",
            "GetAuthor, GetAuthorResult",
            "user_ID, UserIdResult",
            "URL, UrlResult",
            "Get-User, GetUserResult",
            "get user, GetUserResult",
            "'Get**User', GetUserResult",
            "'get user ', GetUserResult",
            "1st_query, _1stQueryResult",
            "1stQuery, _1stQueryResult",
            "default, DefaultResult",
            "int, IntResult",
            "'true', TrueResult"
        }
    )
    void shouldNameResultTypeInUpperCamelCase(String queryName, String expectedResultTypeName) {
        assertEquals(expectedResultTypeName, queryNames(queryName).resultTypeName());
    }

    @ParameterizedTest
    @CsvSource(
        {
            "get_author, getAuthor",
            "GetAuthor, getAuthor",
            "getAuthor, getAuthor",
            "HTTPStatus, httpStatus",
            "HTTP2Status, http2Status",
            "GetHTTPStatus, getHTTPStatus",
            "created_at, createdAt",
            "user_ID, userId",
            "1st_query, _1stQuery",
            "default, default_",
            "'true', true_"
        }
    )
    void shouldNameMethodInLowerCamelCase(String queryName, String expectedMethodName) {
        assertEquals(expectedMethodName, queryNames(queryName).methodName());
    }

    @ParameterizedTest
    @CsvSource(
        {
            "created_at, createdAt",
            "'user id', userId",
            "user_ID, userId",
            "ID, id",
            "URL, url",
            "HTTPStatus, httpStatus",
            "class, class_"
        }
    )
    void shouldNameResultComponentInLowerCamelCase(String columnName, String expectedComponentName) {
        JavaNames.QueryNames names = queryNames("ListUsers", List.of(), List.of(columnName));

        assertEquals(List.of(expectedComponentName), names.componentNames());
    }

    @ParameterizedTest
    @CsvSource(
        {
            "_",
            "'***'",
            "$"
        }
    )
    void shouldRejectQueryNameWithoutAWord(String queryName) {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> queryNames(queryName)
        );

        assertEquals(
            "SQL name '%s' has no letter or digit to generate a Java name from".formatted(queryName),
            exception.getMessage()
        );
    }

    @Test
    void shouldRejectColumnNameWithoutAWord() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> queryNames("ListUsers", List.of(), List.of("***"))
        );

        assertEquals(
            "SQL name '***' of query 'ListUsers' has no letter or digit to generate a Java name from",
            exception.getMessage()
        );
    }

    @ParameterizedTest
    @CsvSource(
        {
            "ToString, toString_",
            "HashCode, hashCode_",
            "GetClass, getClass_",
            "Wait, wait_"
        }
    )
    void shouldRenameMethodConflictingWithInheritedObjectMethod(String queryName, String expectedMethodName) {
        assertEquals(expectedMethodName, queryNames(queryName).methodName());
    }

    @Test
    void shouldRejectQueriesOfOneGroupThatGenerateTheSameMethod() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> names("Author", "get_author", "GetAuthor")
        );

        assertEquals(
            "Queries 'get_author' and 'GetAuthor' generate the same repository method 'getAuthor'",
            exception.getMessage()
        );
    }

    /**
     * The compiled class files of two nested result types that differ only by
     * case are one path on a case-insensitive filesystem.
     */
    @Test
    void shouldRejectQueriesOfOneGroupWhoseResultTypesDifferOnlyByCase() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> names("Author", "GetUser", "getuser")
        );

        assertEquals(
            "Queries 'GetUser' and 'getuser' generate result types that differ only by case: "
                + "GetUserResult and GetuserResult",
            exception.getMessage()
        );
    }

    @Test
    void shouldKeepTheSameQueryNameInTwoRepositoriesIndependent() {
        assertEquals(
            "getUser",
            names("Author", "GetUser").queries().get(0).methodName()
        );

        assertEquals(
            "getUser",
            names("Book", "GetUser").queries().get(0).methodName()
        );
    }

    @Test
    void shouldKeepSelectedColumnOrderOfResultComponents() {
        JavaNames.QueryNames names = queryNames("ListUsers", List.of(), List.of("id", "created_at"));

        assertEquals(List.of("id", "createdAt"), names.componentNames());
    }

    @Test
    void shouldNormalizeAndDisambiguateResultComponents() {
        JavaNames.QueryNames names = queryNames(
            "ListUsers",
            List.of(),
            List.of("user id", "user-id", "class", "hashCode")
        );

        assertEquals(
            List.of("userId1", "userId2", "class_", "hashCode1"),
            names.componentNames()
        );
    }

    @Test
    void shouldSkipUsedCandidateWhenDisambiguatingResultComponents() {
        JavaNames.QueryNames names = queryNames(
            "ListUsers",
            List.of(),
            List.of("id", "id", "id1")
        );

        assertEquals(List.of("id1", "id2", "id11"), names.componentNames());
    }

    @Test
    void shouldKeepExistingDuplicateParameterNaming() {
        JavaNames.QueryNames names = queryNames("FindUsers", List.of("id", "id"), List.of());

        assertEquals(List.of("id1", "id2"), names.parameterNames());
    }

    @Test
    void shouldResolveParameterNamesReservedByGeneratedCode() {
        JavaNames.QueryNames names = queryNames(
            "FindUsers",
            List.of("executor", "findUsersRowMapper", "name"),
            List.of()
        );

        assertEquals(
            List.of("executor1", "findUsersRowMapper1", "name"),
            names.parameterNames()
        );
    }

    /**
     * A parameter must not shadow the row mapper of another method of the same
     * repository either, because every mapper is a field of that repository.
     */
    @Test
    void shouldResolveParameterNameReservedByAnotherQueryRowMapper() {
        JavaNames names = JavaNames.of(
            new QueryGroupModel(
                "Users",
                List.of(
                    query("FindUsers", List.of("listUsersRowMapper"), List.of()),
                    query("ListUsers", List.of(), List.of("id"))
                )
            )
        );

        assertEquals(
            List.of("listUsersRowMapper1"),
            names.queries().get(0).parameterNames()
        );
    }

    @Test
    void shouldResolveParameterAndComponentNamesIndependently() {
        JavaNames.QueryNames names = queryNames(
            "FindUsers",
            List.of("id", "id"),
            List.of("id")
        );

        assertEquals(List.of("id1", "id2"), names.parameterNames());
        assertEquals(List.of("id"), names.componentNames());
    }

    /**
     * One parameter group is one record of the repository, named in upper camel
     * case after the group, whose components are the fields of the first query
     * that uses it, in that query's order of first occurrence.
     */
    @Test
    void shouldNameOneRecordPerParameterGroup() {
        JavaNames names = JavaNames.of(
            new QueryGroupModel(
                "Users",
                List.of(
                    parameterQuery(
                        "SearchUsers",
                        List.of(
                            groupParameter(1, "created at", "search_criteria"),
                            groupParameter(2, "size", "page"),
                            groupParameter(3, "offset", "page")
                        )
                    ),
                    parameterQuery(
                        "ListUsers",
                        List.of(
                            groupParameter(1, "offset", "page"),
                            groupParameter(2, "size", "page")
                        )
                    )
                )
            )
        );

        assertEquals(
            List.of("SearchCriteria", "Page"),
            names.groups().stream()
                .map(JavaNames.GroupNames::typeName)
                .toList()
        );

        assertEquals(List.of("createdAt"), names.groups().get(0).componentNames());
        assertEquals(List.of("size", "offset"), names.groups().get(1).componentNames());
    }

    /**
     * A component of a group record avoids the names its own builder takes
     * beside the inherited {@link Object} members, so a field named
     * {@code build} or {@code builder} is suffixed.
     */
    @Test
    void shouldResolveGroupComponentNamesReservedByItsBuilder() {
        JavaNames names = JavaNames.of(
            new QueryGroupModel(
                "Users",
                List.of(
                    parameterQuery(
                        "SearchUsers",
                        List.of(
                            groupParameter(1, "build", "page"),
                            groupParameter(2, "builder", "page"),
                            groupParameter(3, "hashCode", "page"),
                            groupParameter(4, "size", "page")
                        )
                    )
                )
            )
        );

        assertEquals(
            List.of("build1", "builder1", "hashCode1", "size"),
            names.groups().get(0).componentNames()
        );
    }

    /**
     * A method takes one parameter per plain placeholder and one per parameter
     * group, in order of first occurrence, where a group stands at its first
     * field, and every parameter of the query is named by its method parameter
     * or by the component of its group record.
     */
    @Test
    void shouldOrderMethodParametersByFirstOccurrence() {
        JavaNames.QueryNames names = JavaNames.of(
            new QueryGroupModel(
                "Users",
                List.of(
                    parameterQuery(
                        "SearchUsers",
                        List.of(
                            groupParameter(1, "name", "criteria"),
                            new QueryParameter(2, "id", ColumnType.BIGINT),
                            groupParameter(3, "size", "page"),
                            groupParameter(4, "status", "criteria")
                        )
                    )
                )
            )
        )
            .queries()
            .get(0);

        assertEquals(
            List.of("criteria", "id", "page"),
            names.methodParameters().stream()
                .map(JavaNames.MethodParameter::name)
                .toList()
        );

        assertEquals(
            Arrays.asList("Criteria", null, "Page"),
            names.methodParameters().stream()
                .map(
                    parameter -> parameter.group() == null
                        ? null
                        : parameter.group().typeName()
                )
                .toList()
        );

        assertEquals(List.of("name", "id", "size", "status"), names.parameterNames());
    }

    /**
     * A group record is rejected when its name is equal ignoring case to
     * another type of the repository, because the compiled class files of those
     * types are one path on a case-insensitive filesystem.
     */
    @Test
    void shouldRejectGroupRecordEqualIgnoringCaseToAnotherRepositoryType() {
        assertEquals(
            "Parameter group 'users_repository' generates the record UsersRepository, which is "
                + "equal ignoring case to the repository class UsersRepository; rename the group",
            groupCollision("users_repository")
        );

        assertEquals(
            "Parameter group 'search_users_result' generates the record SearchUsersResult, which "
                + "is equal ignoring case to the result record SearchUsersResult of query "
                + "'SearchUsers'; rename the group",
            groupCollision("search_users_result")
        );

        assertEquals(
            "Parameter group 'builder' generates the record Builder, which every group record's "
                + "builder is named; rename the group",
            groupCollision("builder")
        );
    }

    /** Two groups whose records are equal ignoring case are rejected too. */
    @Test
    void shouldRejectTwoGroupRecordsEqualIgnoringCase() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> JavaNames.of(
                new QueryGroupModel(
                    "Users",
                    List.of(
                        parameterQuery(
                            "SearchUsers",
                            List.of(
                                groupParameter(1, "size", "page"),
                                groupParameter(2, "size", "PAGE")
                            )
                        )
                    )
                )
            )
        );

        assertEquals(
            "Parameter group 'PAGE' generates the record Page, which is equal ignoring case to "
                + "the record Page of parameter group 'page'; rename the group",
            exception.getMessage()
        );
    }

    /**
     * A group record is rejected against the parameter record of a batch query
     * as well, which that query's own method takes a list of.
     */
    @Test
    void shouldRejectGroupRecordEqualIgnoringCaseToABatchParameterRecord() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> JavaNames.of(
                new QueryGroupModel(
                    "Users",
                    List.of(
                        new QueryModel(
                            "InsertUsers",
                            QueryType.BATCH_EXEC,
                            "users",
                            SQL,
                            List.of(1),
                            List.of(),
                            List.of(new QueryParameter(1, "name", ColumnType.VARCHAR)),
                            null
                        ),
                        parameterQuery(
                            "SearchUsers",
                            List.of(groupParameter(1, "size", "insert_users_params"))
                        )
                    )
                )
            )
        );

        assertEquals(
            "Parameter group 'insert_users_params' generates the record InsertUsersParams, which "
                + "is equal ignoring case to the parameter record InsertUsersParams of query "
                + "'InsertUsers'; rename the group",
            exception.getMessage()
        );
    }

    /**
     * A group record is rejected against the shared row records the repository
     * imports as well, which a nested record of that name would shadow inside
     * every member that reads the row.
     */
    @Test
    void shouldRejectGroupRecordEqualIgnoringCaseToARowRecord() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> JavaNames.of(
                new QueryGroupModel(
                    "Users",
                    List.of(
                        new QueryModel(
                            "GetUser",
                            QueryType.ONE,
                            "users",
                            SQL,
                            List.of(1),
                            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
                            List.of(groupParameter(1, "id", "USERS_ROW")),
                            "users"
                        )
                    )
                )
            )
        );

        assertEquals(
            "Parameter group 'USERS_ROW' generates the record UsersRow, which is equal ignoring "
                + "case to the row record UsersRow of table 'users'; rename the group",
            exception.getMessage()
        );
    }

    /**
     * A query with the {@code params} option takes one method parameter named
     * after the option, which holds its parameter record, while its own
     * parameters name that record's components, as a batch query's do.
     */
    @Test
    void shouldNameOneParameterRecordForAQueryWithTheParameterRecordOption() {
        JavaNames.QueryNames names = JavaNames.of(
            new QueryGroupModel(
                "Users",
                List.of(
                    parameterRecordQuery(
                        "SearchUsers",
                        List.of(
                            new QueryParameter(1, "created_at", ColumnType.BIGINT),
                            new QueryParameter(2, "id", ColumnType.BIGINT)
                        )
                    )
                )
            )
        )
            .queries()
            .get(0);

        assertEquals("SearchUsersParams", names.paramsTypeName());
        assertEquals(List.of("createdAt", "id"), names.parameterNames());

        assertEquals(
            List.of("params"),
            names.methodParameters().stream()
                .map(JavaNames.MethodParameter::name)
                .toList()
        );

        assertEquals(
            Arrays.asList((String) null),
            names.methodParameters().stream()
                .map(
                    parameter -> parameter.group() == null
                        ? null
                        : parameter.group().typeName()
                )
                .toList()
        );
    }

    /**
     * The parameters of a query that generates a parameter record are that
     * record's components, so a parameter named {@code build} or
     * {@code builder} takes the suffix a group field of those names takes,
     * whether the record is a batch query's or the option's.
     */
    @Test
    void shouldResolveParameterRecordComponentNamesReservedByItsBuilder() {
        List<QueryParameter> parameters = List.of(
            new QueryParameter(1, "build", ColumnType.BIGINT),
            new QueryParameter(2, "builder", ColumnType.BIGINT),
            new QueryParameter(3, "hash_code", ColumnType.BIGINT),
            new QueryParameter(4, "size", ColumnType.BIGINT)
        );

        assertEquals(
            List.of("build1", "builder1", "hashCode1", "size"),
            JavaNames.of(
                new QueryGroupModel(
                    "Users",
                    List.of(parameterRecordQuery("SearchUsers", parameters))
                )
            )
                .queries()
                .get(0)
                .parameterNames()
        );

        assertEquals(
            List.of("build1", "builder1", "hashCode1", "size"),
            JavaNames.of(
                new QueryGroupModel(
                    "Users",
                    List.of(
                        new QueryModel(
                            "InsertUsers",
                            QueryType.BATCH_EXEC,
                            "users",
                            SQL,
                            List.of(1, 2, 3, 4),
                            List.of(),
                            parameters,
                            null
                        )
                    )
                )
            )
                .queries()
                .get(0)
                .parameterNames()
        );
    }

    /**
     * A group record is rejected against the parameter record of a query with
     * the {@code params} option as well, which that query's own method takes.
     */
    @Test
    void shouldRejectGroupRecordEqualIgnoringCaseToAnOptionParameterRecord() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> JavaNames.of(
                new QueryGroupModel(
                    "Users",
                    List.of(
                        parameterRecordQuery(
                            "ListUsers",
                            List.of(new QueryParameter(1, "name", ColumnType.VARCHAR))
                        ),
                        parameterQuery(
                            "SearchUsers",
                            List.of(groupParameter(1, "size", "list_users_params"))
                        )
                    )
                )
            )
        );

        assertEquals(
            "Parameter group 'list_users_params' generates the record ListUsersParams, which "
                + "is equal ignoring case to the parameter record ListUsersParams of query "
                + "'ListUsers'; rename the group",
            exception.getMessage()
        );
    }

    /** One {@code :many} query that carries the {@code params} option. */
    private QueryModel parameterRecordQuery(String queryName, List<QueryParameter> parameters) {
        return new QueryModel(
            queryName,
            QueryType.MANY,
            "users",
            SQL,
            parameters.stream()
                .map(QueryParameter::index)
                .toList(),
            List.of(),
            parameters,
            null,
            List.of(),
            SQL,
            0,
            true
        );
    }

    /** The message of one rejected group record of a one-query repository. */
    private String groupCollision(String groupName) {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> JavaNames.of(
                new QueryGroupModel(
                    "Users",
                    List.of(
                        parameterQuery(
                            "SearchUsers",
                            List.of(groupParameter(1, "size", groupName))
                        )
                    )
                )
            )
        );

        return exception.getMessage();
    }

    private QueryParameter groupParameter(int index, String name, String group) {
        return new QueryParameter(
            index,
            name,
            ColumnType.BIGINT,
            null,
            false,
            false,
            false,
            group
        );
    }

    /** One {@code :many} query of the given parameters. */
    private QueryModel parameterQuery(String queryName, List<QueryParameter> parameters) {
        return new QueryModel(
            queryName,
            QueryType.MANY,
            "users",
            SQL,
            parameters.stream()
                .map(QueryParameter::index)
                .toList(),
            List.of(),
            parameters,
            null
        );
    }

    private JavaNames names(String groupName, String... queryNames) {
        return JavaNames.of(
            new QueryGroupModel(
                groupName,
                Arrays.stream(queryNames)
                    .map(queryName -> query(queryName, List.of(), List.of()))
                    .toList()
            )
        );
    }

    private JavaNames.QueryNames queryNames(String queryName) {
        return queryNames(queryName, List.of(), List.of());
    }

    private JavaNames.QueryNames queryNames(
        String queryName,
        List<String> parameterNames,
        List<String> columnNames
    ) {
        return JavaNames.of(
            new QueryGroupModel(
                "Users",
                List.of(query(queryName, parameterNames, columnNames))
            )
        )
            .queries()
            .get(0);
    }

    private QueryModel query(
        String queryName,
        List<String> parameterNames,
        List<String> columnNames
    ) {
        List<QueryParameter> parameters = IntStream.range(0, parameterNames.size())
            .mapToObj(
                index -> new QueryParameter(
                    index + 1,
                    parameterNames.get(index),
                    ColumnType.BIGINT
                )
            )
            .toList();

        List<QueryColumn> columns = columnNames.stream()
            .map(name -> new QueryColumn(name, ColumnType.BIGINT, false))
            .toList();

        return new QueryModel(
            queryName,
            QueryType.MANY,
            "users",
            SQL,
            parameters.stream()
                .map(QueryParameter::index)
                .toList(),
            columns,
            parameters,
            null
        );
    }
}
