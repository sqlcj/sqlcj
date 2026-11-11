# Testing

Code that calls a generated sqlcj repository is tested like any other Java
code. A repository is a plain object over one `org.sqlcj.runtime.QueryExecutor`,
so a test either gives it a real database or stands in for the executor. This
guide does both, on the two projects the [Quickstart](quickstart.md) builds —
`my-app` on the runtime and `my-boot-app` on the Spring Boot starter — and
covers:

- an integration test in plain JUnit, with Testcontainers and Flyway;
- an integration test in Spring Boot, with `@SpringBootTest` and
  `@ServiceConnection`;
- two unit tests of the quickstart's `LibraryService`, one mocking the
  generated repository with Mockito and one running the real repository over a
  hand-written `QueryExecutor`;
- what JaCoCo leaves out of its coverage report, and what it reports.

Testing those projects adds test sources, test-scoped dependencies, and build
plugins — two to `my-app` and three to `my-boot-app`. It changes no application
source, no `sqlcj.yaml`, and no SQL, and it asks nothing of sqlcj: the runtime
depends on no test library, and the generated code knows nothing of
Testcontainers, Flyway, Mockito, or JaCoCo.

Every `pom.xml` and Java block below is a copy of a file of the projects these
tests were run in. [Verified on](#verified-on) lists the tools and their
versions.

Related documents:

- [Quickstart](quickstart.md) builds both projects from an empty directory: the
  artifacts, `sqlcj.yaml`, the migrations, the generation step these builds
  keep, and the application classes the tests below call.
- [Cookbook](cookbook.md) holds one tested recipe per everyday task, each with
  its SQL and its Java.
- [Spring](spring.md) owns the starter's rules: the executor it defines over
  the application's `DataSource` and the repository beans it registers, which
  the Spring Boot test autowires.

## Running the Tests

Docker must be running. Each integration test starts a `postgres:16-alpine`
container of its own through Testcontainers and stops it when its class is
done.

The quickstart's sequence gains one step, run from either project root:

```bash
mvn clean
java -jar tools/sqlcj-cli-0.1.0-SNAPSHOT.jar generate
mvn test
```

`sqlcj generate` stays a command of its own before the build, as it is in the
quickstart: the tests call the generated repository, so its sources must exist
before the build compiles them. `mvn test` then compiles `src/main/java`
together with the generated sources, compiles `src/test/java`, runs every
test, and writes the coverage report to `target/site/jacoco/index.html`.

Both integration tests apply the project's own migrations with Flyway from
`filesystem:sql/migrations`, the directory `sql[].schema` names in
`sqlcj.yaml`. The compiler reads those files and the test database runs them,
so one directory defines both and no test repeats the schema. The quickstart's
migrations are already Flyway-named, `V1__create_authors_and_books.sql` and
`V2__add_author_created_at_and_book_index.sql`, and Flyway applies them in the
same version order sqlcj reads them in. sqlcj never applies a migration
itself, as
[Start PostgreSQL and apply the migrations](quickstart.md#9-start-postgresql-and-apply-the-migrations)
states; in these tests Flyway is the tool that does.

## The Plain Project's Build

`my-app/pom.xml` is the quickstart's POM with the test dependencies, Surefire,
and JaCoCo added:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>my-app</artifactId>
    <version>1.0.0-SNAPSHOT</version>

    <properties>
        <maven.compiler.release>17</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <sqlcj.version>0.1.0-SNAPSHOT</sqlcj.version>
        <sqlcj.generated.sources>${project.build.directory}/generated-sources/sqlcj</sqlcj.generated.sources>
        <argLine></argLine>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.junit</groupId>
                <artifactId>junit-bom</artifactId>
                <version>6.1.3</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>org.testcontainers</groupId>
                <artifactId>testcontainers-bom</artifactId>
                <version>2.0.5</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <dependency>
            <groupId>org.sqlcj</groupId>
            <artifactId>sqlcj-runtime</artifactId>
            <version>${sqlcj.version}</version>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <version>42.7.14</version>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.platform</groupId>
            <artifactId>junit-platform-launcher</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
            <version>13.12.1</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
            <version>13.12.1</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.15.0</version>
                <executions>
                    <execution>
                        <id>default-compile</id>
                        <configuration>
                            <compileSourceRoots>
                                <compileSourceRoot>${project.basedir}/src/main/java</compileSourceRoot>
                                <compileSourceRoot>${sqlcj.generated.sources}</compileSourceRoot>
                            </compileSourceRoots>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.6.3</version>
                <configuration>
                    <mainClass>com.example.app.App</mainClass>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.5.6</version>
            </plugin>
            <plugin>
                <groupId>org.jacoco</groupId>
                <artifactId>jacoco-maven-plugin</artifactId>
                <version>0.8.16</version>
                <executions>
                    <execution>
                        <id>prepare-agent</id>
                        <goals>
                            <goal>prepare-agent</goal>
                        </goals>
                    </execution>
                    <execution>
                        <id>report</id>
                        <phase>test</phase>
                        <goals>
                            <goal>report</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

Four build details carry the tests:

- `compileSourceRoots` moves into the `default-compile` execution. At plugin
  level it configures every execution of the compiler plugin,
  `default-testCompile` included, which then compiles the two main source roots
  a second time instead of `src/test/java`: no test class is ever compiled, and
  `mvn test` succeeds having run nothing. Inside the `default-compile`
  execution it adds the generated sources to the main compilation alone, and
  the test compilation keeps its own default source root.
- `maven-surefire-plugin` is declared at `3.5.6`. Maven 3.8.7 defaults to
  Surefire `2.12.4`, which predates the JUnit Platform: it reports
  `Tests run: 0` for a project whose tests are all JUnit Jupiter tests, and the
  build succeeds. Importing a BOM does not change that, Spring Boot's included,
  because a BOM import carries dependency management and not plugin
  management, so the Spring Boot project below declares Surefire as well.
- `jacoco-maven-plugin` `0.8.16` contributes `prepare-agent`, which puts the
  coverage agent on the test JVM, and `report`, bound to the `test` phase so
  that `mvn test` alone writes the report, after Surefire has run.
- The empty `argLine` property is the property `prepare-agent` writes that
  agent option to. Declaring it keeps the option in one named place, which the
  Spring Boot project's Surefire configuration extends with a second agent.

The test dependencies pin the versions this guide was verified with:

- the JUnit BOM at `6.1.3` manages `junit-jupiter` and
  `junit-platform-launcher`, so the launcher Surefire runs the tests through is
  on the test classpath at the version the BOM pins;
- the Testcontainers BOM at `2.0.5` manages `testcontainers-junit-jupiter`, the
  JUnit 5 extension, and `testcontainers-postgresql`, the PostgreSQL container;
- Flyway `13.12.1` is two artifacts, `flyway-core` and the
  `flyway-database-postgresql` module that carries its PostgreSQL support.

All of them are `test`-scoped, so `sqlcj-runtime` and the PostgreSQL driver
remain the application's only dependencies.

## An Integration Test in Plain JUnit

`my-app/src/test/java/com/example/app/AuthorRepositoryIntegrationTest.java`
runs the generated repository against a migrated database:

```java
package com.example.app;

import com.example.app.db.repositories.AuthorRepository;
import com.example.app.db.rows.AuthorsRow;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.sqlcj.runtime.JdbcQueryExecutor;
import org.sqlcj.runtime.QueryCardinalityException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class AuthorRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    static AuthorRepository authors;

    @BeforeAll
    static void migrate() {
        DataSource dataSource = dataSource();

        Flyway.configure()
            .dataSource(dataSource)
            .locations("filesystem:sql/migrations")
            .load()
            .migrate();

        authors = new AuthorRepository(new JdbcQueryExecutor(dataSource));
    }

    @Test
    void writesAndReadsAnAuthor() {
        AuthorsRow created = authors.createAuthor("Ada Lovelace", "First programmer");

        assertEquals(created, authors.getAuthor(created.id()));
        assertEquals(1, authors.updateAuthorBio(created.id(), "Mathematician"));
        assertEquals("Mathematician", authors.getAuthor(created.id()).bio());

        assertEquals(1, authors.createBook(created.id(), "Note G"));
        assertEquals("Note G", authors.listAuthorBooks().get(0).title());
    }

    @Test
    void readsAMissingAuthorByCardinality() {
        assertTrue(authors.findAuthor(-1L).isEmpty());
        assertThrows(QueryCardinalityException.class, () -> authors.getAuthor(-1L));
    }

    private static DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();

        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        return dataSource;
    }
}
```

The test builds the same two objects the quickstart's `App` builds, a
`JdbcQueryExecutor` and the repository over it, on the container's connection
instead of a local server:

- `@Testcontainers` owns the lifecycle of the `static @Container` field, so one
  `postgres:16-alpine` container serves the whole class. In Testcontainers 2 the
  container type is `org.testcontainers.postgresql.PostgreSQLContainer` and
  takes no type argument.
- The container reports the URL, user, and password of the database it started,
  which a `PGSimpleDataSource` is configured with.
- Flyway migrates that data source from `filesystem:sql/migrations` once, in
  `@BeforeAll`, before any query runs.
- The repository is constructed over a `JdbcQueryExecutor` on the same data
  source, so every generated method runs on a connection of its own, the
  contract
  [Connection Ownership and Transactions](postgresql.md#connection-ownership-and-transactions)
  documents.

What is then asserted is the generated API: `createAuthor` returns the row the
database generated the identifier for, `getAuthor` reads that same row back,
`updateAuthorBio` and `createBook` return their affected-row counts,
`listAuthorBooks` returns its own nested result record, and a missing row is
`Optional.empty()` from `findAuthor` and a `QueryCardinalityException` from
`getAuthor`.

## The Spring Boot Project's Build

`my-boot-app/pom.xml` makes the same four build changes — the scoped
`compileSourceRoots`, Surefire, JaCoCo, and the `argLine` property — and adds
the Spring Boot test dependencies and the Mockito agent:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>my-boot-app</artifactId>
    <version>1.0.0-SNAPSHOT</version>

    <properties>
        <maven.compiler.release>17</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <sqlcj.version>0.1.0-SNAPSHOT</sqlcj.version>
        <spring-boot.version>4.1.2</spring-boot.version>
        <sqlcj.generated.sources>${project.build.directory}/generated-sources/sqlcj</sqlcj.generated.sources>
        <argLine></argLine>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>${spring-boot.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <dependency>
            <groupId>org.sqlcj</groupId>
            <artifactId>sqlcj-spring-boot-starter</artifactId>
            <version>${sqlcj.version}</version>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-flyway</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.15.0</version>
                <executions>
                    <execution>
                        <id>default-compile</id>
                        <configuration>
                            <compileSourceRoots>
                                <compileSourceRoot>${project.basedir}/src/main/java</compileSourceRoot>
                                <compileSourceRoot>${sqlcj.generated.sources}</compileSourceRoot>
                            </compileSourceRoots>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-dependency-plugin</artifactId>
                <version>3.10.0</version>
                <executions>
                    <execution>
                        <id>properties</id>
                        <goals>
                            <goal>properties</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.5.6</version>
                <configuration>
                    <argLine>@{argLine} -javaagent:${org.mockito:mockito-core:jar}</argLine>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.jacoco</groupId>
                <artifactId>jacoco-maven-plugin</artifactId>
                <version>0.8.16</version>
                <executions>
                    <execution>
                        <id>prepare-agent</id>
                        <goals>
                            <goal>prepare-agent</goal>
                        </goals>
                    </execution>
                    <execution>
                        <id>report</id>
                        <phase>test</phase>
                        <goals>
                            <goal>report</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <version>${spring-boot.version}</version>
            </plugin>
        </plugins>
    </build>
</project>
```

Spring Boot's BOM versions every test dependency, so this project pins none of
them:

- `spring-boot-starter-test` brings JUnit Jupiter, AssertJ, and Mockito, and
  with them `@SpringBootTest`;
- `spring-boot-testcontainers` brings `@ServiceConnection`, and
  `testcontainers-junit-jupiter` and `testcontainers-postgresql` bring the
  extension and the container, at the Testcontainers version Spring Boot's BOM
  manages;
- `spring-boot-starter-flyway` and `flyway-database-postgresql` are what Spring
  Boot 4 runs Flyway from. Both are `test`-scoped, so the application still
  does not run migrations, exactly as in the quickstart, while the test
  database is migrated before the context uses it.

Surefire gets one more agent than the plain project:

- `maven-dependency-plugin`'s `properties` goal sets one property per resolved
  artifact, which is where `${org.mockito:mockito-core:jar}` comes from: the
  path of the Mockito JAR Spring Boot's BOM chose.
- Surefire's `argLine` attaches that JAR with `-javaagent`, because on JDK 21
  Mockito's inline mock maker otherwise attaches itself at run time, and both
  Mockito and the JVM warn about it: `Mockito is currently
  self-attaching to enable the inline-mock-maker`, followed by
  `WARNING: A Java agent has been loaded dynamically` and
  `Dynamic loading of agents will be disallowed by default in a future
  release`. Declared as an agent, Mockito prints no warning and the mock works
  unchanged.
- The `@{argLine}` prefix is Surefire's late reference to the `argLine`
  property, which JaCoCo's `prepare-agent` has set to the coverage agent by
  then, so the test JVM gets both agents rather than one in place of the other.
  The property has to exist, too: when nothing declares or sets `argLine`,
  Surefire hands the unresolved reference to the JVM and the forked VM fails to
  start.

## An Integration Test in Spring Boot

`my-boot-app/src/test/java/com/example/app/LibraryServiceIntegrationTest.java`
runs the application's own service in a Spring context over the container:

```java
package com.example.app;

import com.example.app.db.repositories.AuthorRepository;
import com.example.app.db.rows.AuthorsRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest(properties = "spring.flyway.locations=filesystem:sql/migrations")
class LibraryServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    LibraryService library;

    @Autowired
    AuthorRepository authors;

    @Test
    void commitsBothWritesOfOneTransaction() {
        AuthorsRow committed = library.createAuthorWithBook(
            "Grace Hopper",
            "Compiler pioneer",
            "The Education of a Computer"
        );

        assertEquals(committed, authors.getAuthor(committed.id()));
        assertEquals(
            "The Education of a Computer",
            authors.listAuthorBooks().get(0).title()
        );
    }

    @Test
    void rollsBackBothWritesOfAFailedTransaction() {
        assertThrows(RuntimeException.class, () -> library.createAuthorWithBookAndFail(
            "Temporary Author",
            "never stored",
            "Never Stored"
        ));

        assertTrue(authors.searchAuthors("Temporary Author").isEmpty());
        assertTrue(authors.listAuthorBooks().stream().noneMatch(book -> "Never Stored".equals(book.title())));
    }
}
```

- `@SpringBootTest` starts the application's context, found from the
  `@SpringBootApplication` class, which is also what brings the starter's
  auto-configuration in. `LibraryService` and `AuthorRepository` are therefore
  autowired beans: the repository is the bean the starter registered, over the
  executor it defined on the context's `DataSource`.
- `@ServiceConnection` on the `@Container` field hands the container's URL,
  user, and password to that auto-configuration, so the test needs no
  `spring.datasource.*` property and the application's own values in
  `application.properties` are not used.
- `spring.flyway.locations` is set on the test alone, so the test database runs
  the project's migration directory while the application keeps its own
  configuration.
- The service's `@Transactional` methods are what the assertions are about: the
  method that returns normally has both of its writes committed, and the method
  that throws has both rolled back, which the repository reports as a stored
  author with a stored book and as no row at all. The starter's executor is why
  both writes of one method share the transaction's connection, the rule
  [Transactions](spring.md#transactions) documents.

## Unit Tests of a Service

`LibraryService` is the quickstart's one service class over a generated
repository, and both tests below run it with no database and no Spring context.
They live in the Spring Boot project because `spring-boot-starter-test` already
brings Mockito there; neither test needs Spring itself.

### Mocking the repository

`my-boot-app/src/test/java/com/example/app/LibraryServiceMockitoTest.java`
replaces the repository:

```java
package com.example.app;

import com.example.app.db.repositories.AuthorRepository;
import com.example.app.db.rows.AuthorsRow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LibraryServiceMockitoTest {

    @Test
    void writesTheAuthorAndThenThatAuthorsBook() {
        AuthorRepository authors = mock(AuthorRepository.class);
        AuthorsRow grace = new AuthorsRow(7L, "Grace Hopper", "Compiler pioneer", null);

        when(authors.createAuthor("Grace Hopper", "Compiler pioneer")).thenReturn(grace);

        LibraryService library = new LibraryService(authors);

        AuthorsRow returned = library.createAuthorWithBook(
            "Grace Hopper",
            "Compiler pioneer",
            "The Education of a Computer"
        );

        assertEquals(grace, returned);
        verify(authors).createBook(7L, "The Education of a Computer");
    }
}
```

A generated repository is a `public final class` with no interface, which
Mockito 5 mocks with its default inline mock maker; nothing has to be
configured for it, and the mock needs no `QueryExecutor`. The test then states
what the repository returns, asserts what the service does with it, and
verifies the second call the service makes, so the service's own logic is
tested without any SQL.

### A hand-written `QueryExecutor`

`my-boot-app/src/test/java/com/example/app/LibraryServiceExecutorTest.java`
keeps the real repository and replaces the database instead:

```java
package com.example.app;

import com.example.app.db.repositories.AuthorRepository;
import com.example.app.db.rows.AuthorsRow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.sqlcj.runtime.QueryExecutor;
import org.sqlcj.runtime.RowMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LibraryServiceExecutorTest {

    static final class RecordingExecutor implements QueryExecutor {

        final List<String> calls = new ArrayList<>();

        final AuthorsRow created;

        RecordingExecutor(AuthorsRow created) {
            this.created = created;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryOne(
            String repository,
            String query,
            String sql,
            List<? extends @Nullable Object> parameters,
            RowMapper<T> mapper
        ) {
            calls.add(query + " " + parameters);

            return (T) created;
        }

        @Override
        public <T> Optional<T> queryOptional(
            String repository,
            String query,
            String sql,
            List<? extends @Nullable Object> parameters,
            RowMapper<T> mapper
        ) {
            throw new UnsupportedOperationException(query);
        }

        @Override
        public <T> List<T> queryMany(
            String repository,
            String query,
            String sql,
            List<? extends @Nullable Object> parameters,
            RowMapper<T> mapper
        ) {
            throw new UnsupportedOperationException(query);
        }

        @Override
        public int execute(
            String repository,
            String query,
            String sql,
            List<? extends @Nullable Object> parameters
        ) {
            calls.add(query + " " + parameters);

            return 1;
        }

        @Override
        public int[] executeBatch(
            String repository,
            String query,
            String sql,
            List<? extends List<? extends @Nullable Object>> batch
        ) {
            throw new UnsupportedOperationException(query);
        }
    }

    @Test
    void writesTheAuthorAndThenThatAuthorsBook() {
        AuthorsRow grace = new AuthorsRow(7L, "Grace Hopper", "Compiler pioneer", null);

        RecordingExecutor executor = new RecordingExecutor(grace);

        LibraryService library = new LibraryService(new AuthorRepository(executor));

        AuthorsRow returned = library.createAuthorWithBook(
            "Grace Hopper",
            "Compiler pioneer",
            "The Education of a Computer"
        );

        assertEquals(grace, returned);
        assertEquals(
            List.of(
                "CreateAuthor [Grace Hopper, Compiler pioneer]",
                "CreateBook [7, The Education of a Computer]"
            ),
            executor.calls
        );
    }
}
```

`QueryExecutor` is an interface of five methods, one per generated operation
shape, and each of them receives the repository's name, the query's name, the
SQL, and the query's arguments in the order their placeholders appear in the
SQL. The recorded calls are therefore the generated calls themselves: the
service's `createAuthor` arrives as `CreateAuthor` with the name before the
bio, and its `createBook` as `CreateBook` with the identifier the first call
returned.

A reading method also receives the `RowMapper` the repository generated. A
mapper maps a `java.sql.ResultSet`, so an executor that returns canned rows
instead of running SQL ignores the mapper and returns the row it was given,
which costs one unchecked cast to the method's type parameter. The methods the
service does not call raise `UnsupportedOperationException` naming the query,
so a call this test did not plan for fails loudly.

## Coverage with JaCoCo

Since 0.8.3, JaCoCo leaves out of its report every class and method annotated
with an annotation whose simple name contains `Generated` and whose retention
is class or runtime. Every generated repository carries
`@org.sqlcj.runtime.GeneratedRepository`, which is retained at run time, so
JaCoCo filters it with no configuration: no `excludes`, no exclusion pattern to
keep in step with the generated packages.

`AuthorRepository` therefore has no counter at all. Its generated methods and
the static row-mapper lambdas inside it are the whole class, and the report
holds none of them: the HTML report lists no `AuthorRepository` page, the CSV
report has no `AuthorRepository` row, and the XML report holds the class only
as an empty element.

```text
<class name="com/example/app/db/repositories/AuthorRepository" sourcefilename="AuthorRepository.java"/>
```

What the report does hold is every other generated class, because the marker is
on the repository alone:

- the row records of the `rows` subpackage, such as `AuthorsRow`;
- the nested `<QueryName>Result` and `<QueryName>Params` records of a
  repository, such as `AuthorRepository.ListAuthorBooksResult`, which are
  classes of their own and carry no marker;
- the generated enums of the `enums` subpackage, counters for the branches of
  `fromLabel` included.

Those classes are covered by the code that constructs and reads them, which is
the application's code and the tests above. The practical consequence is that
coverage of a project that uses sqlcj measures the application's own code: a
query nobody calls shows up as the uncovered application code that was supposed
to call it, never as an uncovered generated method.

## Verified on

Every block of this guide is a copy of a file of `my-app` or `my-boot-app`, and
each project's tests were run with the sequence above against PostgreSQL 16
(`postgres:16-alpine`) under Docker 29.6.1 and Maven 3.8.7, on:

- JDK 17 and JDK 21, with `maven.compiler.release` at 17 in both;
- Spring Boot 4.0.9 and 4.1.2 for `my-boot-app`, which took Flyway 11.14.1 and
  Mockito 5.20.0 from the first BOM and Flyway 12.4.0 and Mockito 5.23.0 from
  the second;
- Testcontainers 2.0.5, which is the version the plain project's BOM pins and
  the version both Spring Boot BOMs manage.

The JaCoCo statements above were read off the reports of those runs, and the
statement about generated enums off the same `my-app` with one more migration
that adds a PostgreSQL enum type and a column of it: its `AuthorStatus` is
reported with its constants, its `label` method, and the branches of
`fromLabel`.
