package org.sqlcj.generator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.sqlcj.analysis.QueryColumn;
import org.sqlcj.analysis.QueryGroupModel;
import org.sqlcj.analysis.QueryModel;
import org.sqlcj.analysis.QueryParameter;
import org.sqlcj.parser.QueryType;
import org.sqlcj.runtime.QueryExecutor;
import org.sqlcj.runtime.RowMapper;
import org.sqlcj.schema.ColumnType;
import org.sqlcj.schema.EnumType;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
            Path.of("generated", "UsersRepository.java"),
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
                "Long id, String name"
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
                "// Code generated by sqlcj. DO NOT EDIT.\n\npackage generated;"
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
                "// Code generated by sqlcj. DO NOT EDIT.\n\npackage dev.example.generated;\n"
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
            Path.of("dev", "example", "generated", "UsersRepository.java"),
            file.path()
        );

        assertTrue(
            file.content().startsWith(
                "// Code generated by sqlcj. DO NOT EDIT.\n\npackage dev.example.generated;"
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
                "public List<ListUsersByIdAndNameResult> listUsersByIdAndName(Long id, String name)"
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

    @Test
    void shouldGenerateListImportForSingleResultWithoutParameters() {
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

        assertTrue(source.contains("import java.util.List;"));
        assertTrue(source.contains("java.util.Arrays.asList()"));
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
                "Long userId, Boolean enabled"
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

        assertTrue(source.contains("resultSet.getObject(1, Long.class)"));
        assertTrue(source.contains("resultSet.getObject(2, Long.class)"));
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
                    new QueryParameter(1, "value", columnType)
                )
            )
        );

        assertTrue(
            file.content().contains(
                expectedJavaType + " value"
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
     * row mapper reads the value itself.
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
                "private static final RowMapper<Long> countUsersRowMapper = "
                    + "resultSet -> resultSet.getObject(1, Long.class);"
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
                "private static final RowMapper<UUID> findUserExternalIdRowMapper = "
                    + "resultSet -> resultSet.getObject(1, UUID.class);"
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
                "private static final RowMapper<String> listUserNamesRowMapper = "
                    + "resultSet -> resultSet.getObject(1, String.class);"
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
                "private static final RowMapper<Long> insertUserRowMapper = "
                    + "resultSet -> resultSet.getObject(1, Long.class);"
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
                "private static final RowMapper<StageSetting> getStageSettingRowMapper = "
                    + "resultSet -> StageSetting.fromLabel(resultSet.getString(1));"
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

        assertTrue(source.contains("public List<String> getUserTags(Long id)"), source);

        assertTrue(
            source.contains(
                "private static final RowMapper<List<String>> getUserTagsRowMapper = "
                    + "resultSet -> org.sqlcj.runtime.SqlArray.getList(resultSet, 1, String.class);"
            ),
            source
        );

        assertFalse(source.contains("public record"), source);

        assertCompiles(file);
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

        assertTrue(source.contains("public GetUserNameResult getUserName(Long id)"), source);

        assertEquals(
            List.of("String name"),
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
                ),
                new QueryParameter(
                    2,
                    "createdAt",
                    ColumnType.TIMESTAMP_WITH_TIME_ZONE
                )
            ),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(source.contains("import java.util.UUID;"));
        assertTrue(source.contains("import java.time.OffsetDateTime;"));

        assertTrue(
            source.contains(
                "public GetUserResult getUser(UUID externalId, OffsetDateTime createdAt)"
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
        assertTrue(source.contains("java.util.Arrays.asList(id)"));
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
        assertTrue(source.contains("byte[] payload"));
        assertTrue(source.contains("LocalTime openedAt"));
        assertTrue(source.contains("resultSet.getObject(3, byte[].class)"));

        assertEquals(0, compile(file, "UsersRepository.java"));
    }

    /**
     * A JSON parameter is bound through {@code org.sqlcj.runtime.UntypedText}
     * at every position its placeholder index is bound at, and a JSON result
     * column is read as text. The other {@code String} types are bound and read
     * exactly as before, and the wrapper is written out in full, so the
     * generated imports are unchanged.
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
                new QueryParameter(1, "metadata", ColumnType.JSONB),
                new QueryParameter(2, "profile", ColumnType.JSON),
                new QueryParameter(3, "name", ColumnType.VARCHAR),
                new QueryParameter(4, "bio", ColumnType.TEXT)
            ),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(
            source.contains(
                "public FindDocumentResult findDocument("
                    + "String metadata, String profile, String name, String bio)"
            )
        );

        assertTrue(
            source.contains(
                "java.util.Arrays.asList("
                    + "name, "
                    + "new org.sqlcj.runtime.UntypedText(metadata), "
                    + "new org.sqlcj.runtime.UntypedText(metadata), "
                    + "bio, "
                    + "new org.sqlcj.runtime.UntypedText(profile))"
            )
        );

        assertTrue(source.contains("resultSet.getObject(1, Long.class)"));
        assertTrue(source.contains("resultSet.getString(2)"));
        assertTrue(source.contains("resultSet.getString(3)"));
        assertTrue(source.contains("resultSet.getObject(4, String.class)"));

        assertFalse(source.contains("import org.sqlcj.runtime.UntypedText;"));

        assertCompiles(file);
    }

    /**
     * A {@code citext} and a network address parameter are bound through
     * {@code org.sqlcj.runtime.UntypedText}, and a column of either is read as
     * text, exactly as JSON is, because the driver reports each of them as a
     * type of its own that is not read as a {@code String}. An array of either
     * is bound as {@code org.sqlcj.runtime.SqlArray} of the type's PostgreSQL
     * name and read as a list of {@code String}. The generated source compiles,
     * and both wrappers are written out in full, so the imports are unchanged.
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
                new QueryParameter(1, "handle", ColumnType.CITEXT),
                new QueryParameter(2, "address", ColumnType.INET),
                new QueryParameter(3, "network", ColumnType.CIDR),
                new QueryParameter(4, "mac", ColumnType.MACADDR),
                new QueryParameter(5, "mac8", ColumnType.MACADDR8),
                new QueryParameter(6, "handles", ColumnType.CITEXT, null, true),
                new QueryParameter(7, "bio", ColumnType.TEXT)
            ),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(
            source.contains(
                "public FindHostResult findHost("
                    + "String handle, String address, String network, String mac, String mac8,"
                    + " List<String> handles, String bio)"
            )
        );

        assertTrue(
            source.contains(
                "java.util.Arrays.asList("
                    + "new org.sqlcj.runtime.UntypedText(handle), "
                    + "new org.sqlcj.runtime.UntypedText(address), "
                    + "new org.sqlcj.runtime.UntypedText(network), "
                    + "new org.sqlcj.runtime.UntypedText(mac), "
                    + "new org.sqlcj.runtime.UntypedText(mac8), "
                    + "new org.sqlcj.runtime.SqlArray(\"citext\", handles), "
                    + "bio)"
            )
        );

        assertTrue(source.contains("resultSet.getObject(1, Long.class)"));
        assertTrue(source.contains("resultSet.getString(2)"));
        assertTrue(source.contains("resultSet.getString(3)"));
        assertTrue(source.contains("resultSet.getString(4)"));
        assertTrue(source.contains("resultSet.getString(5)"));
        assertTrue(source.contains("resultSet.getString(6)"));
        assertTrue(source.contains("org.sqlcj.runtime.SqlArray.getList(resultSet, 7, String.class)"));
        assertTrue(source.contains("resultSet.getObject(8, String.class)"));

        assertFalse(source.contains("import org.sqlcj.runtime.UntypedText;"));
        assertFalse(source.contains("import org.sqlcj.runtime.SqlArray;"));

        assertCompiles(file);
    }

    /**
     * An {@code INTERVAL} parameter is an {@code org.sqlcj.runtime.SqlInterval}
     * bound through {@code org.sqlcj.runtime.UntypedText} as the text the value
     * writes, and an array of one binds the text of each element; a column of
     * either is read back by parsing PostgreSQL's own text, which the driver
     * reports as a type of its own rather than as a character type. The Java
     * type is imported as a {@code java.time} type is, while the runtime
     * wrappers are written out in full, and the generated source compiles.
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
                new QueryParameter(1, "age", ColumnType.INTERVAL),
                new QueryParameter(2, "ages", ColumnType.INTERVAL, null, true)
            ),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(
            source.contains(
                "public FindDurationResult findDuration("
                    + "SqlInterval age, List<SqlInterval> ages)"
            )
        );

        assertTrue(
            source.contains(
                "java.util.Arrays.asList("
                    + "new org.sqlcj.runtime.UntypedText(age == null ? null : age.format()), "
                    + "org.sqlcj.runtime.SqlArray.of("
                    + "\"interval\", ages, org.sqlcj.runtime.SqlInterval::format))"
            )
        );

        assertTrue(source.contains("resultSet.getObject(1, Long.class)"));

        assertTrue(
            source.contains("org.sqlcj.runtime.SqlInterval.parse(resultSet.getString(2))")
        );

        assertTrue(
            source.contains(
                "org.sqlcj.runtime.SqlArray.getList("
                    + "resultSet, 3, String.class, org.sqlcj.runtime.SqlInterval::parse)"
            )
        );

        assertTrue(source.contains("import org.sqlcj.runtime.SqlInterval;"));

        assertFalse(source.contains("import org.sqlcj.runtime.UntypedText;"));
        assertFalse(source.contains("import org.sqlcj.runtime.SqlArray;"));

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

        assertTrue(source.contains("String metadata, String profile"));
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
                expectedJavaType + " value"
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
                "public List<FindUsersResult> findUsers(Long id1, Long id2)"
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
                "resultSet.getObject(1, Long.class)"
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

        int idIndex = source.indexOf("resultSet.getObject(2, Long.class)");

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
                "resultSet.getObject(1, Long.class)"
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
                "java.util.Arrays.asList(id)"
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
                "java.util.Arrays.asList(active)"
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
                "java.util.Arrays.asList(active, id, name)"
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
                "public FindUserResult findUser(Long id, Boolean active)"
            )
        );

        assertTrue(source.contains("WHERE active = ?"));
        assertTrue(source.contains("AND id = ?"));
        assertTrue(source.contains("java.util.Arrays.asList(active, id)"));

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
                "public FindUserResult findUser(Long id, String name)"
            )
        );

        assertTrue(source.contains("java.util.Arrays.asList(name, id, id)"));

        assertCompiles(file);
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

        assertTrue(source.contains("import java.util.List;"));
        assertTrue(source.contains("java.util.Arrays.asList()"));

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
        assertTrue(source.contains("java.util.Arrays.asList(id, name)"));

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
                "batch.stream().map(params -> java.util.Arrays.asList("
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
                "batch.stream().map(java.util.Objects::requireNonNull)"
                    + ".map(params -> java.util.Arrays.asList()).toList()"
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
                "generated.UsersRepository",
                true,
                classLoader
            );

            RecordingQueryExecutor executor = new RecordingQueryExecutor();

            Object repository = repositoryType
                .getConstructor(QueryExecutor.class)
                .newInstance(executor);

            Class<?> paramsType = Class.forName(
                "generated.UsersRepository$TouchUsersParams",
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
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR),
                new QueryParameter(3, "id", ColumnType.BIGINT)
            ),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(
            source.contains(
                """
                        public record InsertUsersParams(
                            Long id1,
                            String name,
                            Long id2
                        ) {
                        }
                    """
                    .stripTrailing()
            )
        );

        assertTrue(
            source.contains(
                "batch.stream().map(params -> java.util.Arrays.asList("
                    + "params.id1(), params.name(), params.id2(), params.name())).toList()"
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
                new QueryParameter(1, "setting", ColumnType.ENUM, "stage_setting"),
                new QueryParameter(2, "payload", ColumnType.JSONB),
                new QueryParameter(3, "span", ColumnType.INTERVAL),
                new QueryParameter(4, "tags", ColumnType.VARCHAR, null, true)
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
                            StageSetting setting,
                            String payload,
                            SqlInterval span,
                            List<String> tags
                        ) {
                        }
                    """
                    .stripTrailing()
            )
        );

        assertTrue(
            source.contains(
                "batch.stream().map(params -> java.util.Arrays.asList("
                    + "new org.sqlcj.runtime.UntypedText("
                    + "params.setting() == null ? null : params.setting().label()), "
                    + "new org.sqlcj.runtime.UntypedText(params.payload()), "
                    + "new org.sqlcj.runtime.UntypedText("
                    + "params.span() == null ? null : params.span().format()), "
                    + "new org.sqlcj.runtime.SqlArray(\"varchar\", params.tags()))).toList()"
            )
        );

        List<GeneratedFile> files = new ArrayList<>();

        files.add(file);
        files.addAll(codeGenerator.generateEnums());

        assertCompiles(files.toArray(new GeneratedFile[0]));
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
            List.of(new QueryParameter(1, "hash_code", ColumnType.VARCHAR)),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(
            source.contains(
                """
                        public record InsertUserParams(
                            String hashCode1
                        ) {
                        }
                    """
                    .stripTrailing()
            )
        );

        assertTrue(
            source.contains(
                "batch.stream().map(params -> java.util.Arrays.asList("
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

        assertEquals(Path.of("generated", "UsersRepository.java"), file.path());

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

        assertTrue(source.contains("public CreateUserResult createUser(String name)"));
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

        assertEquals(Path.of("generated", "UsersRepository.java"), file.path());
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

        assertEquals(Path.of("generated", "UsersRow.java"), row.path());

        assertEquals(
            """
                // Code generated by sqlcj. DO NOT EDIT.

                package generated;

                import java.time.LocalDateTime;

                /**
                 * Generated by sqlcj.
                 *
                 * Table: users
                 */
                public record UsersRow(
                    Long id,
                    String name,
                    LocalDateTime createdAt
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
        assertTrue(source.contains("public Optional<FindUserResult> findUser(Long id)"));
        assertTrue(source.contains("return executor.queryOptional("));
        assertTrue(source.contains("java.util.Arrays.asList(id)"));
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
                            return executor.queryMany(
                                    "UsersRepository",
                                    "ListUsers",
                                    \"""
                                    SELECT id
                                    FROM users

                                    WHERE id = ?\""",
                                    java.util.Arrays.asList(id),
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
                        return executor.execute(
                                "UsersRepository",
                                "DeleteUser",
                                \"""
                                DELETE FROM users
                                WHERE id = ?\""",
                                java.util.Arrays.asList(id)
                        );
                    }
                    """
                    .indent(4)
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
                    java.util.Arrays.asList(params.name())).toList()
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
                     * Repository: Users
                     */
                    public final class UsersRepository {
                    """
            ),
            repository.content()
        );

        assertTrue(
            row.content().contains(
                """
                     * Table: users
                     */
                    public record UsersRow(
                    """
            ),
            row.content()
        );

        assertTrue(
            generatedEnum.content().contains(
                """
                     * Enum: stage_setting
                     */
                    public enum StageSetting {
                    """
            ),
            generatedEnum.content()
        );

        assertCompiles(repository, row, generatedEnum);
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
                Path.of("generated", "UsersRow.java"),
                Path.of("generated", "OrdersRow.java")
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
                                            resultSet.getObject(1, Long.class),
                                            resultSet.getObject(2, String.class)
                                    ),
                                    new ProfilesRow(
                                            resultSet.getObject(3, Long.class),
                                            resultSet.getObject(4, Long.class),
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
                Path.of("generated", "UsersRow.java"),
                Path.of("generated", "ProfilesRow.java")
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
            List.of("UsersRow u", "ProfilesRow p"),
            recordComponents(source, "ListUserProfilesResult")
        );

        assertTrue(
            source.contains(
                """
                    private static final RowMapper<ListUserProfilesResult> listUserProfilesRowMapper =
                            resultSet -> new ListUserProfilesResult(
                                    new UsersRow(
                                            resultSet.getObject(1, Long.class),
                                            resultSet.getObject(2, String.class)
                                    ),
                                    org.sqlcj.runtime.SqlRow.isNull(resultSet, 3, 5)
                                        ? null
                                        : new ProfilesRow(
                                                resultSet.getObject(3, Long.class),
                                                resultSet.getObject(4, Long.class),
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
                Path.of("generated", "UsersRow.java"),
                Path.of("generated", "ProfilesRow.java")
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
            List.of("ProfilesRow p"),
            recordComponents(source, "ListProfilesResult")
        );

        assertTrue(
            source.contains(
                """
                    private static final RowMapper<ListProfilesResult> listProfilesRowMapper =
                            resultSet -> new ListProfilesResult(
                                    org.sqlcj.runtime.SqlRow.isNull(resultSet, 1, 3)
                                        ? null
                                        : new ProfilesRow(
                                                resultSet.getObject(1, Long.class),
                                                resultSet.getObject(2, Long.class),
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
            List.of(Path.of("generated", "ProfilesRow.java")),
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
        assertTrue(source.contains("resultSet.getObject(4, Long.class)"));

        assertFalse(source.contains("RowMapper<StagesRow>"));

        List<GeneratedFile> rows = codeGenerator.generateRows();
        List<GeneratedFile> enums = codeGenerator.generateEnums();

        assertEquals(
            List.of(Path.of("generated", "StagesRow.java")),
            rows.stream().map(GeneratedFile::path).toList()
        );

        assertEquals(
            List.of(Path.of("generated", "StageSetting.java")),
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
        assertEquals(Path.of("generated", "UsersRow.java"), rows.get(0).path());

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
     * generates for their enum type, by its simple name and without an import:
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
                new QueryParameter(1, "setting", ColumnType.ENUM, "stage_setting"),
                new QueryParameter(2, "handle", ColumnType.VARCHAR)
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
            source.contains("public FindStageResult findStage(StageSetting setting, String handle)")
        );

        assertTrue(
            source.contains(
                "java.util.Arrays.asList("
                    + "handle, "
                    + "new org.sqlcj.runtime.UntypedText(setting == null ? null : setting.label()), "
                    + "new org.sqlcj.runtime.UntypedText(setting == null ? null : setting.label()))"
            )
        );

        assertTrue(source.contains("StageSetting setting"));
        assertTrue(source.contains("StageSetting.fromLabel(resultSet.getString(2))"));
        assertTrue(source.contains("resultSet.getObject(1, Long.class)"));
        assertTrue(source.contains("resultSet.getObject(3, String.class)"));

        assertFalse(source.contains("import generated.StageSetting;"));

        assertCompiles(repository, codeGenerator.generateEnums().get(0));
    }

    /**
     * The generated enum names one constant per label, in label order, holds
     * the exact labels, and resolves a label back to its constant. A null label
     * reads back as {@code null}, and a label the enum does not hold, which
     * means the database declares one the schema source does not, fails naming
     * the enum type and the label.
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
        assertEquals(Path.of("generated", "StageSetting.java"), enums.get(0).path());

        assertCompiles(repository, enums.get(0));

        try (URLClassLoader classLoader = classLoader()) {
            Class<?> type = Class.forName("generated.StageSetting", true, classLoader);

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

            assertInstanceOf(IllegalArgumentException.class, failure.getCause());

            assertEquals(
                "Unknown label for enum type stage_setting: covered",
                failure.getCause().getMessage()
            );
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
            List.of(Path.of("generated", "StageSetting.java")),
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

        assertEquals(Path.of("generated", typeName + ".java"), file.path());
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
     * {@code org.sqlcj.runtime.SqlArray} with the element type's PostgreSQL
     * name, written out in full so that the generated imports are unchanged,
     * and the column is read through the same type.
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
            List.of(new QueryParameter(1, "values", elementType, null, true)),
            null
        );

        String source = generate(query).content();

        assertTrue(source.contains("public FindStageResult findStage(List<%s> values)".formatted(javaType)));
        assertTrue(source.contains("List<%s> values".formatted(javaType)));

        assertTrue(
            source.contains(
                "java.util.Arrays.asList(new org.sqlcj.runtime.SqlArray(\"%s\", values))"
                    .formatted(elementTypeName)
            )
        );

        assertTrue(
            source.contains(
                "org.sqlcj.runtime.SqlArray.getList(resultSet, 1, %s.class)".formatted(javaType)
            )
        );

        assertFalse(source.contains("import org.sqlcj.runtime.SqlArray;"));
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
            ),
            null
        );

        String source = generate(query).content();

        assertTrue(source.contains("public FindStageResult findStage(List<String> marks)"));

        assertTrue(
            source.contains(
                "java.util.Arrays.asList(new org.sqlcj.runtime.SqlArray(\"%s\", marks))"
                    .formatted(elementTypeName)
            )
        );

        assertTrue(
            source.contains("org.sqlcj.runtime.SqlArray.getList(resultSet, 1, String.class)")
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
                "public FindStageResult findStage(List<StageSetting> pastSettings)"
            )
        );

        assertTrue(
            source.contains(
                "java.util.Arrays.asList("
                    + "org.sqlcj.runtime.SqlArray.of(\"stage_setting\", pastSettings, StageSetting::label), "
                    + "org.sqlcj.runtime.SqlArray.of(\"stage_setting\", pastSettings, StageSetting::label))"
            )
        );

        assertTrue(
            source.contains(
                "org.sqlcj.runtime.SqlArray.getList(resultSet, 2, String.class, StageSetting::fromLabel)"
            )
        );

        List<GeneratedFile> enums = codeGenerator.generateEnums();

        assertEquals(1, enums.size());
        assertEquals(Path.of("generated", "StageSetting.java"), enums.get(0).path());

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

                package generated;

                import java.time.LocalDate;
                import java.util.List;

                /**
                 * Generated by sqlcj.
                 *
                 * Table: stages
                 */
                public record StagesRow(
                    LocalDate openedOn,
                    List<String> tags,
                    List<LocalDate> closedOn
                ) {
                }
                """,
            row.content()
        );

        assertCompiles(repository, row);
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

    /** An executor that counts the batches a generated method ran. */
    private static final class RecordingQueryExecutor implements QueryExecutor {

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

            return new int[batch.size()];
        }
    }
}
