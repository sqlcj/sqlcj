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
import java.math.BigDecimal;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Proves the milestone acceptance on a service whose schema is {@code billing}:
 * its Flyway-style migration history, whose names are unqualified, is applied
 * to a real PostgreSQL 16 server under the {@code search_path}
 * {@code billing, public}, that server's {@code pg_dump --schema-only}
 * snapshot, which qualifies every name, is taken, and both inputs generate one
 * byte-identical repository tree that compiles and runs against the migrated
 * database over a connection whose {@code search_path} names {@code billing}.
 *
 * <p>The whole class is skipped when Docker is unavailable, so that
 * {@code mvn test} stays runnable without Docker. Setting
 * {@code -Dsqlcj.test.requireDocker=true}, as continuous integration does,
 * turns an unavailable Docker into a failure instead of a skip.
 */
@EnabledIf("dockerAvailable")
class BillingServiceIntegrationTest {

    /**
     * System property that forbids skipping these tests. Continuous
     * integration sets it so that a broken Docker environment fails the build
     * instead of silently reducing coverage.
     */
    private static final String REQUIRE_DOCKER_PROPERTY = "sqlcj.test.requireDocker";

    /** The configured query-group name, which generates {@code BillingRepository}. */
    private static final String GROUP = "Billing";

    /** The one schema the group models, configured as {@code sql[].namespace}. */
    private static final String NAMESPACE = "billing";

    /** The migration directory inside the database container. */
    private static final String CONTAINER_MIGRATIONS = "/tmp/billing-service-migrations";

    /** The billing service's migrations, in Flyway version order. */
    private static final List<String> MIGRATIONS = List.of(
        "V1__customers.sql",
        "V2__invoices.sql",
        "V3__public_invoices.sql"
    );

    /** The files the two schema inputs generate, relative to {@code java.out}. */
    private static final Set<String> GENERATED_FILES = Set.of(
        "generated/sqlcj-manifest.txt",
        "generated/repositories/BillingRepository.java",
        "generated/rows/CustomersRow.java",
        "generated/rows/InvoicesRow.java",
        "generated/enums/InvoiceState.java"
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
     * Creates the {@code billing} schema, applies the service's migrations in
     * Flyway version order with {@code psql} under the {@code search_path} its
     * migration tool sets, and dumps the schema of the whole migrated database,
     * which holds the {@code public} table the history creates as well.
     */
    @BeforeAll
    static void migrateAndDump() throws Exception {
        postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine")
        );

        postgres.start();

        PGSimpleDataSource billingDataSource = new PGSimpleDataSource();

        billingDataSource.setUrl(postgres.getJdbcUrl());
        billingDataSource.setUser(postgres.getUsername());
        billingDataSource.setPassword(postgres.getPassword());
        billingDataSource.setCurrentSchema("billing,public");

        dataSource = billingDataSource;

        assertEquals(
            MIGRATIONS,
            migrationFileNames(),
            "the migration directory holds exactly the applied migrations"
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
            "-c",
            "CREATE SCHEMA billing"
        );

        run("mkdir", "-p", CONTAINER_MIGRATIONS);

        for (String migration : MIGRATIONS) {
            postgres.copyFileToContainer(
                MountableFile.forHostPath(migrations().resolve(migration)),
                CONTAINER_MIGRATIONS + "/" + migration
            );

            run(
                "env",
                "PGOPTIONS=--search_path=billing,public",
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
     * The migration history with its unqualified names and the dump of the
     * migrated database with its {@code billing.}-qualified ones are two
     * spellings of one schema, so each generates the same files with the same
     * bytes and that output compiles. Neither models the {@code public} table
     * the history creates beside them, which holds other columns.
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
     * The generated repository executes the billing service's queries, named
     * unqualified and {@code billing}-qualified, against the migrated database
     * over a connection whose {@code search_path} names {@code billing}.
     */
    @Test
    void shouldExecuteTheGeneratedBillingServiceQueries() throws Exception {
        Path generatedDirectory = tempDir.resolve("generated-runtime");

        generate(List.of(migrations().toString()), generatedDirectory);

        Path classesDirectory = compile(generatedDirectory, tempDir.resolve("classes-runtime"));

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Object customer = invoke(repository, "createCustomer", "Ada@Example.com", "Ada");

            Long customerId = (Long) component(customer, "id");

            assertEquals("Ada@Example.com", component(customer, "email"));

            assertEquals(
                Optional.of(customerId),
                ((Optional<?>) invoke(repository, "getCustomerByEmail", "ada@example.com"))
                    .map(row -> componentOrFail(row, "id")),
                "the citext column of the modeled schema compares ignoring case"
            );

            Object draft = enumConstant(classLoader, "InvoiceState", "DRAFT");
            Object sent = enumConstant(classLoader, "InvoiceState", "SENT");

            Object invoice = invoke(
                repository,
                "createInvoice",
                customerId,
                draft,
                new BigDecimal("120.50"),
                "First invoice"
            );

            Long invoiceId = (Long) component(invoice, "id");

            assertEquals(draft, component(invoice, "state"));

            assertEquals(new BigDecimal("120.50"), component(invoice, "total"));

            List<?> invoices = (List<?>) invoke(repository, "listCustomerInvoices", customerId);

            assertEquals(1, invoices.size());

            assertEquals(
                "InvoicesRow",
                invoices.get(0).getClass().getSimpleName(),
                "a billing-qualified wildcard returns the shared row record"
            );

            assertEquals(invoiceId, component(invoices.get(0), "id"));

            assertEquals(draft, component(invoices.get(0), "state"));

            assertEquals(
                1,
                invoke(repository, "markInvoiceSent", sent, invoiceId, draft),
                "the unqualified update reaches the modeled invoice"
            );

            assertEquals(
                sent,
                component(
                    ((List<?>) invoke(repository, "listCustomerInvoices", customerId)).get(0),
                    "state"
                )
            );
        }
    }

    /**
     * The {@code public} table the history creates is invisible to this group,
     * so a query that names it states the schema it belongs to, the schema the
     * group models, and the setting that would model {@code public} instead,
     * rather than resolving to the {@code invoices} table of the modeled
     * schema.
     */
    @Test
    void shouldRejectAQueryOnAPublicTable() throws Exception {
        Path queriesFile = tempDir.resolve("public-queries.sql");

        Files.writeString(
            queriesFile,
            """
                -- name: ListReportedInvoices :many
                SELECT *
                FROM public.invoices;
                """
        );

        Path generatedDirectory = tempDir.resolve("generated-public");

        CompilationException exception = assertThrows(
            CompilationException.class,
            () -> generate(
                List.of(migrations().toString()),
                queriesFile,
                generatedDirectory
            )
        );

        assertEquals(
            "%s:1: Invalid query 'ListReportedInvoices': ".formatted(queriesFile)
                + "public.invoices belongs to schema public, "
                + "which this query group does not model; it models schema billing. "
                + "Set sql[].namespace to public to model that schema instead.",
            exception.getMessage()
        );

        assertFalse(Files.exists(generatedDirectory));
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
                .sorted()
                .toList();
        }
    }

    private static Path migrations() throws Exception {
        return resource("migrations");
    }

    private static Path queries() throws Exception {
        return resource("queries.sql");
    }

    /** One billing service fixture, resolved from the test classpath. */
    private static Path resource(String name) throws Exception {
        URL url = BillingServiceIntegrationTest.class.getResource(
            "/billing-service/" + name
        );

        assertNotNull(url, "missing billing service fixture: " + name);

        return Paths.get(url.toURI());
    }

    private void generate(List<String> schema, Path generatedDirectory) throws Exception {
        generate(schema, queries(), generatedDirectory);
    }

    /**
     * Generates the one repository of the {@code Billing} group, which models
     * the schema {@code billing} rather than {@code public}.
     */
    private void generate(List<String> schema, Path queriesFile, Path generatedDirectory) {
        Config config = new Config(
            List.of(
                new SqlConfig(
                    GROUP,
                    schema,
                    queriesFile.toString(),
                    NAMESPACE
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

    /** One constant of a generated enum, loaded from the compiled output. */
    private Object enumConstant(
        URLClassLoader classLoader,
        String enumName,
        String constant
    ) throws Exception {
        return Class.forName("generated.enums." + enumName, true, classLoader)
            .getField(constant)
            .get(null);
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

    /** One component of a row read inside a lambda, where a failure cannot throw. */
    private Object componentOrFail(Object record, String componentName) {
        try {
            return component(record, componentName);
        } catch (Exception e) {
            return fail(e);
        }
    }
}
