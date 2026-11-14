package org.sqlcj.generator;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.sqlcj.analysis.QueryColumn;
import org.sqlcj.analysis.QueryGroupModel;
import org.sqlcj.analysis.QueryModel;
import org.sqlcj.analysis.QueryParameter;
import org.sqlcj.parser.QueryType;
import org.sqlcj.runtime.GeneratedRepository;
import org.sqlcj.runtime.QueryExecutor;
import org.sqlcj.runtime.RowMapper;
import org.sqlcj.runtime.UnmappableValueException;
import org.sqlcj.schema.ColumnType;
import org.sqlcj.schema.EnumType;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaCodeGeneratorTest {

    private static final String SQL = "SELECT 1";

    private static final String GROUP = "Users";

    private final CodeGenerator codeGenerator = new JavaCodeGenerator();

    @TempDir
    Path tempDir;

    @Test
    void shouldGenerateFilePath() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false)
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        assertEquals(
            Path.of("generated", "repositories", "UsersRepository.java"),
            file.path()
        );
    }

    @Test
    void shouldGenerateClassName() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        assertTrue(file.content().contains("public final class UsersRepository"));
    }

    @Test
    void shouldGenerateMethodName() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        assertTrue(file.content().contains("getUser("));
    }

    @Test
    void shouldGenerateSingleParameter() {
        GeneratedFile file = generate(
            query(
                "GetUser",
                QueryType.ONE,
                List.of(
                    new QueryParameter(1, "id", ColumnType.BIGINT)
                )
            )
        );

        assertTrue(
            file.content().contains("Long id")
        );
    }

    @Test
    void shouldGenerateMultipleParameters() {
        GeneratedFile file = generate(
            query(
                "ListUsersByIdAndName",
                QueryType.MANY,
                List.of(
                    new QueryParameter(1, "id", ColumnType.BIGINT),
                    new QueryParameter(2, "name", ColumnType.VARCHAR)
                )
            )
        );

        assertTrue(
            file.content().contains(
                "listUsersByIdAndName(Long id, String name)"
            )
        );
    }

    @Test
    void shouldGenerateMethodWithoutParameters() {
        GeneratedFile file = generate(
            query(
                "ListUsers",
                QueryType.MANY,
                List.of()
            )
        );

        assertTrue(
            file.content().contains(
                "public List<ListUsersResult> listUsers()"
            )
        );
    }

    @Test
    void shouldGenerateJavaDoc() {
        GeneratedFile file = generate(
            query(
                "GetUser",
                QueryType.ONE,
                List.of(
                    new QueryParameter(1, "id", ColumnType.BIGINT)
                )
            )
        );

        String source = file.content();

        assertTrue(source.contains("Query: GetUser"));
        assertTrue(source.contains("Table: users"));
        assertTrue(source.contains("Type: ONE"));
    }

    /**
     * A query that reads no source carries no table, so its documentation
     * leaves out the table line and states its name and type alone.
     */
    @Test
    void shouldGenerateJavaDocWithoutATableForASourcelessQuery() throws IOException {
        QueryModel query = new QueryModel(
            "NextOrderNumber",
            QueryType.ONE,
            null,
            "SELECT nextval('order_number_seq')::bigint AS next",
            List.of(),
            List.of(new QueryColumn("next", ColumnType.BIGINT, true)),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(
            source.contains(
                """
                        /**
                         * Query: NextOrderNumber
                         * Type: ONE
                         */
                    """
                    .stripTrailing()
            )
        );

        assertFalse(source.contains("Table:"));

        assertCompiles(file);
    }

    @Test
    void shouldGeneratePackageDeclaration() {
        GeneratedFile file = generate(
            query(
                "GetUser",
                QueryType.ONE,
                List.of(
                    new QueryParameter(1, "id", ColumnType.BIGINT)
                )
            )
        );

        assertTrue(
            file.content().startsWith(
                "// Code generated by sqlcj. DO NOT EDIT.\n\npackage generated.repositories;"
            )
        );
    }

    @Test
    void shouldGenerateDoNotEditNoticeBeforePackageDeclaration() {
        CodeGenerator generator = new JavaCodeGenerator("dev.example.generated");

        GeneratedFile file = generate(
            generator,
            query(
                "GetUser",
                QueryType.ONE,
                List.of(
                    new QueryParameter(1, "id", ColumnType.BIGINT)
                )
            )
        );

        String source = file.content();

        assertTrue(
            source.startsWith(
                "// Code generated by sqlcj. DO NOT EDIT.\n\npackage dev.example.generated.repositories;\n"
            )
        );

        assertEquals(
            1,
            source.split("// Code generated by sqlcj\\. DO NOT EDIT\\.", -1).length - 1
        );

        assertTrue(source.contains(" * Generated by sqlcj."));
    }

    @Test
    void shouldGenerateConfiguredPackageDeclarationAndPath() throws IOException {
        CodeGenerator generator = new JavaCodeGenerator("dev.example.generated");

        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(1),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false)
            ),
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT)
            ),
            null
        );

        GeneratedFile file = generate(generator, query);

        assertEquals(
            Path.of("dev", "example", "generated", "repositories", "UsersRepository.java"),
            file.path()
        );

        assertTrue(
            file.content().startsWith(
                "// Code generated by sqlcj. DO NOT EDIT.\n\npackage dev.example.generated.repositories;"
            )
        );

        assertCompiles(file);
    }

    @Test
    void shouldGenerateResultRecord() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true),
                new QueryColumn("active", ColumnType.BOOLEAN, true)
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(source.contains("public record GetUserResult("));
        assertTrue(source.contains("Long id,"));
        assertTrue(source.contains("String name,"));
        assertTrue(source.contains("Boolean active"));
        assertTrue(source.contains(") {"));
    }

    @Test
    void shouldGenerateSingleResultReturnType() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        assertTrue(
            file.content().contains(
                "public GetUserResult getUser()"
            )
        );
    }

    @Test
    void shouldGenerateListResultReturnType() {
        QueryModel query = new QueryModel(
            "ListUsers",
            QueryType.MANY,
            "users",
            SQL,
            List.of(),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        assertTrue(file.content().contains("import java.util.List;"));

        assertTrue(
            file.content().contains(
                "public List<ListUsersResult> listUsers()"
            )
        );
    }

    @Test
    void shouldGenerateTypedMethodParameters() {
        QueryModel query = new QueryModel(
            "ListUsersByIdAndName",
            QueryType.MANY,
            "users",
            SQL,
            List.of(1, 2),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR)
            ),
            null
        );

        GeneratedFile file = generate(query);

        assertTrue(
            file.content().contains(
                "    public List<ListUsersByIdAndNameResult> listUsersByIdAndName(Long id, String name) {"
            )
        );
    }

    @Test
    void shouldGenerateSingleResultWithTypedParameter() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(1),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT)
            ),
            null
        );

        GeneratedFile file = generate(query);

        assertTrue(
            file.content().contains(
                "public GetUserResult getUser(Long id)"
            )
        );
    }

    /**
     * A {@code :one} query without parameters binds an empty list through
     * {@code Collections}, which it imports, and names no {@code List} of its
     * own, so it imports none either.
     */
    @Test
    void shouldImportCollectionsAndNoListForSingleResultWithoutParameters() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false)
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(source.contains("import java.util.Collections;"));
        assertFalse(source.contains("import java.util.List;"), source);
        assertTrue(source.contains("Collections.emptyList()"));
    }

    @Test
    void shouldGenerateParameterNamesFromQueryParameters() {
        QueryModel query = query(
            "GetUser",
            QueryType.ONE,
            List.of(
                new QueryParameter(1, "userId", ColumnType.BIGINT),
                new QueryParameter(2, "enabled", ColumnType.BOOLEAN)
            )
        );

        GeneratedFile file = generate(query);

        assertTrue(
            file.content().contains(
                "getUser(Long userId, Boolean enabled)"
            )
        );
    }

    @Test
    void shouldGenerateDistinctComponentsAndPositionalReadsForDuplicateColumns() throws IOException {
        QueryModel query = new QueryModel(
            "ListUserProfiles",
            QueryType.MANY,
            "users",
            """
                SELECT u.id, p.id, p.nickname
                FROM users u
                JOIN profiles p ON p.user_id = u.id
                """,
            List.of(),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("nickname", ColumnType.VARCHAR, true)
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(source.contains("Long id1"));
        assertTrue(source.contains("Long id2"));
        assertTrue(source.contains("String nickname"));

        assertTrue(
            source.contains("NotNullColumn.getObject(resultSet, 1, Long.class)")
        );
        assertTrue(
            source.contains("NotNullColumn.getObject(resultSet, 2, Long.class)")
        );
        assertTrue(source.contains("resultSet.getObject(3, String.class)"));

        assertEquals(0, compile(file, "UsersRepository.java"));
    }

    @ParameterizedTest
    @CsvSource(
        {
            "INTEGER, Integer",
            "BIGINT, Long",
            "SMALLINT, Short",
            "BOOLEAN, Boolean",
            "VARCHAR, String",
            "TEXT, String",
            "DATE, LocalDate",
            "TIMESTAMP, LocalDateTime",
            "TIMESTAMP_WITH_TIME_ZONE, OffsetDateTime",
            "DECIMAL, BigDecimal",
            "UUID, UUID",
            "REAL, Float",
            "DOUBLE_PRECISION, Double",
            "BYTEA, byte[]",
            "TIME, LocalTime",
            "JSON, String",
            "JSONB, String",
            "CITEXT, String",
            "INET, String",
            "CIDR, String",
            "MACADDR, String",
            "MACADDR8, String"
        }
    )
    void shouldGenerateJavaTypeForQueryParameter(
        ColumnType columnType,
        String expectedJavaType
    ) {
        GeneratedFile file = generate(
            query(
                "GetUser",
                QueryType.ONE,
                List.of(
                    new QueryParameter(1, "value", columnType).asNullable()
                )
            )
        );

        assertTrue(
            file.content().contains(
                columnType == ColumnType.BYTEA
                    ? "byte @Nullable [] value"
                    : "@Nullable " + expectedJavaType + " value"
            )
        );
    }

    @Test
    void shouldGenerateQuerySpecificResultRecord() {
        QueryModel query = new QueryModel(
            "ListUsers",
            QueryType.MANY,
            "users",
            SQL,
            List.of(),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        assertTrue(
            file.content().contains(
                "public record ListUsersResult("
            )
        );
    }

    /**
     * A {@code :one} query whose result is one column that cannot be NULL
     * returns that column's Java type and generates no result record, so its
     * row mapper reads the value itself, through
     * {@code NotNullColumn}, which fails on a SQL
     * {@code NULL}.
     */
    @Test
    void shouldReturnTheValueOfOneNonNullColumn() throws IOException {
        QueryModel query = new QueryModel(
            "CountUsers",
            QueryType.ONE,
            "users",
            "SELECT COUNT(*) AS total FROM users",
            List.of(),
            List.of(new QueryColumn("total", ColumnType.BIGINT, false)),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(source.contains("public Long countUsers()"), source);

        assertTrue(
            source.contains(
                "private static final RowMapper<Long> countUsersRowMapper =\n"
                    + "            resultSet -> NotNullColumn"
                    + ".getObject(resultSet, 1, Long.class);"
            ),
            source
        );

        assertFalse(source.contains("public record"), source);

        assertCompiles(file);
    }

    /** The same shape for an {@code :optional} read, inside the Optional. */
    @Test
    void shouldReturnAnOptionalValueOfOneNonNullColumn() throws IOException {
        QueryModel query = new QueryModel(
            "FindUserExternalId",
            QueryType.OPTIONAL,
            "users",
            "SELECT external_id FROM users WHERE id = ?",
            List.of(1),
            List.of(new QueryColumn("external_id", ColumnType.UUID, false)),
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(
            source.contains("public Optional<UUID> findUserExternalId(Long id)"),
            source
        );

        assertTrue(
            source.contains(
                "private static final RowMapper<UUID> findUserExternalIdRowMapper =\n"
                    + "            resultSet -> NotNullColumn"
                    + ".getObject(resultSet, 1, UUID.class);"
            ),
            source
        );

        assertFalse(source.contains("public record"), source);

        assertCompiles(file);
    }

    /** The same shape for a {@code :many} read, inside the List. */
    @Test
    void shouldReturnAListOfValuesOfOneNonNullColumn() throws IOException {
        QueryModel query = new QueryModel(
            "ListUserNames",
            QueryType.MANY,
            "users",
            "SELECT name FROM users",
            List.of(),
            List.of(new QueryColumn("name", ColumnType.VARCHAR, false)),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(source.contains("public List<String> listUserNames()"), source);

        assertTrue(
            source.contains(
                "private static final RowMapper<String> listUserNamesRowMapper =\n"
                    + "            resultSet -> NotNullColumn"
                    + ".getObject(resultSet, 1, String.class);"
            ),
            source
        );

        assertFalse(source.contains("public record"), source);

        assertCompiles(file);
    }

    /** A returning write reads its one non-null returned column as a value. */
    @Test
    void shouldReturnTheValueOfOneNonNullReturnedColumn() throws IOException {
        QueryModel query = new QueryModel(
            "InsertUser",
            QueryType.ONE,
            "users",
            "INSERT INTO users (name) VALUES (?) RETURNING id",
            List.of(1),
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            List.of(new QueryParameter(1, "name", ColumnType.VARCHAR)),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(source.contains("public Long insertUser(String name)"), source);

        assertTrue(
            source.contains(
                "private static final RowMapper<Long> insertUserRowMapper =\n"
                    + "            resultSet -> NotNullColumn"
                    + ".getObject(resultSet, 1, Long.class);"
            ),
            source
        );

        assertFalse(source.contains("public record"), source);

        assertCompiles(file);
    }

    /** A non-null enum column returns the generated enum of its enum type. */
    @Test
    void shouldReturnTheValueOfOneNonNullEnumColumn() throws IOException {
        QueryModel query = new QueryModel(
            "GetStageSetting",
            QueryType.ONE,
            "stages",
            "SELECT setting FROM stages WHERE handle = ?",
            List.of(1),
            List.of(new QueryColumn("setting", ColumnType.ENUM, false, "stage_setting")),
            List.of(new QueryParameter(1, "handle", ColumnType.VARCHAR)),
            null
        );

        GeneratedFile repository = generate(
            enumGroup(
                GROUP,
                query,
                new EnumType("stage_setting", List.of("indoor", "outdoor"))
            )
        );

        String source = repository.content();

        assertTrue(
            source.contains("public StageSetting getStageSetting(String handle)"),
            source
        );

        assertTrue(
            source.contains(
                "private static final RowMapper<StageSetting> getStageSettingRowMapper =\n"
                    + "            resultSet -> NotNullColumn"
                    + ".getString(resultSet, 1, StageSetting::fromLabel);"
            ),
            source
        );

        assertFalse(source.contains("public record"), source);

        assertCompiles(repository, codeGenerator.generateEnums().get(0));
    }

    /** A non-null array column returns the list of its element type. */
    @Test
    void shouldReturnTheValueOfOneNonNullArrayColumn() throws IOException {
        QueryModel query = new QueryModel(
            "GetUserTags",
            QueryType.ONE,
            "users",
            "SELECT tags FROM users WHERE id = ?",
            List.of(1),
            List.of(new QueryColumn("tags", ColumnType.VARCHAR, false, null, true)),
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(
            source.contains(
                "public List<@Nullable String> getUserTags(Long id)"
            ),
            source
        );

        assertTrue(
            source.contains(
                "private static final RowMapper<List<@Nullable String>> getUserTagsRowMapper =\n"
                    + "            resultSet -> NotNullColumn"
                    + ".getList(resultSet, 1, String.class);"
            ),
            source
        );

        assertFalse(source.contains("public record"), source);

        assertCompiles(file);
    }

    /**
     * Every kind of read a result mapper writes has two forms: a column the
     * model states non-null is read through
     * {@code NotNullColumn}, which fails on a SQL
     * {@code NULL}, and a nullable column keeps the read it had, character for
     * character.
     */
    @Test
    void shouldReadEveryNonNullColumnThroughNotNullColumn() throws IOException {
        QueryModel query = new QueryModel(
            "ReadEverything",
            QueryType.MANY,
            "readings",
            "SELECT * FROM readings",
            List.of(),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("score", ColumnType.BIGINT, true),
                new QueryColumn("profile", ColumnType.JSON, false),
                new QueryColumn("metadata", ColumnType.JSON, true),
                new QueryColumn("setting", ColumnType.ENUM, false, "stage_setting"),
                new QueryColumn("fallback", ColumnType.ENUM, true, "stage_setting"),
                new QueryColumn("age", ColumnType.INTERVAL, false),
                new QueryColumn("idle", ColumnType.INTERVAL, true),
                new QueryColumn("tags", ColumnType.VARCHAR, false, null, true),
                new QueryColumn("labels", ColumnType.VARCHAR, true, null, true),
                new QueryColumn("settings", ColumnType.ENUM, false, "stage_setting", true),
                new QueryColumn("choices", ColumnType.ENUM, true, "stage_setting", true),
                new QueryColumn("ages", ColumnType.INTERVAL, false, null, true),
                new QueryColumn("spans", ColumnType.INTERVAL, true, null, true)
            ),
            List.of(),
            null
        );

        GeneratedFile repository = generate(
            enumGroup(
                GROUP,
                query,
                new EnumType("stage_setting", List.of("indoor", "outdoor"))
            )
        );

        String source = repository.content();

        assertReads(
            source,
            List.of(
                "NotNullColumn.getObject(resultSet, 1, Long.class)",
                "resultSet.getObject(2, Long.class)",
                "NotNullColumn.getString(resultSet, 3)",
                "resultSet.getString(4)",
                "NotNullColumn.getString(resultSet, 5, StageSetting::fromLabel)",
                "StageSetting.fromLabel(resultSet.getString(6))",
                "NotNullColumn.getString("
                    + "resultSet, 7, SqlInterval::parse)",
                "SqlInterval.parse(resultSet.getString(8))",
                "NotNullColumn.getList(resultSet, 9, String.class)",
                "SqlArray.getList(resultSet, 10, String.class)",
                "NotNullColumn.getList("
                    + "resultSet, 11, String.class, StageSetting::fromLabel)",
                "SqlArray.getList("
                    + "resultSet, 12, String.class, StageSetting::fromLabel)",
                "NotNullColumn.getList("
                    + "resultSet, 13, String.class, SqlInterval::parse)",
                "SqlArray.getList("
                    + "resultSet, 14, String.class, SqlInterval::parse)"
            )
        );

        assertCompiles(repository, codeGenerator.generateEnums().get(0));
    }

    /**
     * Asserts that one generated source holds each read, in the selected order
     * of the columns they read.
     */
    private void assertReads(String source, List<String> reads) {
        int previous = -1;

        for (String read : reads) {
            int index = source.indexOf(read);

            assertTrue(index > previous, read + " in\n" + source);

            previous = index;
        }
    }

    /** One nullable column keeps its result record, which may hold null. */
    @Test
    void shouldKeepTheResultRecordOfOneNullableColumn() throws IOException {
        QueryModel query = new QueryModel(
            "GetUserName",
            QueryType.ONE,
            "users",
            "SELECT name FROM users WHERE id = ?",
            List.of(1),
            List.of(new QueryColumn("name", ColumnType.VARCHAR, true)),
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(
            source.contains("public GetUserNameResult getUserName(Long id)"), source
        );

        assertEquals(
            List.of("@Nullable String name"),
            recordComponents(source, "GetUserNameResult")
        );

        assertTrue(
            source.contains(
                "private static final RowMapper<GetUserNameResult> getUserNameRowMapper ="
            ),
            source
        );

        assertCompiles(file);
    }

    /** One non-null embedded row keeps the result record that holds it. */
    @Test
    void shouldKeepTheResultRecordOfOneEmbeddedRowComponent() throws IOException {
        QueryModel query = new QueryModel(
            "ListUserRows",
            QueryType.MANY,
            "users",
            "SELECT u.* FROM users u",
            List.of(),
            List.of(usersRowColumn("u")),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(source.contains("public List<ListUserRowsResult> listUserRows()"), source);

        assertEquals(
            List.of("UsersRow u"),
            recordComponents(source, "ListUserRowsResult")
        );

        assertCompilesWithRows(file);
    }

    /** A whole-table result of a one-column table keeps its shared row. */
    @Test
    void shouldKeepTheSharedRowOfAOneColumnTable() throws IOException {
        QueryModel query = new QueryModel(
            "GetCode",
            QueryType.ONE,
            "codes",
            "SELECT * FROM codes WHERE code = ?",
            List.of(1),
            List.of(new QueryColumn("code", ColumnType.VARCHAR, false)),
            List.of(new QueryParameter(1, "code", ColumnType.VARCHAR)),
            "codes"
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(source.contains("public CodesRow getCode(String code)"), source);

        assertTrue(
            source.contains("private static final RowMapper<CodesRow> codesRowMapper ="),
            source
        );

        assertFalse(source.contains("public record GetCodeResult("), source);

        assertCompilesWithRows(file);
    }

    /** Two non-null columns keep their result record. */
    @Test
    void shouldKeepTheResultRecordOfTwoNonNullColumns() throws IOException {
        QueryModel query = new QueryModel(
            "CountUsersByCode",
            QueryType.MANY,
            "users",
            "SELECT code, COUNT(*) AS total FROM users GROUP BY code",
            List.of(),
            List.of(
                new QueryColumn("code", ColumnType.VARCHAR, false),
                new QueryColumn("total", ColumnType.BIGINT, false)
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(
            source.contains("public List<CountUsersByCodeResult> countUsersByCode()"),
            source
        );

        assertEquals(
            List.of("String code", "Long total"),
            recordComponents(source, "CountUsersByCodeResult")
        );

        assertCompiles(file);
    }

    @Test
    void shouldGenerateImportForLocalDate() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(
                new QueryColumn(
                    "birthDate",
                    ColumnType.DATE,
                    true
                )
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        assertTrue(
            file.content().contains(
                "import java.time.LocalDate;"
            )
        );
    }

    @Test
    void shouldGenerateImportForLocalTime() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(
                new QueryColumn(
                    "openedAt",
                    ColumnType.TIME,
                    true
                )
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        assertTrue(
            file.content().contains(
                "import java.time.LocalTime;"
            )
        );
    }

    @Test
    void shouldGenerateImportForBigDecimal() {
        QueryModel query = new QueryModel(
            "GetAccount",
            QueryType.ONE,
            "accounts",
            SQL,
            List.of(),
            List.of(
                new QueryColumn(
                    "balance",
                    ColumnType.DECIMAL,
                    false
                )
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        assertTrue(
            file.content().contains(
                "import java.math.BigDecimal;"
            )
        );
    }

    @Test
    void shouldGenerateImportsForUuidAndTimestampWithTimeZone() throws IOException {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(1, 2),
            List.of(
                new QueryColumn(
                    "external_id",
                    ColumnType.UUID,
                    true
                ),
                new QueryColumn(
                    "created_at",
                    ColumnType.TIMESTAMP_WITH_TIME_ZONE,
                    true
                )
            ),
            List.of(
                new QueryParameter(
                    1,
                    "externalId",
                    ColumnType.UUID
                ).asNullable(),
                new QueryParameter(
                    2,
                    "createdAt",
                    ColumnType.TIMESTAMP_WITH_TIME_ZONE
                ).asNullable()
            ),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(source.contains("import java.util.UUID;"));
        assertTrue(source.contains("import java.time.OffsetDateTime;"));

        assertTrue(
            source.contains(
                "    public GetUserResult getUser("
                    + "@Nullable UUID externalId, @Nullable OffsetDateTime createdAt) {"
            )
        );

        assertTrue(source.contains("UUID externalId"));
        assertTrue(source.contains("OffsetDateTime createdAt"));

        assertTrue(source.contains("resultSet.getObject(1, UUID.class)"));
        assertTrue(source.contains("resultSet.getObject(2, OffsetDateTime.class)"));

        assertCompiles(file);
    }

    @Test
    void shouldGenerateImportForQueryParameterType() {
        QueryModel query = new QueryModel(
            "FindUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(1),
            List.of(
                new QueryColumn(
                    "id",
                    ColumnType.BIGINT,
                    false
                )
            ),
            List.of(
                new QueryParameter(
                    1,
                    "createdAt",
                    ColumnType.TIMESTAMP
                )
            ),
            null
        );

        GeneratedFile file = generate(query);

        assertTrue(
            file.content().contains(
                "import java.time.LocalDateTime;"
            )
        );
    }

    @Test
    void shouldGenerateEachImportOnlyOnce() {
        QueryModel query = new QueryModel(
            "FindUsers",
            QueryType.MANY,
            "users",
            SQL,
            List.of(1),
            List.of(
                new QueryColumn(
                    "createdAt",
                    ColumnType.TIMESTAMP,
                    true
                )
            ),
            List.of(
                new QueryParameter(
                    1,
                    "updatedAt",
                    ColumnType.TIMESTAMP
                )
            ),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertEquals(
            1,
            source.lines()
                .filter(line -> line.equals("import java.time.LocalDateTime;"))
                .count()
        );
    }

    @Test
    void shouldGenerateMultipleRequiredImports() {
        QueryModel query = new QueryModel(
            "ListAccounts",
            QueryType.MANY,
            "accounts",
            SQL,
            List.of(),
            List.of(
                new QueryColumn(
                    "createdAt",
                    ColumnType.TIMESTAMP,
                    false
                ),
                new QueryColumn(
                    "balance",
                    ColumnType.DECIMAL,
                    false
                )
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(source.contains("import java.util.List;"));
        assertTrue(source.contains("import java.time.LocalDateTime;"));
        assertTrue(source.contains("import java.math.BigDecimal;"));
    }

    @Test
    void shouldGenerateQueryExecutionMethodBody() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(1),
            List.of(
                new QueryColumn(
                    "id",
                    ColumnType.BIGINT,
                    false
                )
            ),
            List.of(
                new QueryParameter(
                    1,
                    "id",
                    ColumnType.BIGINT
                )
            ),
            null
        );

        String source = generate(query).content();

        assertTrue(source.contains("return executor.queryOne("));
        assertTrue(source.contains("Collections.singletonList(id)"));
        assertTrue(source.contains("getUserRowMapper"));
        assertFalse(source.contains("UnsupportedOperationException"));
    }

    @Test
    void shouldGenerateCompilableJavaSource() throws IOException {
        QueryModel query = new QueryModel(
            "ListUsers",
            QueryType.MANY,
            "users",
            SQL,
            List.of(1),
            List.of(
                new QueryColumn(
                    "id",
                    ColumnType.BIGINT,
                    false
                ),
                new QueryColumn(
                    "birth_date",
                    ColumnType.DATE,
                    true
                ),
                new QueryColumn(
                    "created_at",
                    ColumnType.TIMESTAMP,
                    true
                ),
                new QueryColumn(
                    "balance",
                    ColumnType.DECIMAL,
                    true
                )
            ),
            List.of(
                new QueryParameter(
                    1,
                    "created_at",
                    ColumnType.TIMESTAMP
                )
            ),
            null
        );

        GeneratedFile file = generate(query);

        assertEquals(0, compile(file, "UsersRepository.java"));
    }

    /**
     * Compiles a repository that binds and reads the floating-point, binary,
     * and time types, including a query whose only binding parameter is the
     * array type {@code byte[]}.
     */
    @Test
    void shouldGenerateCompilableJavaSourceForFloatingPointBinaryAndTimeTypes()
        throws IOException {
        QueryModel listMeasurements = new QueryModel(
            "ListMeasurements",
            QueryType.MANY,
            "measurements",
            SQL,
            List.of(1, 2, 3, 4),
            List.of(
                new QueryColumn(
                    "amount",
                    ColumnType.REAL,
                    true
                ),
                new QueryColumn(
                    "ratio",
                    ColumnType.DOUBLE_PRECISION,
                    true
                ),
                new QueryColumn(
                    "payload",
                    ColumnType.BYTEA,
                    true
                ),
                new QueryColumn(
                    "opened_at",
                    ColumnType.TIME,
                    true
                )
            ),
            List.of(
                new QueryParameter(1, "amount", ColumnType.REAL),
                new QueryParameter(2, "ratio", ColumnType.DOUBLE_PRECISION),
                new QueryParameter(3, "payload", ColumnType.BYTEA),
                new QueryParameter(4, "opened_at", ColumnType.TIME)
            ),
            null
        );

        QueryModel getMeasurementByPayload = new QueryModel(
            "GetMeasurementByPayload",
            QueryType.ONE,
            "measurements",
            SQL,
            List.of(1),
            List.of(
                new QueryColumn(
                    "payload",
                    ColumnType.BYTEA,
                    false
                )
            ),
            List.of(
                new QueryParameter(1, "payload", ColumnType.BYTEA)
            ),
            null
        );

        GeneratedFile file = codeGenerator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(listMeasurements, getMeasurementByPayload)
            )
        );

        String source = file.content();

        assertTrue(source.contains("import java.time.LocalTime;"));
        assertTrue(source.contains("Float amount"));
        assertTrue(source.contains("Double ratio"));
        assertTrue(source.contains("byte @Nullable [] payload"));
        assertTrue(source.contains("LocalTime openedAt"));
        assertTrue(source.contains("resultSet.getObject(3, byte[].class)"));

        assertEquals(0, compile(file, "UsersRepository.java"));
    }

    /**
     * A JSON parameter is bound through {@code UntypedText}
     * at every position its placeholder index is bound at, and a JSON result
     * column is read as text. The other {@code String} types are bound and read
     * exactly as before, and the repository imports the wrapper it names.
     */
    @Test
    void shouldWrapJsonArgumentsAndReadJsonColumnsAsText() throws IOException {
        QueryModel query = new QueryModel(
            "FindDocument",
            QueryType.ONE,
            "documents",
            """
                SELECT id, metadata, profile, name
                FROM documents
                WHERE name = ?
                  AND (metadata = ? OR metadata = ?)
                  AND bio = ?
                  AND profile = ?
                """,
            List.of(3, 1, 1, 4, 2),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("metadata", ColumnType.JSONB, true),
                new QueryColumn("profile", ColumnType.JSON, true),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(
                new QueryParameter(1, "metadata", ColumnType.JSONB).asNullable(),
                new QueryParameter(2, "profile", ColumnType.JSON).asNullable(),
                new QueryParameter(3, "name", ColumnType.VARCHAR).asNullable(),
                new QueryParameter(4, "bio", ColumnType.TEXT).asNullable()
            ),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(
            source.contains(
                "    public FindDocumentResult findDocument(\n"
                    + "        @Nullable String metadata,\n"
                    + "        @Nullable String profile,\n"
                    + "        @Nullable String name,\n"
                    + "        @Nullable String bio\n"
                    + "    ) {"
            )
        );

        assertTrue(
            source.contains(
                "                Arrays.<@Nullable Object>asList(\n"
                    + "                        name,\n"
                    + "                        new UntypedText(metadata),\n"
                    + "                        new UntypedText(metadata),\n"
                    + "                        bio,\n"
                    + "                        new UntypedText(profile)\n"
                    + "                ),"
            )
        );

        assertTrue(
            source.contains("NotNullColumn.getObject(resultSet, 1, Long.class)")
        );
        assertTrue(source.contains("resultSet.getString(2)"));
        assertTrue(source.contains("resultSet.getString(3)"));
        assertTrue(source.contains("resultSet.getObject(4, String.class)"));

        assertTrue(source.contains("import org.sqlcj.runtime.UntypedText;"));

        assertCompiles(file);
    }

    /**
     * A {@code citext} and a network address parameter are bound through
     * {@code UntypedText}, and a column of either is read as
     * text, exactly as JSON is, because the driver reports each of them as a
     * type of its own that is not read as a {@code String}. An array of either
     * is bound as {@code SqlArray} of the type's PostgreSQL
     * name and read as a list of {@code String}. The generated source compiles,
     * and the repository imports both wrappers it names.
     */
    @Test
    void shouldWrapCitextAndNetworkArgumentsAndReadThemAsText() throws IOException {
        QueryModel query = new QueryModel(
            "FindHost",
            QueryType.ONE,
            "hosts",
            """
                SELECT id, handle, address, network, mac, mac8, handles, bio
                FROM hosts
                WHERE handle = ?
                  AND address = ?
                  AND network = ?
                  AND mac = ?
                  AND mac8 = ?
                  AND handles = ?
                  AND bio = ?
                """,
            List.of(1, 2, 3, 4, 5, 6, 7),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("handle", ColumnType.CITEXT, true),
                new QueryColumn("address", ColumnType.INET, true),
                new QueryColumn("network", ColumnType.CIDR, true),
                new QueryColumn("mac", ColumnType.MACADDR, true),
                new QueryColumn("mac8", ColumnType.MACADDR8, true),
                new QueryColumn("handles", ColumnType.CITEXT, true, null, true),
                new QueryColumn("bio", ColumnType.TEXT, true)
            ),
            List.of(
                new QueryParameter(1, "handle", ColumnType.CITEXT).asNullable(),
                new QueryParameter(2, "address", ColumnType.INET).asNullable(),
                new QueryParameter(3, "network", ColumnType.CIDR).asNullable(),
                new QueryParameter(4, "mac", ColumnType.MACADDR).asNullable(),
                new QueryParameter(5, "mac8", ColumnType.MACADDR8).asNullable(),
                new QueryParameter(6, "handles", ColumnType.CITEXT, null, true).asNullable(),
                new QueryParameter(7, "bio", ColumnType.TEXT).asNullable()
            ),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(
            source.contains(
                "    public FindHostResult findHost(\n"
                    + "        @Nullable String handle,\n"
                    + "        @Nullable String address,\n"
                    + "        @Nullable String network,\n"
                    + "        @Nullable String mac,\n"
                    + "        @Nullable String mac8,\n"
                    + "        @Nullable List<@Nullable String> handles,\n"
                    + "        @Nullable String bio\n"
                    + "    ) {"
            )
        );

        assertTrue(
            source.contains(
                "                Arrays.<@Nullable Object>asList(\n"
                    + "                        new UntypedText(handle),\n"
                    + "                        new UntypedText(address),\n"
                    + "                        new UntypedText(network),\n"
                    + "                        new UntypedText(mac),\n"
                    + "                        new UntypedText(mac8),\n"
                    + "                        new SqlArray(\"citext\", handles),\n"
                    + "                        bio\n"
                    + "                ),"
            )
        );

        assertTrue(
            source.contains("NotNullColumn.getObject(resultSet, 1, Long.class)")
        );
        assertTrue(source.contains("resultSet.getString(2)"));
        assertTrue(source.contains("resultSet.getString(3)"));
        assertTrue(source.contains("resultSet.getString(4)"));
        assertTrue(source.contains("resultSet.getString(5)"));
        assertTrue(source.contains("resultSet.getString(6)"));
        assertTrue(source.contains("SqlArray.getList(resultSet, 7, String.class)"));
        assertTrue(source.contains("resultSet.getObject(8, String.class)"));

        assertTrue(source.contains("import org.sqlcj.runtime.SqlArray;"));
        assertTrue(source.contains("import org.sqlcj.runtime.UntypedText;"));

        assertCompiles(file);
    }

    /**
     * An {@code INTERVAL} parameter is an {@code SqlInterval}
     * bound through {@code UntypedText} as the text the value
     * writes, and an array of one binds the text of each element; a column of
     * either is read back by parsing PostgreSQL's own text, which the driver
     * reports as a type of its own rather than as a character type. The Java
     * type and both runtime wrappers are imported, and the generated source
     * compiles.
     */
    @Test
    void shouldWrapIntervalArgumentsAndReadThemThroughSqlInterval() throws IOException {
        QueryModel query = new QueryModel(
            "FindDuration",
            QueryType.ONE,
            "durations",
            """
                SELECT id, age, ages
                FROM durations
                WHERE age = ?
                  AND ages = ?
                """,
            List.of(1, 2),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("age", ColumnType.INTERVAL, true),
                new QueryColumn("ages", ColumnType.INTERVAL, true, null, true)
            ),
            List.of(
                new QueryParameter(1, "age", ColumnType.INTERVAL).asNullable(),
                new QueryParameter(2, "ages", ColumnType.INTERVAL, null, true).asNullable()
            ),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(
            source.contains(
                "    public FindDurationResult findDuration("
                    + "@Nullable SqlInterval age, "
                    + "@Nullable List<@Nullable SqlInterval> ages) {"
            )
        );

        assertTrue(
            source.contains(
                "                Arrays.<Object>asList(\n"
                    + "                        new UntypedText(age == null ? null : age.format()),\n"
                    + "                        SqlArray.of("
                    + "\"interval\", ages, SqlInterval::format)\n"
                    + "                ),"
            )
        );

        assertTrue(
            source.contains("NotNullColumn.getObject(resultSet, 1, Long.class)")
        );

        assertTrue(
            source.contains("SqlInterval.parse(resultSet.getString(2))")
        );

        assertTrue(
            source.contains(
                "SqlArray.getList("
                    + "resultSet, 3, String.class, SqlInterval::parse)"
            )
        );

        assertTrue(source.contains("import org.sqlcj.runtime.SqlArray;"));
        assertTrue(source.contains("import org.sqlcj.runtime.SqlInterval;"));
        assertTrue(source.contains("import org.sqlcj.runtime.UntypedText;"));

        assertCompiles(file);
    }

    /**
     * Compiles a repository that binds JSON text in a write and binds and reads
     * it in a query.
     */
    @Test
    void shouldGenerateCompilableJavaSourceForJsonTypes() throws IOException {
        QueryModel insertDocument = new QueryModel(
            "InsertDocument",
            QueryType.EXEC,
            "documents",
            SQL,
            List.of(1, 2, 3),
            List.of(),
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "metadata", ColumnType.JSONB),
                new QueryParameter(3, "profile", ColumnType.JSON)
            ),
            null
        );

        QueryModel getDocument = new QueryModel(
            "GetDocument",
            QueryType.ONE,
            "documents",
            SQL,
            List.of(1),
            List.of(
                new QueryColumn("metadata", ColumnType.JSONB, true),
                new QueryColumn("profile", ColumnType.JSON, true)
            ),
            List.of(
                new QueryParameter(1, "metadata", ColumnType.JSONB)
            ),
            null
        );

        GeneratedFile file = codeGenerator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(insertDocument, getDocument)
            )
        );

        String source = file.content();

        assertTrue(
            source.contains(
                "        @Nullable String metadata,\n"
                    + "        @Nullable String profile\n"
            )
        );
        assertTrue(source.contains("resultSet.getString(1)"));

        assertEquals(0, compile(file, "UsersRepository.java"));
    }

    /** Compiles one generated source file in an isolated temporary location. */
    private int compile(GeneratedFile file, String fileName) throws IOException {
        Path sourceDirectory = tempDir.resolve("generated");
        Path sourceFile = sourceDirectory.resolve(fileName);
        Path outputDirectory = tempDir.resolve("classes");

        Files.createDirectories(sourceDirectory);
        Files.createDirectories(outputDirectory);

        Files.writeString(sourceFile, file.content());

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

        assertNotNull(compiler);

        return compiler.run(
            null,
            null,
            null,
            "-classpath",
            System.getProperty("java.class.path"),
            "-d",
            outputDirectory.toString(),
            sourceFile.toString()
        );
    }

    @ParameterizedTest
    @CsvSource(
        {
            "INTEGER, Integer",
            "BIGINT, Long",
            "SMALLINT, Short",
            "BOOLEAN, Boolean",
            "VARCHAR, String",
            "TEXT, String",
            "DATE, LocalDate",
            "TIMESTAMP, LocalDateTime",
            "TIMESTAMP_WITH_TIME_ZONE, OffsetDateTime",
            "DECIMAL, BigDecimal",
            "UUID, UUID",
            "REAL, Float",
            "DOUBLE_PRECISION, Double",
            "BYTEA, byte[]",
            "TIME, LocalTime",
            "JSON, String",
            "JSONB, String",
            "CITEXT, String",
            "INET, String",
            "CIDR, String",
            "MACADDR, String",
            "MACADDR8, String"
        }
    )
    void shouldGenerateJavaTypeForResultColumn(ColumnType columnType, String expectedJavaType) {
        QueryModel query = new QueryModel(
            "GetValue",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(
                new QueryColumn(
                    "value",
                    columnType,
                    true
                )
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        assertTrue(
            file.content().contains(
                columnType == ColumnType.BYTEA
                    ? "byte @Nullable [] value"
                    : "@Nullable " + expectedJavaType + " value"
            )
        );
    }

    @Test
    void shouldGenerateWrapperTypeForNullableColumn() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(
                new QueryColumn(
                    "id",
                    ColumnType.BIGINT,
                    true
                )
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        assertTrue(
            file.content().contains("Long id")
        );
    }

    @Test
    void shouldGenerateWrapperTypeForNonNullableColumn() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(
                new QueryColumn(
                    "id",
                    ColumnType.BIGINT,
                    false
                ),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        assertTrue(
            file.content().contains("Long id")
        );
    }

    @Test
    void shouldGenerateUniqueParameterNamesForDuplicateColumns() {
        QueryModel query = new QueryModel(
            "FindUsers",
            QueryType.MANY,
            "users",
            SQL,
            List.of(1, 2),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "id", ColumnType.BIGINT)
            ),
            null
        );

        GeneratedFile file = generate(query);

        assertTrue(
            file.content().contains(
                "    public List<FindUsersResult> findUsers(Long id1, Long id2) {"
            )
        );
    }

    @Test
    void shouldGenerateRowMapperForOneQuery() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true),
                new QueryColumn("birth_date", ColumnType.DATE, true),
                new QueryColumn("created_at", ColumnType.TIMESTAMP, true),
                new QueryColumn("balance", ColumnType.DECIMAL, true)
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(
            source.contains(
                "import org.sqlcj.runtime.RowMapper;"
            )
        );

        assertTrue(
            source.contains(
                "private static final RowMapper<GetUserResult> getUserRowMapper"
            )
        );

        assertTrue(
            source.contains(
                "NotNullColumn.getObject(resultSet, 1, Long.class)"
            )
        );

        assertTrue(
            source.contains(
                "resultSet.getObject(2, String.class)"
            )
        );

        assertTrue(
            source.contains(
                "resultSet.getObject(3, LocalDate.class)"
            )
        );

        assertTrue(
            source.contains(
                "resultSet.getObject(4, LocalDateTime.class)"
            )
        );

        assertTrue(
            source.contains(
                "resultSet.getObject(5, BigDecimal.class)"
            )
        );
    }

    @Test
    void shouldPreserveResultColumnOrder() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(
                new QueryColumn("name", ColumnType.VARCHAR, true),
                new QueryColumn("id", ColumnType.BIGINT, false)
            ),
            List.of(),
            null
        );

        String source = generate(query).content();

        int nameIndex = source.indexOf("resultSet.getObject(1, String.class)");

        int idIndex = source.indexOf(
            "NotNullColumn.getObject(resultSet, 2, Long.class)"
        );

        assertTrue(nameIndex >= 0);
        assertTrue(nameIndex < idIndex);
    }

    @Test
    void shouldGenerateRowMapperForManyQuery() {
        QueryModel query = new QueryModel(
            "ListUsers",
            QueryType.MANY,
            "users",
            SQL,
            List.of(),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(),
            null
        );

        String source = generate(query).content();

        assertTrue(
            source.contains(
                "import org.sqlcj.runtime.RowMapper;"
            )
        );

        assertTrue(
            source.contains(
                "private static final RowMapper<ListUsersResult> listUsersRowMapper"
            )
        );

        assertTrue(
            source.contains(
                "NotNullColumn.getObject(resultSet, 1, Long.class)"
            )
        );

        assertTrue(
            source.contains(
                "resultSet.getObject(2, String.class)"
            )
        );
    }

    @Test
    void shouldGenerateQueryExecutorImport() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(),
            List.of(),
            null
        );

        String source = generate(query).content();

        assertTrue(
            source.contains(
                "import org.sqlcj.runtime.QueryExecutor;"
            )
        );
    }

    @Test
    void shouldGenerateQueryExecutorField() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(),
            List.of(),
            null
        );

        String source = generate(query).content();

        assertTrue(
            source.contains(
                "private final QueryExecutor executor;"
            )
        );
    }

    @Test
    void shouldGenerateQueryExecutorConstructor() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(),
            List.of(),
            null
        );

        String source = generate(query).content();

        assertTrue(
            source.contains(
                "public UsersRepository(QueryExecutor executor)"
            )
        );

        assertTrue(
            source.contains(
                "this.executor = executor;"
            )
        );
    }

    @Test
    void shouldGenerateExecutionForOneQuery() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            """
                SELECT id, name
                FROM users
                WHERE id = ?
                """,
            List.of(1),
            List.of(
                new QueryColumn(
                    "id",
                    ColumnType.BIGINT,
                    false
                ),
                new QueryColumn(
                    "name",
                    ColumnType.VARCHAR,
                    true
                )
            ),
            List.of(
                new QueryParameter(
                    1,
                    "id",
                    ColumnType.BIGINT
                )
            ),
            null
        );

        String source = generate(query).content();

        assertTrue(
            source.contains("""
                return executor.queryOne(
                """)
        );

        assertTrue(
            source.contains("""
                WHERE id = ?
                """)
        );

        assertTrue(
            source.contains(
                "Collections.singletonList(id)"
            )
        );

        assertTrue(
            source.contains(
                "getUserRowMapper"
            )
        );

        assertFalse(
            source.contains("UnsupportedOperationException")
        );

        assertTrue(
            source.contains("""
                return executor.queryOne(
                """)
        );

        assertFalse(
            source.contains(
                "return executor.queryMany("
            )
        );
    }

    @Test
    void shouldGenerateQueryManyExecutionForManyQuery() {
        QueryModel query = new QueryModel(
            "ListUsers",
            QueryType.MANY,
            "users",
            SQL,
            List.of(1),
            List.of(
                new QueryColumn(
                    "id",
                    ColumnType.BIGINT,
                    false
                ),
                new QueryColumn(
                    "name",
                    ColumnType.VARCHAR,
                    true
                )
            ),
            List.of(
                new QueryParameter(
                    1,
                    "active",
                    ColumnType.BOOLEAN
                )
            ),
            null
        );

        String source = generate(query).content();

        assertTrue(
            source.contains("""
                return executor.queryMany(
                """)
        );

        assertTrue(
            source.contains(
                "Collections.singletonList(active)"
            )
        );

        assertTrue(
            source.contains(
                "listUsersRowMapper"
            )
        );

        assertFalse(
            source.contains(
                "return executor.queryOne("
            )
        );
    }

    @Test
    void shouldPreserveParameterOrderWhenGeneratingExecution() {
        QueryModel query = new QueryModel(
            "FindUser",
            QueryType.ONE,
            "users",
            """
                SELECT id, name
                FROM users
                WHERE active = ?
                  AND id = ?
                  AND name = ?
                """,
            List.of(1, 2, 3),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(
                new QueryParameter(1, "active", ColumnType.BOOLEAN),
                new QueryParameter(2, "id", ColumnType.BIGINT),
                new QueryParameter(3, "name", ColumnType.VARCHAR)
            ),
            null
        );

        String source = generate(query).content();

        assertTrue(
            source.contains(
                "Arrays.<Object>asList(active, id, name)"
            )
        );
    }

    @Test
    void shouldGenerateLogicalParametersWithTextualBindingOrder() throws IOException {
        QueryModel query = new QueryModel(
            "FindUser",
            QueryType.ONE,
            "users",
            """
                SELECT id, name
                FROM users
                WHERE active = ?
                  AND id = ?
                """,
            List.of(2, 1),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "active", ColumnType.BOOLEAN)
            ),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(
            source.contains(
                "    public FindUserResult findUser(Long id, Boolean active) {"
            )
        );

        assertTrue(source.contains("WHERE active = ?"));
        assertTrue(source.contains("AND id = ?"));
        assertTrue(source.contains("Arrays.<Object>asList(active, id)"));

        assertCompiles(file);
    }

    @Test
    void shouldRepeatArgumentForRepeatedPlaceholderIndex() throws IOException {
        QueryModel query = new QueryModel(
            "FindUser",
            QueryType.ONE,
            "users",
            """
                SELECT id, name
                FROM users
                WHERE name = ?
                  AND (id = ? OR id = ?)
                """,
            List.of(2, 1, 1),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR)
            ),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(
            source.contains(
                "    public FindUserResult findUser(Long id, String name) {"
            )
        );

        assertTrue(source.contains("Arrays.<Object>asList(name, id, id)"));

        assertCompiles(file);
    }

    /**
     * The argument list counts binding positions, not logical parameters, so
     * one parameter bound at two placeholders is rendered with
     * {@code java.util.Arrays.asList} rather than as a singleton.
     */
    @Test
    void shouldGenerateTwoArgumentListForOneParameterBoundAtTwoPlaceholders()
        throws IOException {
        QueryModel query = new QueryModel(
            "FindUser",
            QueryType.ONE,
            "users",
            """
                SELECT id
                FROM users
                WHERE id = ? OR parent_id = ?
                """,
            List.of(1, 1),
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(source.contains("public Long findUser(Long id)"), source);
        assertTrue(source.contains("Arrays.<Object>asList(id, id)"), source);

        assertCompiles(file);
    }

    /**
     * A generated single execution passes the executor the arguments of its
     * binding positions: no element when it binds none, and otherwise the
     * argument itself, a {@code null} and a {@code byte[]} included.
     */
    @Test
    void shouldPassTheArgumentListOfASingleExecutionToTheExecutor() throws Exception {
        QueryModel touchUsers = new QueryModel(
            "TouchUsers",
            QueryType.EXEC,
            "users",
            "UPDATE users SET updated_at = now()",
            List.of(),
            List.of(),
            List.of(),
            null
        );

        QueryModel deleteUser = new QueryModel(
            "DeleteUser",
            QueryType.EXEC,
            "users",
            "DELETE FROM users\nWHERE id = ?",
            List.of(1),
            List.of(),
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT).asNullable()),
            null
        );

        QueryModel storeAvatar = new QueryModel(
            "StoreAvatar",
            QueryType.EXEC,
            "users",
            "UPDATE users SET avatar = ?",
            List.of(1),
            List.of(),
            List.of(new QueryParameter(1, "avatar", ColumnType.BYTEA)),
            null
        );

        GeneratedFile file = generate(
            new QueryGroupModel(GROUP, List.of(touchUsers, deleteUser, storeAvatar))
        );

        assertCompiles(file);

        byte[] avatar = { 1, 2, 3 };

        try (URLClassLoader classLoader = classLoader()) {
            Class<?> repositoryType = Class.forName(
                "generated.repositories.UsersRepository",
                true,
                classLoader
            );

            RecordingQueryExecutor executor = new RecordingQueryExecutor();

            Object repository = repositoryType
                .getConstructor(QueryExecutor.class)
                .newInstance(executor);

            repositoryType.getMethod("touchUsers").invoke(repository);
            repositoryType.getMethod("deleteUser", Long.class).invoke(repository, (Long) null);
            repositoryType.getMethod("storeAvatar", byte[].class).invoke(repository, avatar);

            assertEquals(3, executor.parameters.size());
            assertEquals(List.of(), executor.parameters.get(0));
            assertEquals(Collections.singletonList(null), executor.parameters.get(1));
            assertEquals(1, executor.parameters.get(2).size());
            assertSame(avatar, executor.parameters.get(2).get(0));
        }
    }

    /**
     * A generated batch passes the executor one argument list per row of the
     * list it takes, in its order, each holding that row's arguments.
     */
    @Test
    void shouldPassOneArgumentListPerBatchRowToTheExecutor() throws Exception {
        QueryModel query = new QueryModel(
            "StoreAvatars",
            QueryType.BATCH_EXEC,
            "users",
            "UPDATE users SET avatar = ?",
            List.of(1),
            List.of(),
            List.of(new QueryParameter(1, "avatar", ColumnType.BYTEA)),
            null
        );

        GeneratedFile file = generate(query);

        assertCompiles(file);

        byte[] first = { 1, 2 };
        byte[] second = { 3, 4 };

        try (URLClassLoader classLoader = classLoader()) {
            Class<?> repositoryType = Class.forName(
                "generated.repositories.UsersRepository",
                true,
                classLoader
            );

            Class<?> paramsType = Class.forName(
                "generated.repositories.UsersRepository$StoreAvatarsParams",
                true,
                classLoader
            );

            RecordingQueryExecutor executor = new RecordingQueryExecutor();

            Object repository = repositoryType
                .getConstructor(QueryExecutor.class)
                .newInstance(executor);

            List<Object> batch = List.of(
                paramsType.getConstructor(byte[].class).newInstance(first),
                paramsType.getConstructor(byte[].class).newInstance(second)
            );

            repositoryType.getMethod("storeAvatars", List.class).invoke(repository, batch);

            assertEquals(1, executor.batches);
            assertEquals(2, executor.batchParameters.size());
            assertEquals(1, executor.batchParameters.get(0).size());
            assertSame(first, executor.batchParameters.get(0).get(0));
            assertEquals(1, executor.batchParameters.get(1).size());
            assertSame(second, executor.batchParameters.get(1).get(0));
        }
    }

    @Test
    void shouldGenerateEmptyArgumentListForQueryWithoutParameters() throws IOException {
        QueryModel query = new QueryModel(
            "GetFirstUser",
            QueryType.ONE,
            "users",
            """
                SELECT id, name
                FROM users
                """,
            List.of(),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(
            source.contains(
                "public GetFirstUserResult getFirstUser()"
            )
        );

        assertTrue(source.contains("import java.util.Collections;"));
        assertFalse(source.contains("import java.util.List;"), source);
        assertTrue(source.contains("Collections.emptyList()"));

        assertCompiles(file);
    }

    @Test
    void shouldGenerateExecMethodForWriteQuery() throws IOException {
        QueryModel query = new QueryModel(
            "InsertUser",
            QueryType.EXEC,
            "users",
            """
                INSERT INTO users (id, name)
                VALUES (?, ?)
                """,
            List.of(1, 2),
            List.of(),
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR)
            ),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(
            source.contains(
                "public int insertUser(Long id, String name)"
            )
        );

        assertTrue(source.contains("return executor.execute("));
        assertTrue(source.contains("INSERT INTO users (id, name)"));
        assertTrue(source.contains("Arrays.<Object>asList(id, name)"));

        assertFalse(source.contains("public record InsertUserResult("));
        assertFalse(source.contains("RowMapper"));
        assertFalse(source.contains("UnsupportedOperationException"));

        assertCompiles(file);
    }

    /**
     * A {@code :batchexec} write generates its nested parameter record in
     * logical parameter order, where a result record would stand, and one
     * method that takes a list of it and returns the driver's counts. It
     * generates no result record and no row mapper, and imports
     * {@code java.util.List} for the list it takes.
     */
    @Test
    void shouldGenerateBatchExecMethodForWriteQuery() throws IOException {
        QueryModel query = new QueryModel(
            "InsertUser",
            QueryType.BATCH_EXEC,
            "users",
            """
                INSERT INTO users (id, name)
                VALUES (?, ?)
                """,
            List.of(1, 2),
            List.of(),
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR)
            ),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(
            source.contains(
                """
                        public record InsertUserParams(
                            Long id,
                            String name
                        ) {

                            public InsertUserParams {
                                Objects.requireNonNull(id, \
                    "Parameter 'id' of query 'InsertUser' in UsersRepository must not be null");
                                Objects.requireNonNull(name, \
                    "Parameter 'name' of query 'InsertUser' in UsersRepository must not be null");
                            }
                        }
                    """
                    .stripTrailing()
            )
        );

        assertTrue(
            source.contains("public int[] insertUser(List<InsertUserParams> batch) {")
        );

        assertTrue(source.contains("return executor.executeBatch("));
        assertTrue(source.contains("\"UsersRepository\","));
        assertTrue(source.contains("\"InsertUser\","));
        assertTrue(source.contains("INSERT INTO users (id, name)"));

        assertTrue(
            source.contains(
                "batch.stream().map(params -> Arrays.<Object>asList("
                    + "params.id(), params.name())).toList()"
            )
        );

        assertTrue(source.contains("import java.util.List;"));

        assertFalse(source.contains("public record InsertUserResult("));
        assertFalse(source.contains("RowMapper"));
        assertFalse(source.contains("UnsupportedOperationException"));

        assertCompiles(file);
    }

    /**
     * A batch query that binds no parameter generates a parameter record
     * without components, and a batch of it binds an empty argument list per
     * row, rejecting a {@code null} row that no component would read.
     */
    @Test
    void shouldGenerateParamsRecordWithoutComponentsForBatchQueryWithoutParameters()
        throws IOException {
        GeneratedFile file = generate(batchQueryWithoutParameters());

        String source = file.content();

        assertTrue(source.contains("public record TouchUsersParams() {"));

        assertTrue(
            source.contains("public int[] touchUsers(List<TouchUsersParams> batch) {")
        );

        assertTrue(
            source.contains(
                "batch.stream().map(Objects::requireNonNull)"
                    + ".map(params -> Collections.emptyList()).toList()"
            )
        );

        assertCompiles(file);
    }

    /**
     * A {@code null} row of a batch query that binds no parameter fails while
     * the generated method builds its argument lists, so the executor is never
     * called.
     */
    @Test
    void shouldRejectANullRowOfABatchQueryWithoutParametersBeforeTheExecutor()
        throws Exception {
        GeneratedFile file = generate(batchQueryWithoutParameters());

        assertCompiles(file);

        try (URLClassLoader classLoader = classLoader()) {
            Class<?> repositoryType = Class.forName(
                "generated.repositories.UsersRepository",
                true,
                classLoader
            );

            RecordingQueryExecutor executor = new RecordingQueryExecutor();

            Object repository = repositoryType
                .getConstructor(QueryExecutor.class)
                .newInstance(executor);

            Class<?> paramsType = Class.forName(
                "generated.repositories.UsersRepository$TouchUsersParams",
                true,
                classLoader
            );

            List<Object> batch = new ArrayList<>();

            batch.add(paramsType.getConstructor().newInstance());
            batch.add(null);

            InvocationTargetException thrown = assertThrows(
                InvocationTargetException.class,
                () -> repositoryType
                    .getMethod("touchUsers", List.class)
                    .invoke(repository, batch)
            );

            assertInstanceOf(NullPointerException.class, thrown.getCause());
            assertEquals(0, executor.batches);
        }
    }

    /** A batch query whose statement binds no parameter. */
    private QueryModel batchQueryWithoutParameters() {
        return new QueryModel(
            "TouchUsers",
            QueryType.BATCH_EXEC,
            "users",
            "UPDATE users SET updated_at = now()\n",
            List.of(),
            List.of(),
            List.of(),
            null
        );
    }

    /**
     * A batch query's record holds one component per logical parameter, while
     * its argument list repeats that component at every binding position of
     * its placeholder, in textual order.
     */
    @Test
    void shouldRepeatABatchParamsComponentAtEveryBindingPosition() throws IOException {
        QueryModel query = new QueryModel(
            "InsertUsers",
            QueryType.BATCH_EXEC,
            "users",
            """
                INSERT INTO users (id, name)
                VALUES (?, ?), (?, ?)
                """,
            List.of(1, 2, 3, 2),
            List.of(),
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT).asNullable(),
                new QueryParameter(2, "name", ColumnType.VARCHAR).asNullable(),
                new QueryParameter(3, "id", ColumnType.BIGINT).asNullable()
            ),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(
            source.contains(
                """
                        public record InsertUsersParams(
                            @Nullable Long id1,
                            @Nullable String name,
                            @Nullable Long id2
                        ) {
                        }
                    """
                    .stripTrailing()
            )
        );

        assertTrue(
            source.contains(
                "                batch.stream().map(params -> Arrays.<@Nullable Object>asList(\n"
                    + "                        params.id1(),\n"
                    + "                        params.name(),\n"
                    + "                        params.id2(),\n"
                    + "                        params.name()\n"
                    + "                )).toList()"
            )
        );

        assertCompiles(file);
    }

    /**
     * A batch parameter is wrapped exactly as the same parameter of a single
     * execution is: an enum binds its label, a {@code JSONB} and an
     * {@code INTERVAL} parameter their text, and an array its list with the
     * element type's PostgreSQL name.
     */
    @Test
    void shouldWrapBatchArgumentsLikeSingleExecutionArguments() throws IOException {
        QueryModel query = new QueryModel(
            "InsertStages",
            QueryType.BATCH_EXEC,
            "stages",
            """
                INSERT INTO stages (setting, payload, span, tags)
                VALUES (?, ?, ?, ?)
                """,
            List.of(1, 2, 3, 4),
            List.of(),
            List.of(
                new QueryParameter(1, "setting", ColumnType.ENUM, "stage_setting").asNullable(),
                new QueryParameter(2, "payload", ColumnType.JSONB).asNullable(),
                new QueryParameter(3, "span", ColumnType.INTERVAL).asNullable(),
                new QueryParameter(4, "tags", ColumnType.VARCHAR, null, true).asNullable()
            ),
            null
        );

        GeneratedFile file = codeGenerator.generate(
            new QueryGroupModel(
                "Stages",
                List.of(query),
                List.of(new EnumType("stage_setting", List.of("indoor", "outdoor")))
            )
        );

        String source = file.content();

        assertTrue(
            source.contains(
                """
                        public record InsertStagesParams(
                            @Nullable StageSetting setting,
                            @Nullable String payload,
                            @Nullable SqlInterval span,
                            @Nullable List<@Nullable String> tags
                        ) {
                        }
                    """
                    .stripTrailing()
            )
        );

        assertTrue(
            source.contains(
                "                batch.stream().map(params -> Arrays.<Object>asList(\n"
                    + "                        new UntypedText("
                    + "params.setting() == null ? null : params.setting().label()),\n"
                    + "                        new UntypedText(params.payload()),\n"
                    + "                        new UntypedText("
                    + "params.span() == null ? null : params.span().format()),\n"
                    + "                        new SqlArray(\"varchar\", params.tags())\n"
                    + "                )).toList()"
            )
        );

        List<GeneratedFile> files = new ArrayList<>();

        files.add(file);
        files.addAll(codeGenerator.generateEnums());

        assertCompiles(files.toArray(new GeneratedFile[0]));
    }

    /**
     * A method declaration that ends at exactly column 120, its member
     * indentation and its trailing brace counted, stays on one line.
     */
    @Test
    void shouldKeepAMethodDeclarationOfExactlyTheColumnLimitOnOneLine() throws IOException {
        GeneratedFile file = generate(noteQuery("note"));

        String declaration = "    public int updateTaskCompletionNote("
            + "@Nullable Long id, "
            + "@Nullable String note, "
            + "@Nullable OffsetDateTime reviewedAt) {";

        assertEquals(120, declaration.length());

        String source = file.content();

        assertTrue(source.contains(declaration + "\n"), source);

        assertCompiles(file);
    }

    /**
     * One column further the declaration writes each parameter, with its
     * annotation, on a line of its own, indented one level past the method,
     * and closes with {@code ) {} at the method's column.
     */
    @Test
    void shouldWrapAMethodDeclarationOneColumnPastTheLimit() throws IOException {
        GeneratedFile file = generate(noteQuery("notes"));

        String oneLine = "    public int updateTaskCompletionNote("
            + "@Nullable Long id, "
            + "@Nullable String notes, "
            + "@Nullable OffsetDateTime reviewedAt) {";

        assertEquals(121, oneLine.length());

        String source = file.content();

        assertFalse(source.contains(oneLine), source);

        assertTrue(
            source.contains(
                "    public int updateTaskCompletionNote(\n"
                    + "        @Nullable Long id,\n"
                    + "        @Nullable String notes,\n"
                    + "        @Nullable OffsetDateTime reviewedAt\n"
                    + "    ) {"
            ),
            source
        );

        assertCompiles(file);
    }

    /**
     * The one {@code UpdateTaskCompletionNote} query of the two declaration
     * tests, whose declaration reaches exactly the limit with a parameter named
     * {@code note} and passes it by one column with one named {@code notes}.
     */
    private QueryModel noteQuery(String parameterName) {
        return new QueryModel(
            "UpdateTaskCompletionNote",
            QueryType.EXEC,
            "tasks",
            """
                UPDATE tasks SET note = ?, reviewed_at = ?
                WHERE id = ?
                """,
            List.of(2, 3, 1),
            List.of(),
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT).asNullable(),
                new QueryParameter(2, parameterName, ColumnType.VARCHAR).asNullable(),
                new QueryParameter(
                    3,
                    "reviewedAt",
                    ColumnType.TIMESTAMP_WITH_TIME_ZONE
                ).asNullable()
            ),
            null
        );
    }

    /**
     * A generated method rejects a {@code null} argument for each of its
     * non-null parameters before it does anything else, in logical parameter
     * order, naming the parameter, the query, and the repository. A nullable
     * parameter is not checked, because the query gives its {@code null} a
     * meaning.
     */
    @Test
    void shouldCheckEveryNonNullParameterOfAQueryMethod() throws IOException {
        GeneratedFile file = generate(
            new QueryModel(
                "GetUser",
                QueryType.ONE,
                "users",
                SQL,
                List.of(1, 2, 3),
                List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
                List.of(
                    new QueryParameter(1, "id", ColumnType.BIGINT),
                    new QueryParameter(2, "name", ColumnType.VARCHAR).asNullable(),
                    new QueryParameter(3, "live", ColumnType.BOOLEAN)
                ),
                null
            )
        );

        String source = file.content();

        assertTrue(
            source.contains(
                "        Objects.requireNonNull(id, "
                    + "\"Parameter 'id' of query 'GetUser' in UsersRepository must not be null\");\n"
                    + "        Objects.requireNonNull(live, "
                    + "\"Parameter 'live' of query 'GetUser' in UsersRepository must not be null\");\n"
                    + "\n"
                    + "        return executor.queryOne("
            ),
            source
        );

        assertFalse(source.contains("requireNonNull(name"), source);

        assertCompiles(file);
    }

    /** An {@code :exec} method checks its non-null parameters the same way. */
    @Test
    void shouldCheckEveryNonNullParameterOfAnExecMethod() throws IOException {
        GeneratedFile file = generate(
            query(
                "DeleteUser",
                QueryType.EXEC,
                List.of(new QueryParameter(1, "id", ColumnType.BIGINT))
            )
        );

        String source = file.content();

        assertTrue(
            source.contains(
                "    public int deleteUser(Long id) {\n"
                    + "        Objects.requireNonNull(id, "
                    + "\"Parameter 'id' of query 'DeleteUser' in UsersRepository"
                    + " must not be null\");\n"
                    + "\n"
                    + "        return executor.execute("
            ),
            source
        );

        assertCompiles(file);
    }

    /**
     * A method whose every parameter is nullable, and one that binds none,
     * carries no check at all.
     */
    @Test
    void shouldCheckNothingWhenNoParameterIsNonNull() throws IOException {
        GeneratedFile file = generate(
            query(
                "FilterUsers",
                QueryType.MANY,
                List.of(new QueryParameter(1, "name", ColumnType.VARCHAR).asNullable())
            )
        );

        String source = file.content();

        assertFalse(source.contains("requireNonNull"), source);

        assertTrue(
            source.contains(
                "    public List<FilterUsersResult> filterUsers("
                    + "@Nullable String name) {\n"
                    + "        return executor.queryMany("
            ),
            source
        );

        assertCompiles(file);
    }

    /**
     * A check whose line would pass the limit writes its two arguments one per
     * line, one call continuation past the line that opens the call, with the
     * closing parenthesis at that line's column.
     */
    @Test
    void shouldWrapANullCheckThatPassesTheLimit() throws IOException {
        GeneratedFile file = generate(
            query(
                "DeleteScheduledMaintenanceWindow",
                QueryType.EXEC,
                List.of(new QueryParameter(1, "windowId", ColumnType.BIGINT))
            )
        );

        String source = file.content();

        assertTrue(
            source.contains(
                "        Objects.requireNonNull(\n"
                    + "                windowId,\n"
                    + "                \"Parameter 'windowId' of query "
                    + "'DeleteScheduledMaintenanceWindow' in UsersRepository must not be null\"\n"
                    + "        );\n"
            ),
            source
        );

        assertCompiles(file);
    }

    /**
     * The parameter record of a batch query rejects a {@code null} for each of
     * its non-null components in its compact constructor, so a row fails where
     * it is built, and keeps no constructor when every component is nullable.
     */
    @Test
    void shouldCheckEveryNonNullComponentOfABatchParamsRecord() throws IOException {
        GeneratedFile file = generate(
            query(
                "InsertUsers",
                QueryType.BATCH_EXEC,
                List.of(
                    new QueryParameter(1, "id", ColumnType.BIGINT),
                    new QueryParameter(2, "bio", ColumnType.TEXT).asNullable()
                )
            )
        );

        String source = file.content();

        assertTrue(
            source.contains(
                """
                        public record InsertUsersParams(
                            Long id,
                            @Nullable String bio
                        ) {

                            public InsertUsersParams {
                                Objects.requireNonNull(id, \
                    "Parameter 'id' of query 'InsertUsers' in UsersRepository must not be null");
                            }
                        }
                    """
                    .stripTrailing()
            ),
            source
        );

        assertCompiles(file);
    }

    /** A record of nullable components alone declares no constructor. */
    @Test
    void shouldGenerateNoCompactConstructorForNullableBatchComponentsAlone() throws IOException {
        GeneratedFile file = generate(
            query(
                "InsertUsers",
                QueryType.BATCH_EXEC,
                List.of(new QueryParameter(1, "bio", ColumnType.TEXT).asNullable())
            )
        );

        String source = file.content();

        assertTrue(
            source.contains(
                """
                        public record InsertUsersParams(
                            @Nullable String bio
                        ) {
                        }
                    """
                    .stripTrailing()
            ),
            source
        );

        assertFalse(source.contains("requireNonNull"), source);

        assertCompiles(file);
    }

    /**
     * The method of a query type sqlcj does not generate yet keeps the
     * nullness of its parameters and checks nothing, because it throws instead
     * of binding them.
     */
    @Test
    void shouldCheckNothingInTheMethodOfAnUnsupportedQueryType() {
        GeneratedFile file = generate(
            query(
                "CountUsers",
                QueryType.EXEC_RESULT,
                List.of(
                    new QueryParameter(1, "id", ColumnType.BIGINT),
                    new QueryParameter(2, "name", ColumnType.VARCHAR).asNullable()
                )
            )
        );

        String source = file.content();

        assertTrue(
            source.contains(
                "countUsers(Long id, @Nullable String name) {\n"
                    + "        throw new UnsupportedOperationException(\"Not implemented\");"
            ),
            source
        );

        assertFalse(source.contains("requireNonNull"), source);
    }

    /**
     * A non-null enum or {@code INTERVAL} argument is bound without the null
     * test a nullable one needs, so no generated body carries a test a null
     * checker reads as always false.
     */
    @Test
    void shouldBindANonNullEnumAndIntervalArgumentWithoutANullTest() throws IOException {
        QueryModel query = new QueryModel(
            "InsertStage",
            QueryType.EXEC,
            "stages",
            SQL,
            List.of(1, 2, 3, 4),
            List.of(),
            List.of(
                new QueryParameter(1, "setting", ColumnType.ENUM, "stage_setting"),
                new QueryParameter(2, "state", ColumnType.ENUM, "stage_setting").asNullable(),
                new QueryParameter(3, "span", ColumnType.INTERVAL),
                new QueryParameter(4, "pause", ColumnType.INTERVAL).asNullable()
            ),
            null
        );

        GeneratedFile file = codeGenerator.generate(
            new QueryGroupModel(
                "Stages",
                List.of(query),
                List.of(new EnumType("stage_setting", List.of("indoor", "outdoor")))
            )
        );

        String source = file.content();

        assertTrue(
            source.contains("new UntypedText(setting.label())"),
            source
        );
        assertTrue(
            source.contains(
                "new UntypedText(state == null ? null : state.label())"
            ),
            source
        );
        assertTrue(
            source.contains("new UntypedText(span.format())"),
            source
        );
        assertTrue(
            source.contains(
                "new UntypedText(pause == null ? null : pause.format())"
            ),
            source
        );

        List<GeneratedFile> files = new ArrayList<>();

        files.add(file);
        files.addAll(codeGenerator.generateEnums());

        assertCompiles(files.toArray(new GeneratedFile[0]));
    }

    /**
     * A bound-argument list of two or more arguments states its element type,
     * which is {@code Object} when no argument can be null and a nullable
     * {@code Object} when one can, so the list's type never follows from the
     * Java types its arguments happen to have.
     */
    @Test
    void shouldStateTheElementTypeOfABoundArgumentList() throws IOException {
        GeneratedFile nonNull = generate(
            query(
                "DeleteUser",
                QueryType.EXEC,
                List.of(
                    new QueryParameter(1, "id", ColumnType.BIGINT),
                    new QueryParameter(2, "name", ColumnType.VARCHAR)
                )
            )
        );

        assertTrue(
            nonNull.content().contains("Arrays.<Object>asList(id, name)"),
            nonNull.content()
        );

        GeneratedFile nullable = generate(
            query(
                "DeleteUser",
                QueryType.EXEC,
                List.of(
                    new QueryParameter(1, "id", ColumnType.BIGINT),
                    new QueryParameter(2, "name", ColumnType.VARCHAR).asNullable()
                )
            )
        );

        assertTrue(
            nullable.content().contains(
                "Arrays.<@Nullable Object>asList(id, name)"
            ),
            nullable.content()
        );

        assertCompiles(nonNull);
        assertCompiles(nullable);
    }

    /**
     * An {@code :exec} whose bound-argument list passes the limit writes each
     * argument on a line of its own, continued one call level past the line
     * that opens the list, with the closing parenthesis at that line's column.
     */
    @Test
    void shouldWrapTheArgumentListOfALongExec() throws IOException {
        GeneratedFile file = generate(
            new QueryModel(
                "InsertTask",
                QueryType.EXEC,
                "tasks",
                """
                    INSERT INTO tasks (project_id, title, metadata, tags)
                    VALUES (?, ?, ?, ?)
                    """,
                List.of(1, 2, 3, 4),
                List.of(),
                taskParameters(),
                null
            )
        );

        String source = file.content();

        assertTrue(
            source.contains(
                "    public int insertTask(\n"
                    + "        @Nullable Long projectId,\n"
                    + "        @Nullable String title,\n"
                    + "        @Nullable String metadata,\n"
                    + "        @Nullable List<"
                    + "@Nullable String> tags\n"
                    + "    ) {\n"
                    + "        return executor.execute(\n"
                    + "                \"UsersRepository\",\n"
                    + "                \"InsertTask\",\n"
                    + "                \"\"\"\n"
                    + "                INSERT INTO tasks (project_id, title, metadata, tags)\n"
                    + "                VALUES (?, ?, ?, ?)\n"
                    + "                \"\"\",\n"
                    + "                Arrays.<@Nullable Object>asList(\n"
                    + "                        projectId,\n"
                    + "                        title,\n"
                    + "                        new UntypedText(metadata),\n"
                    + "                        new SqlArray(\"varchar\", tags)\n"
                    + "                )\n"
                    + "        );\n"
                    + "    }"
            ),
            source
        );

        assertCompiles(file);
    }

    /** A read's wrapped list keeps the row mapper on the argument after it. */
    @Test
    void shouldWrapTheArgumentListOfALongReadBeforeItsRowMapper() throws IOException {
        GeneratedFile file = generate(
            new QueryModel(
                "FindTask",
                QueryType.ONE,
                "tasks",
                """
                    SELECT id, title
                    FROM tasks
                    WHERE project_id = ?
                      AND title = ?
                      AND metadata = ?
                      AND tags = ?
                    """,
                List.of(1, 2, 3, 4),
                List.of(
                    new QueryColumn("id", ColumnType.BIGINT, false),
                    new QueryColumn("title", ColumnType.VARCHAR, true)
                ),
                taskParameters(),
                null
            )
        );

        String source = file.content();

        assertTrue(
            source.contains(
                "    public FindTaskResult findTask(\n"
                    + "        @Nullable Long projectId,\n"
                    + "        @Nullable String title,\n"
                    + "        @Nullable String metadata,\n"
                    + "        @Nullable List<"
                    + "@Nullable String> tags\n"
                    + "    ) {\n"
                    + "        return executor.queryOne(\n"
                    + "                \"UsersRepository\",\n"
                    + "                \"FindTask\",\n"
                    + "                \"\"\"\n"
                    + "                SELECT id, title\n"
                    + "                FROM tasks\n"
                    + "                WHERE project_id = ?\n"
                    + "                  AND title = ?\n"
                    + "                  AND metadata = ?\n"
                    + "                  AND tags = ?\n"
                    + "                \"\"\",\n"
                    + "                Arrays.<@Nullable Object>asList(\n"
                    + "                        projectId,\n"
                    + "                        title,\n"
                    + "                        new UntypedText(metadata),\n"
                    + "                        new SqlArray(\"varchar\", tags)\n"
                    + "                ),\n"
                    + "                findTaskRowMapper\n"
                    + "        );\n"
                    + "    }"
            ),
            source
        );

        assertCompiles(file);
    }

    /** The parameters both long single executions bind, in logical order. */
    private List<QueryParameter> taskParameters() {
        return List.of(
            new QueryParameter(1, "projectId", ColumnType.BIGINT).asNullable(),
            new QueryParameter(2, "title", ColumnType.VARCHAR).asNullable(),
            new QueryParameter(3, "metadata", ColumnType.JSONB).asNullable(),
            new QueryParameter(4, "tags", ColumnType.VARCHAR, null, true).asNullable()
        );
    }

    /**
     * A batch method wraps its declaration like a single execution and keeps
     * the pipeline of a wrapped row on the lines that open and close its list.
     */
    @Test
    void shouldWrapALongBatchDeclarationAndItsRow() throws IOException {
        GeneratedFile file = generate(
            new QueryModel(
                "InsertScheduledMaintenanceWindowAssignments",
                QueryType.BATCH_EXEC,
                "assignments",
                """
                    INSERT INTO assignments (window_id, assignee_id, starts_at)
                    VALUES (?, ?, ?)
                    """,
                List.of(1, 2, 3),
                List.of(),
                List.of(
                    new QueryParameter(1, "windowId", ColumnType.BIGINT),
                    new QueryParameter(2, "assigneeId", ColumnType.BIGINT),
                    new QueryParameter(3, "startsAt", ColumnType.DATE)
                ),
                null
            )
        );

        String source = file.content();

        assertTrue(
            source.contains(
                "    public int[] insertScheduledMaintenanceWindowAssignments(\n"
                    + "        List<InsertScheduledMaintenanceWindowAssignmentsParams> batch\n"
                    + "    ) {\n"
                    + "        return executor.executeBatch(\n"
                    + "                \"UsersRepository\",\n"
                    + "                \"InsertScheduledMaintenanceWindowAssignments\",\n"
                    + "                \"\"\"\n"
                    + "                INSERT INTO assignments (window_id, assignee_id, starts_at)\n"
                    + "                VALUES (?, ?, ?)\n"
                    + "                \"\"\",\n"
                    + "                batch.stream().map(params -> Arrays.<Object>asList(\n"
                    + "                        params.windowId(),\n"
                    + "                        params.assigneeId(),\n"
                    + "                        params.startsAt()\n"
                    + "                )).toList()\n"
                    + "        );\n"
                    + "    }"
            ),
            source
        );

        assertCompiles(file);
    }

    /**
     * A {@code singletonList} wraps like an {@code Arrays.asList}, and the one
     * argument that alone passes the limit stays on its line, as the one
     * parameter of the declaration does.
     */
    @Test
    void shouldWrapASingletonListPastTheLimit() throws IOException {
        GeneratedFile file = generate(
            new QueryModel(
                "DeleteTasksScheduledAt",
                QueryType.EXEC,
                "tasks",
                """
                    DELETE FROM tasks
                    WHERE duration = ANY(?)
                    """,
                List.of(1),
                List.of(),
                List.of(
                    new QueryParameter(
                        1,
                        "scheduledMaintenanceWindowDurations",
                        ColumnType.INTERVAL,
                        null,
                        true
                    ).asNullable()
                ),
                null
            )
        );

        String source = file.content();

        assertTrue(
            source.contains(
                "    public int deleteTasksScheduledAt("
                    + "@Nullable List<@Nullable SqlInterval> "
                    + "scheduledMaintenanceWindowDurations) {\n"
                    + "        return executor.execute(\n"
                    + "                \"UsersRepository\",\n"
                    + "                \"DeleteTasksScheduledAt\",\n"
                    + "                \"\"\"\n"
                    + "                DELETE FROM tasks\n"
                    + "                WHERE duration = ANY(?)\n"
                    + "                \"\"\",\n"
                    + "                Collections.singletonList(\n"
                    + "                        SqlArray.of(\"interval\", "
                    + "scheduledMaintenanceWindowDurations, SqlInterval::format)\n"
                    + "                )\n"
                    + "        );\n"
                    + "    }"
            ),
            source
        );

        assertCompiles(file);
    }

    /**
     * A value row mapper whose line passes the limit continues its mapping on
     * the next line, one call level past its field.
     */
    @Test
    void shouldWrapAValueRowMapperPastTheLimit() throws IOException {
        GeneratedFile file = generate(
            new QueryModel(
                "ListEveryProjectAndTaskTitle",
                QueryType.MANY,
                "tasks",
                """
                    SELECT title
                    FROM tasks
                    """,
                List.of(),
                List.of(new QueryColumn("title", ColumnType.VARCHAR, false)),
                List.of(),
                null
            )
        );

        String source = file.content();

        assertTrue(
            source.contains(
                "    private static final RowMapper<String> "
                    + "listEveryProjectAndTaskTitleRowMapper =\n"
                    + "            resultSet -> NotNullColumn"
                    + ".getObject(resultSet, 1, String.class);"
            ),
            source
        );

        assertCompiles(file);
    }

    /**
     * A batch query's parameters are the components of its generated record, so
     * a parameter named after an inherited {@link Object} member a record
     * component cannot declare is disambiguated.
     */
    @Test
    void shouldDisambiguateABatchParamsComponentFromAnInheritedMemberName() throws IOException {
        QueryModel query = new QueryModel(
            "InsertUser",
            QueryType.BATCH_EXEC,
            "users",
            """
                INSERT INTO users (hash_code)
                VALUES (?)
                """,
            List.of(1),
            List.of(),
            List.of(new QueryParameter(1, "hash_code", ColumnType.VARCHAR).asNullable()),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(
            source.contains(
                """
                        public record InsertUserParams(
                            @Nullable String hashCode1
                        ) {
                        }
                    """
                    .stripTrailing()
            )
        );

        assertTrue(
            source.contains(
                "batch.stream().map(params -> Collections.singletonList("
                    + "params.hashCode1())).toList()"
            )
        );

        assertCompiles(file);
    }

    /**
     * A Java enum must not take the name of a generated parameter record
     * either, because their compiled class files are one path on a
     * case-insensitive filesystem.
     */
    @Test
    void shouldRejectAnEnumTypeThatCollidesWithAGeneratedParamsRecord() {
        QueryGroupModel group = new QueryGroupModel(
            GROUP,
            List.of(
                new QueryModel(
                    "InsertUser",
                    QueryType.BATCH_EXEC,
                    "users",
                    "INSERT INTO users (setting) VALUES (?)",
                    List.of(1),
                    List.of(),
                    List.of(
                        new QueryParameter(1, "setting", ColumnType.ENUM, "insert_user_params")
                    ),
                    null
                )
            ),
            List.of(new EnumType("insert_user_params", List.of("indoor")))
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> codeGenerator.generate(group)
        );

        assertEquals(
            "Enum type 'insert_user_params' generates InsertUserParams, "
                + "which is equal ignoring case to the generated type InsertUserParams",
            exception.getMessage()
        );
    }

    /** Compiles the generated files of one package together. */
    private void assertCompiles(GeneratedFile... files) throws IOException {
        Path sourceDirectory = tempDir.resolve("generated");
        Path outputDirectory = tempDir.resolve("classes");

        Files.createDirectories(sourceDirectory);
        Files.createDirectories(outputDirectory);

        List<String> arguments = new ArrayList<>(
            List.of(
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                outputDirectory.toString()
            )
        );

        for (GeneratedFile file : files) {
            Path sourceFile = sourceDirectory.resolve(file.path());

            Files.createDirectories(sourceFile.getParent());

            Files.writeString(sourceFile, file.content());

            arguments.add(sourceFile.toString());
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

        assertNotNull(compiler);

        int result = compiler.run(
            null,
            null,
            null,
            arguments.toArray(new String[0])
        );

        assertEquals(0, result);
    }

    /** Compiles one repository together with the row records it returns. */
    private void assertCompilesWithRows(GeneratedFile repository) throws IOException {
        List<GeneratedFile> files = new ArrayList<>();

        files.add(repository);
        files.addAll(codeGenerator.generateRows());

        assertCompiles(files.toArray(new GeneratedFile[0]));
    }

    /**
     * One group holding every supported query kind becomes one repository with
     * one executor field, one constructor, and one method per query.
     */
    @Test
    void shouldGenerateOneRepositoryForEveryQueryOfTheGroup() throws IOException {
        QueryModel createUser = new QueryModel(
            "CreateUser",
            QueryType.ONE,
            "users",
            "INSERT INTO users (name) VALUES (?) RETURNING id, name",
            List.of(1),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(new QueryParameter(1, "name", ColumnType.VARCHAR)),
            null
        );

        QueryModel getUser = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            "SELECT id, name FROM users WHERE id = ?",
            List.of(1),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            null
        );

        QueryModel listUsers = new QueryModel(
            "ListUsers",
            QueryType.MANY,
            "users",
            "SELECT id, birth_date FROM users",
            List.of(),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("birth_date", ColumnType.DATE, true)
            ),
            List.of(),
            null
        );

        QueryModel deleteUser = new QueryModel(
            "DeleteUser",
            QueryType.EXEC,
            "users",
            "DELETE FROM users WHERE id = ?",
            List.of(1),
            List.of(),
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            null
        );

        GeneratedFile file = codeGenerator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(createUser, getUser, listUsers, deleteUser)
            )
        );

        String source = file.content();

        assertEquals(Path.of("generated", "repositories", "UsersRepository.java"), file.path());

        assertEquals(
            1,
            source.lines()
                .filter(line -> line.equals("    private final QueryExecutor executor;"))
                .count()
        );

        assertEquals(
            1,
            source.lines()
                .filter(line -> line.contains("public UsersRepository(QueryExecutor executor)"))
                .count()
        );

        assertTrue(
            source.contains("public CreateUserResult createUser(String name)")
        );
        assertTrue(source.contains("public GetUserResult getUser(Long id)"));
        assertTrue(source.contains("public List<ListUsersResult> listUsers()"));
        assertTrue(source.contains("public int deleteUser(Long id)"));

        assertTrue(source.contains("public record CreateUserResult("));
        assertTrue(source.contains("public record GetUserResult("));
        assertTrue(source.contains("public record ListUsersResult("));
        assertFalse(source.contains("public record DeleteUserResult("));

        assertTrue(source.contains("private static final RowMapper<CreateUserResult> createUserRowMapper"));
        assertTrue(source.contains("private static final RowMapper<GetUserResult> getUserRowMapper"));
        assertTrue(source.contains("private static final RowMapper<ListUsersResult> listUsersRowMapper"));

        assertTrue(source.contains("import java.time.LocalDate;"));

        assertEquals(
            1,
            source.lines()
                .filter(line -> line.equals("import org.sqlcj.runtime.RowMapper;"))
                .count()
        );

        assertTrue(source.indexOf("createUser(") < source.indexOf("getUser("));
        assertTrue(source.indexOf("getUser(") < source.indexOf("listUsers("));
        assertTrue(source.indexOf("listUsers(") < source.indexOf("deleteUser("));

        assertCompiles(file);
    }

    @Test
    void shouldGenerateRepositoryWithoutMethodsForEmptyGroup() throws IOException {
        GeneratedFile file = codeGenerator.generate(
            new QueryGroupModel(GROUP, List.of())
        );

        String source = file.content();

        assertEquals(Path.of("generated", "repositories", "UsersRepository.java"), file.path());
        assertTrue(source.contains("public final class UsersRepository {"));
        assertTrue(source.contains("private final QueryExecutor executor;"));
        assertTrue(source.contains("public UsersRepository(QueryExecutor executor)"));
        assertFalse(source.contains("RowMapper"));
        assertFalse(source.contains("public record"));

        assertCompiles(file);
    }

    /**
     * Every query that returns one complete row of a table returns the
     * package's single row record, which the repository reads through one row
     * mapper generated after the constructor.
     */
    @Test
    void shouldShareOneRowRecordAcrossFullRowQueriesOfOneTable() throws IOException {
        List<QueryColumn> columns = List.of(
            new QueryColumn("id", ColumnType.BIGINT, false),
            new QueryColumn("name", ColumnType.VARCHAR, true),
            new QueryColumn("created_at", ColumnType.TIMESTAMP, true)
        );

        QueryModel createUser = new QueryModel(
            "CreateUser",
            QueryType.ONE,
            "users",
            "INSERT INTO users (name) VALUES (?) RETURNING *",
            List.of(1),
            columns,
            List.of(new QueryParameter(1, "name", ColumnType.VARCHAR)),
            "users"
        );

        QueryModel getUser = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            "SELECT * FROM users WHERE id = ?",
            List.of(1),
            columns,
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            "users"
        );

        QueryModel listUsers = new QueryModel(
            "ListUsers",
            QueryType.MANY,
            "users",
            "SELECT u.* FROM users u",
            List.of(),
            columns,
            List.of(),
            "users"
        );

        GeneratedFile file = codeGenerator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(createUser, getUser, listUsers)
            )
        );

        String source = file.content();

        assertFalse(source.contains("public record"));

        assertEquals(
            1,
            source.lines()
                .filter(line -> line.contains("private static final RowMapper<UsersRow> usersRowMapper"))
                .count()
        );

        assertFalse(source.contains("Result"));

        assertTrue(source.contains("public UsersRow createUser(String name)"));
        assertTrue(source.contains("public UsersRow getUser(Long id)"));
        assertTrue(source.contains("public List<UsersRow> listUsers()"));

        assertEquals(
            3,
            source.lines()
                .filter(line -> line.strip().equals("usersRowMapper"))
                .count()
        );

        assertTrue(source.contains("resultSet.getObject(3, LocalDateTime.class)"));

        assertTrue(
            source.indexOf("public UsersRepository(QueryExecutor executor)") < source
                .indexOf("private static final RowMapper<UsersRow> usersRowMapper")
        );

        List<GeneratedFile> rows = codeGenerator.generateRows();

        assertEquals(1, rows.size());

        GeneratedFile row = rows.get(0);

        assertEquals(Path.of("generated", "rows", "UsersRow.java"), row.path());

        assertEquals(
            """
                // Code generated by sqlcj. DO NOT EDIT.

                package generated.rows;

                import org.jspecify.annotations.NullMarked;
                import org.jspecify.annotations.Nullable;

                import java.time.LocalDateTime;

                /**
                 * Generated by sqlcj.
                 *
                 * <p>Table: users
                 */
                @NullMarked
                public record UsersRow(
                    Long id,
                    @Nullable String name,
                    @Nullable LocalDateTime createdAt
                ) {
                }
                """,
            row.content()
        );

        assertCompiles(file, row);
    }

    @Test
    void shouldGenerateOptionalExecutionForOptionalQuery() throws IOException {
        QueryModel query = new QueryModel(
            "FindUser",
            QueryType.OPTIONAL,
            "users",
            """
                SELECT id, name
                FROM users
                WHERE id = ?
                """,
            List.of(1),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(source.contains("import java.util.Optional;"));
        assertTrue(source.contains("public record FindUserResult("));
        assertTrue(
            source.contains("private static final RowMapper<FindUserResult> findUserRowMapper =")
        );
        assertTrue(
            source.contains("public Optional<FindUserResult> findUser(Long id)")
        );
        assertTrue(source.contains("return executor.queryOptional("));
        assertTrue(source.contains("Collections.singletonList(id)"));
        assertTrue(source.contains("Type: OPTIONAL"));

        assertFalse(source.contains("return executor.queryOne("));
        assertFalse(source.contains("UnsupportedOperationException"));

        assertCompiles(file);
    }

    @Test
    void shouldShareOneRowRecordBetweenOptionalAndOtherFullRowQueries() throws IOException {
        List<QueryColumn> columns = List.of(
            new QueryColumn("id", ColumnType.BIGINT, false),
            new QueryColumn("name", ColumnType.VARCHAR, true)
        );

        QueryModel getUser = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            "SELECT * FROM users WHERE id = ?",
            List.of(1),
            columns,
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            "users"
        );

        QueryModel findUser = new QueryModel(
            "FindUser",
            QueryType.OPTIONAL,
            "users",
            "SELECT * FROM users WHERE id = ?",
            List.of(1),
            columns,
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            "users"
        );

        GeneratedFile file = codeGenerator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(getUser, findUser)
            )
        );

        String source = file.content();

        assertFalse(source.contains("public record"));

        assertEquals(
            1,
            source.lines()
                .filter(line -> line.contains("private static final RowMapper<UsersRow> usersRowMapper"))
                .count()
        );

        assertTrue(source.contains("public UsersRow getUser(Long id)"));
        assertTrue(source.contains("public Optional<UsersRow> findUser(Long id)"));

        assertEquals(
            1,
            codeGenerator.generateRows().size()
        );

        assertCompilesWithRows(file);
    }

    @Test
    void shouldNotImportOptionalWithoutAnOptionalQuery() {
        QueryModel getUser = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            List.of(),
            null
        );

        QueryModel listUsers = new QueryModel(
            "ListUsers",
            QueryType.MANY,
            "users",
            SQL,
            List.of(),
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            List.of(),
            null
        );

        String source = codeGenerator
            .generate(
                new QueryGroupModel(
                    GROUP,
                    List.of(getUser, listUsers)
                )
            )
            .content();

        assertTrue(source.contains("import java.util.List;"));
        assertFalse(source.contains("import java.util.Optional;"));
    }

    /**
     * Every executor call names the generated repository and the query it was
     * generated from, so a runtime failure identifies the method the
     * application called.
     */
    @Test
    void shouldPassRepositoryAndQueryIdentityToEveryExecutorCall() throws IOException {
        QueryModel getUser = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            "SELECT id FROM users WHERE id = ?",
            List.of(1),
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            null
        );

        QueryModel findUser = new QueryModel(
            "FindUser",
            QueryType.OPTIONAL,
            "users",
            "SELECT id FROM users WHERE id = ?",
            List.of(1),
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            null
        );

        QueryModel listUsers = new QueryModel(
            "ListUsers",
            QueryType.MANY,
            "users",
            "SELECT id FROM users",
            List.of(),
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            List.of(),
            null
        );

        QueryModel deleteUser = new QueryModel(
            "DeleteUser",
            QueryType.EXEC,
            "users",
            "DELETE FROM users WHERE id = ?",
            List.of(1),
            List.of(),
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            null
        );

        GeneratedFile file = codeGenerator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(getUser, findUser, listUsers, deleteUser)
            )
        );

        String source = file.content();

        assertTrue(
            source.contains("""
                        return executor.queryOne(
                                "UsersRepository",
                                "GetUser",
                """)
        );

        assertTrue(
            source.contains("""
                        return executor.queryOptional(
                                "UsersRepository",
                                "FindUser",
                """)
        );

        assertTrue(
            source.contains("""
                        return executor.queryMany(
                                "UsersRepository",
                                "ListUsers",
                """)
        );

        assertTrue(
            source.contains("""
                        return executor.execute(
                                "UsersRepository",
                                "DeleteUser",
                """)
        );

        assertCompiles(file);
    }

    /**
     * Every line of a generated method's SQL is written at the column of its
     * opening delimiter, where the other executor arguments start, in a read,
     * an exec, and a batch call alike, and a blank SQL line is written empty.
     */
    @Test
    void shouldWriteSqlAtTheColumnOfItsOpeningDelimiter() throws IOException {
        QueryModel listUsers = new QueryModel(
            "ListUsers",
            QueryType.MANY,
            "users",
            "SELECT id\nFROM users\n\nWHERE id = ?",
            List.of(1),
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            null
        );

        QueryModel deleteUser = new QueryModel(
            "DeleteUser",
            QueryType.EXEC,
            "users",
            "DELETE FROM users\nWHERE id = ?",
            List.of(1),
            List.of(),
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            null
        );

        QueryModel createUsers = new QueryModel(
            "CreateUsers",
            QueryType.BATCH_EXEC,
            "users",
            "INSERT INTO users (name)\nVALUES (?)",
            List.of(1),
            List.of(),
            List.of(new QueryParameter(1, "name", ColumnType.VARCHAR)),
            null
        );

        GeneratedFile file = codeGenerator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(listUsers, deleteUser, createUsers)
            )
        );

        String source = file.content();

        assertTrue(
            source.contains(
                """
                        public List<Long> listUsers(Long id) {
                            Objects.requireNonNull(id, \
                    "Parameter 'id' of query 'ListUsers' in UsersRepository must not be null");

                            return executor.queryMany(
                                    "UsersRepository",
                                    "ListUsers",
                                    \"""
                                    SELECT id
                                    FROM users

                                    WHERE id = ?\""",
                                    Collections.singletonList(id),
                                    listUsersRowMapper
                            );
                        }
                    """
            ),
            source
        );

        assertTrue(
            source.contains(
                """
                        public int deleteUser(Long id) {
                            Objects.requireNonNull(id, \
                    "Parameter 'id' of query 'DeleteUser' in UsersRepository must not be null");

                            return executor.execute(
                                    "UsersRepository",
                                    "DeleteUser",
                                    \"""
                                    DELETE FROM users
                                    WHERE id = ?\""",
                                    Collections.singletonList(id)
                            );
                        }
                    """
            ),
            source
        );

        assertTrue(
            source.contains(
                """
                    public int[] createUsers(List<CreateUsersParams> batch) {
                        return executor.executeBatch(
                                "UsersRepository",
                                "CreateUsers",
                                \"""
                                INSERT INTO users (name)
                                VALUES (?)\""",
                                batch.stream().map(params -> \
                    Collections.singletonList(params.name())).toList()
                        );
                    }
                    """
                    .indent(4)
            ),
            source
        );

        assertTrue(
            source.lines().noneMatch(line -> line.isBlank() && !line.isEmpty()),
            source
        );

        assertCompiles(file);
    }

    /**
     * The Javadoc of every generated type is written directly above the
     * declaration it documents, in the repository, the row record, and the
     * generated enum alike.
     */
    @Test
    void shouldWriteEveryClassJavadocDirectlyAboveItsDeclaration() throws IOException {
        GeneratedFile repository = codeGenerator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(getUsersRow("GetUser"), enumQuery("GetStage")),
                List.of(new EnumType("stage_setting", List.of("indoor")))
            )
        );

        GeneratedFile row = codeGenerator.generateRows().get(0);
        GeneratedFile generatedEnum = codeGenerator.generateEnums().get(0);

        assertTrue(
            repository.content().contains(
                """
                     * <p>Repository: Users
                     */
                    @GeneratedRepository
                    @NullMarked
                    public final class UsersRepository {
                    """
            ),
            repository.content()
        );

        assertTrue(
            row.content().contains(
                """
                     * <p>Table: users
                     */
                    @NullMarked
                    public record UsersRow(
                    """
            ),
            row.content()
        );

        assertTrue(
            generatedEnum.content().contains(
                """
                     * <p>Enum: stage_setting
                     */
                    @NullMarked
                    public enum StageSetting {
                    """
            ),
            generatedEnum.content()
        );

        assertCompiles(repository, row, generatedEnum);
    }

    /**
     * Every repository carries the runtime's marker directly above its
     * declaration, imported by its simple name beside every other type the
     * file names, in two groups separated by one blank line. A row record and
     * a generated enum are no repositories and carry none.
     */
    @Test
    void shouldMarkEveryRepositoryWithTheRuntimeMarker() throws IOException {
        GeneratedFile repository = codeGenerator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(getUsersRow("GetUser"), enumQuery("GetStage")),
                List.of(new EnumType("stage_setting", List.of("indoor")))
            )
        );

        GeneratedFile row = codeGenerator.generateRows().get(0);
        GeneratedFile generatedEnum = codeGenerator.generateEnums().get(0);

        String source = repository.content();

        assertTrue(
            source.contains(
                """
                     * <p>Repository: Users
                     */
                    @GeneratedRepository
                    @NullMarked
                    public final class UsersRepository {
                    """
            ),
            source
        );

        assertEquals(
            List.of(
                "import generated.enums.StageSetting;",
                "import generated.rows.UsersRow;",
                "import org.jspecify.annotations.NullMarked;",
                "import org.jspecify.annotations.Nullable;",
                "import org.sqlcj.runtime.GeneratedRepository;",
                "import org.sqlcj.runtime.NotNullColumn;",
                "import org.sqlcj.runtime.QueryExecutor;",
                "import org.sqlcj.runtime.RowMapper;",
                "import org.sqlcj.runtime.UntypedText;",
                "import java.util.Collections;",
                "import java.util.Objects;"
            ),
            source.lines()
                .filter(line -> line.startsWith("import "))
                .toList()
        );

        assertFalse(row.content().contains("GeneratedRepository"));
        assertFalse(generatedEnum.content().contains("GeneratedRepository"));

        assertCompiles(repository, row, generatedEnum);
    }

    /**
     * Every generated repository, row record, and generated enum states that
     * its declarations are non-null unless annotated, with the mark written in
     * full directly above the declaration and below the repository's own
     * marker. A nested result or parameter record is marked by its repository
     * and carries none of its own.
     */
    @Test
    void shouldMarkEveryGeneratedTypeAsNullMarked() throws IOException {
        GeneratedFile repository = codeGenerator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(getUsersRow("GetUser"), enumQuery("GetStage"), insertUsers()),
                List.of(new EnumType("stage_setting", List.of("indoor")))
            )
        );

        GeneratedFile row = codeGenerator.generateRows().get(0);
        GeneratedFile generatedEnum = codeGenerator.generateEnums().get(0);

        String source = repository.content();

        assertTrue(
            source.contains(
                """
                    @GeneratedRepository
                    @NullMarked
                    public final class UsersRepository {
                    """
            ),
            source
        );

        assertEquals(
            1,
            source.lines()
                .filter(line -> line.contains("@NullMarked"))
                .count(),
            source
        );

        assertTrue(
            row.content().contains(
                """
                    @NullMarked
                    public record UsersRow(
                    """
            ),
            row.content()
        );

        assertTrue(
            generatedEnum.content().contains(
                """
                    @NullMarked
                    public enum StageSetting {
                    """
            ),
            generatedEnum.content()
        );

        assertCompiles(repository, row, generatedEnum);
    }

    /**
     * A nullable {@code BYTEA} states that the array itself may be null, with
     * the annotation before its brackets rather than before its {@code byte}
     * elements, while the non-null value one query returns stays unannotated.
     */
    @Test
    void shouldAnnotateANullableByteArrayBeforeItsBrackets() throws IOException {
        QueryModel listPayloads = new QueryModel(
            "ListPayloads",
            QueryType.MANY,
            "files",
            SQL,
            List.of(1),
            List.of(
                new QueryColumn("payload", ColumnType.BYTEA, true),
                new QueryColumn("checksum", ColumnType.BYTEA, false)
            ),
            List.of(new QueryParameter(1, "payload", ColumnType.BYTEA).asNullable()),
            null
        );

        QueryModel getChecksum = new QueryModel(
            "GetChecksum",
            QueryType.ONE,
            "files",
            SQL,
            List.of(),
            List.of(new QueryColumn("checksum", ColumnType.BYTEA, false)),
            List.of(),
            null
        );

        GeneratedFile file = codeGenerator.generate(
            new QueryGroupModel(GROUP, List.of(listPayloads, getChecksum))
        );

        String source = file.content();

        assertEquals(
            List.of("byte @Nullable [] payload", "byte[] checksum"),
            recordComponents(source, "ListPayloadsResult")
        );

        assertTrue(
            source.contains(
                "public List<ListPayloadsResult> listPayloads("
                    + "byte @Nullable [] payload)"
            ),
            source
        );

        assertTrue(source.contains("public byte[] getChecksum()"), source);

        assertCompiles(file);
    }

    /**
     * The declarations that cannot hold a null stay unannotated: the executor
     * field and the constructor that takes it, a batch method's list and its
     * elements, an {@code Optional}, a {@code List}, an {@code int}, and an
     * {@code int[]} return type, and a generated enum's constructor, label,
     * and constants.
     */
    @Test
    void shouldLeaveTheNonNullGeneratedDeclarationsUnannotated() throws IOException {
        GeneratedFile repository = codeGenerator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(findUsersRow(), getUsersRow("GetUser"), insertUsers(), enumQuery("GetStage")),
                List.of(new EnumType("stage_setting", List.of("indoor")))
            )
        );

        GeneratedFile generatedEnum = codeGenerator.generateEnums().get(0);

        String source = repository.content();

        assertTrue(source.contains("private final QueryExecutor executor;"), source);
        assertTrue(source.contains("public UsersRepository(QueryExecutor executor) {"), source);
        assertTrue(source.contains("public int[] insertUsers(List<InsertUsersParams> batch) {"), source);
        assertTrue(source.contains("public Optional<UsersRow> findUser("), source);
        assertTrue(source.contains("public UsersRow getUser("), source);

        String enumSource = generatedEnum.content();

        assertTrue(enumSource.contains("StageSetting(String label) {"), enumSource);
        assertTrue(enumSource.contains("public String label() {"), enumSource);
        assertTrue(enumSource.contains("INDOOR(\"indoor\");"), enumSource);

        assertCompiles(repository, codeGenerator.generateRows().get(0), generatedEnum);
    }

    /**
     * A generated enum reads back a label that may be a SQL {@code NULL} and
     * returns {@code null} for it, so both the parameter and the result of
     * {@code fromLabel} state that they may be null.
     */
    @Test
    void shouldAnnotateFromLabelOfAGeneratedEnum() throws IOException {
        GeneratedFile repository = generate(
            enumGroup(
                GROUP,
                enumQuery("GetStage"),
                new EnumType("stage_setting", List.of("indoor", "outdoor"))
            )
        );

        GeneratedFile generatedEnum = codeGenerator.generateEnums().get(0);

        assertTrue(
            generatedEnum.content().contains(
                "    public static @Nullable StageSetting fromLabel(@Nullable String label) {"
            ),
            generatedEnum.content()
        );

        assertCompiles(repository, generatedEnum);
    }

    /** A {@code :batchexec} query of one parameter. */
    private QueryModel insertUsers() {
        return new QueryModel(
            "InsertUsers",
            QueryType.BATCH_EXEC,
            "users",
            "INSERT INTO users (name) VALUES (?)",
            List.of(1),
            List.of(),
            List.of(new QueryParameter(1, "name", ColumnType.VARCHAR)),
            null
        );
    }

    /** An {@code :optional} query returning one complete row of a table. */
    private QueryModel findUsersRow() {
        return new QueryModel(
            "FindUser",
            QueryType.OPTIONAL,
            "users",
            "SELECT * FROM users WHERE id = ?",
            List.of(1),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            "users"
        );
    }

    /**
     * A generated repository compiles against only the runtime's classes, the
     * runtime's one JSpecify dependency, and the JDK, as an application that
     * depends on the runtime alone does, and the class it leaves carries the
     * marker at run time.
     */
    @Test
    void shouldCarryTheMarkerWhenCompiledAgainstTheRuntimeAlone() throws Exception {
        GeneratedFile file = generate(
            new QueryModel(
                "FindUser",
                QueryType.OPTIONAL,
                "users",
                "SELECT id, name FROM users WHERE id = ?",
                List.of(1),
                List.of(
                    new QueryColumn("id", ColumnType.BIGINT, false),
                    new QueryColumn("name", ColumnType.VARCHAR, true)
                ),
                List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
                null
            )
        );

        Path sourceDirectory = tempDir.resolve("generated");
        Path outputDirectory = tempDir.resolve("classes");

        Files.createDirectories(sourceDirectory);
        Files.createDirectories(outputDirectory);

        Path sourceFile = sourceDirectory.resolve("UsersRepository.java");

        Files.writeString(sourceFile, file.content());

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

        assertNotNull(compiler);

        assertEquals(
            0,
            compiler.run(
                null,
                null,
                null,
                "-classpath",
                runtimeAloneClasspath(),
                "-d",
                outputDirectory.toString(),
                sourceFile.toString()
            ),
            file.content()
        );

        try (URLClassLoader classLoader = classLoader()) {
            Class<?> repositoryType = Class.forName(
                "generated.repositories.UsersRepository",
                true,
                classLoader
            );

            assertTrue(repositoryType.isAnnotationPresent(GeneratedRepository.class));
        }
    }

    /**
     * The classpath an application that depends on the runtime alone compiles
     * generated sources against: the runtime's own compiled classes and the
     * JSpecify artifact the runtime depends on, whose annotations the
     * generated sources name.
     */
    private String runtimeAloneClasspath() throws URISyntaxException {
        return String.join(
            File.pathSeparator,
            codeSource(QueryExecutor.class),
            codeSource(Nullable.class)
        );
    }

    /** The location one class's own compiled classes are read from. */
    private String codeSource(Class<?> type) throws URISyntaxException {
        return Path.of(
            type.getProtectionDomain().getCodeSource().getLocation().toURI()
        )
            .toString();
    }

    @Test
    void shouldGenerateOneRowRecordPerRowTable() throws IOException {
        QueryModel getUser = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            "SELECT * FROM users WHERE id = ?",
            List.of(1),
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            "users"
        );

        QueryModel getOrder = new QueryModel(
            "GetOrder",
            QueryType.ONE,
            "orders",
            "SELECT * FROM orders WHERE id = ?",
            List.of(1),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("user_id", ColumnType.BIGINT, false)
            ),
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            "orders"
        );

        GeneratedFile file = codeGenerator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(getUser, getOrder)
            )
        );

        String source = file.content();

        assertFalse(source.contains("public record"));
        assertTrue(source.contains("private static final RowMapper<UsersRow> usersRowMapper"));
        assertTrue(source.contains("private static final RowMapper<OrdersRow> ordersRowMapper"));
        assertTrue(source.contains("public UsersRow getUser(Long id)"));
        assertTrue(source.contains("public OrdersRow getOrder(Long id)"));

        List<GeneratedFile> rows = codeGenerator.generateRows();

        assertEquals(
            List.of(
                Path.of("generated", "rows", "UsersRow.java"),
                Path.of("generated", "rows", "OrdersRow.java")
            ),
            rows.stream().map(GeneratedFile::path).toList()
        );

        assertTrue(rows.get(0).content().contains("public record UsersRow("));
        assertTrue(rows.get(1).content().contains("public record OrdersRow("));

        assertCompilesWithRows(file);
    }

    /**
     * A row mapper yields to the mapper of a query that still generates its
     * own, so the two fields stay distinct.
     */
    @Test
    void shouldDisambiguateRowMapperFromQueryRowMapper() throws IOException {
        QueryModel authors = new QueryModel(
            "Authors",
            QueryType.MANY,
            "authors",
            "SELECT id, name FROM authors",
            List.of(),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(),
            null
        );

        QueryModel getAuthor = new QueryModel(
            "GetAuthor",
            QueryType.ONE,
            "authors",
            "SELECT * FROM authors WHERE id = ?",
            List.of(1),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true),
                new QueryColumn("bio", ColumnType.TEXT, true)
            ),
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            "authors"
        );

        GeneratedFile file = codeGenerator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(authors, getAuthor)
            )
        );

        String source = file.content();

        assertTrue(source.contains("private static final RowMapper<AuthorsResult> authorsRowMapper ="));
        assertTrue(source.contains("private static final RowMapper<AuthorsRow> authorsRowMapper1 ="));
        assertTrue(source.contains("public List<AuthorsResult> authors()"));
        assertTrue(source.contains("public AuthorsRow getAuthor(Long id)"));

        assertCompilesWithRows(file);
    }

    @Test
    void shouldRejectRowTypesThatAreEqualIgnoringCase() {
        QueryModel getUserData = new QueryModel(
            "GetUserData",
            QueryType.ONE,
            "user_data",
            SQL,
            List.of(),
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            List.of(),
            "user_data"
        );

        QueryModel fetchUserdata = new QueryModel(
            "FetchUserdata",
            QueryType.ONE,
            "userdata",
            SQL,
            List.of(),
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            List.of(),
            "userdata"
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> codeGenerator.generate(
                new QueryGroupModel(
                    GROUP,
                    List.of(getUserData, fetchUserdata)
                )
            )
        );

        assertEquals(
            "Tables 'user_data' and 'userdata' generate row types that are equal ignoring case: "
                + "UserDataRow and UserdataRow",
            exception.getMessage()
        );
    }

    @Test
    void shouldGenerateNoRowRecordWithoutAFullRowQuery() {
        generate(
            new QueryModel(
                "GetUser",
                QueryType.ONE,
                "users",
                SQL,
                List.of(),
                List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
                List.of(),
                null
            )
        );

        assertEquals(List.of(), codeGenerator.generateRows());
    }

    /**
     * A result that embeds the row of each joined source holds one component
     * per row, typed by the package's row record and read inline from the
     * consecutive positions its columns were selected at. Neither row is the
     * whole result of a query here, so the repository generates no mapper field
     * for either of them.
     */
    @Test
    void shouldGenerateResultRecordEmbeddingTheRowOfEachJoinedSource() throws IOException {
        QueryModel query = new QueryModel(
            "ListUserProfiles",
            QueryType.MANY,
            "users",
            """
                SELECT u.*, p.*
                FROM users u
                JOIN profiles p ON p.user_id = u.id
                """,
            List.of(),
            List.of(usersRowColumn("u"), profilesRowColumn("p")),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertEquals(
            List.of("UsersRow u", "ProfilesRow p"),
            recordComponents(source, "ListUserProfilesResult")
        );

        assertTrue(
            source.contains(
                """
                    private static final RowMapper<ListUserProfilesResult> listUserProfilesRowMapper =
                            resultSet -> new ListUserProfilesResult(
                                    new UsersRow(
                                            NotNullColumn.getObject(resultSet, 1, Long.class),
                                            resultSet.getObject(2, String.class)
                                    ),
                                    new ProfilesRow(
                                            NotNullColumn.getObject(resultSet, 3, Long.class),
                                            NotNullColumn.getObject(resultSet, 4, Long.class),
                                            resultSet.getObject(5, String.class)
                                    )
                            );
                    """
                    .indent(4)
            ),
            source
        );

        assertFalse(source.contains("RowMapper<UsersRow>"));
        assertFalse(source.contains("RowMapper<ProfilesRow>"));

        List<GeneratedFile> rows = codeGenerator.generateRows();

        assertEquals(
            List.of(
                Path.of("generated", "rows", "UsersRow.java"),
                Path.of("generated", "rows", "ProfilesRow.java")
            ),
            rows.stream().map(GeneratedFile::path).toList()
        );

        assertCompilesWithRows(file);
    }

    /**
     * A left-joined source's embedded row is a nullable component, read as
     * {@code null} when every column of its own range reads SQL {@code NULL},
     * so the guarded construction covers exactly that range and the row of the
     * source beside it is built unguarded.
     */
    @Test
    void shouldGenerateGuardedConstructionForALeftJoinedEmbeddedRow() throws IOException {
        QueryModel query = new QueryModel(
            "ListUserProfiles",
            QueryType.MANY,
            "users",
            """
                SELECT u.*, p.*
                FROM users u
                LEFT JOIN profiles p ON p.user_id = u.id
                """,
            List.of(),
            List.of(usersRowColumn("u"), leftJoinedProfilesRowColumn("p")),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertEquals(
            List.of("UsersRow u", "@Nullable ProfilesRow p"),
            recordComponents(source, "ListUserProfilesResult")
        );

        assertTrue(
            source.contains(
                """
                    private static final RowMapper<ListUserProfilesResult> listUserProfilesRowMapper =
                            resultSet -> new ListUserProfilesResult(
                                    new UsersRow(
                                            NotNullColumn.getObject(resultSet, 1, Long.class),
                                            resultSet.getObject(2, String.class)
                                    ),
                                    SqlRow.isNull(resultSet, 3, 5)
                                        ? null
                                        : new ProfilesRow(
                                                NotNullColumn.getObject(resultSet, 3, Long.class),
                                                NotNullColumn.getObject(resultSet, 4, Long.class),
                                                resultSet.getObject(5, String.class)
                                        )
                            );
                    """
                    .indent(4)
            ),
            source
        );

        assertFalse(source.contains("RowMapper<ProfilesRow>"));

        List<GeneratedFile> rows = codeGenerator.generateRows();

        assertEquals(
            List.of(
                Path.of("generated", "rows", "UsersRow.java"),
                Path.of("generated", "rows", "ProfilesRow.java")
            ),
            rows.stream().map(GeneratedFile::path).toList()
        );

        assertTrue(rows.get(1).content().contains("Long userId"), rows.get(1).content());

        assertCompilesWithRows(file);
    }

    /**
     * A lone wildcard of a left-joined source returns a result of its own
     * holding that row as its single nullable component, so the row record is
     * generated without a mapper field and the guard covers the whole
     * projection.
     */
    @Test
    void shouldGenerateResultRecordForALoneLeftJoinedWildcard() throws IOException {
        QueryModel query = new QueryModel(
            "ListProfiles",
            QueryType.MANY,
            "users",
            """
                SELECT p.*
                FROM users u
                LEFT JOIN profiles p ON p.user_id = u.id
                """,
            List.of(),
            List.of(leftJoinedProfilesRowColumn("p")),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertEquals(
            List.of("@Nullable ProfilesRow p"),
            recordComponents(source, "ListProfilesResult")
        );

        assertTrue(
            source.contains(
                """
                    private static final RowMapper<ListProfilesResult> listProfilesRowMapper =
                            resultSet -> new ListProfilesResult(
                                    SqlRow.isNull(resultSet, 1, 3)
                                        ? null
                                        : new ProfilesRow(
                                                NotNullColumn.getObject(resultSet, 1, Long.class),
                                                NotNullColumn.getObject(resultSet, 2, Long.class),
                                                resultSet.getObject(3, String.class)
                                        )
                            );
                    """
                    .indent(4)
            ),
            source
        );

        assertFalse(source.contains("RowMapper<ProfilesRow>"));

        assertEquals(
            List.of(Path.of("generated", "rows", "ProfilesRow.java")),
            codeGenerator.generateRows().stream().map(GeneratedFile::path).toList()
        );

        assertCompilesWithRows(file);
    }

    /**
     * An embedded row's own columns reach the generated package: the row record
     * and the Java enum of its enum column are generated although no query
     * returns that row whole, and the repository imports the JDK types the
     * inline read names.
     */
    @Test
    void shouldGenerateRowRecordAndEnumOnlyForAnEmbeddedRow() throws IOException {
        QueryModel query = new QueryModel(
            "ListStageOwners",
            QueryType.MANY,
            "stages",
            "SELECT s.*, COUNT(*) AS owners FROM stages s GROUP BY s.id, s.setting, s.opened_at",
            List.of(),
            List.of(
                QueryColumn.row(
                    "s",
                    "stages",
                    List.of(
                        new QueryColumn("id", ColumnType.BIGINT, false),
                        new QueryColumn("setting", ColumnType.ENUM, true, "stage_setting"),
                        new QueryColumn("opened_at", ColumnType.TIMESTAMP, true)
                    )
                ),
                new QueryColumn("owners", ColumnType.BIGINT, false)
            ),
            List.of(),
            null
        );

        GeneratedFile repository = codeGenerator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(query),
                List.of(new EnumType("stage_setting", List.of("indoor", "outdoor")))
            )
        );

        String source = repository.content();

        assertEquals(
            List.of("StagesRow s", "Long owners"),
            recordComponents(source, "ListStageOwnersResult")
        );

        assertTrue(source.contains("import java.time.LocalDateTime;"));
        assertTrue(source.contains("StageSetting.fromLabel(resultSet.getString(2))"));
        assertTrue(source.contains("resultSet.getObject(3, LocalDateTime.class)"));
        assertTrue(
            source.contains("NotNullColumn.getObject(resultSet, 4, Long.class)")
        );

        assertFalse(source.contains("RowMapper<StagesRow>"));

        List<GeneratedFile> rows = codeGenerator.generateRows();
        List<GeneratedFile> enums = codeGenerator.generateEnums();

        assertEquals(
            List.of(Path.of("generated", "rows", "StagesRow.java")),
            rows.stream().map(GeneratedFile::path).toList()
        );

        assertEquals(
            List.of(Path.of("generated", "enums", "StageSetting.java")),
            enums.stream().map(GeneratedFile::path).toList()
        );

        assertTrue(rows.get(0).content().contains("StageSetting setting"));

        assertCompiles(repository, rows.get(0), enums.get(0));
    }

    /**
     * An embedded row's component is named after its source's exposed name by
     * the same rules every other component follows, so a count aliased like the
     * source disambiguates both components in selected order.
     */
    @Test
    void shouldDisambiguateAnEmbeddedRowComponentFromAColumnOfTheSameName() throws IOException {
        QueryModel query = new QueryModel(
            "CountUsers",
            QueryType.MANY,
            "users",
            "SELECT users.*, COUNT(*) AS users FROM users GROUP BY users.id, users.name",
            List.of(),
            List.of(
                usersRowColumn("users"),
                new QueryColumn("users", ColumnType.BIGINT, false)
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        assertEquals(
            List.of("UsersRow users1", "Long users2"),
            recordComponents(file.content(), "CountUsersResult")
        );

        assertCompilesWithRows(file);
    }

    /** The embedded row of the {@code users} table under one exposed name. */
    private QueryColumn usersRowColumn(String name) {
        return QueryColumn.row(
            name,
            "users",
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            )
        );
    }

    /** The embedded row of the {@code profiles} table under one exposed name. */
    private QueryColumn profilesRowColumn(String name) {
        return QueryColumn.row(
            name,
            "profiles",
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("user_id", ColumnType.BIGINT, false),
                new QueryColumn("nickname", ColumnType.VARCHAR, true)
            )
        );
    }

    /**
     * The embedded row of the {@code profiles} table under one exposed name,
     * nullable because its source is left-joined, whose own columns keep their
     * declared nullability.
     */
    private QueryColumn leftJoinedProfilesRowColumn(String name) {
        return QueryColumn.row(
            name,
            true,
            "profiles",
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("user_id", ColumnType.BIGINT, false),
                new QueryColumn("nickname", ColumnType.VARCHAR, true)
            )
        );
    }

    /** The components of one generated record, in declared order. */
    private List<String> recordComponents(String source, String recordName) {
        int start = source.indexOf("public record " + recordName + "(");

        assertTrue(start >= 0, source);

        return source.substring(start, source.indexOf(") {", start))
            .lines()
            .skip(1)
            .map(String::strip)
            .filter(component -> !component.isEmpty())
            .map(
                component -> component.endsWith(",")
                    ? component.substring(0, component.length() - 1)
                    : component
            )
            .toList();
    }

    /**
     * Two groups of one package that return the full row of one table share the
     * single row record the package generates for it.
     */
    @Test
    void shouldGenerateOneRowRecordForTwoGroupsReturningTheSameRow() throws IOException {
        GeneratedFile users = codeGenerator.generate(
            new QueryGroupModel(GROUP, List.of(getUsersRow("GetUser")))
        );

        GeneratedFile admins = codeGenerator.generate(
            new QueryGroupModel("Admins", List.of(getUsersRow("GetAdmin")))
        );

        List<GeneratedFile> rows = codeGenerator.generateRows();

        assertEquals(1, rows.size());
        assertEquals(Path.of("generated", "rows", "UsersRow.java"), rows.get(0).path());

        assertTrue(users.content().contains("public UsersRow getUser(Long id)"));
        assertTrue(admins.content().contains("public UsersRow getAdmin(Long id)"));

        assertFalse(users.content().contains("public record"));
        assertFalse(admins.content().contains("public record"));

        assertCompiles(users, admins, rows.get(0));
    }

    /**
     * Two groups that return the same row type of the same table must define it
     * from the same columns, because the package generates one record.
     */
    @Test
    void shouldRejectARowOfAnotherGroupDefinedFromDifferentColumns() {
        codeGenerator.generate(
            new QueryGroupModel(GROUP, List.of(getUsersRow("GetUser")))
        );

        QueryModel getAdmin = new QueryModel(
            "GetAdmin",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, false)
            ),
            List.of(),
            "users"
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> codeGenerator.generate(
                new QueryGroupModel("Admins", List.of(getAdmin))
            )
        );

        assertEquals(
            "Table 'users' differs from its definition in query group 'Users', "
                + "which generates the same row type UsersRow",
            exception.getMessage()
        );
    }

    @Test
    void shouldRejectRowTypesOfTwoGroupsThatAreEqualIgnoringCase() {
        QueryModel getUserData = new QueryModel(
            "GetUserData",
            QueryType.ONE,
            "user_data",
            SQL,
            List.of(),
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            List.of(),
            "user_data"
        );

        QueryModel fetchUserdata = new QueryModel(
            "FetchUserdata",
            QueryType.ONE,
            "userdata",
            SQL,
            List.of(),
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            List.of(),
            "userdata"
        );

        codeGenerator.generate(
            new QueryGroupModel(GROUP, List.of(getUserData))
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> codeGenerator.generate(
                new QueryGroupModel("Admins", List.of(fetchUserdata))
            )
        );

        assertEquals(
            "Tables 'user_data' and 'userdata' generate row types that are equal ignoring case: "
                + "UserDataRow and UserdataRow",
            exception.getMessage()
        );
    }

    /** A {@code :one} query returning the complete row of the table {@code users}. */
    private QueryModel getUsersRow(String queryName) {
        return new QueryModel(
            queryName,
            QueryType.ONE,
            "users",
            "SELECT * FROM users WHERE id = ?",
            List.of(1),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            "users"
        );
    }

    private GeneratedFile generate(QueryModel query) {
        return generate(codeGenerator, query);
    }

    /** Generates the repository of a single-query group. */
    private GeneratedFile generate(CodeGenerator generator, QueryModel query) {
        return generator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(query)
            )
        );
    }

    private QueryModel query(String name, QueryType type, List<QueryParameter> parameters) {
        return new QueryModel(
            name,
            type,
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

    /**
     * An enum column and an enum parameter use the Java enum the package
     * generates for their enum type, by the simple name it imports:
     * the parameter is bound as the label of its constant, wrapped so that
     * PostgreSQL types it from the context of its placeholder, and the column
     * is read from its label at every binding position of its index.
     */
    @Test
    void shouldBindEnumLabelsAndReadEnumColumnsByLabel() throws IOException {
        QueryModel query = new QueryModel(
            "FindStage",
            QueryType.ONE,
            "stages",
            """
                SELECT id, setting, handle
                FROM stages
                WHERE handle = ?
                  AND (setting = ? OR setting = ?)
                """,
            List.of(2, 1, 1),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("setting", ColumnType.ENUM, true, "stage_setting"),
                new QueryColumn("handle", ColumnType.VARCHAR, true)
            ),
            List.of(
                new QueryParameter(1, "setting", ColumnType.ENUM, "stage_setting").asNullable(),
                new QueryParameter(2, "handle", ColumnType.VARCHAR).asNullable()
            ),
            null
        );

        GeneratedFile repository = generate(
            enumGroup(
                GROUP,
                query,
                new EnumType("stage_setting", List.of("indoor", "outdoor"))
            )
        );

        String source = repository.content();

        assertTrue(
            source.contains(
                "    public FindStageResult findStage("
                    + "@Nullable StageSetting setting, @Nullable String handle) {"
            )
        );

        assertTrue(
            source.contains(
                "                Arrays.<@Nullable Object>asList(\n"
                    + "                        handle,\n"
                    + "                        new UntypedText(setting == null ? null : setting.label()),\n"
                    + "                        new UntypedText(setting == null ? null : setting.label())\n"
                    + "                ),"
            )
        );

        assertTrue(source.contains("StageSetting setting"));
        assertTrue(source.contains("StageSetting.fromLabel(resultSet.getString(2))"));
        assertTrue(
            source.contains("NotNullColumn.getObject(resultSet, 1, Long.class)")
        );
        assertTrue(source.contains("resultSet.getObject(3, String.class)"));

        assertFalse(source.contains("import generated.StageSetting;"));

        assertCompiles(repository, codeGenerator.generateEnums().get(0));
    }

    /**
     * The generated enum names one constant per label, in label order, holds
     * the exact labels, and resolves a label back to its constant. A null label
     * reads back as {@code null}, and a label the enum does not hold, which
     * means the database declares one the schema source does not, fails as an
     * {@code UnmappableValueException} naming the enum type and the label and
     * carrying both.
     */
    @Test
    void shouldGenerateCompilableEnumWithLabelLookups() throws Exception {
        GeneratedFile repository = generate(
            enumGroup(
                GROUP,
                enumQuery("GetStage"),
                new EnumType("stage_setting", List.of("indoor", "out door"))
            )
        );

        List<GeneratedFile> enums = codeGenerator.generateEnums();

        assertEquals(1, enums.size());
        assertEquals(Path.of("generated", "enums", "StageSetting.java"), enums.get(0).path());

        assertCompiles(repository, enums.get(0));

        try (URLClassLoader classLoader = classLoader()) {
            Class<?> type = Class.forName("generated.enums.StageSetting", true, classLoader);

            assertTrue(type.isEnum());

            Object[] constants = type.getEnumConstants();

            assertEquals(
                List.of("INDOOR", "OUT_DOOR"),
                Arrays.stream(constants).map(Object::toString).toList()
            );

            Method label = type.getMethod("label");

            assertEquals("indoor", label.invoke(constants[0]));
            assertEquals("out door", label.invoke(constants[1]));

            Method fromLabel = type.getMethod("fromLabel", String.class);

            assertEquals(constants[1], fromLabel.invoke(null, "out door"));
            assertNull(fromLabel.invoke(null, new Object[] { null }));

            InvocationTargetException failure = assertThrows(
                InvocationTargetException.class,
                () -> fromLabel.invoke(null, "covered")
            );

            UnmappableValueException unmappable = assertInstanceOf(
                UnmappableValueException.class,
                failure.getCause()
            );

            assertEquals(
                "Unknown label for enum type stage_setting: covered",
                unmappable.getMessage()
            );

            assertEquals("stage_setting", unmappable.type());
            assertEquals("covered", unmappable.value());
            assertNull(unmappable.getCause());
            assertNull(unmappable.sqlState());
        }
    }

    /** An enum type no column and no parameter uses generates no file. */
    @Test
    void shouldGenerateOnlyTheEnumTypesTheQueriesUse() {
        codeGenerator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(enumQuery("GetStage")),
                List.of(
                    new EnumType("stage_setting", List.of("indoor")),
                    new EnumType("shelf_state", List.of("stocked"))
                )
            )
        );

        assertEquals(
            List.of(Path.of("generated", "enums", "StageSetting.java")),
            codeGenerator.generateEnums().stream()
                .map(GeneratedFile::path)
                .toList()
        );
    }

    /**
     * An enum type name is the upper camel form of its PostgreSQL name, and a
     * constant is the label's words, upper-cased and joined with {@code _},
     * with a leading {@code _} for a constant that would start with a digit.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "stage_setting|indoor|StageSetting|INDOOR",
            "HTTP_state|in progress|HttpState|IN_PROGRESS",
            "stage_setting|in-progress|StageSetting|IN_PROGRESS",
            "stage_setting|InProgress|StageSetting|INPROGRESS",
            "stage_setting|ID|StageSetting|ID",
            "stage_setting|2fast|StageSetting|_2FAST"
        }
    )
    void shouldNameTheGeneratedEnumAndItsConstants(
        String enumName,
        String label,
        String typeName,
        String constant
    ) {
        codeGenerator.generate(
            enumGroup(
                GROUP,
                enumQuery("GetStage", enumName),
                new EnumType(enumName, List.of(label))
            )
        );

        GeneratedFile file = codeGenerator.generateEnums().get(0);

        assertEquals(Path.of("generated", "enums", typeName + ".java"), file.path());
        assertTrue(file.content().contains("public enum " + typeName + " {"));
        assertTrue(file.content().contains(constant + "(\"" + label + "\");"));
    }

    /** A label with no letter and no digit generates no Java constant. */
    @Test
    void shouldRejectALabelWithoutALetterOrDigit() {
        QueryGroupModel group = enumGroup(
            GROUP,
            enumQuery("GetStage"),
            new EnumType("stage_setting", List.of("indoor", "***"))
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> codeGenerator.generate(group)
        );

        assertEquals(
            "Label '***' of enum type 'stage_setting' has no letter or digit to generate a Java constant from",
            exception.getMessage()
        );
    }

    /** Two labels of one enum type must generate two constants. */
    @Test
    void shouldRejectTwoLabelsThatGenerateOneConstant() {
        QueryGroupModel group = enumGroup(
            GROUP,
            enumQuery("GetStage"),
            new EnumType("stage_setting", List.of("in progress", "in-progress"))
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> codeGenerator.generate(group)
        );

        assertEquals(
            "Labels 'in progress' and 'in-progress' of enum type 'stage_setting' generate the same constant IN_PROGRESS",
            exception.getMessage()
        );
    }

    /**
     * Two groups that use one enum type generate one Java enum, so they must
     * define its labels alike.
     */
    @Test
    void shouldRejectTwoGroupsThatDefineOneEnumTypeDifferently() {
        codeGenerator.generate(
            enumGroup(
                "Stages",
                enumQuery("GetStage"),
                new EnumType("stage_setting", List.of("indoor", "outdoor"))
            )
        );

        QueryGroupModel group = enumGroup(
            "Venues",
            enumQuery("GetVenue"),
            new EnumType("stage_setting", List.of("indoor"))
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> codeGenerator.generate(group)
        );

        assertEquals(
            "Enum type 'stage_setting' differs from its definition in query group 'Stages', "
                + "which generates the same enum type StageSetting",
            exception.getMessage()
        );

        assertEquals(1, codeGenerator.generateEnums().size());
    }

    /**
     * Two enum types whose Java enums are equal ignoring case are rejected,
     * because the compiled class files of those types are one path on a
     * case-insensitive filesystem.
     */
    @Test
    void shouldRejectEnumTypesThatAreEqualIgnoringCase() {
        codeGenerator.generate(
            enumGroup(
                "Stages",
                enumQuery("GetStage"),
                new EnumType("stage_setting", List.of("indoor"))
            )
        );

        QueryGroupModel group = enumGroup(
            "Venues",
            enumQuery("GetVenue", "stagesetting"),
            new EnumType("stagesetting", List.of("indoor"))
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> codeGenerator.generate(group)
        );

        assertEquals(
            "Enum types 'stage_setting' and 'stagesetting' generate enum types that are equal ignoring case: "
                + "StageSetting and Stagesetting",
            exception.getMessage()
        );
    }

    /**
     * A Java enum must not take the name of another generated type of the
     * package, in either generation order.
     */
    @Test
    void shouldRejectAnEnumTypeThatCollidesWithAnEarlierGeneratedType() {
        codeGenerator.generate(
            new QueryGroupModel("Users", List.of(getUsersRow("GetUser")))
        );

        QueryGroupModel group = enumGroup(
            "Stages",
            enumQuery("GetStage", "users_row"),
            new EnumType("users_row", List.of("indoor"))
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> codeGenerator.generate(group)
        );

        assertEquals(
            "Enum type 'users_row' generates UsersRow, which is equal ignoring case to the generated type UsersRow",
            exception.getMessage()
        );
    }

    @Test
    void shouldRejectAGeneratedTypeThatCollidesWithAnEarlierEnumType() {
        codeGenerator.generate(
            enumGroup(
                "Stages",
                enumQuery("GetStage", "usersrow"),
                new EnumType("usersrow", List.of("indoor"))
            )
        );

        QueryGroupModel group = new QueryGroupModel("Users", List.of(getUsersRow("GetUser")));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> codeGenerator.generate(group)
        );

        assertEquals(
            "Enum type 'usersrow' generates Usersrow, which is equal ignoring case to the generated type UsersRow",
            exception.getMessage()
        );
    }

    /**
     * An array column and an array parameter are a {@code List} of the
     * element's Java type. The parameter is wrapped in
     * {@code SqlArray} with the element type's PostgreSQL
     * name, imported by its simple name, and the column is read through the
     * same type.
     */
    @ParameterizedTest
    @CsvSource(
        {
            "INTEGER, int4, Integer",
            "BIGINT, int8, Long",
            "SMALLINT, int2, Short",
            "BOOLEAN, bool, Boolean",
            "VARCHAR, varchar, String",
            "TEXT, text, String",
            "DATE, date, LocalDate",
            "TIME, time, LocalTime",
            "TIMESTAMP, timestamp, LocalDateTime",
            "TIMESTAMP_WITH_TIME_ZONE, timestamptz, OffsetDateTime",
            "DECIMAL, numeric, BigDecimal",
            "REAL, float4, Float",
            "DOUBLE_PRECISION, float8, Double",
            "UUID, uuid, UUID",
            "CITEXT, citext, String",
            "INET, inet, String",
            "CIDR, cidr, String",
            "MACADDR, macaddr, String",
            "MACADDR8, macaddr8, String"
        }
    )
    void shouldBindAndReadArrayColumnsPerElementType(
        ColumnType elementType,
        String elementTypeName,
        String javaType
    ) {
        QueryModel query = new QueryModel(
            "FindStage",
            QueryType.ONE,
            "stages",
            SQL,
            List.of(1),
            List.of(new QueryColumn("values", elementType, true, null, true)),
            List.of(new QueryParameter(1, "values", elementType, null, true).asNullable()),
            null
        );

        String source = generate(query).content();

        assertTrue(
            source.contains(
                "    public FindStageResult findStage(@Nullable List<@Nullable %s> values) {"
                    .formatted(javaType)
            )
        );
        assertEquals(
            List.of(
                "@Nullable List<@Nullable %s> values"
                    .formatted(javaType)
            ),
            recordComponents(source, "FindStageResult")
        );

        assertTrue(
            source.contains(
                "Collections.singletonList(new SqlArray(\"%s\", values))"
                    .formatted(elementTypeName)
            )
        );

        assertTrue(
            source.contains(
                "SqlArray.getList(resultSet, 1, %s.class)".formatted(javaType)
            )
        );

        assertTrue(source.contains("import org.sqlcj.runtime.SqlArray;"));
    }

    /**
     * A blank-padded character array parameter is bound with PostgreSQL's own
     * name of that type, {@code bpchar}, because PostgreSQL compares a
     * {@code bpchar} array only with another one, and a varying one keeps
     * {@code varchar}. Both are read as lists of {@code String}.
     */
    @ParameterizedTest
    @CsvSource(
        {
            "true, bpchar",
            "false, varchar"
        }
    )
    void shouldBindBlankPaddedCharacterArraysAsBpchar(
        boolean blankPadded,
        String elementTypeName
    ) {
        QueryModel query = new QueryModel(
            "FindStage",
            QueryType.ONE,
            "stages",
            SQL,
            List.of(1),
            List.of(new QueryColumn("marks", ColumnType.VARCHAR, true, null, true)),
            List.of(
                new QueryParameter(1, "marks", ColumnType.VARCHAR, null, true, blankPadded)
                    .asNullable()
            ),
            null
        );

        String source = generate(query).content();

        assertTrue(
            source.contains(
                "    public FindStageResult findStage("
                    + "@Nullable List<@Nullable String> marks) {"
            )
        );

        assertTrue(
            source.contains(
                "Collections.singletonList(new SqlArray(\"%s\", marks))"
                    .formatted(elementTypeName)
            )
        );

        assertTrue(
            source.contains("SqlArray.getList(resultSet, 1, String.class)")
        );
    }

    /**
     * An array of an enum binds the labels of its constants and reads each
     * label back into a constant, at every binding position of its index. The
     * enum an array element alone names is generated like any other used enum.
     */
    @Test
    void shouldBindEnumArrayLabelsAndReadEnumArrayColumnsByLabel() throws IOException {
        QueryModel query = new QueryModel(
            "FindStage",
            QueryType.ONE,
            "stages",
            """
                SELECT id, past_settings
                FROM stages
                WHERE past_settings = ? OR past_settings = ?
                """,
            List.of(1, 1),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("past_settings", ColumnType.ENUM, true, "stage_setting", true)
            ),
            List.of(
                new QueryParameter(1, "past_settings", ColumnType.ENUM, "stage_setting", true)
                    .asNullable()
            ),
            null
        );

        GeneratedFile repository = generate(
            enumGroup(
                GROUP,
                query,
                new EnumType("stage_setting", List.of("indoor", "outdoor"))
            )
        );

        String source = repository.content();

        assertTrue(
            source.contains(
                "    public FindStageResult findStage("
                    + "@Nullable List<@Nullable StageSetting> pastSettings) {"
            )
        );

        assertTrue(
            source.contains(
                "                Arrays.<Object>asList(\n"
                    + "                        SqlArray.of(\"stage_setting\", pastSettings, StageSetting::label),\n"
                    + "                        SqlArray.of(\"stage_setting\", pastSettings, StageSetting::label)\n"
                    + "                ),"
            )
        );

        assertTrue(
            source.contains(
                "SqlArray.getList(resultSet, 2, String.class, StageSetting::fromLabel)"
            )
        );

        List<GeneratedFile> enums = codeGenerator.generateEnums();

        assertEquals(1, enums.size());
        assertEquals(Path.of("generated", "enums", "StageSetting.java"), enums.get(0).path());

        assertCompiles(repository, enums.get(0));
    }

    /**
     * A row record of an array component imports {@code java.util.List} beside
     * the JDK types of its elements, in the order its components name them.
     */
    @Test
    void shouldImportListForAnArrayRowComponent() throws IOException {
        QueryModel query = new QueryModel(
            "GetStage",
            QueryType.ONE,
            "stages",
            SQL,
            List.of(),
            List.of(
                new QueryColumn("opened_on", ColumnType.DATE, true),
                new QueryColumn("tags", ColumnType.VARCHAR, true, null, true),
                new QueryColumn("closed_on", ColumnType.DATE, true, null, true)
            ),
            List.of(),
            "stages"
        );

        GeneratedFile repository = generate(query);

        GeneratedFile row = codeGenerator.generateRows().get(0);

        assertEquals(
            """
                // Code generated by sqlcj. DO NOT EDIT.

                package generated.rows;

                import org.jspecify.annotations.NullMarked;
                import org.jspecify.annotations.Nullable;

                import java.time.LocalDate;
                import java.util.List;

                /**
                 * Generated by sqlcj.
                 *
                 * <p>Table: stages
                 */
                @NullMarked
                public record StagesRow(
                    @Nullable LocalDate openedOn,
                    @Nullable List<@Nullable String> tags,
                    @Nullable List<@Nullable LocalDate> closedOn
                ) {
                }
                """,
            row.content()
        );

        assertCompiles(repository, row);
    }

    /**
     * Each kind of generated file is written to a subpackage of the configured
     * package, and a repository names every generated type it uses by its
     * simple name, imported from the subpackage that holds it: the row record
     * one query returns whole, the row record another embeds, and the enum its
     * columns read and its parameters bind. Every import outside {@code java.}
     * comes first, sorted by qualified name, then the {@code java.} imports,
     * separated from them by one blank line.
     */
    @Test
    void shouldImportTheRowsAndEnumsOneRepositoryNames() throws IOException {
        GeneratedFile repository = codeGenerator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(getUsersRow("GetUser"), listStageOwners(true)),
                List.of(new EnumType("stage_setting", List.of("indoor", "outdoor")))
            )
        );

        assertEquals(
            Path.of("generated", "repositories", "UsersRepository.java"),
            repository.path()
        );

        assertEquals(
            """
                // Code generated by sqlcj. DO NOT EDIT.

                package generated.repositories;

                import generated.enums.StageSetting;
                import generated.rows.StagesRow;
                import generated.rows.UsersRow;
                import org.jspecify.annotations.NullMarked;
                import org.sqlcj.runtime.GeneratedRepository;
                import org.sqlcj.runtime.NotNullColumn;
                import org.sqlcj.runtime.QueryExecutor;
                import org.sqlcj.runtime.RowMapper;
                import org.sqlcj.runtime.UntypedText;

                import java.util.Collections;
                import java.util.List;
                import java.util.Objects;

                """,
            prologue(repository.content())
        );

        List<GeneratedFile> rows = codeGenerator.generateRows();
        List<GeneratedFile> enums = codeGenerator.generateEnums();

        assertEquals(
            List.of(
                Path.of("generated", "rows", "UsersRow.java"),
                Path.of("generated", "rows", "StagesRow.java")
            ),
            rows.stream().map(GeneratedFile::path).toList()
        );

        assertEquals(
            List.of(Path.of("generated", "enums", "StageSetting.java")),
            enums.stream().map(GeneratedFile::path).toList()
        );

        assertCompiles(repository, rows.get(0), rows.get(1), enums.get(0));
    }

    /**
     * An enum one repository names only through an embedded row's own column is
     * imported all the same, because the repository reads that column inside
     * its own mapper.
     */
    @Test
    void shouldImportTheEnumOfAnEmbeddedRowColumn() throws IOException {
        GeneratedFile repository = codeGenerator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(listStageOwners(false)),
                List.of(new EnumType("stage_setting", List.of("indoor", "outdoor")))
            )
        );

        assertEquals(
            """
                // Code generated by sqlcj. DO NOT EDIT.

                package generated.repositories;

                import generated.enums.StageSetting;
                import generated.rows.StagesRow;
                import org.jspecify.annotations.NullMarked;
                import org.sqlcj.runtime.GeneratedRepository;
                import org.sqlcj.runtime.NotNullColumn;
                import org.sqlcj.runtime.QueryExecutor;
                import org.sqlcj.runtime.RowMapper;

                import java.util.Collections;
                import java.util.List;

                """,
            prologue(repository.content())
        );

        assertCompiles(
            repository,
            codeGenerator.generateRows().get(0),
            codeGenerator.generateEnums().get(0)
        );
    }

    /**
     * A row record is written to the row subpackage and imports the generated
     * enum of its components once, an array component's elements included.
     */
    @Test
    void shouldImportTheEnumOfAScalarAndAnArrayRowComponent() throws IOException {
        QueryModel query = new QueryModel(
            "GetStage",
            QueryType.ONE,
            "stages",
            "SELECT setting, past_settings FROM stages WHERE id = ?",
            List.of(1),
            List.of(
                new QueryColumn("setting", ColumnType.ENUM, true, "stage_setting"),
                new QueryColumn("past_settings", ColumnType.ENUM, true, "stage_setting", true)
            ),
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            "stages"
        );

        GeneratedFile repository = codeGenerator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(query),
                List.of(new EnumType("stage_setting", List.of("indoor", "outdoor")))
            )
        );

        GeneratedFile row = codeGenerator.generateRows().get(0);

        assertEquals(Path.of("generated", "rows", "StagesRow.java"), row.path());

        assertEquals(
            """
                // Code generated by sqlcj. DO NOT EDIT.

                package generated.rows;

                import generated.enums.StageSetting;
                import org.jspecify.annotations.NullMarked;
                import org.jspecify.annotations.Nullable;

                import java.util.List;

                /**
                 * Generated by sqlcj.
                 *
                 * <p>Table: stages
                 */
                @NullMarked
                public record StagesRow(
                    @Nullable StageSetting setting,
                    @Nullable List<@Nullable StageSetting> pastSettings
                ) {
                }
                """,
            row.content()
        );

        assertCompiles(repository, row, codeGenerator.generateEnums().get(0));
    }

    /**
     * A repository that names no generated type imports the runtime, JSpecify,
     * and JDK types it names and no generated type at all.
     */
    @Test
    void shouldImportNoGeneratedTypeForARepositoryNamingNone() throws IOException {
        QueryModel query = new QueryModel(
            "ListUsers",
            QueryType.MANY,
            "users",
            "SELECT id, name FROM users",
            List.of(),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(),
            null
        );

        GeneratedFile repository = generate(query);

        assertEquals(
            Path.of("generated", "repositories", "UsersRepository.java"),
            repository.path()
        );

        assertEquals(
            """
                // Code generated by sqlcj. DO NOT EDIT.

                package generated.repositories;

                import org.jspecify.annotations.NullMarked;
                import org.jspecify.annotations.Nullable;
                import org.sqlcj.runtime.GeneratedRepository;
                import org.sqlcj.runtime.NotNullColumn;
                import org.sqlcj.runtime.QueryExecutor;
                import org.sqlcj.runtime.RowMapper;

                import java.util.Collections;
                import java.util.List;

                """,
            prologue(repository.content())
        );

        assertEquals(List.of(), codeGenerator.generateRows());
        assertEquals(List.of(), codeGenerator.generateEnums());

        assertCompiles(repository);
    }

    /**
     * A generated enum named like a JDK type the same file names keeps its
     * simple name, and the JDK type is written fully qualified in that file
     * alone: the repository's {@code Optional} return type, its
     * {@code Objects.requireNonNull} check, and the row record's
     * {@code BigDecimal} component, with the nullable annotation before the
     * simple-name segment. Every file compiles.
     */
    @Test
    void shouldQualifyAJdkTypeAnEnumOfTheSameSimpleNameTakes() throws IOException {
        QueryModel findReading = new QueryModel(
            "FindReading",
            QueryType.OPTIONAL,
            "readings",
            "SELECT id, total, scale, slot, bucket FROM readings WHERE id = ?",
            List.of(1),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("total", ColumnType.DECIMAL, true),
                new QueryColumn("scale", ColumnType.ENUM, true, "big_decimal"),
                new QueryColumn("slot", ColumnType.ENUM, true, "optional"),
                new QueryColumn("bucket", ColumnType.ENUM, false, "objects")
            ),
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            "readings"
        );

        GeneratedFile repository = codeGenerator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(findReading),
                List.of(
                    new EnumType("big_decimal", List.of("small")),
                    new EnumType("optional", List.of("maybe")),
                    new EnumType("objects", List.of("many"))
                )
            )
        );

        String source = repository.content();

        assertTrue(
            source.contains("public java.util.Optional<ReadingsRow> findReading(Long id) {"),
            source
        );

        assertTrue(
            source.contains(
                "        java.util.Objects.requireNonNull(\n"
                    + "                id,\n"
                    + "                \"Parameter 'id' of query 'FindReading' in UsersRepository"
                    + " must not be null\"\n"
                    + "        );"
            ),
            source
        );

        assertTrue(
            source.contains("resultSet.getObject(2, java.math.BigDecimal.class)"),
            source
        );

        assertEquals(
            List.of(
                "import generated.enums.BigDecimal;",
                "import generated.enums.Objects;",
                "import generated.enums.Optional;",
                "import generated.rows.ReadingsRow;",
                "import org.jspecify.annotations.NullMarked;",
                "import org.sqlcj.runtime.GeneratedRepository;",
                "import org.sqlcj.runtime.NotNullColumn;",
                "import org.sqlcj.runtime.QueryExecutor;",
                "import org.sqlcj.runtime.RowMapper;",
                "import java.util.Collections;"
            ),
            source.lines()
                .filter(line -> line.startsWith("import "))
                .toList()
        );

        GeneratedFile row = codeGenerator.generateRows().get(0);

        assertEquals(
            List.of(
                "Long id",
                "java.math.@Nullable BigDecimal total",
                "@Nullable BigDecimal scale",
                "@Nullable Optional slot",
                "Objects bucket"
            ),
            recordComponents(row.content(), "ReadingsRow")
        );

        List<GeneratedFile> enums = codeGenerator.generateEnums();

        assertEquals(3, enums.size());

        List<GeneratedFile> files = new ArrayList<>();

        files.add(repository);
        files.add(row);
        files.addAll(enums);

        assertCompiles(files.toArray(new GeneratedFile[0]));
    }

    /**
     * A generated enum named {@code Nullable} or {@code NullMarked} keeps its
     * simple name, and the JSpecify annotation of the same simple name is
     * written fully qualified in every file that declares or names that enum.
     * A file that neither declares nor names it imports the annotation by its
     * simple name, which shadows the enum of that name in its own package.
     */
    @Test
    void shouldQualifyAJSpecifyAnnotationAnEnumOfTheSameSimpleNameTakes() throws IOException {
        QueryModel getFlag = new QueryModel(
            "GetFlag",
            QueryType.ONE,
            "flags",
            "SELECT state, mark FROM flags WHERE state = ?",
            List.of(1),
            List.of(
                new QueryColumn("state", ColumnType.ENUM, true, "nullable"),
                new QueryColumn("mark", ColumnType.ENUM, false, "null_marked")
            ),
            List.of(new QueryParameter(1, "state", ColumnType.ENUM, "nullable")),
            "flags"
        );

        GeneratedFile repository = codeGenerator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(getFlag),
                List.of(
                    new EnumType("nullable", List.of("maybe")),
                    new EnumType("null_marked", List.of("marked"))
                )
            )
        );

        GeneratedFile row = codeGenerator.generateRows().get(0);
        List<GeneratedFile> enums = codeGenerator.generateEnums();

        assertEquals(
            List.of(
                "@org.jspecify.annotations.Nullable Nullable state",
                "NullMarked mark"
            ),
            recordComponents(row.content(), "FlagsRow")
        );

        assertTrue(
            enums.get(0).content().contains("@NullMarked\npublic enum Nullable {"),
            enums.get(0).content()
        );

        assertTrue(
            enums.get(0).content().contains(
                "    public static @org.jspecify.annotations.Nullable Nullable fromLabel(\n"
                    + "        @org.jspecify.annotations.Nullable String label\n"
                    + "    ) {"
            ),
            enums.get(0).content()
        );

        assertTrue(
            enums.get(1).content().contains(
                "@org.jspecify.annotations.NullMarked\npublic enum NullMarked {"
            ),
            enums.get(1).content()
        );

        assertTrue(
            enums.get(1).content().contains(
                "    public static @Nullable NullMarked fromLabel(@Nullable String label) {"
            ),
            enums.get(1).content()
        );

        List<GeneratedFile> files = new ArrayList<>();

        files.add(repository);
        files.add(row);
        files.addAll(enums);

        assertCompiles(files.toArray(new GeneratedFile[0]));
    }

    /**
     * A generated enum named {@code String} is declared in the package every
     * other generated enum belongs to, which shadows {@code java.lang.String}
     * there because {@code java.lang} is never imported, so each of them writes
     * its label type in full. A row record and a repository that name the enum
     * write their own text components in full for the same reason.
     */
    @Test
    void shouldQualifyJavaLangStringBesideAGeneratedEnumOfThatName() throws IOException {
        QueryModel getStage = new QueryModel(
            "GetStage",
            QueryType.ONE,
            "stages",
            "SELECT handle, kind, setting FROM stages WHERE handle = ?",
            List.of(1),
            List.of(
                new QueryColumn("handle", ColumnType.VARCHAR, true),
                new QueryColumn("kind", ColumnType.ENUM, true, "string"),
                new QueryColumn("setting", ColumnType.ENUM, false, "stage_setting")
            ),
            List.of(new QueryParameter(1, "handle", ColumnType.VARCHAR)),
            "stages"
        );

        GeneratedFile repository = codeGenerator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(getStage),
                List.of(
                    new EnumType("string", List.of("text")),
                    new EnumType("stage_setting", List.of("indoor"))
                )
            )
        );

        GeneratedFile row = codeGenerator.generateRows().get(0);
        List<GeneratedFile> enums = codeGenerator.generateEnums();

        assertTrue(
            repository.content().contains("public StagesRow getStage(java.lang.String handle) {"),
            repository.content()
        );

        assertEquals(
            List.of(
                "java.lang.@Nullable String handle",
                "@Nullable String kind",
                "StageSetting setting"
            ),
            recordComponents(row.content(), "StagesRow")
        );

        assertTrue(
            enums.get(0).content().contains("public enum String {"),
            enums.get(0).content()
        );

        assertTrue(
            enums.get(0).content().contains("    private final java.lang.String label;"),
            enums.get(0).content()
        );

        assertTrue(
            enums.get(0).content().contains("    String(java.lang.String label) {"),
            enums.get(0).content()
        );

        assertTrue(
            enums.get(1).content().contains("    private final java.lang.String label;"),
            enums.get(1).content()
        );

        List<GeneratedFile> files = new ArrayList<>();

        files.add(repository);
        files.add(row);
        files.addAll(enums);

        assertCompiles(files.toArray(new GeneratedFile[0]));
    }

    /**
     * A table named {@code sql} generates the row record {@code SqlRow}, which
     * takes the simple name of the runtime class a nullable embedded row is
     * read through, so the repository calls that class in full.
     */
    @Test
    void shouldQualifyTheRuntimeClassARowRecordOfThatNameTakes() throws IOException {
        QueryModel listSql = new QueryModel(
            "ListSql",
            QueryType.MANY,
            "runs",
            "SELECT s.*, COUNT(*) AS runs FROM runs LEFT JOIN sql s ON s.id = runs.sql_id",
            List.of(),
            List.of(
                QueryColumn.row(
                    "s",
                    true,
                    "sql",
                    List.of(
                        new QueryColumn("id", ColumnType.BIGINT, false),
                        new QueryColumn("text", ColumnType.TEXT, true)
                    )
                ),
                new QueryColumn("runs", ColumnType.BIGINT, false)
            ),
            List.of(),
            null
        );

        GeneratedFile repository = codeGenerator.generate(
            new QueryGroupModel(GROUP, List.of(listSql))
        );

        String source = repository.content();

        assertTrue(source.contains("import generated.rows.SqlRow;"), source);
        assertFalse(source.contains("import org.sqlcj.runtime.SqlRow;"), source);

        assertTrue(
            source.contains("org.sqlcj.runtime.SqlRow.isNull(resultSet, 1, 2)"),
            source
        );

        assertEquals(
            List.of("@Nullable SqlRow s", "Long runs"),
            recordComponents(source, "ListSqlResult")
        );

        assertCompiles(repository, codeGenerator.generateRows().get(0));
    }

    /**
     * A query group named {@code Generated} declares the repository
     * {@code GeneratedRepository}, which takes the simple name of the runtime
     * marker, so the file writes the marker in full and imports it nowhere.
     */
    @Test
    void shouldQualifyTheRepositoryMarkerARepositoryOfThatNameTakes() throws IOException {
        GeneratedFile repository = codeGenerator.generate(
            new QueryGroupModel("Generated", List.of(getUsersRow("GetUser")))
        );

        String source = repository.content();

        assertEquals(
            Path.of("generated", "repositories", "GeneratedRepository.java"),
            repository.path()
        );

        assertTrue(
            source.contains(
                "@org.sqlcj.runtime.GeneratedRepository\n"
                    + "@NullMarked\n"
                    + "public final class GeneratedRepository {"
            ),
            source
        );

        assertFalse(source.contains("import org.sqlcj.runtime.GeneratedRepository;"), source);

        assertCompiles(repository, codeGenerator.generateRows().get(0));
    }

    /**
     * The text of one generated file before its first declaration, which is its
     * notice, its package declaration, and its import block.
     */
    private String prologue(String source) {
        int declaration = source.indexOf("/**");

        assertTrue(declaration > 0, source);

        return source.substring(0, declaration);
    }

    /**
     * A {@code :many} query embedding the complete {@code stages} row beside a
     * count, optionally binding the enum of that row's own column as well.
     */
    private QueryModel listStageOwners(boolean bindsTheEnum) {
        return new QueryModel(
            "ListStageOwners",
            QueryType.MANY,
            "stages",
            "SELECT s.*, COUNT(*) AS owners FROM stages s GROUP BY s.id, s.setting",
            bindsTheEnum ? List.of(1) : List.of(),
            List.of(
                QueryColumn.row(
                    "s",
                    "stages",
                    List.of(
                        new QueryColumn("id", ColumnType.BIGINT, false),
                        new QueryColumn("setting", ColumnType.ENUM, true, "stage_setting")
                    )
                ),
                new QueryColumn("owners", ColumnType.BIGINT, false)
            ),
            bindsTheEnum
                ? List.of(new QueryParameter(1, "setting", ColumnType.ENUM, "stage_setting"))
                : List.of(),
            null
        );
    }

    /** A {@code :one} query reading and binding one enum column. */
    private QueryModel enumQuery(String queryName) {
        return enumQuery(queryName, "stage_setting");
    }

    private QueryModel enumQuery(String queryName, String enumName) {
        return new QueryModel(
            queryName,
            QueryType.ONE,
            "stages",
            "SELECT setting FROM stages WHERE setting = ?",
            List.of(1),
            List.of(new QueryColumn("setting", ColumnType.ENUM, true, enumName)),
            List.of(new QueryParameter(1, "setting", ColumnType.ENUM, enumName)),
            null
        );
    }

    private QueryGroupModel enumGroup(String groupName, QueryModel query, EnumType enumType) {
        return new QueryGroupModel(groupName, List.of(query), List.of(enumType));
    }

    private GeneratedFile generate(QueryGroupModel group) {
        return codeGenerator.generate(group);
    }

    /** Loads the classes the compiled generated files left. */
    private URLClassLoader classLoader() throws IOException {
        return new URLClassLoader(
            new URL[] { tempDir.resolve("classes").toUri().toURL() },
            getClass().getClassLoader()
        );
    }

    /**
     * An executor that counts the batches a generated method ran and records
     * the argument list of every execution and of every row of every batch.
     */
    private static final class RecordingQueryExecutor implements QueryExecutor {

        private final List<List<?>> parameters = new ArrayList<>();

        private final List<List<?>> batchParameters = new ArrayList<>();

        private int batches;

        @Override
        public <T> T queryOne(
            String repository,
            String query,
            String sql,
            List<?> parameters,
            RowMapper<T> mapper
        ) {
            return null;
        }

        @Override
        public <T> Optional<T> queryOptional(
            String repository,
            String query,
            String sql,
            List<?> parameters,
            RowMapper<T> mapper
        ) {
            return Optional.empty();
        }

        @Override
        public <T> List<T> queryMany(
            String repository,
            String query,
            String sql,
            List<?> parameters,
            RowMapper<T> mapper
        ) {
            return List.of();
        }

        @Override
        public int execute(String repository, String query, String sql, List<?> parameters) {
            this.parameters.add(parameters);

            return 0;
        }

        @Override
        public int[] executeBatch(
            String repository,
            String query,
            String sql,
            List<? extends List<?>> batch
        ) {
            batches++;
            batchParameters.addAll(batch);

            return new int[batch.size()];
        }
    }
}
