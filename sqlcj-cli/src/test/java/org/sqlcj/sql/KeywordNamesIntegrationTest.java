package org.sqlcj.sql;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.sqlcj.compiler.SqlcjCompiler;
import org.sqlcj.config.Config;
import org.sqlcj.config.JavaConfig;
import org.sqlcj.config.SqlConfig;
import org.sqlcj.runtime.JdbcQueryExecutor;
import org.sqlcj.schema.Schema;
import org.sqlcj.schema.parser.DefaultSchemaParser;
import org.sqlcj.schema.parser.SchemaParseException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Proves the milestone acceptance for names PostgreSQL does not reserve: every
 * word PostgreSQL 16 accepts unquoted as a name, plus {@code settings} and
 * {@code uuid}, loads and compiles in every schema and query position sqlcj
 * models, keeps its spelling in the executable SQL, and runs against a real
 * PostgreSQL 16 server, while the keyword uses of those same words keep their
 * meaning.
 *
 * <p>The words come from a committed list of PostgreSQL 16's own
 * {@code pg_get_keywords()} categories {@code U} and {@code C}, which the
 * PostgreSQL tests below check against the server they run against, together
 * with {@link KeywordNames#POSTGRESQL_RESERVED}. The schema and the queries of
 * each word are built from templates into a temporary directory, so no
 * generated source, class file, or SQL file is left in the repository.
 *
 * <p>Only the tests that need a database are skipped when Docker is
 * unavailable, so that {@code mvn test} stays runnable without Docker and the
 * generation, compilation, and diagnostic tests always run. Setting
 * {@code -Dsqlcj.test.requireDocker=true}, as continuous integration does,
 * turns an unavailable Docker into a failure instead of a skip.
 */
class KeywordNamesIntegrationTest {

    /**
     * System property that forbids skipping the PostgreSQL tests. Continuous
     * integration sets it so that a broken Docker environment fails the build
     * instead of silently reducing coverage.
     */
    private static final String REQUIRE_DOCKER_PROPERTY = "sqlcj.test.requireDocker";

    /** The committed list of PostgreSQL 16's unreserved keywords. */
    private static final String WORD_LIST = "/keyword-names/postgresql-16-unreserved-keywords.txt";

    /**
     * The words PostgreSQL keeps no keyword of at all, which the fixture adds to
     * the committed list. The outside review's first project failed on a
     * {@code settings jsonb} column, and a public {@code uuid} column is as
     * ordinary.
     */
    private static final List<String> NO_KEYWORD_WORDS = List.of("settings", "uuid");

    /** The words each generated repository covers, which keeps each class small. */
    private static final int WORDS_PER_GROUP = 40;

    /** The queries the fixture states for each word. */
    private static final int QUERIES_PER_WORD = 7;

    /** The group whose queries use the same words as keywords. */
    private static final String USES_GROUP = "Uses";

    /** The schema the keyword uses read. */
    private static final String USES_SCHEMA = """
        CREATE TABLE events (
            id bigint PRIMARY KEY,
            at timestamp NOT NULL,
            amount numeric NOT NULL,
            label text NOT NULL
        );
        """;

    /** The keyword uses that must keep their meaning. */
    private static final String USES_QUERIES = """
        -- name: ExtractDay :many
        SELECT (extract(day FROM at))::bigint AS day_number FROM events;

        -- name: IntervalDay :many
        SELECT (at + interval '1' day)::timestamp AS later FROM events;

        -- name: FetchNext :many
        SELECT id FROM events ORDER BY id FETCH NEXT 10 ROWS ONLY;

        -- name: NotBetween :many
        SELECT id FROM events WHERE amount NOT BETWEEN :low AND :high;

        -- name: ExistsRow :many
        SELECT e.id FROM events e WHERE EXISTS (SELECT 1 FROM events o WHERE o.id = e.id + 1);

        -- name: CastDouble :many
        SELECT CAST(amount AS double precision) AS value FROM events;

        -- name: RunningTotal :many
        SELECT
            id,
            (sum(amount) OVER (ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW))::numeric AS running
        FROM events;

        -- name: GroupedSets :many
        SELECT count(*) AS rows_counted FROM events GROUP BY GROUPING SETS ((label), ());

        -- name: NotBetweenSigned :many
        SELECT id FROM events WHERE amount NOT BETWEEN -1 AND 1;

        -- name: NotBetweenSignedName :many
        SELECT label FROM events
        WHERE amount NOT BETWEEN +1 AND 2 OR amount NOT BETWEEN -5 AND -1;

        -- name: UpsertLabel :one
        INSERT INTO events (id, at, amount, label) VALUES (:id, :at, :amount, :label)
        ON CONFLICT (id) DO UPDATE SET label = :newLabel RETURNING id;
        """;

    /** The identifier every generated method is called with. */
    private static final Long ROW_ID = 1L;

    /** The text every generated method is called with. */
    private static final String TEXT_VALUE = "zy";

    /** The fixture's words, the committed list by its category. */
    private static Map<String, String> categories;

    /** The committed words and {@link #NO_KEYWORD_WORDS}, in list order. */
    private static List<String> words;

    @TempDir
    static Path fixtureDirectory;

    /** The directory the fixture's repositories were generated into. */
    private static Path generatedDirectory;

    /** The directory the generated sources were compiled into. */
    private static Path classesDirectory;

    private static PostgreSQLContainer<?> postgres;

    private static DataSource dataSource;

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
     * Writes the schema and the queries of every word, and of the keyword uses,
     * into the temporary directory, generates them with the compiler, and
     * compiles every generated source together.
     */
    @BeforeAll
    static void generateAndCompileTheFixture() throws Exception {
        categories = readWordList();
        words = new ArrayList<>(categories.keySet());

        words.addAll(NO_KEYWORD_WORDS);

        List<SqlConfig> groups = new ArrayList<>();

        for (int group = 0; group * WORDS_PER_GROUP < words.size(); group++) {
            StringBuilder schema = new StringBuilder();
            StringBuilder queries = new StringBuilder();
            int last = Math.min(words.size(), (group + 1) * WORDS_PER_GROUP);

            for (int word = group * WORDS_PER_GROUP; word < last; word++) {
                schema.append(schemaOf(words.get(word), word));
                queries.append(queriesOf(words.get(word), word));
            }

            groups.add(
                new SqlConfig(
                    "Keywords" + group,
                    write("schema-" + group + ".sql", schema.toString()).toString(),
                    write("queries-" + group + ".sql", queries.toString()).toString()
                )
            );
        }

        groups.add(
            new SqlConfig(
                USES_GROUP,
                write("uses-schema.sql", USES_SCHEMA).toString(),
                write("uses-queries.sql", USES_QUERIES).toString()
            )
        );

        generatedDirectory = fixtureDirectory.resolve("generated");

        new SqlcjCompiler().compile(
            new Config(groups, new JavaConfig(generatedDirectory.toString(), "generated")),
            fixtureDirectory.resolve("sqlcj.yaml")
        );

        classesDirectory = compile(generatedDirectory, fixtureDirectory.resolve("classes"));
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    /**
     * Every word of the committed list, plus {@code settings} and {@code uuid},
     * is read as a name in every position: the schema of all of them loads as
     * one source, every query of every word compiles, and every generated source
     * compiles as Java.
     */
    @Test
    void shouldGenerateAndCompileEveryPositionOfEveryWord() throws Exception {
        assertEquals(372, words.size(), "370 unreserved keywords, settings, and uuid");

        assertEquals(
            315,
            categories.values().stream().filter("U"::equals).count(),
            "PostgreSQL 16 lists 315 unreserved keywords"
        );

        assertEquals(
            55,
            categories.values().stream().filter("C"::equals).count(),
            "PostgreSQL 16 lists 55 keywords that are unreserved but no function or type name"
        );

        assertEquals(
            101,
            KeywordNames.POSTGRESQL_RESERVED.size(),
            "PostgreSQL 16 reserves 101 words"
        );

        assertTrue(
            Set.copyOf(words).stream().noneMatch(KeywordNames.POSTGRESQL_RESERVED::contains),
            "no word of the fixture is a word PostgreSQL reserves"
        );

        assertEquals(
            words.size() * QUERIES_PER_WORD,
            generatedMethodCount(),
            "every query of every word generates one method"
        );

        assertTrue(
            Files.exists(classesDirectory.resolve("generated/repositories/Keywords0Repository.class")),
            "the generated sources compiled"
        );
    }

    /**
     * The executable SQL of a query keeps every name as the user wrote it: the
     * compiled statement differs from the source only in its placeholders, and
     * the generated wildcard expansion quotes the name sqlcj itself writes, as
     * it does for any name.
     */
    @Test
    void shouldKeepTheExecutableSqlAsWritten() throws Exception {
        Path schema = write(
            "written-schema.sql",
            "CREATE TABLE users (id bigint PRIMARY KEY, settings text NOT NULL);\n"
        );

        Path queries = write(
            "written-queries.sql",
            """
                -- name: ReadSettings :many
                SELECT settings FROM users WHERE settings = :s;

                -- name: ReadEveryColumn :many
                SELECT * FROM users WHERE settings = :s;
                """
        );

        Path generated = fixtureDirectory.resolve("written");

        new SqlcjCompiler().compile(
            new Config(
                List.of(new SqlConfig("Written", schema.toString(), queries.toString())),
                new JavaConfig(generated.toString(), "generated")
            ),
            fixtureDirectory.resolve("sqlcj.yaml")
        );

        String repository = Files.readString(
            generated.resolve("generated/repositories/WrittenRepository.java")
        );

        assertTrue(
            repository.contains("SELECT settings FROM users WHERE settings = ?;"),
            "the executable SQL keeps the name as written"
        );

        assertTrue(
            repository.contains("SELECT \"users\".\"id\", \"users\".\"settings\" FROM users WHERE settings = ?;"),
            "the wildcard expansion quotes the name as it does any name"
        );
    }

    /**
     * A schema syntax error that stands after a name PostgreSQL does not reserve
     * is reported at the line and the column of the source as the user wrote it,
     * because no name is rewritten and no character is moved. A plain name of the
     * same length reports exactly the same position.
     */
    @Test
    void shouldReportTheWrittenPositionOfASchemaSyntaxError() {
        SchemaParseException keyword = assertThrows(
            SchemaParseException.class,
            () -> parseSchema(brokenSchema("settings"))
        );

        SchemaParseException plain = assertThrows(
            SchemaParseException.class,
            () -> parseSchema(brokenSchema("labelxxx"))
        );

        assertEquals(6, keyword.line(), "the failing statement stands on line 6");

        assertEquals(
            "Encountered unexpected token: \")\" at column 38",
            keyword.getMessage()
        );

        assertEquals(plain.line(), keyword.line());

        assertEquals(plain.getMessage(), keyword.getMessage());

        assertEquals(
            ')',
            brokenSchema("settings").lines().toList().get(5).charAt(37),
            "column 38 of the written line holds the token the message names"
        );
    }

    /**
     * {@code BETWEEN} keeps its keyword meaning after the {@code NOT} that
     * negates it, where the bound that follows is signed. That {@code NOT}
     * negates an operator rather than opening an expression, so the word after
     * it is no operand, in a query as in a table statement.
     */
    @Test
    void shouldKeepBetweenAKeywordAfterAnInfixNot() throws Exception {
        Schema created = parseSchema(
            """
                CREATE TABLE readings (
                    id bigint PRIMARY KEY,
                    delta integer NOT NULL,
                    CHECK (delta NOT BETWEEN -5 AND 5)
                );
                """
        );

        assertEquals(
            List.of("id", "delta"),
            created.tables().get(0).columns().stream().map(column -> column.name()).toList(),
            "a CHECK with a signed NOT BETWEEN loads its table"
        );

        Schema altered = parseSchema(
            """
                CREATE TABLE readings (id bigint PRIMARY KEY, delta integer NOT NULL);
                ALTER TABLE readings
                    ADD COLUMN note text,
                    ADD CONSTRAINT readings_delta CHECK (delta NOT BETWEEN -10 AND -6);
                """
        );

        assertEquals(
            List.of("id", "delta", "note"),
            altered.tables().get(0).columns().stream().map(column -> column.name()).toList(),
            "an ALTER TABLE that both adds a column and a CHECK with a signed NOT BETWEEN applies"
        );

        String uses = Files.readString(
            generatedDirectory.resolve("generated/repositories/" + USES_GROUP + "Repository.java")
        );

        assertTrue(
            uses.contains("SELECT id FROM events WHERE amount NOT BETWEEN -1 AND 1;"),
            "the executable SQL of a signed NOT BETWEEN keeps the statement as written"
        );
    }

    /**
     * A clause sqlcj does not model reads its table name the same way whether
     * that name is a word PostgreSQL does not reserve or a plain one, so it
     * reports the one reason it has: {@code INHERITS} is an unsupported
     * {@code CREATE TABLE} clause, and {@code LIKE} copies the columns of the
     * table it names.
     */
    @Test
    void shouldReadTheTableNameOfEveryTableClause() {
        SchemaParseException keyword = assertThrows(
            SchemaParseException.class,
            () -> parseSchema(
                """
                    CREATE TABLE settings (id bigint PRIMARY KEY);
                    CREATE TABLE children (extra text) INHERITS (settings);
                    """
            )
        );

        assertEquals("Unsupported CREATE TABLE clause: INHERITS", keyword.getMessage());

        assertEquals(2, keyword.line());

        Schema copied = parseSchema(
            """
                CREATE TABLE settings (id bigint PRIMARY KEY, settings text NOT NULL);
                CREATE TABLE copy (LIKE settings);
                """
        );

        assertEquals(
            List.of("settings", "copy"),
            copied.tables().stream().map(table -> table.name()).toList()
        );

        assertEquals(
            List.of("id", "settings"),
            copied.tables().get(1).columns().stream().map(column -> column.name()).toList(),
            "LIKE copies the columns of the table it names"
        );
    }

    /**
     * Every word of the fixture is read as a name by PostgreSQL 16 itself: the
     * whole fixture schema applies to a real server, and every generated method
     * runs against it.
     */
    @EnabledIf("dockerAvailable")
    @Test
    void shouldApplyTheSchemaAndRunEveryGeneratedMethod() throws Exception {
        applyFixtureSchema();

        int calls = 0;

        try (URLClassLoader classLoader = classLoader()) {
            for (String repository : repositoryClassNames()) {
                calls += invokeEveryMethod(classLoader, repository);
            }
        }

        assertEquals(
            words.size() * QUERIES_PER_WORD + usesQueryCount(),
            calls,
            "every generated method ran once"
        );
    }

    /**
     * The committed word list and {@link KeywordNames#POSTGRESQL_RESERVED} are
     * the keyword categories of the server the fixture runs against, read from
     * that server's own {@code pg_get_keywords()}.
     */
    @EnabledIf("dockerAvailable")
    @Test
    void shouldMatchTheKeywordCategoriesOfPostgresql16() throws Exception {
        Map<String, String> server = serverKeywords();

        assertEquals(
            categories,
            keywordsOf(server, "U", "C"),
            "the committed list states the server's unreserved keywords"
        );

        assertEquals(
            keywordsOf(server, "R", "T").keySet(),
            new LinkedHashSet<>(KeywordNames.POSTGRESQL_RESERVED),
            "the reserved constant states the server's reserved keywords"
        );
    }

    /**
     * The keyword uses of the same words keep their meaning and run against
     * PostgreSQL 16: a date field of {@code extract}, an interval unit,
     * {@code FETCH NEXT ... ROWS ONLY}, {@code NOT BETWEEN}, {@code EXISTS},
     * {@code CAST(... AS double precision)},
     * {@code UNBOUNDED PRECEDING AND CURRENT ROW}, {@code GROUPING SETS}, and
     * {@code ON CONFLICT ... DO UPDATE SET}.
     */
    @EnabledIf("dockerAvailable")
    @Test
    void shouldRunTheKeywordUsesAgainstPostgresql16() throws Exception {
        applyFixtureSchema();

        try (URLClassLoader classLoader = classLoader()) {
            Object uses = newRepository(classLoader, "generated.repositories." + USES_GROUP + "Repository");

            Object inserted = invoke(
                uses, "upsertLabel", ROW_ID, LocalDateTime.of(2024, 3, 4, 5, 6), new BigDecimal("3.50"), "first",
                "second"
            );

            assertEquals(ROW_ID, inserted, "ON CONFLICT ... DO UPDATE SET returns the written identifier");

            assertEquals(
                4L,
                component(((List<?>) invoke(uses, "extractDay")).get(0), "dayNumber"),
                "extract reads the day of the timestamp"
            );

            assertEquals(1, ((List<?>) invoke(uses, "intervalDay")).size(), "the interval unit is a unit");

            assertEquals(List.of(ROW_ID), invoke(uses, "fetchNext"));

            assertEquals(
                List.of(ROW_ID),
                invoke(uses, "notBetween", new BigDecimal("10"), new BigDecimal("20")),
                "NOT BETWEEN holds for an amount outside the range"
            );

            assertEquals(List.of(), invoke(uses, "existsRow"));

            assertEquals(1, ((List<?>) invoke(uses, "castDouble")).size());

            assertEquals(1, ((List<?>) invoke(uses, "runningTotal")).size());

            assertEquals(
                List.of(1L, 1L),
                invoke(uses, "groupedSets"),
                "GROUPING SETS states two groupings of the one row"
            );

            assertEquals(
                List.of(ROW_ID),
                invoke(uses, "notBetweenSigned"),
                "NOT BETWEEN keeps its meaning before a signed bound"
            );

            assertEquals(
                1,
                ((List<?>) invoke(uses, "notBetweenSignedName")).size(),
                "a name and a signed NOT BETWEEN are read in one query"
            );
        }
    }

    /** The committed word list, by word in the order the file states them. */
    private static Map<String, String> readWordList() throws Exception {
        Map<String, String> read = new LinkedHashMap<>();

        try (InputStream stream = KeywordNamesIntegrationTest.class.getResourceAsStream(WORD_LIST)) {
            assertNotNull(stream, "missing committed word list: " + WORD_LIST);

            for (String line : new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }

                String[] parts = line.split("\\|");

                read.put(parts[0], parts[1]);
            }
        }

        return read;
    }

    /**
     * The schema of one word: the word as a view name, as a table name in
     * {@code CREATE}, {@code ALTER}, and {@code DROP TABLE}, alone and in a
     * list, as the parent of a partition, as a column of {@code CREATE TABLE},
     * of a constraint column list, of a {@code CHECK}, and after
     * {@code DEFAULT}, as the column of every {@code ALTER TABLE} action, as a
     * referenced table, in the statements sqlcj ignores, and in a view's column
     * list and body.
     */
    private static String schemaOf(String word, int index) {
        return """
            CREATE TABLE zz_base_%2$d (id bigint PRIMARY KEY, zz_val text);
            CREATE VIEW %1$s AS SELECT id, zz_val FROM zz_base_%2$d;
            DROP VIEW %1$s;
            DROP TABLE zz_base_%2$d;
            CREATE TABLE %1$s (id bigint);
            DROP TABLE %1$s;
            CREATE TABLE zz_a_%2$d (id bigint);
            CREATE TABLE %1$s (id bigint);
            DROP TABLE zz_a_%2$d, %1$s;
            CREATE TABLE %1$s (zz_key text NOT NULL, id bigint) PARTITION BY LIST (zz_key);
            CREATE TABLE zz_part_%2$d PARTITION OF %1$s FOR VALUES IN ('a');
            DROP TABLE %1$s;
            CREATE TABLE %1$s (
                id bigint PRIMARY KEY,
                %1$s text NOT NULL DEFAULT '',
                CONSTRAINT zz_uq_%2$d UNIQUE (%1$s),
                CHECK (%1$s <> 'zz')
            );
            ALTER TABLE public.%1$s ADD COLUMN zz_added text;
            ALTER TABLE ONLY %1$s ALTER COLUMN %1$s SET DEFAULT '';
            ALTER TABLE %1$s DROP COLUMN zz_added;
            ALTER TABLE %1$s RENAME COLUMN %1$s TO zz_tmp;
            ALTER TABLE %1$s RENAME COLUMN zz_tmp TO %1$s;
            ALTER TABLE %1$s RENAME TO zz_renamed_%2$d;
            ALTER TABLE zz_renamed_%2$d RENAME TO %1$s;
            CREATE TABLE public.t_%2$d (
                id bigint PRIMARY KEY,
                zz_ref bigint REFERENCES %1$s (id),
                zz_old text,
                zz_drop text
            );
            ALTER TABLE t_%2$d ADD COLUMN %1$s text NOT NULL DEFAULT '';
            ALTER TABLE t_%2$d DROP COLUMN zz_drop;
            ALTER TABLE t_%2$d ALTER COLUMN %1$s SET DEFAULT '';
            ALTER TABLE t_%2$d RENAME COLUMN zz_old TO zz_old_%2$d;
            CREATE TABLE zz_d_%2$d (id bigint PRIMARY KEY, %1$s text);
            ALTER TABLE zz_d_%2$d DROP COLUMN %1$s;
            CREATE INDEX zz_ix_%2$d ON %1$s (%1$s);
            COMMENT ON COLUMN %1$s.%1$s IS 'kept';
            CREATE VIEW zz_v_%2$d (id, %1$s) AS
                SELECT %1$s.id, %1$s.%1$s FROM %1$s WHERE %1$s.%1$s <> 'zz';
            """.formatted(word, index);
    }

    /**
     * The queries of one word: the word as an unqualified and a qualified column
     * reference in a select list, a {@code WHERE}, a {@code GROUP BY}, an
     * {@code ORDER BY}, an {@code INSERT} column list, an {@code UPDATE SET}, an
     * {@code ON CONFLICT DO UPDATE SET}, and a {@code RETURNING}; as a table
     * reference, a table qualifier, and a table alias written with {@code AS}
     * and without it; as a column alias written with {@code AS}; and as a column
     * a wildcard expands to.
     */
    private static String queriesOf(String word, int index) {
        return """
            -- name: Kw%2$dUnqualified :many
            SELECT %1$s, id FROM t_%2$d WHERE %1$s = :p GROUP BY %1$s, id ORDER BY %1$s;

            -- name: Kw%2$dQualified :many
            SELECT %1$s.id, %1$s.%1$s AS %1$s FROM t_%2$d AS %1$s
            WHERE %1$s.%1$s = :p ORDER BY %1$s.%1$s;

            -- name: Kw%2$dBareAlias :many
            SELECT %1$s.%1$s FROM t_%2$d %1$s WHERE %1$s.id = :id;

            -- name: Kw%2$dWildcard :many
            SELECT * FROM %1$s WHERE %1$s.%1$s = :p;

            -- name: Kw%2$dUpsert :one
            INSERT INTO t_%2$d (id, %1$s) VALUES (:id, :p)
            ON CONFLICT (id) DO UPDATE SET %1$s = :q RETURNING id, %1$s;

            -- name: Kw%2$dUpdate :exec
            UPDATE %1$s SET %1$s = :p WHERE id = :id;

            -- name: Kw%2$dViewColumns :many
            SELECT id, %1$s FROM zz_v_%2$d ORDER BY %1$s;

            """.formatted(word, index);
    }

    /** The number of queries the keyword-use group states. */
    private static int usesQueryCount() {
        return (int) USES_QUERIES.lines().filter(line -> line.startsWith("-- name:")).count();
    }

    /** The schema whose syntax error stands on line 6, after {@code name}. */
    private static String brokenSchema(String name) {
        return """
            CREATE TABLE users (
                id bigint PRIMARY KEY,
                %1$s text NOT NULL
            );

            CREATE TABLE zz (id bigint, %1$s );
            """.formatted(name);
    }

    private static Schema parseSchema(String sql) {
        return new DefaultSchemaParser().parse(
            new Schema(List.of(), List.of(), List.of(), List.of(), "public"),
            sql
        );
    }

    private static Path write(String name, String content) throws Exception {
        Path file = fixtureDirectory.resolve(name);

        Files.writeString(file, content);

        return file;
    }

    /** Compiles every generated source of one output directory together. */
    private static Path compile(Path sources, Path classes) throws Exception {
        Files.createDirectories(classes);

        JavaCompiler compilerApi = ToolProvider.getSystemJavaCompiler();

        assertNotNull(compilerApi);

        List<String> arguments = new ArrayList<>(
            List.of(
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                classes.toString()
            )
        );

        try (Stream<Path> files = Files.walk(sources)) {
            files
                .map(Path::toString)
                .filter(path -> path.endsWith(".java"))
                .sorted()
                .forEach(arguments::add);
        }

        assertEquals(0, compilerApi.run(null, null, null, arguments.toArray(new String[0])));

        return classes;
    }

    /** The number of methods the generated repositories of the words declare. */
    private static int generatedMethodCount() throws Exception {
        int methods = 0;

        try (URLClassLoader classLoader = classLoader()) {
            for (String className : repositoryClassNames()) {
                if (className.endsWith(USES_GROUP + "Repository")) {
                    continue;
                }

                methods += (int) Arrays.stream(Class.forName(className, false, classLoader).getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()) && !method.isSynthetic())
                    .count();
            }
        }

        return methods;
    }

    /** The generated repository class names of the whole fixture. */
    private static List<String> repositoryClassNames() throws Exception {
        List<String> names = new ArrayList<>();

        try (Stream<Path> files = Files.walk(generatedDirectory.resolve("generated/repositories"))) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String fileName = file.getFileName().toString();

                names.add("generated.repositories." + fileName.substring(0, fileName.length() - ".java".length()));
            }
        }

        return names;
    }

    /**
     * Applies every schema file of the fixture to a PostgreSQL 16 server, which
     * is started once and reused by the tests that need it.
     */
    private static synchronized void applyFixtureSchema() throws Exception {
        if (dataSource != null) {
            return;
        }

        postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

        postgres.start();

        PGSimpleDataSource fixtureDataSource = new PGSimpleDataSource();

        fixtureDataSource.setUrl(postgres.getJdbcUrl());
        fixtureDataSource.setUser(postgres.getUsername());
        fixtureDataSource.setPassword(postgres.getPassword());

        try (
            Connection connection = fixtureDataSource.getConnection();
            Statement statement = connection.createStatement()
        ) {
            for (Path file : schemaFiles()) {
                statement.execute(Files.readString(file));
            }
        }

        dataSource = fixtureDataSource;
    }

    private static List<Path> schemaFiles() throws Exception {
        try (Stream<Path> files = Files.list(fixtureDirectory)) {
            List<Path> schemas = new ArrayList<>(
                files
                    .filter(file -> file.getFileName().toString().startsWith("schema-"))
                    .sorted()
                    .toList()
            );

            schemas.add(fixtureDirectory.resolve("uses-schema.sql"));

            return schemas;
        }
    }

    /** The keyword categories of the server under test, by word. */
    private static Map<String, String> serverKeywords() throws Exception {
        Map<String, String> keywords = new LinkedHashMap<>();

        applyFixtureSchema();

        try (
            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(
                "SELECT word, catcode::text FROM pg_get_keywords() ORDER BY word"
            )
        ) {
            while (rows.next()) {
                keywords.put(rows.getString(1), rows.getString(2));
            }
        }

        return keywords;
    }

    /** The keyword categories of {@code server} that fall in one of {@code codes}. */
    private static Map<String, String> keywordsOf(Map<String, String> server, String... codes) {
        Map<String, String> selected = new LinkedHashMap<>();

        for (Map.Entry<String, String> keyword : server.entrySet()) {
            if (List.of(codes).contains(keyword.getValue())) {
                selected.put(keyword.getKey(), keyword.getValue());
            }
        }

        return selected;
    }

    private static URLClassLoader classLoader() throws Exception {
        return new URLClassLoader(
            new URL[] { classesDirectory.toUri().toURL() },
            KeywordNamesIntegrationTest.class.getClassLoader()
        );
    }

    private static Object newRepository(URLClassLoader classLoader, String className) throws Exception {
        return Class.forName(className, true, classLoader)
            .getConstructors()[0]
            .newInstance(new JdbcQueryExecutor(dataSource));
    }

    /**
     * Calls every method of one generated repository once, with one value of
     * each generated parameter type, and reports how many it called.
     */
    private static int invokeEveryMethod(URLClassLoader classLoader, String className) throws Exception {
        Object repository = newRepository(classLoader, className);
        int calls = 0;

        for (Method method : repository.getClass().getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                continue;
            }

            method.invoke(
                repository,
                Arrays.stream(method.getParameterTypes())
                    .map(KeywordNamesIntegrationTest::argument)
                    .toArray()
            );

            calls++;
        }

        return calls;
    }

    /** One value of each type the fixture's generated parameters take. */
    private static Object argument(Class<?> type) {
        if (type == Long.class) {
            return ROW_ID;
        }

        if (type == String.class) {
            return TEXT_VALUE;
        }

        if (type == LocalDateTime.class) {
            return LocalDateTime.of(2024, 3, 4, 5, 6);
        }

        if (type == BigDecimal.class) {
            return new BigDecimal("3.50");
        }

        return fail("unexpected generated parameter type: " + type);
    }

    private static Object component(Object record, String componentName) throws Exception {
        return record.getClass().getMethod(componentName).invoke(record);
    }

    /** Calls one generated repository method, which has one name of its own. */
    private static Object invoke(Object repository, String methodName, Object... arguments)
        throws Exception {
        for (Method method : repository.getClass().getMethods()) {
            if (method.getName().equals(methodName)) {
                return method.invoke(repository, arguments);
            }
        }

        return fail("missing generated method: " + methodName);
    }
}
