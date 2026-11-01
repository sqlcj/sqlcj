# sqlcj

sqlcj is a SQL compiler and type-safe Java code generator for PostgreSQL,
inspired by [sqlc](https://github.com/sqlc-dev/sqlc).

You write a PostgreSQL schema snapshot and named SQL queries. sqlcj analyzes
them against the schema and generates one readable Java repository per named
query group, with typed parameters and typed result records, which executes
through a small JDBC runtime:

```text
schema + named SQL  ->  sqlcj generate  ->  generated Java  ->  JDBC
```

```yaml
sql:
  - name: Author
    schema: sql/schema.sql
    queries: sql/queries.sql
```

```sql
-- name: GetAuthor :one
SELECT *
FROM authors
WHERE id = $1;
```

```java
AuthorRepository authors = new AuthorRepository(executor);

AuthorsRow author = authors.getAuthor(1L);
```

Every query of `sql/queries.sql` becomes a method of that one
`AuthorRepository`, and the `authors` row it returns is the top-level
`AuthorsRow` record generated once for the configured package.

The SQL stays visible and owned by the application. sqlcj is not an ORM, a
migration tool, or a query builder: it does not run migrations, inspect a live
database, or build queries at runtime.

## Project status

sqlcj is pre-release. The current version is `0.1.0-SNAPSHOT`, and no artifact
has been published to Maven Central or any other public repository yet, so the
CLI and runtime must be built from this repository. There is no release
procedure yet either.

## Requirements

- Java 17 or later for the CLI and for applications that use the generated code.
- Maven, to build sqlcj and to build a consuming project.
- PostgreSQL, reached through the application's own PostgreSQL JDBC driver.
  sqlcj does not ship a driver. Behavior is verified against PostgreSQL 16.

## Artifacts

`mvn -DskipTests install` produces all three artifacts from this repository:

| Artifact | What it is |
| --- | --- |
| `sqlcj-cli/target/sqlcj-cli-0.1.0-SNAPSHOT.jar` | The executable command line tool. It bundles its own dependencies and starts `org.sqlcj.Main`. |
| `org.sqlcj:sqlcj-runtime:0.1.0-SNAPSHOT` | The dependency of generated code. Its one dependency is `org.jspecify:jspecify`, the annotation-only artifact the runtime and the generated sources state their nullness with, so compiler, YAML, and SQL-parser libraries never reach an application. |
| `org.sqlcj:sqlcj-spring-boot-starter:0.1.0-SNAPSHOT` | The Spring Boot 4 starter, released at the runtime's version. It brings the runtime, auto-configures the executor over the application's `DataSource`, and registers the generated repositories as beans. |

The CLI and the runtime must always be used at the same version.

## Commands

```bash
java -jar sqlcj-cli-0.1.0-SNAPSHOT.jar version          # print the tool version
java -jar sqlcj-cli-0.1.0-SNAPSHOT.jar --version        # the same, as an option
java -jar sqlcj-cli-0.1.0-SNAPSHOT.jar -v               # the same, short
java -jar sqlcj-cli-0.1.0-SNAPSHOT.jar generate         # compile sqlcj.yaml into Java
java -jar sqlcj-cli-0.1.0-SNAPSHOT.jar                  # list the commands
java -jar sqlcj-cli-0.1.0-SNAPSHOT.jar --help           # the same, as an option
java -jar sqlcj-cli-0.1.0-SNAPSHOT.jar -h               # the same, short
java -jar sqlcj-cli-0.1.0-SNAPSHOT.jar generate --help  # usage of one command
```

Every version and help form above prints on standard output and exits with
status `0`. `-h` and `--help` work on every command; the version options belong
to the root command. See
[Configuration](docs/configuration.md#version-and-help) for the full list.

`generate` reads whichever of `sqlcj.yaml` and `sqlcj.yml` exists in the
directory it is run in; neither name wins, so holding both files, like holding
neither, ends the run with one diagnostic and status `1`.
`--config <path>` names the configuration file itself, so generation can run
from any working directory:

```bash
java -jar sqlcj-cli-0.1.0-SNAPSHOT.jar generate --config ../project/sqlcj.yaml
```

An explicit `--config` path is read exactly as named, with no discovery and no
conflict check. A relative `--config` value is resolved against the directory
the command is
run in, while the relative paths inside the file stay relative to the file's own
directory. `generate` exits with status `0` on success, and with status `1`
after printing one diagnostic for a configuration file it cannot find or choose,
invalid configuration, an unreadable source, a
schema it cannot parse, an output directory or file
it cannot write, a stale generated file it cannot delete, or an output manifest
it cannot read or write.

A query it cannot compile does not end the run: every rejected query of every
configured entry is reported, one diagnostic per rejected query, before the run
exits with status `1` having written nothing. Every diagnostic that names a
source line begins with the location `<absolute path>:<line>: `, which IDE
terminals and CI log viewers turn into a link to the file.

## Getting started

Follow the [Quickstart](docs/quickstart.md) to build a Maven application that
generates, compiles, and runs PostgreSQL create, read, list, update, and delete
operations, including a committed and a rolled-back transaction run through the
runtime's transaction helper.

Its last step builds the same queries into a Spring Boot application on the
Spring Boot starter, which declares no sqlcj bean of its own and commits and
rolls back its generated writes with `@Transactional`; see
[Spring](docs/spring.md) for the Spring configurations in full.

## Documentation

- [Quickstart](docs/quickstart.md) — an end-to-end PostgreSQL project from an
  empty directory.
- [Queries](docs/queries.md) — query annotations, the supported SQL shapes,
  parameter and binding order, the generated API, and what is not supported.
- [Configuration](docs/configuration.md) — the `sqlcj.yaml` format, query-group
  names, path resolution, and generated Java naming.
- [PostgreSQL Support](docs/postgresql.md) — the engine contract, accepted
  column types and `CREATE TABLE` constructs, null handling, and connection
  ownership.
- [Spring](docs/spring.md) — the Spring Boot starter, a generated package
  outside the application's packages, several databases, and Spring without the
  starter.

## License

sqlcj is licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
