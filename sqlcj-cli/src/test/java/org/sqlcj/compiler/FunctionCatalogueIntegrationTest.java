package org.sqlcj.compiler;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.sqlcj.config.Config;
import org.sqlcj.config.JavaConfig;
import org.sqlcj.config.SqlConfig;
import org.sqlcj.parser.DefaultQueryParser;
import org.sqlcj.parser.Query;
import org.sqlcj.runtime.JdbcQueryExecutor;
import org.sqlcj.runtime.SqlInterval;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Proves that every row and form of the function catalogue compiles without a
 * cast and carries the type and the nullness PostgreSQL 16 returns: the
 * fixture's queries generate a repository that compiles, each result's Java
 * type and nullness is asserted, the driver reports for each result column the
 * type sqlcj analyzed, every query runs against a migrated PostgreSQL 16
 * database, and each nullable form reads {@code null} for the one row whose
 * columns are {@code NULL}.
 *
 * <p>The whole class is skipped when Docker is unavailable, so that
 * {@code mvn test} stays runnable without Docker. Setting
 * {@code -Dsqlcj.test.requireDocker=true}, as continuous integration does,
 * turns an unavailable Docker into a failure instead of a skip.
 */
@EnabledIf("dockerAvailable")
class FunctionCatalogueIntegrationTest {

    /**
     * System property that forbids skipping these tests. Continuous
     * integration sets it so that a broken Docker environment fails the build
     * instead of silently reducing coverage.
     */
    private static final String REQUIRE_DOCKER_PROPERTY = "sqlcj.test.requireDocker";

    /** The configured query-group name, which generates its repository. */
    private static final String GROUP = "FunctionCatalogue";

    /** The schema file inside the database container. */
    private static final String CONTAINER_SCHEMA = "/tmp/function-catalogue-schema.sql";

    /**
     * The result of each query of the fixture: its component name in Java, the
     * generated Java type, whether it is nullable, the alias the statement
     * selects it under, and the type name the driver reports for it.
     */
    private static final Map<String, List<ExpectedColumn>> EXPECTED_RESULTS = expectedResults();

    private static PostgreSQLContainer<?> postgres;

    private static DataSource dataSource;

    @TempDir
    static Path tempDir;

    /** The compiled output of the fixture, generated once for every test. */
    private static Path classesDirectory;

    static boolean dockerAvailable() {
        boolean available = DockerClientFactory.instance().isDockerAvailable();

        if (!available && Boolean.getBoolean(REQUIRE_DOCKER_PROPERTY)) {
            throw new IllegalStateException(
                "Docker is unavailable, but " + REQUIRE_DOCKER_PROPERTY + " requires these tests to run."
            );
        }

        return available;
    }

    /**
     * Applies the fixture's schema to a real PostgreSQL 16 server, writes the
     * three rows the queries read — one of full values, one whose every
     * nullable column is {@code NULL}, and one of full values again — and
     * generates and compiles the repository of the fixture's queries.
     */
    @BeforeAll
    static void migrateAndGenerate() throws Exception {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

        postgres.start();

        PGSimpleDataSource catalogueDataSource = new PGSimpleDataSource();

        catalogueDataSource.setUrl(postgres.getJdbcUrl());
        catalogueDataSource.setUser(postgres.getUsername());
        catalogueDataSource.setPassword(postgres.getPassword());

        dataSource = catalogueDataSource;

        postgres.copyFileToContainer(MountableFile.forHostPath(schema()), CONTAINER_SCHEMA);

        Container.ExecResult applied = postgres.execInContainer(
            "psql",
            "-X",
            "-v",
            "ON_ERROR_STOP=1",
            "-U",
            postgres.getUsername(),
            "-d",
            postgres.getDatabaseName(),
            "-f",
            CONTAINER_SCHEMA
        );

        assertEquals(0, applied.getExitCode(), applied.getStderr());

        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement insert = connection.prepareStatement(
                """
                    INSERT INTO events
                        (id, label, nickname, code, score, visits, total, amount,
                         at_day, at_local, at_zoned, span)
                    VALUES (1, 'Alpha', 'Ally', 'AA  ', 1, 10, 100, 1.50,
                            '2026-01-02', '2026-01-02 10:30', '2026-01-02 10:30+00', '25 hours'),
                           (2, 'Beta', NULL, NULL, NULL, 20, NULL, NULL,
                            NULL, NULL, '2026-01-03 10:30+00', NULL),
                           (3, 'Gamma', 'Gam', 'CC  ', 3, 30, 300, 3.50,
                            '2026-01-04', '2026-01-04 10:30', '2026-01-04 10:30+00', '2 hours')"""
            )
        ) {
            assertEquals(3, insert.executeUpdate());
        }

        Path generatedDirectory = tempDir.resolve("generated");

        generate(generatedDirectory);

        classesDirectory = compile(generatedDirectory, tempDir.resolve("classes"));
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    /**
     * Every result column of the catalogue's queries carries the Java type and
     * the nullness the catalogue states, and a single non-null column is
     * returned as its own value rather than as a record.
     */
    @Test
    void shouldGenerateTheAnalyzedJavaTypeAndNullnessOfEachResult() throws Exception {
        try (URLClassLoader classLoader = classLoader()) {
            Object repository = newRepository(classLoader);

            for (Map.Entry<String, List<ExpectedColumn>> expected : EXPECTED_RESULTS.entrySet()) {
                String queryName = expected.getKey();
                Method method = method(repository, methodName(queryName));
                Class<?> result = resultType(method);
                List<ExpectedColumn> columns = expected.getValue();

                if (!result.isRecord()) {
                    assertEquals(1, columns.size(), queryName);

                    assertEquals(
                        columns.get(0).javaType(),
                        result.getSimpleName(),
                        queryName + " returns its single non-null column itself"
                    );

                    assertFalse(columns.get(0).nullable(), queryName);

                    continue;
                }

                assertEquals(
                    columns.stream()
                        .map(column -> column.name() + " " + column.javaType() + nullness(column))
                        .toList(),
                    components(result),
                    queryName + " result components"
                );
            }
        }
    }

    /**
     * PostgreSQL 16 reports for each result column of each query the type sqlcj
     * analyzed, so the catalogue states no type the database does not return.
     */
    @Test
    void shouldReadTheAnalyzedTypeFromPostgresForEachResultColumn() throws Exception {
        Map<String, String> statements = queryStatements();

        assertEquals(
            EXPECTED_RESULTS.keySet(),
            statements.keySet(),
            "the fixture holds exactly the expected queries"
        );

        try (Connection connection = dataSource.getConnection()) {
            for (Map.Entry<String, String> statement : statements.entrySet()) {
                String queryName = statement.getKey();
                List<ExpectedColumn> columns = EXPECTED_RESULTS.get(queryName);

                assertEquals(
                    columns.stream()
                        .map(column -> column.alias() + " " + column.postgresType())
                        .toList(),
                    postgresColumns(connection, statement.getValue()),
                    queryName + " result columns as PostgreSQL returns them"
                );
            }
        }
    }

    /**
     * Every query of the catalogue runs through the generated repository, and
     * each nullable form reads {@code null} for the one row whose columns are
     * {@code NULL} while the non-null forms read a value for every row.
     */
    @Test
    void shouldExecuteEachCatalogueQuery() throws Exception {
        try (URLClassLoader classLoader = classLoader()) {
            Object repository = newRepository(classLoader);

            assertNotNull(invoke(repository, "selectNow"), "now() is never null");

            assertNotNull(
                invoke(repository, "selectGeneratedId"),
                "gen_random_uuid() is never null"
            );

            Long number = (Long) invoke(repository, "nextEventNumber");

            assertEquals(
                number + 1L,
                invoke(repository, "nextEventNumber"),
                "a second sequence read advances the sequence by one"
            );

            assertEquals(true, invoke(repository, "tryLockEvent", 7L));
            assertEquals(true, invoke(repository, "tryLockEventPair", 7, 8));

            assertEquals(List.of(1L, 2L, 3L), invoke(repository, "numberEvents"));

            assertEquals(
                List.of("1 1", "2 2", "3 3"),
                rows(repository, "rankEvents", "ranked", "denselyRanked")
            );

            assertEquals(
                List.of(
                    "alpha ALPHA ally Alpha AA",
                    "beta BETA null Beta null",
                    "gamma GAMMA gam Gamma CC"
                ),
                rows(
                    repository,
                    "listEventNames",
                    "lowered",
                    "uppered",
                    "loweredNickname",
                    "trimmed",
                    "trimmedCode"
                )
            );

            assertEquals(
                List.of("2026-01-02T00:00", "null", "2026-01-04T00:00"),
                rows(repository, "listEventDays", "localDay"),
                "date_trunc of a timestamp without a time zone truncates it as written"
            );

            assertEquals(
                List.of("set set set", "set null null", "set set set"),
                presence(
                    repository,
                    "listEventDays",
                    "zonedDay",
                    "calendarDay",
                    "zonedLocalDay"
                ),
                "a date_trunc of a time-zoned value reads null exactly where its value is NULL"
            );

            List<?> days = (List<?>) invoke(repository, "listEventDays");

            assertEquals(
                Duration.ofHours(25),
                ((SqlInterval) component(days.get(0), "truncatedSpan")).toDuration(),
                "date_trunc of an interval truncates the interval itself"
            );

            assertNull(
                component(days.get(1), "truncatedSpan"),
                "a date_trunc of a NULL interval reads null"
            );

            assertEquals(
                List.of("1 1 10 1", "0 null 20 20", "3 3 30 3"),
                rows(
                    repository,
                    "listEventTotals",
                    "scoreOrZero",
                    "scoreOrTotal",
                    "highest",
                    "lowest"
                )
            );

            assertEquals(
                List.of("null null 0 1 1", "1 3 1 null null", "null null null 3 3"),
                rows(
                    repository,
                    "listEventTrends",
                    "previousScore",
                    "nextScore",
                    "previousOrZero",
                    "firstScore",
                    "lastScore"
                )
            );
        }
    }

    /**
     * The result each query of the fixture returns, in the order the fixture
     * writes its queries and its columns.
     */
    private static Map<String, List<ExpectedColumn>> expectedResults() {
        Map<String, List<ExpectedColumn>> results = new LinkedHashMap<>();

        results.put(
            "SelectNow",
            List.of(new ExpectedColumn("readAt", "OffsetDateTime", false, "read_at", "timestamptz"))
        );

        results.put(
            "SelectGeneratedId",
            List.of(new ExpectedColumn("generatedId", "UUID", false, "generated_id", "uuid"))
        );

        results.put(
            "NextEventNumber",
            List.of(new ExpectedColumn("nextNumber", "Long", false, "next_number", "int8"))
        );

        results.put(
            "TryLockEvent",
            List.of(new ExpectedColumn("locked", "Boolean", false, "locked", "bool"))
        );

        results.put(
            "TryLockEventPair",
            List.of(new ExpectedColumn("locked", "Boolean", false, "locked", "bool"))
        );

        results.put(
            "NumberEvents",
            List.of(new ExpectedColumn("rn", "Long", false, "rn", "int8"))
        );

        results.put(
            "RankEvents",
            List.of(
                new ExpectedColumn("ranked", "Long", false, "ranked", "int8"),
                new ExpectedColumn("denselyRanked", "Long", false, "densely_ranked", "int8")
            )
        );

        results.put(
            "ListEventNames",
            List.of(
                new ExpectedColumn("lowered", "String", false, "lowered", "text"),
                new ExpectedColumn("uppered", "String", false, "uppered", "text"),
                new ExpectedColumn("loweredNickname", "String", true, "lowered_nickname", "text"),
                new ExpectedColumn("trimmed", "String", false, "trimmed", "text"),
                new ExpectedColumn("trimmedCode", "String", true, "trimmed_code", "text")
            )
        );

        results.put(
            "ListEventDays",
            List.of(
                new ExpectedColumn("localDay", "LocalDateTime", true, "local_day", "timestamp"),
                new ExpectedColumn("zonedDay", "OffsetDateTime", false, "zoned_day", "timestamptz"),
                new ExpectedColumn(
                    "calendarDay",
                    "OffsetDateTime",
                    true,
                    "calendar_day",
                    "timestamptz"
                ),
                new ExpectedColumn("truncatedSpan", "SqlInterval", true, "truncated_span", "interval"),
                new ExpectedColumn(
                    "zonedLocalDay",
                    "OffsetDateTime",
                    true,
                    "zoned_local_day",
                    "timestamptz"
                )
            )
        );

        results.put(
            "ListEventTotals",
            List.of(
                new ExpectedColumn("scoreOrZero", "Integer", false, "score_or_zero", "int4"),
                new ExpectedColumn("scoreOrTotal", "Long", true, "score_or_total", "int8"),
                new ExpectedColumn("highest", "Integer", false, "highest", "int4"),
                new ExpectedColumn("lowest", "Integer", false, "lowest", "int4")
            )
        );

        results.put(
            "ListEventTrends",
            List.of(
                new ExpectedColumn("previousScore", "Short", true, "previous_score", "int2"),
                new ExpectedColumn("nextScore", "Short", true, "next_score", "int2"),
                new ExpectedColumn("previousOrZero", "Integer", true, "previous_or_zero", "int4"),
                new ExpectedColumn("firstScore", "Short", true, "first_score", "int2"),
                new ExpectedColumn("lastScore", "Short", true, "last_score", "int2")
            )
        );

        return results;
    }

    /**
     * The statement of each query of the fixture, in query order, written with
     * the {@code ?} the executed statement binds in place of each placeholder.
     */
    private Map<String, String> queryStatements() throws Exception {
        Map<String, String> statements = new LinkedHashMap<>();

        for (Query query : new DefaultQueryParser().parse(Files.readString(queries())).queries()) {
            statements.put(
                query.name(),
                query.sql()
                    .replace("$1", "?")
                    .replace("$2", "?")
            );
        }

        return statements;
    }

    /**
     * The result columns PostgreSQL returns for one statement, each as its
     * label and the type name the driver reports, read by executing the
     * statement with a key of {@code 1} bound at every placeholder.
     */
    private List<String> postgresColumns(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int parameter = 1; parameter <= placeholders(sql); parameter++) {
                statement.setInt(parameter, 1);
            }

            try (ResultSet rows = statement.executeQuery()) {
                ResultSetMetaData metaData = rows.getMetaData();
                List<String> columns = new ArrayList<>();

                for (int column = 1; column <= metaData.getColumnCount(); column++) {
                    columns.add(
                        metaData.getColumnLabel(column) + " " + metaData.getColumnTypeName(column)
                    );
                }

                return columns;
            }
        }
    }

    private int placeholders(String sql) {
        return (int) sql.chars()
            .filter(character -> character == '?')
            .count();
    }

    /** The components of one generated result record, each with its nullness. */
    private List<String> components(Class<?> recordClass) {
        return Arrays.stream(recordClass.getRecordComponents())
            .map(
                component -> component.getName()
                    + " "
                    + component.getType().getSimpleName()
                    + (nullable(component) ? " nullable" : " non-null")
            )
            .toList();
    }

    private boolean nullable(RecordComponent component) {
        AnnotatedType type = component.getAnnotatedType();

        return type.getAnnotation(Nullable.class) != null;
    }

    private String nullness(ExpectedColumn column) {
        return column.nullable()
            ? " nullable"
            : " non-null";
    }

    /**
     * The result type one generated method returns, which is the type argument
     * of a {@code List} or an {@code Optional} and the returned type itself
     * otherwise.
     */
    private Class<?> resultType(Method method) {
        Type returned = method.getGenericReturnType();

        return returned instanceof ParameterizedType parameterized
            ? (Class<?>) parameterized.getActualTypeArguments()[0]
            : method.getReturnType();
    }

    /** The generated method name of one query, which lowercases its first letter. */
    private String methodName(String queryName) {
        return Character.toLowerCase(queryName.charAt(0)) + queryName.substring(1);
    }

    /**
     * The named components of every row one generated read returns, each row
     * written as its component values separated by a space, so that a
     * {@code null} is read as such.
     */
    private List<String> rows(
        Object repository,
        String methodName,
        String... componentNames
    ) throws Exception {
        List<String> rows = new ArrayList<>();

        for (Object row : (List<?>) invoke(repository, methodName)) {
            List<String> values = new ArrayList<>();

            for (String componentName : componentNames) {
                values.add(String.valueOf(component(row, componentName)));
            }

            rows.add(String.join(" ", values));
        }

        return rows;
    }

    /**
     * Whether each named component of every row one generated read returns
     * holds a value, which reads a result whose value depends on the session's
     * own time zone.
     */
    private List<String> presence(
        Object repository,
        String methodName,
        String... componentNames
    ) throws Exception {
        List<String> rows = new ArrayList<>();

        for (Object row : (List<?>) invoke(repository, methodName)) {
            List<String> values = new ArrayList<>();

            for (String componentName : componentNames) {
                values.add(
                    component(row, componentName) == null
                        ? "null"
                        : "set"
                );
            }

            rows.add(String.join(" ", values));
        }

        return rows;
    }

    /** Generates the one repository of the fixture's query group. */
    private static void generate(Path generatedDirectory) throws Exception {
        Config config = new Config(
            List.of(
                new SqlConfig(
                    GROUP,
                    List.of(schema().toString()),
                    queries().toString()
                )
            ),
            new JavaConfig(generatedDirectory.toString(), "generated")
        );

        new SqlcjCompiler().compile(config, queries().resolveSibling("sqlcj.yaml"));

        assertTrue(
            Files.exists(
                generatedDirectory.resolve("generated/repositories/" + GROUP + "Repository.java")
            )
        );
    }

    /** Compiles every generated source of the output directory together. */
    private static Path compile(Path generatedDirectory, Path classesDirectory) throws Exception {
        Files.createDirectories(classesDirectory);

        JavaCompiler compilerApi = ToolProvider.getSystemJavaCompiler();

        assertNotNull(compilerApi);

        List<String> arguments = new ArrayList<>(
            List.of(
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                classesDirectory.toString()
            )
        );

        try (Stream<Path> generatedFiles = Files.walk(generatedDirectory)) {
            generatedFiles
                .map(Path::toString)
                .filter(path -> path.endsWith(".java"))
                .sorted()
                .forEach(arguments::add);
        }

        assertEquals(0, compilerApi.run(null, null, null, arguments.toArray(new String[0])));

        return classesDirectory;
    }

    private static Path schema() throws Exception {
        return resource("schema.sql");
    }

    private static Path queries() throws Exception {
        return resource("queries.sql");
    }

    /** One fixture of the function catalogue, resolved from the test classpath. */
    private static Path resource(String name) throws Exception {
        URL url = FunctionCatalogueIntegrationTest.class.getResource(
            "/function-catalogue/" + name
        );

        assertNotNull(url, "missing function catalogue fixture: " + name);

        return Paths.get(url.toURI());
    }

    private URLClassLoader classLoader() throws Exception {
        return new URLClassLoader(
            new URL[] { classesDirectory.toUri().toURL() },
            getClass().getClassLoader()
        );
    }

    private Object newRepository(URLClassLoader classLoader) throws Exception {
        Class<?> generatedClass = Class.forName(
            "generated.repositories." + GROUP + "Repository",
            true,
            classLoader
        );

        Constructor<?> constructor = generatedClass.getConstructors()[0];

        return constructor.newInstance(new JdbcQueryExecutor(dataSource));
    }

    /** One generated repository method, which has one name of its own. */
    private Method method(Object repository, String methodName) {
        for (Method method : repository.getClass().getMethods()) {
            if (method.getName().equals(methodName)) {
                return method;
            }
        }

        return fail("missing generated method: " + methodName);
    }

    private Object invoke(Object repository, String methodName, Object... arguments)
        throws Exception {
        return method(repository, methodName).invoke(repository, arguments);
    }

    private @Nullable Object component(Object record, String componentName) throws Exception {
        return record
            .getClass()
            .getMethod(componentName)
            .invoke(record);
    }

    /**
     * One expected result column: the generated component name, the generated
     * Java type, whether it is nullable, the alias the statement selects it
     * under, and the type name the driver reports for it.
     */
    private record ExpectedColumn(
        String name,
        String javaType,
        boolean nullable,
        String alias,
        String postgresType
    ) {
    }
}
