# Spring

Generated code depends on the sqlcj runtime alone, which brings the JSpecify
annotations the generated sources state their nullness with, so a generated
repository is an ordinary Java object in a Spring application: it runs its
queries through the `org.sqlcj.runtime.QueryExecutor` it was constructed with.
All a Spring application decides is which executor that is and how the
repositories become beans.

This guide covers four cases:

- the `org.sqlcj:sqlcj-spring-boot-starter` on Spring Boot 4, which
  auto-configures the executor and registers the generated repositories;
- a generated package that sits outside the application's own packages;
- an application with several databases;
- Spring Framework, Spring Boot 3, or any Spring Boot application without the
  starter.

The [Quickstart](quickstart.md) builds a Spring Boot application on the starter
from an empty directory, and
[Connection Ownership and Transactions](postgresql.md#connection-ownership-and-transactions)
documents the connection and transaction contract every configuration here
rests on.

## The Starter on Spring Boot 4

The starter is for Spring Boot 4 and Java 17, and is released at the runtime's
version. Its behavior is verified against Spring Boot 4.0.9 and 4.1.2.

An application needs two dependencies, the starter and a PostgreSQL driver:

```xml
<dependency>
    <groupId>org.sqlcj</groupId>
    <artifactId>sqlcj-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
</dependency>
```

The starter brings `org.sqlcj:sqlcj-runtime`, which the generated sources
compile against, and `spring-boot-starter-jdbc`, which brings Spring Boot's
data source and transaction support. Generation itself stays outside the build:
the command line tool writes the sources, exactly as it does for a plain
application.

### The executor

The starter defines one `QueryExecutor` bean, after Spring Boot's own data
source auto-configuration. It is a `JdbcQueryExecutor` over a
`TransactionAwareDataSourceProxy` of the application's `DataSource`, so every
generated operation runs on the connection of the Spring-managed transaction it
finds itself in, and on a connection of its own when there is none. The proxy
is not registered as a bean, so the application keeps exactly one `DataSource`
candidate.

That executor is wrapped in an `ExceptionTranslatingQueryExecutor`, so a
generated operation fails with the same `DataAccessException` a `JdbcTemplate`
over the `DataSource` raises for the same failure; see
[Exceptions](#exceptions).

The executor is defined only when exactly one `DataSource` candidate qualifies:
one `DataSource` bean, or several of which one is `@Primary`. With two
candidates and none primary, or with no `DataSource` at all, the application
starts with no sqlcj executor, and with no registered repository either, rather
than with repositories bound to a database nobody chose.

### The registered repositories

Every class sqlcj generated in the application's auto-configuration packages —
the package of the `@SpringBootApplication` class and the packages below it —
is registered as a singleton bean under its fully qualified class name, built
from the one `QueryExecutor` bean definition. The generated repositories sit in
`<java.package>.repositories`, which is one of those packages whenever
`java.package` itself is. The generator marks every
repository with `@GeneratedRepository`, which is how the
starter finds them; a row record and a generated enum carry no marker and are
not registered.

So a generated repository is injected like any other bean, and the application
declares neither a `@Bean` for it nor an `@Import`:

```java
@Service
public class LibraryService {

    private final AuthorRepository authors;

    public LibraryService(AuthorRepository authors) {
        this.authors = authors;
    }

    @Transactional
    public AuthorsRow createAuthorWithBook(String name, String bio, String title) {
        AuthorsRow author = authors.createAuthor(name, bio);

        authors.createBook(author.id(), title);

        return author;
    }
}
```

### What makes the starter back off

- An application's own `QueryExecutor` bean replaces the starter's: no sqlcj
  executor is defined, and the repositories are registered over the
  application's executor instead. That executor translates no exception unless
  the application wraps it in the starter's decorator itself; see
  [Exceptions](#exceptions).
- A repository type the application declares itself keeps that declaration.
  The starter registers nothing of a type an existing bean definition already
  produces.
- No repository at all is registered unless the context holds exactly one
  `QueryExecutor` bean definition, counting primary, fallback, and non-default
  candidates alike. An application with two of them wires its repositories
  itself; see [Several Databases](#several-databases).

An application that defines its own executor builds it over a
`TransactionAwareDataSourceProxy` the way the starter does. An executor over
the plain `DataSource` obtains a connection of its own, so each of its writes
is committed at once and survives the rollback of the transaction it ran in.

### When a repository is missing at startup

An application that asks for a generated repository the starter did not
register fails to start, as it would for any missing bean, and the startup
report names the starter's own decision that left it unregistered and the fix
this guide documents for it, in place of Spring Boot's generic
`Consider defining a bean of type ...`. A missing type that carries no
`@GeneratedRepository` is none of the starter's business and keeps Spring
Boot's report unchanged.

There are four reasons, each citing the section that documents its fix:

| Why nothing was registered | The fix it cites |
| --- | --- |
| No `QueryExecutor` bean, because the application has no `DataSource` bean | [The executor](#the-executor) |
| No `QueryExecutor` bean, because several `DataSource` beans, none of them `@Primary`, leave no single candidate; the report names them | [Several Databases](#several-databases) |
| Several `QueryExecutor` beans, which the report names | [Several Databases](#several-databases) |
| The repository's package lies outside the application's auto-configuration packages, which the report names beside it | [A Generated Package Outside the Application's Packages](#a-generated-package-outside-the-applications-packages) |

The last one reads:

```text
Description:

Parameter 0 of constructor in com.example.app.LibraryService required a bean of type 'com.example.generated.repositories.AuthorRepository' that could not be found.

The sqlcj starter registers only the generated repositories in the application's auto-configuration packages (com.example.app), and this repository's package is com.example.generated.repositories.

Action:

Register the repository with @Import(AuthorRepository.class) on a @Configuration class, or generate it under the application's packages by changing java.package in the sqlcj configuration. See "A Generated Package Outside the Application's Packages" in the sqlcj Spring guide.
```

### Transactions

A `@Transactional` method commits or rolls back every generated write it made,
because the executor reaches the transaction's connection through the proxy.
Spring Boot's JPA support shares that transaction: an entity write and a
generated write inside one `@Transactional` method are committed or rolled back
together. A pending JPA change is visible to a generated read only after
`EntityManager.flush()`, because a generated read reaches the database rather
than the persistence context; see
[Connection Ownership and Transactions](postgresql.md#connection-ownership-and-transactions).

The runtime's own `JdbcTransactions` helper is not used inside a Spring-managed
transaction. It obtains a connection of its own, so its work would be a second,
independent transaction.

### Turning it off

```properties
spring.autoconfigure.exclude=org.sqlcj.spring.boot.autoconfigure.SqlcjAutoConfiguration
```

The context then holds neither the executor nor any registered repository, and
the application declares both itself, as in
[Spring Framework and Spring Boot Without the Starter](#spring-framework-and-spring-boot-without-the-starter).

The starter adds no configuration property of its own: the database is
configured by Spring Boot's own `spring.datasource.*` properties.

### Exceptions

A generated operation on the starter's executor fails with the
`org.springframework.dao.DataAccessException` that a `JdbcTemplate` over the
same `DataSource` raises for the same failure, so a `@Transactional` service
method catches a duplicate key as Spring's `DuplicateKeyException` and an
application's existing handlers, retry rules, and rollback rules treat a
generated repository as they treat the rest of its data layer:

| What failed | What the operation raises |
| --- | --- |
| a statement, a batch, or the connection either needed | exactly what the translator builds from the driver's `SQLException`, or `UncategorizedSQLException` when it builds none, as from `JdbcTemplate` |
| a `:one` read that matched no row | `EmptyResultDataAccessException` |
| a `:one` or `:optional` read that matched more rows than it allows | `IncorrectResultSizeDataAccessException` |
| a value the generated API cannot represent | `TypeMismatchDataAccessException` |
| a row mapper's own exception | the runtime's `QueryExecutionException`, unchanged, as `JdbcTemplate` lets a row mapper's exception through |

Over PostgreSQL and Spring's own SQLSTATE translation, that makes `23505` a
`DuplicateKeyException`, `23503` a `DataIntegrityViolationException`, and
`40001` a `CannotAcquireLockException`.

The cause is the driver's `SQLException`, as from `JdbcTemplate`, so a handler
that reads the constraint name off a `PSQLException` works unchanged; for a
cardinality failure it is the runtime's `QueryCardinalityException` and for an
unmappable value its `UnmappableValueException`, which keeps the database type
and the value. The message begins with the runtime's, which names the query and
its repository, and Spring appends the SQL and the driver's message to it:

```text
Failed to execute query 'CreateAuthor' in AuthorRepository; SQL [INSERT INTO authors ...]; ERROR: duplicate key value violates unique constraint "authors_name_key"
```

The translator is the one Spring Boot's own `JdbcTemplate` uses: the
application's `SQLExceptionTranslator` bean when it defines exactly one, and
otherwise the translator a `JdbcTemplate` over the application's `DataSource`
chooses, which reads a `sql-error-codes.xml` at the root of the class path when
there is one and falls back to the SQLSTATE otherwise. So one database error
raises one class from the generated repository and from `JdbcTemplate` alike.

An application's own `QueryExecutor` bean is translated by nothing, because the
starter backs off from it entirely. Such an application wraps its executor in
the starter's own decorator to get the same exceptions:

```java
package com.example.app;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.sqlcj.runtime.JdbcQueryExecutor;
import org.sqlcj.runtime.QueryExecutor;
import org.sqlcj.spring.boot.autoconfigure.ExceptionTranslatingQueryExecutor;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
public class ApplicationExecutorConfiguration {

    @Bean
    QueryExecutor applicationQueryExecutor(DataSource dataSource) {
        return new ExceptionTranslatingQueryExecutor(
            new JdbcQueryExecutor(new TransactionAwareDataSourceProxy(dataSource)),
            new JdbcTemplate(dataSource).getExceptionTranslator()
        );
    }
}
```

`ExceptionTranslatingQueryExecutor` takes the executor it wraps and the
`SQLExceptionTranslator` it translates with, and hands parameters, row order,
and results on unchanged. The translator of a `JdbcTemplate` over the same
`DataSource` is what makes its exceptions that `JdbcTemplate`'s; any other
`SQLExceptionTranslator`, the application's own bean included, works too.

Outside the starter nothing is translated: in plain Java, and in a Spring
application that declares an executor of its own without the decorator, a
generated operation raises the runtime's own `QueryExecutionException` family —
`UniqueViolationException`, `ForeignKeyViolationException`,
`NotNullViolationException`, `CheckViolationException`, their parent
`ConstraintViolationException`, `TransactionConflictException`,
`QueryTimeoutException`, and `QueryCardinalityException`.
[Connection Ownership and Transactions](postgresql.md#connection-ownership-and-transactions)
lists the exception each SQLSTATE raises there, and `sqlState()` reports the
code itself.

The `PersistenceExceptionTranslationPostProcessor` Spring Boot registers is not
what translates any of this, and still does not reach a generated repository: a
generated class carries no Spring stereotype.

A commit failure of a Spring-managed transaction is Spring's own exception,
because Spring commits that transaction: its transaction manager translates
the failure into its family, and the runtime is not involved. The runtime's
mapped commit failure belongs to its own `JdbcTransactions` helper, which a
Spring application does not use inside a Spring-managed transaction.

## A Generated Package Outside the Application's Packages

The starter scans the auto-configuration packages only, so a `java.package`
that is not under the `@SpringBootApplication` package is never found. Such an
application registers its repositories with `@Import`:

```java
package com.example.app;

import com.example.generated.repositories.AuthorRepository;
import com.example.generated.repositories.BookRepository;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import({ AuthorRepository.class, BookRepository.class })
public class GeneratedRepositoryConfiguration {
}
```

`@Import` registers each class under its fully qualified class name, which is
the name the starter would have used, and a generated repository has one
constructor taking one `QueryExecutor`, so each imported repository is built
from the starter's executor and injected like a registered one. An imported
repository is itself a bean definition of its type, so a repository the starter
does scan after all ends up as one bean rather than two.

Moving the generated package under the application's own package is the other
way out. Widening the auto-configuration packages is not: they also decide
where Spring Boot looks for JPA entities and Spring Data repositories.

## Several Databases

Each database gets its own configuration file and Java package, because a
`java` block holds one package and one `java.out`. The two configurations may
share one `java.out`, because each package of an output directory has a
manifest of its own and a run cleans up its own package alone; generating the
same package into the same `java.out` from two configuration files is refused
with a diagnostic. The configurations below keep one output directory each:

```yaml
version: "1"
sql:
  - name: Author
    schema: sql/library/migrations
    queries: sql/library/queries.sql
java:
  package: com.example.app.library
  out: target/generated-sources/sqlcj-library
```

```yaml
version: "1"
sql:
  - name: Event
    schema: sql/audit/migrations
    queries: sql/audit/queries.sql
java:
  package: com.example.app.audit
  out: target/generated-sources/sqlcj-audit
```

`--config` names the file to generate from, so each database is generated by
its own command:

```bash
java -jar tools/sqlcj-cli-0.1.0-SNAPSHOT.jar generate --config sqlcj-library.yaml
java -jar tools/sqlcj-cli-0.1.0-SNAPSHOT.jar generate --config sqlcj-audit.yaml
```

Both output directories are compiled, so both are compile source roots:

```xml
<compileSourceRoots>
    <compileSourceRoot>${project.basedir}/src/main/java</compileSourceRoot>
    <compileSourceRoot>${project.build.directory}/generated-sources/sqlcj-library</compileSourceRoot>
    <compileSourceRoot>${project.build.directory}/generated-sources/sqlcj-audit</compileSourceRoot>
</compileSourceRoots>
```

The second `DataSource` is declared the way Spring Boot's
[Configure Two DataSources](https://docs.spring.io/spring-boot/how-to/data-access.html#howto.data-access.configure-two-datasources)
how-to shows: `@Qualifier("second")` and `@Bean(defaultCandidate = false)`, so
that the first database keeps the only default `DataSource` candidate and
Spring Boot's auto-configured `DataSource` and transaction manager stay over
it.

The second database's executor and transaction manager are declared the same
way, over the qualified `DataSource`, and each repository of that database gets
one `@Bean` over the qualified executor:

```java
package com.example.app;

import com.example.app.audit.repositories.EventRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.sqlcj.runtime.JdbcQueryExecutor;
import org.sqlcj.runtime.QueryExecutor;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
public class AuditDatabaseConfiguration {

    @Bean(defaultCandidate = false)
    @Qualifier("second")
    QueryExecutor secondQueryExecutor(@Qualifier("second") DataSource dataSource) {
        return new JdbcQueryExecutor(new TransactionAwareDataSourceProxy(dataSource));
    }

    @Bean(defaultCandidate = false)
    @Qualifier("second")
    JdbcTransactionManager secondTransactionManager(@Qualifier("second") DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }

    @Bean
    EventRepository eventRepository(@Qualifier("second") QueryExecutor executor) {
        return new EventRepository(executor);
    }
}
```

The second database's executor is the application's own, so it translates no
exception: wrapping it in an `ExceptionTranslatingQueryExecutor` over the
translator of a `JdbcTemplate` on the qualified `DataSource` gives that
database the exceptions the first database's executor raises; see
[Exceptions](#exceptions).

Two `QueryExecutor` bean definitions, the non-default one counted, leave the
starter registering no repository at all, so the first database's repositories
are the application's to register as well, with `@Import` even when their
package sits under the application's own:

```java
package com.example.app;

import com.example.app.library.repositories.AuthorRepository;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import(AuthorRepository.class)
public class LibraryDatabaseConfiguration {
}
```

Those repositories still run on the starter's executor: it is the only default
`QueryExecutor` candidate, so an unqualified injection point resolves to it.

`@Transactional` uses the auto-configured transaction manager and therefore
covers the first database, while `@Transactional("second")` names the second
database's manager by its qualifier and covers the second. Each transaction
covers one database: a repository takes part only in a transaction of the
`DataSource` its executor was built over, so a method whose transaction covers
one database leaves a write it makes through the other database's repository
outside that transaction, committed on its own connection.

## Spring Framework and Spring Boot Without the Starter

The starter is for Spring Boot 4. A Spring Framework application, a Spring Boot
3 application, and a Spring Boot application that excludes the
auto-configuration declare the executor and the repositories themselves. Their
sqlcj dependency is `org.sqlcj:sqlcj-runtime`, beside a PostgreSQL driver and
Spring's JDBC and transaction support, which is `spring-boot-starter-jdbc` in a
Spring Boot application:

```java
package com.example.app;

import com.example.app.db.repositories.AuthorRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.sqlcj.runtime.JdbcQueryExecutor;
import org.sqlcj.runtime.QueryExecutor;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@Import(AuthorRepository.class)
public class SqlcjConfiguration {

    @Bean
    QueryExecutor queryExecutor(DataSource dataSource) {
        return new JdbcQueryExecutor(new TransactionAwareDataSourceProxy(dataSource));
    }
}
```

One executor serves every repository, and `@Import` registers each generated
repository under its fully qualified class name over it, wherever its package
sits. Component scanning is no alternative: generated classes carry no Spring
annotation, so a scan never finds them. `@Import`, or one `@Bean` method per
repository, is what makes them beans.

The transaction manager keeps the plain `DataSource`; only the `DataSource`
handed to the executor is wrapped in the proxy. A Spring Boot application
already has that manager auto-configured. A Spring Framework application
declares a `JdbcTransactionManager` or `DataSourceTransactionManager` over the
`DataSource` itself and turns `@Transactional` on with
`@EnableTransactionManagement`. With that in place a `@Transactional` method
commits or rolls back its generated writes exactly as it does under the
starter.

For several databases the same two beans are repeated per database: one
qualified executor over a `TransactionAwareDataSourceProxy` of that database's
`DataSource`, one qualified transaction manager over the plain one, and one
`@Bean` per repository over its own database's executor, as
[Several Databases](#several-databases) shows.
