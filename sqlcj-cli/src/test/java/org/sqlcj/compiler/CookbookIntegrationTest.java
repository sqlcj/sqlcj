package org.sqlcj.compiler;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.sqlcj.config.Config;
import org.sqlcj.config.JavaConfig;
import org.sqlcj.config.SqlConfig;
import org.sqlcj.runtime.JdbcQueryExecutor;
import org.sqlcj.runtime.JdbcTransactions;
import org.sqlcj.runtime.QueryExecutor;
import org.sqlcj.runtime.TransactionCallback;
import org.sqlcj.runtime.TransactionConflictException;
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
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Proves the cookbook's SQL recipes against a real PostgreSQL 16 instance: the
 * newsletter fixture's migration and query file generate with no rejected
 * query, the generated tree compiles together with the recipes' hand-written
 * Java, and each recipe returns the rows its section claims against a seeded
 * database.
 *
 * <p>The fixture under {@code src/test/resources/cookbook} is the cookbook's
 * single source: its migration, its named SQL, and the ten recipe classes,
 * which live in the hand-written root package and import the generated
 * repository, row records, and enum from its subpackages.
 *
 * <p>The whole class is skipped when Docker is unavailable, so that
 * {@code mvn test} stays runnable without Docker. Setting
 * {@code -Dsqlcj.test.requireDocker=true}, as continuous integration does,
 * turns an unavailable Docker into a failure instead of a skip.
 */
@EnabledIf("dockerAvailable")
class CookbookIntegrationTest {

    /**
     * System property that forbids skipping these tests. Continuous
     * integration sets it so that a broken Docker environment fails the build
     * instead of silently reducing coverage.
     */
    private static final String REQUIRE_DOCKER_PROPERTY = "sqlcj.test.requireDocker";

    /** The configured query-group name, which generates {@code NewsletterRepository}. */
    private static final String GROUP = "Newsletter";

    /** The package the recipes are written in and the tree is generated into. */
    private static final String PACKAGE = "com.example.newsletter";

    private static final OffsetDateTime JANUARY = OffsetDateTime.of(2026, 1, 5, 9, 0, 0, 0, ZoneOffset.UTC);

    private static final OffsetDateTime FEBRUARY = OffsetDateTime.of(2026, 2, 10, 9, 0, 0, 0, ZoneOffset.UTC);

    private static final OffsetDateTime MARCH = OffsetDateTime.of(2026, 3, 1, 9, 0, 0, 0, ZoneOffset.UTC);

    private static final OffsetDateTime APRIL = OffsetDateTime.of(2026, 4, 15, 9, 0, 0, 0, ZoneOffset.UTC);

    /** The moment the batch recipe records its views at. */
    private static final OffsetDateTime VIEWED_AT = OffsetDateTime.of(2026, 5, 1, 12, 0, 0, 0, ZoneOffset.UTC);

    /** The moment every article the transaction and draft recipes write is published at. */
    private static final OffsetDateTime MAY = OffsetDateTime.of(2026, 5, 20, 9, 0, 0, 0, ZoneOffset.UTC);

    /** The slug the replacement of both transaction recipes is stored under. */
    private static final String REPLACEMENT_SLUG = "the-cost-of-indexes-revisited";

    private static final String REPLACEMENT_TITLE = "The Cost of Indexes, Revisited";

    /** The title the rival transaction of the retry test commits. */
    private static final String RIVAL_TITLE = "The Cost of Indexes, Edited";

    /**
     * The seeded articles, in the order they are inserted, so that the
     * restarted identity gives them ascending ids. The two March articles share
     * one {@code published_at}, which is the tie the keyset key breaks by id.
     */
    private static final List<SeededArticle> ARTICLES = List.of(
        new SeededArticle("the-cost-of-indexes", "The Cost of Indexes", "published", JANUARY),
        new SeededArticle("keyset-pagination", "Keyset Pagination in SQL", "published", FEBRUARY),
        new SeededArticle("query-planning-notes", "Notes on Query Planning", "draft", MARCH),
        new SeededArticle("the-first-release", "The First Release", "archived", MARCH),
        new SeededArticle("pagination-at-scale", "Pagination at Scale", "published", APRIL)
    );

    /** The seeded slugs in the {@code (published_at DESC, id DESC)} order both page recipes read. */
    private static final List<String> FEED = List.of(
        "pagination-at-scale",
        "the-first-release",
        "query-planning-notes",
        "keyset-pagination",
        "the-cost-of-indexes"
    );

    @TempDir
    static Path tempDir;

    private static PostgreSQLContainer<?> postgres;

    private static DataSource dataSource;

    private static URLClassLoader classLoader;

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
     * Starts the database, applies the fixture's migration to it, generates the
     * fixture's query group, and compiles the generated tree together with the
     * recipes.
     */
    @BeforeAll
    static void startPostgresAndBuildFixtures() throws Exception {
        postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine")
        );

        postgres.start();

        PGSimpleDataSource postgresDataSource = new PGSimpleDataSource();

        postgresDataSource.setUrl(postgres.getJdbcUrl());
        postgresDataSource.setUser(postgres.getUsername());
        postgresDataSource.setPassword(postgres.getPassword());

        dataSource = postgresDataSource;

        execute(Files.readString(resource("cookbook/migrations/V1__newsletter.sql")));

        classLoader = new URLClassLoader(
            new URL[] { generateAndCompile().toUri().toURL() },
            CookbookIntegrationTest.class.getClassLoader()
        );
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (classLoader != null) {
            classLoader.close();
        }

        if (postgres != null) {
            postgres.stop();
        }
    }

    /** Empties the fixture's tables and seeds the articles every recipe reads. */
    @BeforeEach
    void seedDatabase() throws Exception {
        execute("TRUNCATE articles, subscribers, article_views RESTART IDENTITY");

        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO articles (slug, title, status, published_at) VALUES (?, ?, ?::article_status, ?)"
            )
        ) {
            for (SeededArticle article : ARTICLES) {
                statement.setString(1, article.slug());
                statement.setString(2, article.title());
                statement.setString(3, article.status());
                statement.setObject(4, article.publishedAt());

                assertEquals(1, statement.executeUpdate());
            }
        }
    }

    /**
     * The offset recipe turns a page number into the offset its query skips, so
     * two consecutive pages return the newest articles in the feed's order and
     * share no article.
     */
    @Test
    void shouldPageArticlesByOffset() throws Exception {
        Object recipe = recipe("ArticlePages");
        Object repository = repository();

        List<String> firstPage = slugs((List<?>) invoke(recipe, "page", repository, 1, 2));
        List<String> secondPage = slugs((List<?>) invoke(recipe, "page", repository, 2, 2));

        assertEquals(FEED.subList(0, 2), firstPage);

        assertEquals(FEED.subList(2, 4), secondPage);

        assertTrue(
            firstPage.stream().noneMatch(secondPage::contains),
            "consecutive offset pages share no article"
        );

        assertEquals(
            FEED.subList(4, 5),
            slugs((List<?>) invoke(recipe, "page", repository, 3, 2)),
            "the last page returns the articles that remain"
        );
    }

    /**
     * The keyset recipe follows the feed from the key of the last row of the
     * page before, so the pages together visit every article exactly once, in
     * the feed's order, the two articles of one {@code published_at} included.
     */
    @Test
    void shouldPageArticlesByKeyset() throws Exception {
        Object recipe = recipe("ArticleFeed");
        Object repository = repository();

        List<String> visited = new ArrayList<>();

        List<?> page = (List<?>) invoke(recipe, "firstPage", repository, 2);

        while (!page.isEmpty()) {
            visited.addAll(slugs(page));

            page = (List<?>) invoke(recipe, "nextPage", repository, page, 2);
        }

        assertEquals(FEED, visited);

        assertEquals(
            List.of(),
            invoke(recipe, "nextPage", repository, List.of(), 2),
            "nothing follows an empty page"
        );
    }

    /**
     * The criteria recipe returns every article when every criterion is null,
     * narrows the result by the search, the status list, and the published-since
     * bound, and counts the unpaged matches beside the page it returns.
     */
    @Test
    void shouldSearchArticlesByCriteria() throws Exception {
        Object recipe = recipe("ArticleSearch");
        Object repository = repository();

        Object everything = search(recipe, repository, null, null, null, 1, 10);

        assertEquals(FEED, pageSlugs(everything), "null criteria match every article");

        assertEquals(5L, total(everything));

        Object searched = search(recipe, repository, "paginat", null, null, 1, 10);

        assertEquals(
            List.of("pagination-at-scale", "keyset-pagination"),
            pageSlugs(searched),
            "the search matches the titles that contain it, ignoring case"
        );

        assertEquals(2L, total(searched));

        Object published = search(
            recipe,
            repository,
            null,
            List.of(articleStatus("PUBLISHED")),
            null,
            1,
            10
        );

        assertEquals(
            List.of("pagination-at-scale", "keyset-pagination", "the-cost-of-indexes"),
            pageSlugs(published),
            "the status list keeps the articles of the listed status"
        );

        assertEquals(3L, total(published));

        Object since = search(recipe, repository, null, null, MARCH, 1, 10);

        assertEquals(
            List.of("pagination-at-scale", "the-first-release", "query-planning-notes"),
            pageSlugs(since),
            "the since bound keeps the articles published at or after it"
        );

        assertEquals(3L, total(since));

        Object onePage = search(recipe, repository, null, null, null, 1, 2);

        assertEquals(FEED.subList(0, 2), pageSlugs(onePage));

        assertEquals(5L, total(onePage), "the count counts the unpaged matches");
    }

    /**
     * The upsert recipe inserts the subscriber of a new address and renames the
     * subscriber of an address it already stored, keeping that subscriber's id
     * and leaving one row.
     */
    @Test
    void shouldUpsertASubscriber() throws Exception {
        Object recipe = recipe("SubscriberSignup");
        Object repository = repository();

        Object inserted = invoke(recipe, "subscribe", repository, "ada@example.com", "Ada");

        assertNotNull(component(inserted, "id"));

        assertEquals("ada@example.com", component(inserted, "email"));

        assertEquals("Ada", component(inserted, "name"));

        Object updated = invoke(recipe, "subscribe", repository, "ada@example.com", "Ada Lovelace");

        assertEquals(
            component(inserted, "id"),
            component(updated, "id"),
            "the upsert keeps the id of the subscriber it renames"
        );

        assertEquals("Ada Lovelace", component(updated, "name"));

        assertNotEquals(component(inserted, "name"), component(updated, "name"));

        assertEquals(1L, subscriberCount(), "the second sign-up left one row");
    }

    /**
     * The batch recipe writes one view row per article id and reports one count
     * of 1 for each, while an empty list executes nothing and returns an empty
     * array.
     */
    @Test
    void shouldRecordArticleViewsInOneBatch() throws Exception {
        Object recipe = recipe("ArticleViewLog");
        Object repository = repository();

        assertArrayEquals(
            new int[] { 1, 1 },
            (int[]) invoke(
                recipe,
                "recordViews",
                repository,
                List.of(articleId("the-cost-of-indexes"), articleId("pagination-at-scale")),
                VIEWED_AT
            )
        );

        assertEquals(
            List.of(
                "the-cost-of-indexes at " + VIEWED_AT.toInstant(),
                "pagination-at-scale at " + VIEWED_AT.toInstant()
            ),
            recordedViews(),
            "the batch stored one view of each article it was given"
        );

        assertArrayEquals(
            new int[0],
            (int[]) invoke(recipe, "recordViews", repository, List.of(), VIEWED_AT),
            "an empty batch returns an empty array"
        );

        assertEquals(2, recordedViews().size(), "the empty batch stored no view");
    }

    /**
     * The id list recipe returns the rows of exactly the ids it is given, in id
     * order, and ignores an id no article has.
     */
    @Test
    void shouldListArticlesByIds() throws Exception {
        Object recipe = recipe("ArticleLookup");
        Object repository = repository();

        long indexes = articleId("the-cost-of-indexes");
        long scale = articleId("pagination-at-scale");

        assertEquals(
            List.of("the-cost-of-indexes", "pagination-at-scale"),
            slugs(
                (List<?>) invoke(
                    recipe,
                    "byIds",
                    repository,
                    List.of(scale, indexes, scale + 1_000L)
                )
            ),
            "the listed ids are returned in id order and the absent id is ignored"
        );
    }

    /**
     * The plain transaction recipe archives an article and publishes its
     * replacement as one unit of work: a replacement whose slug another article
     * holds fails with the unique violation its caller sees and leaves the
     * archiving undone, while a free slug commits both writes.
     */
    @Test
    void shouldReplaceAnArticleInOneTransaction() throws Exception {
        Object recipe = recipe("ArticleReplacement");
        JdbcTransactions transactions = new JdbcTransactions(dataSource);

        long articleId = articleId("the-cost-of-indexes");

        UniqueViolationException failure = assertThrows(
            UniqueViolationException.class,
            () -> invokeUnwrapped(
                recipe,
                "replace",
                transactions,
                articleId,
                "keyset-pagination",
                REPLACEMENT_TITLE,
                MAY
            )
        );

        assertEquals("23505", failure.sqlState());

        assertRolledBackReplacement();

        assertCommittedReplacement(
            invokeUnwrapped(
                recipe,
                "replace",
                transactions,
                articleId,
                REPLACEMENT_SLUG,
                REPLACEMENT_TITLE,
                MAY
            )
        );
    }

    /**
     * The Spring recipe makes the same two writes in one
     * {@code @Transactional} method: the generated repository bean, built over
     * the executor on the transaction-aware data source, takes part in the
     * transaction Spring opens, so the taken slug rolls the archiving back and
     * the free slug commits both writes.
     */
    @Test
    void shouldReplaceAnArticleInASpringTransaction() throws Exception {
        long articleId = articleId("the-cost-of-indexes");

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.setClassLoader(classLoader);
            context.register(
                TransactionConfiguration.class,
                fixtureClass("repositories." + GROUP + "Repository"),
                fixtureClass("ArticleReplacementService")
            );
            context.refresh();

            Object service = context.getBean(fixtureClass("ArticleReplacementService"));

            UniqueViolationException failure = assertThrows(
                UniqueViolationException.class,
                () -> invokeUnwrapped(
                    service,
                    "replace",
                    articleId,
                    "keyset-pagination",
                    REPLACEMENT_TITLE,
                    MAY
                )
            );

            assertEquals("23505", failure.sqlState());

            assertRolledBackReplacement();

            assertCommittedReplacement(
                invokeUnwrapped(service, "replace", articleId, REPLACEMENT_SLUG, REPLACEMENT_TITLE, MAY)
            );
        }
    }

    /**
     * The duplicate key recipe stores the draft of a free slug and reports a
     * slug the newsletter already uses as an empty result, leaving the article
     * that holds the slug as it was.
     */
    @Test
    void shouldReportATakenSlugAsAnEmptyDraft() throws Exception {
        Object recipe = recipe("ArticleDrafts");
        Object repository = repository();

        Optional<?> draft = (Optional<?>) invoke(
            recipe,
            "createDraft",
            repository,
            "indexes-in-practice",
            "Indexes in Practice",
            MAY
        );

        assertTrue(draft.isPresent(), "the free slug stored a draft");

        assertEquals(articleStatus("DRAFT"), component(draft.get(), "status"));

        assertEquals("Indexes in Practice", component(draft.get(), "title"));

        assertEquals(
            Optional.empty(),
            invoke(
                recipe,
                "createDraft",
                repository,
                "indexes-in-practice",
                "Indexes in Practice, Again",
                MAY
            ),
            "the taken slug is reported instead of raised"
        );

        assertEquals(
            "Indexes in Practice",
            storedTitle("indexes-in-practice"),
            "the second draft stored nothing"
        );

        assertEquals(6L, articleCount());
    }

    /**
     * The retry recipe runs its unit of work again after the serialization
     * failure a rival transaction causes, and gives up after its last attempt:
     * with three attempts allowed and one rival, the work succeeds on its
     * second attempt; with a rival on every attempt and two allowed, the
     * conflict of the second attempt reaches the caller and no attempt's write
     * survives.
     */
    @Test
    void shouldRetryAConflictingTransactionUpToItsBound() throws Exception {
        Object recipe = recipe("RetryingTransactions");
        JdbcTransactions transactions = new JdbcTransactions(serializableDataSource());

        AtomicInteger attempts = new AtomicInteger();

        assertEquals(
            1,
            (int) invokeUnwrapped(
                recipe,
                "inTransaction",
                transactions,
                3,
                archiving(articleId("the-cost-of-indexes"), 1, attempts)
            ),
            "the attempt that survived archived the article"
        );

        assertEquals(2, attempts.get(), "the work was run again exactly once");

        assertEquals("archived", storedStatus("the-cost-of-indexes"));

        assertEquals(
            RIVAL_TITLE,
            storedTitle("the-cost-of-indexes"),
            "the retried attempt read the title the rival committed"
        );

        // The exhausted retry is asserted against the published article again.
        seedDatabase();

        attempts.set(0);

        TransactionConflictException failure = assertThrows(
            TransactionConflictException.class,
            () -> invokeUnwrapped(
                recipe,
                "inTransaction",
                transactions,
                2,
                archiving(articleId("the-cost-of-indexes"), 2, attempts)
            )
        );

        assertEquals("40001", failure.sqlState());

        assertEquals(2, attempts.get(), "the retry stopped after its last attempt");

        assertEquals(
            "published",
            storedStatus("the-cost-of-indexes"),
            "every attempt of the exhausted retry was rolled back"
        );
    }

    /**
     * Generates the fixture's query group from its migration directory, then
     * compiles the generated tree and the recipes together into an isolated
     * temporary classes directory.
     */
    private static Path generateAndCompile() throws Exception {
        Path generatedDirectory = tempDir.resolve("generated");
        Path classesDirectory = tempDir.resolve("classes");

        Config config = new Config(
            List.of(
                new SqlConfig(
                    GROUP,
                    resource("cookbook/migrations").toString(),
                    resource("cookbook/queries.sql").toString()
                )
            ),
            new JavaConfig(
                generatedDirectory.toString(),
                PACKAGE
            )
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

        arguments.addAll(javaSources(generatedDirectory));
        arguments.addAll(javaSources(resource("cookbook/java")));

        assertEquals(
            0,
            compilerApi.run(
                null,
                null,
                null,
                arguments.toArray(new String[0])
            ),
            "the generated tree and the recipes compile together"
        );

        return classesDirectory;
    }

    /** Every Java source of one directory tree, in a stable order. */
    private static List<String> javaSources(Path directory) throws Exception {
        try (Stream<Path> paths = Files.walk(directory)) {
            List<String> sources = paths
                .map(Path::toString)
                .filter(path -> path.endsWith(".java"))
                .sorted()
                .toList();

            assertFalse(sources.isEmpty(), "no Java source under " + directory);

            return sources;
        }
    }

    /** One cookbook fixture file, resolved from the test classpath. */
    private static Path resource(String name) throws Exception {
        URL url = CookbookIntegrationTest.class.getResource("/" + name);

        assertNotNull(url, "missing cookbook fixture: " + name);

        return Paths.get(url.toURI());
    }

    /**
     * A data source whose connections open {@code SERIALIZABLE} transactions,
     * which is the isolation the conflict the retry recipe survives needs.
     */
    private static DataSource serializableDataSource() {
        PGSimpleDataSource serializable = new PGSimpleDataSource();

        serializable.setUrl(postgres.getJdbcUrl());
        serializable.setUser(postgres.getUsername());
        serializable.setPassword(postgres.getPassword());
        serializable.setOptions("-c default_transaction_isolation=serializable");

        return serializable;
    }

    /** One class of the compiled fixture, named relative to its root package. */
    private static Class<?> fixtureClass(String name) throws Exception {
        return Class.forName(PACKAGE + "." + name, true, classLoader);
    }

    /** The generated repository, bound to the database the recipes run against. */
    private Object repository() throws Exception {
        return repository(new JdbcQueryExecutor(dataSource));
    }

    /** The generated repository, bound to the given executor. */
    private Object repository(QueryExecutor executor) throws Exception {
        return fixtureClass("repositories." + GROUP + "Repository")
            .getConstructor(QueryExecutor.class)
            .newInstance(executor);
    }

    /** One hand-written recipe of the fixture, loaded from the compiled output. */
    private Object recipe(String simpleName) throws Exception {
        return fixtureClass(simpleName)
            .getConstructor()
            .newInstance();
    }

    /** One constant of the generated enum, loaded from the compiled output. */
    private Object articleStatus(String constant) throws Exception {
        return fixtureClass("enums.ArticleStatus")
            .getField(constant)
            .get(null);
    }

    /** Calls one recipe method, which has one name of its own. */
    private Object invoke(Object recipe, String methodName, Object... arguments) throws Exception {
        for (Method method : recipe.getClass().getMethods()) {
            if (method.getName().equals(methodName)) {
                return method.invoke(recipe, arguments);
            }
        }

        return fail("missing recipe method: " + methodName);
    }

    /**
     * Calls one method of the compiled fixture and rethrows a runtime failure
     * of it unchanged, so a test asserts the exception a caller of the recipe
     * sees rather than the wrapper the reflective call adds.
     */
    private Object invokeUnwrapped(Object target, String methodName, Object... arguments) throws Exception {
        try {
            return invoke(target, methodName, arguments);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException failure) {
                throw failure;
            }

            throw e;
        }
    }

    /**
     * The unit of work the retry recipe runs: inside its own transaction it
     * reads the article, lets a rival transaction commit a new title of that
     * article on the first {@code rivalAttempts} attempts, so that its own
     * write of the same row raises the serialization failure, and archives the
     * article.
     */
    private TransactionCallback<Object, Exception> archiving(
        long articleId,
        int rivalAttempts,
        AtomicInteger attempts
    ) {
        return connection -> {
            int attempt = attempts.incrementAndGet();

            Object newsletter = repository(new JdbcQueryExecutor(connection));

            // The read takes the transaction's snapshot, which the update the
            // rival commits next leaves behind.
            assertEquals(
                1,
                ((List<?>) invokeUnwrapped(newsletter, "listArticlesByIds", List.of(articleId))).size(),
                "the attempt read the article it archives"
            );

            if (attempt <= rivalAttempts) {
                commitRivalTitle(articleId);
            }

            return invokeUnwrapped(newsletter, "setArticleStatus", articleStatus("ARCHIVED"), articleId);
        };
    }

    /**
     * Commits a new title of one article on a separate connection of its own,
     * which the retried transaction's update of that article conflicts with.
     */
    private void commitRivalTitle(long articleId) throws Exception {
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(
                "UPDATE articles SET title = ? WHERE id = ?"
            )
        ) {
            statement.setString(1, RIVAL_TITLE);
            statement.setLong(2, articleId);

            assertEquals(1, statement.executeUpdate());
        }
    }

    /**
     * Asserts the state a committed replacement leaves: the returned row is the
     * published replacement, the article it replaces is archived, and the
     * replacement is the newsletter's sixth article.
     */
    private void assertCommittedReplacement(Object replacement) throws Exception {
        assertEquals(REPLACEMENT_SLUG, component(replacement, "slug"));

        assertEquals(articleStatus("PUBLISHED"), component(replacement, "status"));

        assertEquals("published", storedStatus(REPLACEMENT_SLUG));

        assertEquals(
            "archived",
            storedStatus("the-cost-of-indexes"),
            "the same transaction committed the archiving too"
        );

        assertEquals(6L, articleCount());
    }

    /**
     * Asserts the state a rolled back replacement leaves: the article it would
     * have replaced is still published and no replacement was stored.
     */
    private void assertRolledBackReplacement() throws Exception {
        assertEquals(
            "published",
            storedStatus("the-cost-of-indexes"),
            "the rolled back transaction left the archiving undone"
        );

        assertEquals(5L, articleCount(), "the rolled back transaction stored no replacement");
    }

    private Object search(
        Object recipe,
        Object repository,
        String titleContains,
        List<?> statuses,
        OffsetDateTime publishedSince,
        int pageNumber,
        int pageSize
    ) throws Exception {
        return invoke(
            recipe,
            "search",
            repository,
            titleContains,
            statuses,
            publishedSince,
            pageNumber,
            pageSize
        );
    }

    /** The slugs of the page one search result holds, in the result's order. */
    private List<String> pageSlugs(Object result) throws Exception {
        return slugs((List<?>) component(result, "articles"));
    }

    private long total(Object result) throws Exception {
        return (long) component(result, "total");
    }

    /** The slugs of one article result, in the result's order. */
    private List<String> slugs(List<?> rows) throws Exception {
        List<String> slugs = new ArrayList<>();

        for (Object row : rows) {
            slugs.add((String) component(row, "slug"));
        }

        return slugs;
    }

    private Object component(Object record, String componentName) throws Exception {
        return record
            .getClass()
            .getMethod(componentName)
            .invoke(record);
    }

    /** The id of one seeded article, read outside the generated repository. */
    private long articleId(String slug) throws Exception {
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM articles WHERE slug = ?"
            )
        ) {
            statement.setString(1, slug);

            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), "missing seeded article: " + slug);

                return rows.getLong(1);
            }
        }
    }

    /** The stored status of one article, read outside the generated repository. */
    private String storedStatus(String slug) throws Exception {
        return storedColumn("SELECT status::text FROM articles WHERE slug = ?", slug);
    }

    /** The stored title of one article, read outside the generated repository. */
    private String storedTitle(String slug) throws Exception {
        return storedColumn("SELECT title FROM articles WHERE slug = ?", slug);
    }

    /** One text column of the article one slug names. */
    private String storedColumn(String sql, String slug) throws Exception {
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, slug);

            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), "missing article: " + slug);

                return rows.getString(1);
            }
        }
    }

    /** The stored views, as the slug and moment of each, in article id order. */
    private List<String> recordedViews() throws Exception {
        List<String> views = new ArrayList<>();

        try (
            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(
                """
                    SELECT a.slug, v.viewed_at
                    FROM article_views v
                             JOIN articles a ON a.id = v.article_id
                    ORDER BY v.article_id
                    """
            )
        ) {
            while (rows.next()) {
                views.add(
                    rows.getString(1) + " at " + rows.getObject(2, OffsetDateTime.class).toInstant()
                );
            }
        }

        return views;
    }

    private long articleCount() throws Exception {
        return count("SELECT COUNT(*) FROM articles");
    }

    private long subscriberCount() throws Exception {
        return count("SELECT COUNT(*) FROM subscribers");
    }

    private long count(String sql) throws Exception {
        try (
            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(sql)
        ) {
            assertTrue(rows.next());

            return rows.getLong(1);
        }
    }

    private static void execute(String sql) throws Exception {
        try (
            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement()
        ) {
            statement.execute(sql);
        }
    }

    /** One article the fixture seeds before each test. */
    private record SeededArticle(String slug, String title, String status, OffsetDateTime publishedAt) {
    }

    /**
     * The plain Spring context the {@code @Transactional} recipe runs in: a
     * transaction manager over the test's data source, and the one executor
     * every generated repository bean is built over, which runs its statements
     * on a transaction-aware view of the same data source, as the sqlcj Spring
     * Boot starter configures it. The recipe's own service and repository are
     * registered by the test.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfiguration {

        @Bean
        DataSourceTransactionManager transactionManager() {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        QueryExecutor queryExecutor() {
            return new JdbcQueryExecutor(new TransactionAwareDataSourceProxy(dataSource));
        }
    }
}
