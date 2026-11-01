package org.sqlcj.spring.boot.postgres;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.sqlcj.compiler.SqlcjCompiler;
import org.sqlcj.config.Config;
import org.sqlcj.config.JavaConfig;
import org.sqlcj.config.SqlConfig;
import org.sqlcj.runtime.QueryExecutionException;
import org.sqlcj.runtime.QueryExecutor;
import org.sqlcj.runtime.UniqueViolationException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves against a real PostgreSQL instance what a Spring Boot application
 * started with Spring Boot's own defaults gets from the starter: one executor,
 * two registered generated repositories, and generated operations that take
 * part in the transaction Spring Boot's transaction manager owns.
 *
 * <p>Each subclass names one {@code @SpringBootApplication}, the parent of the
 * class loader the application is started with, and the transaction manager
 * Spring Boot configures for it, so that every scenario below runs once on
 * Spring Boot's JDBC support and once on its JPA support.
 *
 * <p>Like every other PostgreSQL test, the repositories are generated with
 * {@link SqlcjCompiler} into a temporary directory, compiled there, and loaded
 * from a class loader of their own, which the application is given as its
 * resource loader's; the application itself sets no property but the data
 * source's URL and credentials, and excludes nothing.
 *
 * <p>The scenarios that leave the starter's own configuration in place share
 * one application context, started once per class; the two that replace part of
 * it start a context of their own and close it.
 *
 * <p>The whole class is skipped when Docker is unavailable, so that
 * {@code mvn test} stays runnable without Docker. Setting
 * {@code -Dsqlcj.test.requireDocker=true}, as continuous integration does,
 * turns an unavailable Docker into a failure instead of a skip.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class PostgresApplicationScenarios {

    /**
     * System property that forbids skipping these tests. Continuous
     * integration sets it so that a broken Docker environment fails the build
     * instead of silently reducing coverage.
     */
    private static final String REQUIRE_DOCKER_PROPERTY = "sqlcj.test.requireDocker";

    /** The package the repositories are generated into, below the application's. */
    private static final String GENERATED_PACKAGE = ".generated";

    private static final String AUTHORS_GROUP = "Authors";

    private static final String BOOKS_GROUP = "Books";

    /** The two tables the generated repositories write and read. */
    private static final String SCHEMA = """
        CREATE TABLE authors
        (
            id   BIGINT PRIMARY KEY,
            name VARCHAR(255) NOT NULL
        );

        CREATE TABLE books
        (
            id    BIGINT PRIMARY KEY,
            title VARCHAR(255) NOT NULL
        );
        """;

    /** One write, one batch of that write, and one ordered read. */
    private static final String AUTHORS_QUERIES = """
        -- name: InsertAuthor :exec
        INSERT INTO authors (id, name)
        VALUES ($1, $2);

        -- name: InsertAuthors :batchexec
        INSERT INTO authors (id, name)
        VALUES ($1, $2);

        -- name: ListAuthorNames :many
        SELECT name
        FROM authors
        ORDER BY id;
        """;

    /** The second group, which proves both repositories share one transaction. */
    private static final String BOOKS_QUERIES = """
        -- name: InsertBook :exec
        INSERT INTO books (id, title)
        VALUES ($1, $2);

        -- name: ListBookTitles :many
        SELECT title
        FROM books
        ORDER BY id;
        """;

    private static final String TRUNCATE = "TRUNCATE authors, books";

    private static final String SELECT_AUTHOR_NAMES = "SELECT name FROM authors ORDER BY id";

    private static final String SELECT_BOOK_TITLES = "SELECT title FROM books ORDER BY id";

    /**
     * The generated sources and classes of one test class. The field is
     * inherited, and JUnit fills it again for each subclass, which is sound
     * because the classes run one after another.
     */
    @TempDir
    static Path tempDir;

    private PostgreSQLContainer<?> postgres;

    /** The database outside any application, which owns the schema. */
    private DataSource database;

    private URLClassLoader classLoader;

    private Class<?> authorsRepositoryClass;

    private Class<?> booksRepositoryClass;

    /** The application every scenario but the two overriding ones runs on. */
    private ConfigurableApplicationContext context;

    protected static boolean dockerAvailable() {
        boolean available = DockerClientFactory.instance().isDockerAvailable();

        if (!available && Boolean.getBoolean(REQUIRE_DOCKER_PROPERTY)) {
            throw new IllegalStateException(
                "Docker is unavailable, but " + REQUIRE_DOCKER_PROPERTY + " requires these tests to run."
            );
        }

        return available;
    }

    /** The {@code @SpringBootApplication} the scenarios start. */
    protected abstract Class<?> applicationClass();

    /**
     * The parent of the class loader the generated repositories are loaded
     * from, which decides what the application sees of the test classpath.
     */
    protected abstract ClassLoader parentClassLoader();

    /** The transaction manager Spring Boot configures for the application. */
    protected abstract Class<? extends PlatformTransactionManager> transactionManagerType();

    /**
     * Starts the database, creates the schema, generates and compiles the two
     * query groups, and starts the shared application on them.
     */
    @BeforeAll
    void startDatabaseAndApplication() throws Exception {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

        postgres.start();

        PGSimpleDataSource dataSource = new PGSimpleDataSource();

        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        database = dataSource;

        execute(SCHEMA);

        classLoader = new URLClassLoader(
            new URL[] { generateAndCompile().toUri().toURL() },
            parentClassLoader()
        );

        authorsRepositoryClass = generatedClass(AUTHORS_GROUP + "Repository");
        booksRepositoryClass = generatedClass(BOOKS_GROUP + "Repository");

        context = start();
    }

    @AfterAll
    void stopApplicationAndDatabase() throws Exception {
        if (context != null) {
            context.close();
        }

        if (classLoader != null) {
            classLoader.close();
        }

        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void emptyTables() throws Exception {
        execute(TRUNCATE);
    }

    @Test
    void registersOnePersistenceExceptionTranslationPostProcessor() {
        assertEquals(
            1,
            context.getBeansOfType(PersistenceExceptionTranslationPostProcessor.class).size()
        );
    }

    @Test
    void registersBothGeneratedRepositoriesUnderTheirClassNames() {
        assertArrayEquals(
            new String[] { authorsRepositoryClass.getName() },
            context.getBeanNamesForType(authorsRepositoryClass)
        );
        assertArrayEquals(
            new String[] { booksRepositoryClass.getName() },
            context.getBeanNamesForType(booksRepositoryClass)
        );
        assertSame(authorsRepositoryClass, authors().getClass());
        assertSame(booksRepositoryClass, books().getClass());
    }

    @Test
    void configuresTheStarterExecutorAsTheOnlyQueryExecutor() {
        assertArrayEquals(
            new String[] { "sqlcjQueryExecutor" },
            context.getBeanNamesForType(QueryExecutor.class)
        );
    }

    @Test
    void configuresTheTransactionManagerOfTheApplicationsSupport() {
        assertInstanceOf(transactionManagerType(), context.getBean(PlatformTransactionManager.class));
    }

    @Test
    void commitsTheWritesOfBothRepositoriesTogether() {
        run(() -> {
            assertEquals(1, insertAuthor(authors(), 1L, "Alice"));
            assertEquals(1, insertBook(books(), 1L, "Dune"));

            assertEquals(List.of(), storedAuthorNames());
            assertEquals(List.of(), storedBookTitles(context));
        });

        assertEquals(List.of("Alice"), authorNames(authors()));
        assertEquals(List.of("Dune"), bookTitles(books()));
    }

    @Test
    void rollsBackTheWritesOfBothRepositoriesTogether() {
        CallbackFailure failure = new CallbackFailure();

        assertSame(
            failure,
            assertThrows(
                CallbackFailure.class,
                () -> run(() -> {
                    assertEquals(1, insertAuthor(authors(), 1L, "Alice"));
                    assertEquals(1, insertBook(books(), 1L, "Dune"));

                    throw failure;
                })
            )
        );

        assertEquals(List.of(), authorNames(authors()));
        assertEquals(List.of(), bookTitles(books()));
    }

    /**
     * A duplicate key a generated write hits inside a Spring-managed
     * transaction reaches the caller as the runtime's own
     * {@link UniqueViolationException}, with PostgreSQL's SQLSTATE and the
     * driver's exception as its cause: the starter converts no exception of
     * the runtime into Spring's {@code DataAccessException} family. The
     * transaction it failed in is rolled back, so only the row written before
     * it remains.
     */
    @Test
    void raisesTheRuntimeUniqueViolationInsideATransaction() {
        assertEquals(1, insertAuthor(authors(), 1L, "Alice"));

        UniqueViolationException failure = assertThrows(
            UniqueViolationException.class,
            () -> run(() -> {
                assertEquals(1, insertAuthor(authors(), 2L, "Bob"));

                insertAuthor(authors(), 1L, "Duplicate");
            })
        );

        assertEquals(
            "Failed to execute query 'InsertAuthor' in AuthorsRepository",
            failure.getMessage()
        );

        assertEquals("23505", failure.sqlState());
        assertInstanceOf(SQLException.class, failure.getCause());

        assertEquals(List.of("Alice"), storedAuthorNames());
    }

    @Test
    void commitsAWriteMadeOutsideATransaction() {
        assertEquals(1, insertAuthor(authors(), 1L, "Alice"));

        assertEquals(List.of("Alice"), storedAuthorNames());
    }

    @Test
    void storesEveryRowOfABatchMadeOutsideATransaction() {
        assertArrayEquals(
            new int[] { 1, 1 },
            insertAuthors(authors(), List.of(authorRow(1L, "Alice"), authorRow(2L, "Bob")))
        );

        assertEquals(List.of("Alice", "Bob"), storedAuthorNames());
    }

    @Test
    void storesNoRowOfAFailingBatchMadeOutsideATransaction() {
        assertThrows(
            QueryExecutionException.class,
            () -> insertAuthors(authors(), List.of(authorRow(1L, "Alice"), authorRow(1L, "Bob")))
        );

        assertEquals(List.of(), storedAuthorNames());
    }

    @Test
    void rollsBackABatchWithTheTransaction() {
        CallbackFailure failure = new CallbackFailure();

        assertSame(
            failure,
            assertThrows(
                CallbackFailure.class,
                () -> run(() -> {
                    assertArrayEquals(
                        new int[] { 1, 1 },
                        insertAuthors(authors(), List.of(authorRow(1L, "Alice"), authorRow(2L, "Bob")))
                    );

                    throw failure;
                })
            )
        );

        assertEquals(List.of(), storedAuthorNames());
    }

    @Test
    void runsTheRegisteredRepositoriesOnAnApplicationExecutor() {
        try (ConfigurableApplicationContext application = start(ApplicationExecutorConfiguration.class)) {
            assertArrayEquals(
                new String[] { "applicationQueryExecutor" },
                application.getBeanNamesForType(QueryExecutor.class)
            );

            assertEquals(1, insertAuthor(application.getBean(authorsRepositoryClass), 1L, "Alice"));

            assertEquals(List.of("InsertAuthor"), recordedQueries(application));
            assertEquals(List.of("Alice"), storedAuthorNames(application));
        }
    }

    @Test
    void leavesAnApplicationRepositoryBeanAloneWithItsType() {
        ApplicationRepositoryInitializer initializer = new ApplicationRepositoryInitializer(
            "applicationAuthorsRepository",
            authorsRepositoryClass
        );

        try (ConfigurableApplicationContext application = builder().initializers(initializer).run()) {
            assertArrayEquals(
                new String[] { "applicationAuthorsRepository" },
                application.getBeanNamesForType(authorsRepositoryClass)
            );
            assertArrayEquals(
                new String[] { booksRepositoryClass.getName() },
                application.getBeanNamesForType(booksRepositoryClass)
            );

            assertEquals(
                1,
                insertAuthor(application.getBean("applicationAuthorsRepository"), 1L, "Alice")
            );

            assertEquals(List.of("Alice"), storedAuthorNames(application));
        }
    }

    /** The names of the queries the application's own executor recorded. */
    private static List<String> recordedQueries(ApplicationContext application) {
        return application
            .getBean(
                "applicationQueryExecutor",
                ApplicationExecutorConfiguration.RecordingQueryExecutor.class
            )
            .queries();
    }

    /** The application every scenario but the two overriding ones runs on. */
    protected final ConfigurableApplicationContext context() {
        return context;
    }

    /** The registered {@code AuthorsRepository} of the shared application. */
    protected final Object authors() {
        return context.getBean(authorsRepositoryClass);
    }

    /** The registered {@code BooksRepository} of the shared application. */
    private Object books() {
        return context.getBean(booksRepositoryClass);
    }

    /**
     * Runs the work in the transaction the shared application's transaction
     * manager owns, the way a {@code @Transactional} service method does.
     */
    protected final void run(Runnable work) {
        context.getBean(TransactionalRunner.class).run(work);
    }

    /**
     * The stored author names, read with plain JDBC on the application's own
     * data source, which hands out a connection of its own and therefore sees
     * only what a transaction has committed.
     */
    protected final List<String> storedAuthorNames() {
        return storedAuthorNames(context);
    }

    private static List<String> storedAuthorNames(ApplicationContext application) {
        return stored(application, SELECT_AUTHOR_NAMES);
    }

    private static List<String> storedBookTitles(ApplicationContext application) {
        return stored(application, SELECT_BOOK_TITLES);
    }

    private static List<String> stored(ApplicationContext application, String sql) {
        DataSource dataSource = application.getBean(DataSource.class);

        try (
            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(sql)
        ) {
            List<String> values = new ArrayList<>();

            while (rows.next()) {
                values.add(rows.getString(1));
            }

            return values;
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to read the table", failure);
        }
    }

    protected static int insertAuthor(Object repository, long id, String name) {
        return (int) invoke(repository, "insertAuthor", new Class<?>[] { Long.class, String.class }, id, name);
    }

    private static int insertBook(Object repository, long id, String title) {
        return (int) invoke(repository, "insertBook", new Class<?>[] { Long.class, String.class }, id, title);
    }

    private static int[] insertAuthors(Object repository, List<Object> batch) {
        return (int[]) invoke(repository, "insertAuthors", new Class<?>[] { List.class }, batch);
    }

    /** One row of the generated batch write. */
    private Object authorRow(long id, String name) {
        try {
            return generatedClass(AUTHORS_GROUP + "Repository$InsertAuthorsParams")
                .getConstructor(Long.class, String.class)
                .newInstance(id, name);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Failed to build a row of the generated batch write", failure);
        }
    }

    protected static List<String> authorNames(Object repository) {
        return values(repository, "listAuthorNames");
    }

    private static List<String> bookTitles(Object repository) {
        return values(repository, "listBookTitles");
    }

    /** The one-column rows a generated read returned. */
    private static List<String> values(Object repository, String method) {
        List<?> rows = (List<?>) invoke(repository, method, new Class<?>[0]);

        List<String> values = new ArrayList<>();

        for (Object row : rows) {
            values.add((String) row);
        }

        return values;
    }

    /**
     * Calls one method of a generated repository, rethrowing the failure that
     * method itself raised, as the same instance, rather than the reflective
     * wrapper around it.
     */
    private static Object invoke(
        Object repository,
        String method,
        Class<?>[] parameterTypes,
        Object... arguments
    ) {
        Method generated;

        try {
            generated = repository.getClass().getMethod(method, parameterTypes);
        } catch (NoSuchMethodException failure) {
            throw new IllegalStateException("The generated repository has no " + method, failure);
        }

        try {
            return generated.invoke(repository, arguments);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();

            if (cause instanceof RuntimeException unchecked) {
                throw unchecked;
            }

            if (cause instanceof Error error) {
                throw error;
            }

            throw new IllegalStateException("The generated method " + method + " failed", cause);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Failed to call the generated method " + method, failure);
        }
    }

    /** Starts the application, with the given sources beside it. */
    private ConfigurableApplicationContext start(Class<?>... extraSources) {
        return builder(extraSources).run();
    }

    /**
     * The application as Spring Boot's defaults configure it: no exclusion and
     * no property but the data source's, started over the class loader the
     * generated repositories were loaded from.
     */
    private SpringApplicationBuilder builder(Class<?>... extraSources) {
        Class<?>[] sources = new Class<?>[extraSources.length + 1];

        sources[0] = applicationClass();

        System.arraycopy(extraSources, 0, sources, 1, extraSources.length);

        return new SpringApplicationBuilder(sources)
            .resourceLoader(new DefaultResourceLoader(classLoader))
            .properties(
                "spring.datasource.url=" + postgres.getJdbcUrl(),
                "spring.datasource.username=" + postgres.getUsername(),
                "spring.datasource.password=" + postgres.getPassword()
            );
    }

    private Class<?> generatedClass(String simpleName) throws ClassNotFoundException {
        return Class.forName(generatedPackage() + "." + simpleName, true, classLoader);
    }

    private String generatedPackage() {
        return applicationClass().getPackageName() + GENERATED_PACKAGE;
    }

    /**
     * Generates the two query groups of the schema into the temporary
     * directory and compiles every generated file into a classes directory of
     * its own, which is returned.
     */
    private Path generateAndCompile() throws Exception {
        Path schemaFile = tempDir.resolve("schema.sql");
        Path authorsFile = tempDir.resolve("authors.sql");
        Path booksFile = tempDir.resolve("books.sql");
        Path generatedDirectory = tempDir.resolve("generated");
        Path classesDirectory = tempDir.resolve("classes");

        Files.writeString(schemaFile, SCHEMA);
        Files.writeString(authorsFile, AUTHORS_QUERIES);
        Files.writeString(booksFile, BOOKS_QUERIES);

        Config config = new Config(
            List.of(
                new SqlConfig(AUTHORS_GROUP, schemaFile.toString(), authorsFile.toString()),
                new SqlConfig(BOOKS_GROUP, schemaFile.toString(), booksFile.toString())
            ),
            new JavaConfig(generatedDirectory.toString(), generatedPackage())
        );

        new SqlcjCompiler().compile(config);

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

        Path packageDirectory = generatedDirectory.resolve(generatedPackage().replace('.', '/'));

        try (Stream<Path> generatedFiles = Files.list(packageDirectory)) {
            generatedFiles
                .map(Path::toString)
                .sorted()
                .forEach(arguments::add);
        }

        assertEquals(0, compilerApi.run(null, null, null, arguments.toArray(new String[0])));

        return classesDirectory;
    }

    private void execute(String sql) throws Exception {
        try (
            Connection connection = database.getConnection();
            Statement statement = connection.createStatement()
        ) {
            statement.execute(sql);
        }
    }

    /**
     * The failure a test throws from the transactional method to force a
     * rollback. It is public so that a subclass in another package can throw it
     * too.
     */
    public static final class CallbackFailure extends RuntimeException {
    }
}
