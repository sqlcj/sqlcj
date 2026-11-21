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
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlcj.compiler.SqlcjCompiler;
import org.sqlcj.config.Config;
import org.sqlcj.config.JavaConfig;
import org.sqlcj.config.SqlConfig;
import org.sqlcj.runtime.QueryCardinalityException;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves against a real PostgreSQL instance what a Spring Boot application
 * started with Spring Boot's own defaults gets from the starter: one executor,
 * two registered generated repositories, generated operations that take part
 * in the transaction Spring Boot's transaction manager owns, and failures that
 * raise the Spring exception the application's own {@link JdbcTemplate} raises
 * for the same statement.
 *
 * <p>Each exception scenario runs its failure twice: once through the
 * generated repository and once, as the same SQL, through the
 * {@code JdbcTemplate} Spring Boot configured for the application, and asserts
 * that the two classes are equal and which class that is. An application's own
 * executor, which the starter does not wrap, keeps raising the runtime's own
 * exception.
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

    /**
     * The configured package of the generated code, below the application's.
     * The repositories themselves are generated into its {@code repositories}
     * subpackage.
     */
    private static final String GENERATED_PACKAGE = ".generated";

    private static final String AUTHORS_GROUP = "Authors";

    private static final String BOOKS_GROUP = "Books";

    /**
     * The three tables the generated repositories write and read. One award
     * row references an author, so an award of an author nobody stored is the
     * foreign-key violation one scenario raises.
     */
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

        CREATE TABLE awards
        (
            id        BIGINT PRIMARY KEY,
            author_id BIGINT NOT NULL REFERENCES authors (id)
        );
        """;

    /**
     * One write, one batch of that write, and one ordered read, beside the
     * writes and reads the exception scenarios need: the award that references
     * an author, an update of one author, and the two reads of one author by
     * name, which are the same query as a {@code :one} and as an
     * {@code :optional}.
     */
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

        -- name: InsertAward :exec
        INSERT INTO awards (id, author_id)
        VALUES ($1, $2);

        -- name: RenameAuthor :exec
        UPDATE authors
        SET name = $1
        WHERE id = $2;

        -- name: GetAuthorNamed :one
        SELECT id
        FROM authors
        WHERE name = $1;

        -- name: FindAuthorNamed :optional
        SELECT id
        FROM authors
        WHERE name = $1;
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

    private static final String TRUNCATE = "TRUNCATE authors, books, awards";

    private static final String SELECT_AUTHOR_NAMES = "SELECT name FROM authors ORDER BY id";

    private static final String SELECT_BOOK_TITLES = "SELECT title FROM books ORDER BY id";

    /*
     * The SQL of the generated queries the exception scenarios run, which they
     * also run through the application's own JdbcTemplate to compare the class
     * it raises for the same failure.
     */

    private static final String INSERT_AUTHOR = "INSERT INTO authors (id, name) VALUES (?, ?)";

    private static final String INSERT_AWARD = "INSERT INTO awards (id, author_id) VALUES (?, ?)";

    private static final String RENAME_AUTHOR = "UPDATE authors SET name = ? WHERE id = ?";

    private static final String SELECT_AUTHOR_ID_NAMED = "SELECT id FROM authors WHERE name = ?";

    private static final String SELECT_AUTHOR_NAME = "SELECT name FROM authors WHERE id = ?";

    /** The author the serialization failure is raised over. */
    private static final long CONFLICTING_ID = 1L;

    private static final String CONFLICTING_NAME = "Alice";

    /** The author no table holds, so that an award of it has no referent. */
    private static final long MISSING_ID = 404L;

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

        authorsRepositoryClass = generatedRepositoryClass(AUTHORS_GROUP + "Repository");
        booksRepositoryClass = generatedRepositoryClass(BOOKS_GROUP + "Repository");

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
     * transaction reaches the caller as the {@code DuplicateKeyException} the
     * application's own {@code JdbcTemplate} raises for the same statement,
     * with the driver's exception as its cause and a message that begins with
     * the one naming the query and its repository. The transaction it failed
     * in is rolled back, so only the row written before it remains.
     */
    @Test
    void raisesSpringsDuplicateKeyInsideATransaction() {
        assertEquals(1, insertAuthor(authors(), CONFLICTING_ID, CONFLICTING_NAME));

        DataAccessException failure = raised(
            () -> run(() -> {
                assertEquals(1, insertAuthor(authors(), 2L, "Bob"));

                insertAuthor(authors(), CONFLICTING_ID, "Duplicate");
            })
        );

        assertEquals(DuplicateKeyException.class, failure.getClass());
        assertMessageOf("InsertAuthor", failure);
        assertEquals("23505", sqlState(failure));

        assertEquals(List.of(CONFLICTING_NAME), storedAuthorNames());

        assertEquals(
            raised(() -> jdbcTemplate().update(INSERT_AUTHOR, CONFLICTING_ID, "Duplicate")).getClass(),
            failure.getClass()
        );
    }

    /**
     * An award of an author nobody stored is the {@code 23503} PostgreSQL
     * reports for a missing referenced row, and raises the
     * {@code DataIntegrityViolationException} the application's own
     * {@code JdbcTemplate} raises for the same statement.
     */
    @Test
    void raisesSpringsDataIntegrityViolationForAMissingReferencedRow() {
        DataAccessException failure = raised(() -> insertAward(authors(), 1L, MISSING_ID));

        assertEquals(DataIntegrityViolationException.class, failure.getClass());
        assertMessageOf("InsertAward", failure);
        assertEquals("23503", sqlState(failure));

        assertEquals(
            raised(() -> jdbcTemplate().update(INSERT_AWARD, 2L, MISSING_ID)).getClass(),
            failure.getClass()
        );
    }

    /**
     * A generated update of a row another connection committed an update of,
     * inside a {@code REPEATABLE READ} transaction that had read that row, is
     * the {@code 40001} PostgreSQL reports for a serialization failure, and
     * raises the {@code CannotAcquireLockException} the application's own
     * {@code JdbcTemplate} raises for the same statement.
     */
    @Test
    void raisesSpringsLockFailureForASerializationFailure() {
        DataAccessException failure = concurrentUpdateFailure(
            () -> renameAuthor(authors(), "Generated", CONFLICTING_ID)
        );

        assertEquals(CannotAcquireLockException.class, failure.getClass());
        assertMessageOf("RenameAuthor", failure);
        assertEquals("40001", sqlState(failure));

        assertEquals(
            concurrentUpdateFailure(
                () -> jdbcTemplate().update(RENAME_AUTHOR, "Template", CONFLICTING_ID)
            )
                .getClass(),
            failure.getClass()
        );
    }

    /**
     * A {@code :one} read that matched no row raises the
     * {@code EmptyResultDataAccessException} that
     * {@code JdbcTemplate.queryForObject} raises for the same query, with the
     * runtime's cardinality exception as its cause.
     */
    @Test
    void raisesSpringsEmptyResultForAOneReadWithoutARow() {
        DataAccessException failure = raised(() -> getAuthorNamed(authors(), "Nobody"));

        assertEquals(EmptyResultDataAccessException.class, failure.getClass());
        assertEquals(
            "Query 'GetAuthorNamed' in AuthorsRepository returned no row; expected exactly one",
            failure.getMessage()
        );
        assertInstanceOf(QueryCardinalityException.class, failure.getCause());

        assertEquals(
            raised(() -> jdbcTemplate().queryForObject(SELECT_AUTHOR_ID_NAMED, Long.class, "Nobody"))
                .getClass(),
            failure.getClass()
        );
    }

    /**
     * A {@code :one} read that matched two rows raises the
     * {@code IncorrectResultSizeDataAccessException} that
     * {@code JdbcTemplate.queryForObject} raises for the same query, which is
     * that class itself rather than the empty-result subclass of it.
     */
    @Test
    void raisesSpringsIncorrectResultSizeForAOneReadWithSeveralRows() {
        seedTwins();

        DataAccessException failure = raised(() -> getAuthorNamed(authors(), "Twin"));

        assertEquals(IncorrectResultSizeDataAccessException.class, failure.getClass());
        assertEquals(
            "Query 'GetAuthorNamed' in AuthorsRepository"
                + " returned more than one row; expected exactly one",
            failure.getMessage()
        );
        assertInstanceOf(QueryCardinalityException.class, failure.getCause());

        assertEquals(
            raised(() -> jdbcTemplate().queryForObject(SELECT_AUTHOR_ID_NAMED, Long.class, "Twin"))
                .getClass(),
            failure.getClass()
        );
    }

    /**
     * An {@code :optional} read that matched two rows raises the same
     * {@code IncorrectResultSizeDataAccessException}, because at most one row
     * was allowed there too.
     */
    @Test
    void raisesSpringsIncorrectResultSizeForAnOptionalReadWithSeveralRows() {
        seedTwins();

        DataAccessException failure = raised(() -> findAuthorNamed(authors(), "Twin"));

        assertEquals(IncorrectResultSizeDataAccessException.class, failure.getClass());
        assertEquals(
            "Query 'FindAuthorNamed' in AuthorsRepository"
                + " returned more than one row; expected at most one",
            failure.getMessage()
        );
        assertInstanceOf(QueryCardinalityException.class, failure.getCause());

        assertEquals(
            raised(() -> jdbcTemplate().queryForObject(SELECT_AUTHOR_ID_NAMED, Long.class, "Twin"))
                .getClass(),
            failure.getClass()
        );
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

    /**
     * The duplicate key of a batch raises the {@code DuplicateKeyException}
     * the application's own {@code JdbcTemplate} raises for the same batch,
     * which both translate from the driver's {@code BatchUpdateException}, and
     * the generated batch is its own transaction, so it stores no row at all.
     */
    @Test
    void storesNoRowOfAFailingBatchMadeOutsideATransaction() {
        DataAccessException failure = raised(
            () -> insertAuthors(authors(), List.of(authorRow(1L, "Alice"), authorRow(1L, "Bob")))
        );

        assertEquals(DuplicateKeyException.class, failure.getClass());
        assertMessageOf("InsertAuthors", failure);
        assertEquals("23505", sqlState(failure));

        assertEquals(List.of(), storedAuthorNames());

        assertEquals(
            raised(
                () -> jdbcTemplate().batchUpdate(
                    INSERT_AUTHOR,
                    List.of(new Object[] { 1L, "Alice" }, new Object[] { 1L, "Bob" })
                )
            )
                .getClass(),
            failure.getClass()
        );
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

    /**
     * An application's own executor is translated by nothing: a duplicate key
     * a repository running on it hits reaches the caller as the runtime's own
     * {@link UniqueViolationException}, with PostgreSQL's SQLSTATE and the
     * driver's exception as its cause. Such an application wraps its executor
     * in the starter's {@code ExceptionTranslatingQueryExecutor} itself to get
     * Spring's exceptions.
     */
    @Test
    void raisesTheRuntimeUniqueViolationOnAnApplicationExecutor() {
        try (ConfigurableApplicationContext application = start(ApplicationExecutorConfiguration.class)) {
            Object repository = application.getBean(authorsRepositoryClass);

            assertEquals(1, insertAuthor(repository, CONFLICTING_ID, CONFLICTING_NAME));

            UniqueViolationException failure = assertThrows(
                UniqueViolationException.class,
                () -> insertAuthor(repository, CONFLICTING_ID, "Duplicate")
            );

            assertEquals(
                "Failed to execute query 'InsertAuthor' in AuthorsRepository",
                failure.getMessage()
            );

            assertEquals("23505", failure.sqlState());
            assertInstanceOf(SQLException.class, failure.getCause());

            assertEquals(List.of(CONFLICTING_NAME), storedAuthorNames(application));
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

    /**
     * The Spring exception {@code work} raised. Every failure of a generated
     * operation on the starter's executor is one, so a scenario that expects a
     * database failure asserts the exact class on what this returns.
     */
    private static DataAccessException raised(Runnable work) {
        return assertThrows(DataAccessException.class, work::run);
    }

    /** The application's own {@code JdbcTemplate}, which Spring Boot configures. */
    private JdbcTemplate jdbcTemplate() {
        return context.getBean(JdbcTemplate.class);
    }

    /**
     * Asserts that the message of a translated database failure begins with
     * the runtime's, which names {@code query} and its repository.
     */
    private static void assertMessageOf(String query, DataAccessException failure) {
        String message = "Failed to execute query '" + query + "' in " + AUTHORS_GROUP + "Repository";

        assertTrue(
            failure.getMessage().startsWith(message),
            () -> "Expected a message beginning with '" + message + "' but got: " + failure.getMessage()
        );
    }

    /** The SQLSTATE of the driver exception a translated failure carries. */
    private static String sqlState(DataAccessException failure) {
        return assertInstanceOf(SQLException.class, failure.getCause()).getSQLState();
    }

    /** Two authors of one name, which no read of one author allows. */
    private void seedTwins() {
        assertEquals(1, insertAuthor(authors(), 1L, "Twin"));
        assertEquals(1, insertAuthor(authors(), 2L, "Twin"));
    }

    /**
     * Runs {@code update} over the one seeded author in a transaction of the
     * application's own transaction manager that runs at
     * {@code REPEATABLE READ} and has read that author, after a connection of
     * its own has committed an update of the same row, which is what
     * PostgreSQL reports a serialization failure for, and returns the failure
     * the update raised.
     */
    private DataAccessException concurrentUpdateFailure(Runnable update) {
        emptyTablesQuietly();

        assertEquals(1, insertAuthor(authors(), CONFLICTING_ID, CONFLICTING_NAME));

        TransactionTemplate transaction = new TransactionTemplate(
            context.getBean(PlatformTransactionManager.class)
        );

        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        return raised(
            () -> transaction.executeWithoutResult(status -> {
                assertEquals(
                    CONFLICTING_NAME,
                    jdbcTemplate().queryForObject(SELECT_AUTHOR_NAME, String.class, CONFLICTING_ID)
                );

                commitTheRivalUpdate();

                update.run();
            })
        );
    }

    /**
     * Renames the seeded author on a connection of the database itself, which
     * is outside every transaction of the application, and commits it.
     */
    private void commitTheRivalUpdate() {
        try (
            Connection connection = database.getConnection();
            PreparedStatement statement = connection.prepareStatement(RENAME_AUTHOR)
        ) {
            statement.setString(1, "Rival");
            statement.setLong(2, CONFLICTING_ID);

            assertEquals(1, statement.executeUpdate());
        } catch (SQLException failure) {
            throw new IllegalStateException("Failed to commit the rival update", failure);
        }
    }

    private void emptyTablesQuietly() {
        try {
            emptyTables();
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to empty the tables", failure);
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

    private static int insertAward(Object repository, long id, long authorId) {
        return (int) invoke(
            repository,
            "insertAward",
            new Class<?>[] { Long.class, Long.class },
            id,
            authorId
        );
    }

    private static int renameAuthor(Object repository, String name, long id) {
        return (int) invoke(
            repository,
            "renameAuthor",
            new Class<?>[] { String.class, Long.class },
            name,
            id
        );
    }

    private static long getAuthorNamed(Object repository, String name) {
        return (long) invoke(repository, "getAuthorNamed", new Class<?>[] { String.class }, name);
    }

    private static Optional<?> findAuthorNamed(Object repository, String name) {
        return (Optional<?>) invoke(
            repository,
            "findAuthorNamed",
            new Class<?>[] { String.class },
            name
        );
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
            return generatedRepositoryClass(AUTHORS_GROUP + "Repository$InsertAuthorsParams")
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

    /**
     * One generated repository class, loaded from the repositories subpackage
     * of the configured package, which is where the generator writes it.
     */
    private Class<?> generatedRepositoryClass(String simpleName) throws ClassNotFoundException {
        return Class.forName(
            generatedPackage() + ".repositories." + simpleName,
            true,
            classLoader
        );
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

        new SqlcjCompiler().compile(config, tempDir.resolve("sqlcj.yaml"));

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

        try (Stream<Path> generatedFiles = Files.walk(packageDirectory)) {
            generatedFiles
                .filter(Files::isRegularFile)
                .map(Path::toString)
                .filter(path -> path.endsWith(".java"))
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
