package org.sqlcj.compiler;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.sqlcj.config.Config;
import org.sqlcj.config.JavaConfig;
import org.sqlcj.config.SqlConfig;
import org.sqlcj.runtime.JdbcQueryExecutor;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Proves the milestone acceptance for views read as query sources: a
 * sqlcj-owned migration history whose views cover every recorded shape — a
 * {@code SELECT *} view created before a column was added to its table, a view
 * with a column list, a view a later {@code CREATE OR REPLACE} appended a
 * column to, a view dropped and recreated with a new definition, a view over a
 * view, and a materialized view created {@code WITH NO DATA} and refreshed —
 * is applied to a real PostgreSQL 16 server, that server's
 * {@code pg_dump --schema-only} snapshot is taken, and both inputs generate
 * one byte-identical repository tree that compiles and runs against the
 * migrated database.
 *
 * <p>The whole class is skipped when Docker is unavailable, so that
 * {@code mvn test} stays runnable without Docker. Setting
 * {@code -Dsqlcj.test.requireDocker=true}, as continuous integration does,
 * turns an unavailable Docker into a failure instead of a skip.
 */
@EnabledIf("dockerAvailable")
class ViewSourcesIntegrationTest {

    /**
     * System property that forbids skipping these tests. Continuous
     * integration sets it so that a broken Docker environment fails the build
     * instead of silently reducing coverage.
     */
    private static final String REQUIRE_DOCKER_PROPERTY = "sqlcj.test.requireDocker";

    /** The configured query-group name, which generates {@code CatalogRepository}. */
    private static final String GROUP = "Catalog";

    /** The migration directory inside the database container. */
    private static final String CONTAINER_MIGRATIONS = "/tmp/view-sources-migrations";

    /** The fixture's migrations, in Flyway version order. */
    private static final List<String> MIGRATIONS = List.of(
        "V1__authors_and_books.sql",
        "V2__views.sql",
        "V3__later_column_and_new_definitions.sql"
    );

    /** The files the two schema inputs generate, relative to {@code java.out}. */
    private static final Set<String> GENERATED_FILES = Set.of(
        "generated/sqlcj-manifest.txt",
        "generated/repositories/CatalogRepository.java",
        "generated/rows/AuthorsRow.java",
        "generated/rows/BooksRow.java"
    );

    private static PostgreSQLContainer<?> postgres;

    private static DataSource dataSource;

    /** The directory that receives the {@code pg_dump --schema-only} snapshot. */
    @TempDir
    static Path dumpDirectory;

    /** The {@code pg_dump --schema-only} snapshot of the migrated database. */
    private static Path dumpFile;

    @TempDir
    Path tempDir;

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
     * Applies the fixture's migrations in Flyway version order with
     * {@code psql}, then dumps the schema of the migrated database, which
     * writes every view as PostgreSQL stored it.
     */
    @BeforeAll
    static void migrateAndDump() throws Exception {
        postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine")
        );

        postgres.start();

        PGSimpleDataSource catalogDataSource = new PGSimpleDataSource();

        catalogDataSource.setUrl(postgres.getJdbcUrl());
        catalogDataSource.setUser(postgres.getUsername());
        catalogDataSource.setPassword(postgres.getPassword());

        dataSource = catalogDataSource;

        assertEquals(
            MIGRATIONS,
            migrationFileNames(),
            "the migration directory holds exactly the applied migrations"
        );

        run("mkdir", "-p", CONTAINER_MIGRATIONS);

        for (String migration : MIGRATIONS) {
            postgres.copyFileToContainer(
                MountableFile.forHostPath(migrations().resolve(migration)),
                CONTAINER_MIGRATIONS + "/" + migration
            );

            run(
                "psql",
                "-X",
                "-v",
                "ON_ERROR_STOP=1",
                "-U",
                postgres.getUsername(),
                "-d",
                postgres.getDatabaseName(),
                "-f",
                CONTAINER_MIGRATIONS + "/" + migration
            );
        }

        Container.ExecResult dump = postgres.execInContainer(
            "pg_dump",
            "--schema-only",
            "-U",
            postgres.getUsername(),
            "-d",
            postgres.getDatabaseName()
        );

        assertEquals(0, dump.getExitCode(), dump.getStderr());

        dumpFile = dumpDirectory.resolve("dump.sql");

        Files.writeString(dumpFile, dump.getStdout());
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    /**
     * The migration history and the dump of the migrated database are two
     * spellings of one schema, the views included: the dump writes the
     * {@code SELECT *} view with the columns it had when it was created, the
     * column list as aliases, each later definition as it stands, and the
     * materialized view's join in parentheses, so both inputs generate the
     * same files with the same bytes and that output compiles.
     */
    @Test
    void shouldGenerateIdenticalRepositoriesFromHistoryAndDump() throws Exception {
        Path fromHistory = tempDir.resolve("from-history");
        Path fromDump = tempDir.resolve("from-dump");

        generate(List.of(migrations().toString()), fromHistory);
        generate(List.of(dumpFile.toString()), fromDump);

        Map<String, byte[]> historyFiles = generatedFiles(fromHistory);

        assertEquals(GENERATED_FILES, historyFiles.keySet());

        assertIdentical(historyFiles, generatedFiles(fromDump));

        compile(fromHistory, tempDir.resolve("classes-history"));
    }

    /**
     * The generated repository reads every view of the history against the
     * migrated database: the {@code SELECT *} view returns the columns it had
     * when it was created and not the one a later migration added, the column
     * list names the columns of its view, the view over it reads those names,
     * the replaced view returns its appended column, the recreated view
     * returns its new definition's columns, and the materialized view, once
     * refreshed, counts the books of each author.
     */
    @Test
    void shouldExecuteTheGeneratedViewQueries() throws Exception {
        Path generatedDirectory = tempDir.resolve("generated-runtime");

        generate(List.of(migrations().toString()), generatedDirectory);

        Path classesDirectory = compile(generatedDirectory, tempDir.resolve("classes-runtime"));

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Object ada = invoke(repository, "createAuthor", "Ada Lovelace", "A. L.");
            Object kurt = invoke(repository, "createAuthor", "Kurt Godel", null);

            Long adaId = (Long) component(ada, "id");
            Long kurtId = (Long) component(kurt, "id");

            invoke(repository, "createBook", adaId, "Notes", true);
            invoke(repository, "createBook", adaId, "Drafts", false);

            assertEquals(
                List.of("id", "name", "retired"),
                recordComponentNames(
                    ((List<?>) invoke(repository, "listAuthorRows")).get(0).getClass()
                ),
                "the pen_name column added after the view was created is no column of it"
            );

            assertEquals(
                List.of("Ada Lovelace", "Kurt Godel"),
                componentsOf((List<?>) invoke(repository, "listAuthorRows"), "name")
            );

            assertEquals(
                List.of(adaId, kurtId),
                componentsOf((List<?>) invoke(repository, "listActiveAuthors"), "ident"),
                "the column list names the view's first column"
            );

            assertEquals(
                List.of("Ada Lovelace", "Kurt Godel"),
                ((List<?>) invoke(repository, "listActiveAuthorNames")).stream()
                    .map(name -> (Object) name)
                    .toList(),
                "the view over a view returns its one non-null column's value"
            );

            invoke(repository, "retireAuthor", kurtId);

            assertEquals(
                List.of(adaId),
                componentsOf((List<?>) invoke(repository, "listActiveAuthors"), "ident"),
                "the view's own WHERE leaves out the retired author"
            );

            List<?> published = (List<?>) invoke(repository, "listPublishedBooks");

            assertEquals(1, published.size(), "the replaced view keeps its own WHERE");

            assertEquals("Notes", component(published.get(0), "title"));

            assertEquals(
                adaId,
                component(published.get(0), "authorId"),
                "the column CREATE OR REPLACE appended is read"
            );

            assertEquals("Ada Lovelace", component(published.get(0), "authorName"));

            assertEquals(
                List.of("id", "authorId"),
                recordComponentNames(
                    ((List<?>) invoke(repository, "listRecentBooks")).get(0).getClass()
                ),
                "the recreated view takes its new definition"
            );

            refreshMaterializedView();

            assertEquals(
                Optional.of(2L),
                invoke(repository, "countAuthorBooks", adaId),
                "the materialized view counts the author's books"
            );

            assertEquals(
                Optional.of(0L),
                invoke(repository, "countAuthorBooks", kurtId),
                "the left join counts no book of an author that has none"
            );
        }
    }

    /** Refreshes the materialized view, which holds no row until it is. */
    private void refreshMaterializedView() throws Exception {
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement refresh = connection.prepareStatement(
                "REFRESH MATERIALIZED VIEW author_book_counts"
            )
        ) {
            refresh.execute();
        }
    }

    /** Runs one command inside the database container and fails on its stderr. */
    private static void run(String... command) throws Exception {
        Container.ExecResult result = postgres.execInContainer(command);

        assertEquals(
            0,
            result.getExitCode(),
            String.join(" ", command) + System.lineSeparator() + result.getStderr()
        );
    }

    /** The {@code .sql} file names of the migration directory, in version order. */
    private static List<String> migrationFileNames() throws Exception {
        try (Stream<Path> files = Files.list(migrations())) {
            return files
                .map(file -> file.getFileName().toString())
                .sorted(Comparator.comparingInt(ViewSourcesIntegrationTest::migrationVersion))
                .toList();
        }
    }

    private static int migrationVersion(String fileName) {
        return Integer.parseInt(fileName.substring(1, fileName.indexOf("__")));
    }

    private static Path migrations() throws Exception {
        return resource("migrations");
    }

    private static Path queries() throws Exception {
        return resource("queries.sql");
    }

    /** One fixture of the view sources, resolved from the test classpath. */
    private static Path resource(String name) throws Exception {
        URL url = ViewSourcesIntegrationTest.class.getResource("/view-sources/" + name);

        assertNotNull(url, "missing view sources fixture: " + name);

        return Paths.get(url.toURI());
    }

    /** Generates the one repository of the {@code Catalog} group. */
    private void generate(List<String> schema, Path generatedDirectory) throws Exception {
        Path queriesFile = queries();

        Config config = new Config(
            List.of(
                new SqlConfig(
                    GROUP,
                    schema,
                    queriesFile.toString()
                )
            ),
            new JavaConfig(
                generatedDirectory.toString(),
                "generated"
            )
        );

        new SqlcjCompiler().compile(config, queriesFile.resolveSibling("sqlcj.yaml"));
    }

    /** Every generated file of one output directory, by its relative path. */
    private Map<String, byte[]> generatedFiles(Path generatedDirectory) throws Exception {
        Map<String, byte[]> files = new TreeMap<>();

        try (Stream<Path> paths = Files.walk(generatedDirectory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                files.put(
                    generatedDirectory.relativize(path).toString().replace('\\', '/'),
                    Files.readAllBytes(path)
                );
            }
        }

        return files;
    }

    private void assertIdentical(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        assertEquals(expected.keySet(), actual.keySet());

        for (Map.Entry<String, byte[]> file : expected.entrySet()) {
            assertArrayEquals(
                file.getValue(),
                actual.get(file.getKey()),
                file.getKey() + " differs"
            );
        }
    }

    /** Compiles every generated source of one output directory together. */
    private Path compile(Path generatedDirectory, Path classesDirectory) throws Exception {
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

        assertEquals(
            0,
            compilerApi.run(
                null,
                null,
                null,
                arguments.toArray(new String[0])
            )
        );

        return classesDirectory;
    }

    private URLClassLoader classLoader(Path classesDirectory) throws Exception {
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

    /** Calls one generated repository method, which has one name of its own. */
    private Object invoke(Object repository, String methodName, Object... arguments)
        throws Exception {
        for (Method method : repository.getClass().getMethods()) {
            if (method.getName().equals(methodName)) {
                return method.invoke(repository, arguments);
            }
        }

        return fail("missing generated method: " + methodName);
    }

    private Object component(Object record, String componentName) throws Exception {
        return record
            .getClass()
            .getMethod(componentName)
            .invoke(record);
    }

    /** One component of every row of a result list, in the list's order. */
    private List<Object> componentsOf(List<?> rows, String componentName) throws Exception {
        List<Object> values = new ArrayList<>();

        for (Object row : rows) {
            values.add(component(row, componentName));
        }

        return values;
    }

    private List<String> recordComponentNames(Class<?> recordClass) {
        assertTrue(recordClass.isRecord());

        return Arrays.stream(recordClass.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();
    }
}
