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

The executor is defined only when exactly one `DataSource` candidate qualifies:
one `DataSource` bean, or several of which one is `@Primary`. With two
candidates and none primary, or with no `DataSource` at all, the application
starts with no sqlcj executor, and with no registered repository either, rather
than with repositories bound to a database nobody chose.

### The registered repositories

Every class sqlcj generated in the application's auto-configuration packages —
the package of the `@SpringBootApplication` class and the packages below it —
is registered as a singleton bean under its fully qualified class name, built
from the one `QueryExecutor` bean definition. The generator marks every
repository with `@org.sqlcj.runtime.GeneratedRepository`, which is how the
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
  application's executor instead.
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
configured by Spring Boot's own `spring.datasource.*` properties. It also
translates no exception. A failing operation throws the runtime's
`org.sqlcj.runtime.QueryExecutionException`, and a `:one` read that matches no
row or several throws `org.sqlcj.runtime.QueryCardinalityException`, inside a
Spring application exactly as outside one.

## A Generated Package Outside the Application's Packages

The starter scans the auto-configuration packages only, so a `java.package`
that is not under the `@SpringBootApplication` package is never found. Such an
application registers its repositories with `@Import`:

```java
package com.example.app;

import com.example.generated.AuthorRepository;
import com.example.generated.BookRepository;
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

Each database gets its own configuration file, Java package, and output
directory, because a `java` block holds one package and one `java.out`, and the
manifest of an output directory records the files generated into it:

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

import com.example.app.audit.EventRepository;
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

Two `QueryExecutor` bean definitions, the non-default one counted, leave the
starter registering no repository at all, so the first database's repositories
are the application's to register as well, with `@Import` even when their
package sits under the application's own:

```java
package com.example.app;

import com.example.app.library.AuthorRepository;
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

import com.example.app.db.AuthorRepository;
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
