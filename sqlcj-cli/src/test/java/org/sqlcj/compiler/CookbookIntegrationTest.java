package org.sqlcj.compiler;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.sqlcj.config.Config;
import org.sqlcj.config.JavaConfig;
import org.sqlcj.config.SqlConfig;
import org.sqlcj.runtime.JdbcQueryExecutor;
import org.sqlcj.runtime.QueryExecutor;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * single source: its migration, its named SQL, and the six recipe classes,
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

    /** The generated repository, bound to the database the recipes run against. */
    private Object repository() throws Exception {
        return Class.forName(PACKAGE + ".repositories." + GROUP + "Repository", true, classLoader)
            .getConstructor(QueryExecutor.class)
            .newInstance(new JdbcQueryExecutor(dataSource));
    }

    /** One hand-written recipe of the fixture, loaded from the compiled output. */
    private Object recipe(String simpleName) throws Exception {
        return Class.forName(PACKAGE + "." + simpleName, true, classLoader)
            .getConstructor()
            .newInstance();
    }

    /** One constant of the generated enum, loaded from the compiled output. */
    private Object articleStatus(String constant) throws Exception {
        return Class.forName(PACKAGE + ".enums.ArticleStatus", true, classLoader)
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

    private long subscriberCount() throws Exception {
        try (
            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM subscribers")
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
}
