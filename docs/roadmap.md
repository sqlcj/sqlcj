# sqlcj Roadmap

This roadmap defines the path from the current implementation to the first
public `0.1.0` release, the scope of sqlcj before and after that release, and
the direction to `1.0`. It is a scope contract, not a promise of sqlc parity
and not a backlog of every useful database feature.

sqlcj is still pre-release. The generated API, configuration, and runtime API
may change while the milestones below are implemented. Publishing is deferred
until the release gates in this document are satisfied.

## Product Direction

sqlcj is a PostgreSQL-first SQL compiler and type-safe Java data-access code
generator. A user owns the schema and the named SQL. sqlcj analyzes those
inputs at build time and generates a small, readable Java API that executes
through JDBC.

```text
schema (migrations or snapshot) + named SQL
            -> compile-time analysis
            -> generated repositories, row records, and enums
            -> small JDBC runtime
```

The product promise is:

> Write SQL, catch supported schema/query/type mistakes before the application
> runs, and call the resulting operations through an ordinary Java data-access
> object.

sqlcj is not an ORM, a migration runner, a query builder, or a Java clone of
sqlc. SQL stays visible and authoritative. Generated code has no persistence
context, dirty tracking, lazy loading, relationship management, or runtime
reflection.

The generated API is one concrete, final repository per configured query group,
constructed once from a `QueryExecutor`:

```java
AuthorRepository authors = new AuthorRepository(new JdbcQueryExecutor(dataSource));

AuthorsRow created = authors.createAuthor("Ada", null);
Optional<AuthorsRow> found = authors.findAuthor(created.id());
```

A transaction runs several generated operations on one connection, through
repositories constructed over an executor bound to it. The runtime's
transaction helper (D-H) or a framework's transaction supplies that connection,
or the application manages a `Connection` itself. The name `Repository`
describes a cohesive data-access API; it does not imply JPA semantics.

### Product principles

- Make the common path pleasant before making the supported SQL set broad.
- Read the schema the application already maintains; never require a second
  hand-written copy of it.
- Generate ordinary Java that can be read, debugged, and constructed directly.
- Keep one explicit boundary between generated code and JDBC execution.
- Preserve known database types, parameter order, selected-column order, and
  source SQL meaning.
- Leave no dead end. When sqlcj cannot infer a type, the SQL states it with a
  cast, in any clause, rather than in a sqlcj-specific language. When a schema
  statement cannot change what sqlcj models, sqlcj ignores it.
- Never model silently wrong. A table, column, or type sqlcj cannot model
  exactly is rejected or left invisible; it never becomes a wrong Java type or
  a record with missing components.
- Analyze a finite, documented subset and reject the rest with a diagnostic
  that says what to write instead.
- Require no database, Docker, or network access at generation time.
- Add one tested vertical slice at a time; do not build abstractions for an
  unimplemented database, framework, query kind, or type system.
- Prefer one good default over configuration switches.
- Keep framework integration optional. Plain Java and JDBC remain sufficient.
- Generate only what a query uses: a row record or an enum exists because a
  query returns, embeds, reads, or binds it.
- Integrate with a framework through the `DataSource` it already manages, not
  through an adapter, an annotation, or a dependency on the framework.
- Never own the whole data layer: hand-written SQL lives beside generated code
  and survives regeneration.

## Where sqlcj Stands

Milestones 1 through 32 are completed (see
[Completed Milestone Record](#completed-milestone-record)). The state below is
that of `dfc52bb`, where Milestone 32's last change merged; at its review
`mvn test` ran 101 runtime tests and 1918 CLI tests, with the PostgreSQL tests
required. The code is named `org.sqlcj` and licensed under the Apache License
2.0. The reference service, an original multi-tenant work tracker with eleven Flyway
migrations and 56 named queries, generates one byte-identical tree from its
migration history, a hand-written snapshot, and a `pg_dump` snapshot, and runs
every query against PostgreSQL 16.

### Supported today

| Area | Supported |
| --- | --- |
| Build and tooling | Java 17 or later; a CLI with `generate [--config <path>]` and `version`; strict, versioned YAML configuration; deterministic output with a generated-code notice; removal of stale generated files through an output manifest; one-line diagnostics and no internal error shown as an exception; no database, Docker, or network at generation time |
| Schema input | Schema snapshots, ordered Flyway-style migration directories, and `pg_dump --schema-only` snapshots, several per query group. `CREATE`, `ALTER`, and `DROP TABLE` are modeled, including `LIKE`, `PARTITION OF`, identity and generated columns, and renames, and so are enum types and their changes. Every statement that cannot change the model, such as an index, view, function, trigger, `DO` block, policy, grant, sequence, comment, or data statement, is ignored |
| Column types | Integer types, `BOOLEAN`, character types, `DATE`, `TIME`, `TIMESTAMP`, `TIMESTAMPTZ` as `OffsetDateTime`, `NUMERIC` as `BigDecimal`, `REAL`, `DOUBLE PRECISION`, and `FLOAT(p)` as either, `UUID`, `BYTEA` as `byte[]`, `JSON`, `JSONB`, `citext`, `inet`, `cidr`, `macaddr`, and `macaddr8` as `String`, `INTERVAL` as the runtime's `SqlInterval`, enums as generated Java enums, domains over a mapped type as their base type, and one-dimensional arrays as `List<T>` |
| Query forms | `:one`, `:optional`, `:many`, `:exec`, which returns the affected-row count, and `:batchexec`, which runs one write for many rows as a JDBC batch and returns the driver's counts; `$N` or `:name` placeholders; a cast types any placeholder in any clause, and an occurrence that nothing types takes the type another occurrence of its parameter states, so a criteria query needs at most one cast per parameter |
| Reads | Tables, CTEs, recursive ones included, and subqueries as sources; `UNION`, `INTERSECT`, and `EXCEPT`, with or without `ALL`, at any depth; inner, left, right, full, cross, and comma-separated joins, `USING` and `NATURAL` joins, and `LATERAL` subqueries; comparisons, `AND`/`OR`/`NOT`, `IN` lists, `LIKE`/`ILIKE`, null tests, `BETWEEN`, `= ANY(:list)`, and correlated `EXISTS`, `IN (SELECT ...)`, and scalar subqueries; optional filters written `(:x::text IS NULL OR col = :x)`; dynamic sorting with `CASE` and a cast placeholder; offset and keyset pagination; `COUNT`, `MIN`, `MAX`, `SUM`, and `AVG` typed without a cast and other expressions with a cast and an alias; `DISTINCT`, `GROUP BY`, `HAVING`, and `FOR UPDATE [SKIP LOCKED]` carried as written |
| Writes | `INSERT` of one or several `VALUES` rows or from a `SELECT`; `UPDATE`, including `UPDATE ... FROM`; `DELETE`, including `DELETE ... USING`; `ON CONFLICT DO NOTHING` and `DO UPDATE` upserts with a target, `ON CONSTRAINT`, or a conflict predicate; `RETURNING` |
| Generated Java | One final repository per query group; one `<TableName>Row` record and one enum per used table and type, shared by every repository of a package; a `<QueryName>Result` record for every other result; a joined table's row embedded as its row record, and a left-joined row `null` when absent; `Optional` and `List` cardinality |
| Runtime | No dependencies. The `QueryExecutor` interface receives each call's repository and query names, so it can be decorated for logging or metrics; `JdbcQueryExecutor` runs over a `DataSource`, one connection per operation, or over a caller-owned `Connection`; a batch runs on one connection, as its own transaction only on an auto-commit `DataSource` connection; a failure names the repository and query and keeps the `SQLException` as its cause |
| Transactions | The runtime's `JdbcTransactions` runs a callback on one connection, commits on a normal return, and rolls back on any failure; a caller-owned `Connection` covers an isolation level, a savepoint, or `SET LOCAL`; repositories over a `TransactionAwareDataSourceProxy` take part in Spring-managed transactions, JPA's included, verified against PostgreSQL 16 |
| Verification | PostgreSQL 16 integration tests through Testcontainers, the reference service, and an external Maven sample checked by `scripts/verify-release.sh` |

### Not supported today

| Area | Not supported | When |
| --- | --- | --- |
| Distribution | Published artifacts, a Maven plugin, a Gradle plugin | Maven plugin: Milestone 34; Gradle plugin: 35; artifacts: 37 |
| Transactions | Verified participation in Quarkus, Micronaut, or Jakarta EE transactions | After `0.1.0` |
| SQL | `MERGE`, data-modifying CTEs, set-returning functions as sources, and window and other functions without a cast | After `0.1.0`, by evidence |
| Types | Ranges, composites, multidimensional arrays, arrays of a domain, domains over an unmapped type, `hstore`, geometric types, `MONEY`, `XML`, `TIMETZ`; `JSONB` as a JSON library type | After `0.1.0`, by evidence |
| Execution | Streaming large results, `COPY`, asynchronous or reactive execution | Streaming and `COPY` after `0.1.0`, by evidence; asynchronous execution is not planned |
| Migration tools | Liquibase formatted SQL and Liquibase's offline `update-sql` output load but are untested and undocumented; a MyBatis Migrations script's `-- //@UNDO` section is applied as part of the migration, and the changelog script `migrate init` writes, which names its table `${changelog}`, is rejected | Milestone 33 |
| Schema | PostgreSQL schemas other than `public`; views, materialized views, and table inheritance as query sources | After `0.1.0`, one configurable schema first |
| Customization | Type and naming overrides, user-supplied result classes, generated parameter objects, nullness annotations, Javadoc from query comments | After `0.1.0` |
| Dynamic SQL | SQL built at runtime, optional-clause markers, dynamic `IN` expansion, generated query variants, a query DSL | Never (D-M) |
| Generated models | A record for every table of the schema, entities | Never (D-I) |
| Other databases and languages | MySQL, SQLite, and other databases; Kotlin or other output languages | Long-term options only |

### Is it ready for `0.1.0`?

No, and the owner is right not to publish it today. The reasons are specific:

1. It cannot be consumed. No artifact is on Maven Central, and generation has
   no Maven or Gradle integration.
2. Some schemas a service already maintains do not load as written: Liquibase
   changelogs are unproven, and a MyBatis Migrations script's undo section is
   applied as if it were part of the migration.

Milestones 33 to 37 close exactly these; Milestone 26 gave the project its
permanent `org.sqlcj` name and its license before anything was published,
Milestone 27 made a transaction correct without a framework and verified it
inside Spring's, Milestone 28 analyzed set operations and recursive CTEs,
Milestone 29 accepted every join kind PostgreSQL has, Milestone 30 mapped
the `citext`, network address, `interval`, domain, and `FLOAT(p)` columns the
first-contact probe recorded as unmapped, Milestone 31 runs one write
statement for many rows as a JDBC batch, and Milestone 32 made a criteria
query need at most one cast per parameter.
Beyond them, a feature waits for evidence from users (D-K): added before
publication, it would be chosen without that evidence and would enlarge the
surface `1.0` must keep compatible.
`0.1.0` starts the feedback loop; it does not claim completeness. The
production promise is `1.0`.

### First-contact probe

The compiler core is sound and every documented shape is tested. The open
question was whether an ordinary service can adopt it. On 2026-10-05 the CLI
built from `8c8637e` was run against an original, sqlcj-owned probe: the
migration history and queries of a small multi-tenant task-tracking service,
written the way a Java team typically writes them, one statement or query at a
time. Nothing was fetched from sqlc. The results:

| Area | Outcome on 2026-10-05 | Examples |
| --- | --- | --- |
| Schema statements that load | Accepted | identity and generated columns, `public.`-qualified tables, partial, expression, `GIN`, and `CONCURRENTLY` indexes, PL/pgSQL functions and triggers, sequences, data statements, `citext`/`inet`/`interval` columns recorded as unmapped |
| Schema statements that stop the whole schema | Rejected | `ALTER COLUMN ... SET DEFAULT` and `DROP DEFAULT`, `CREATE`/`DROP VIEW`, materialized views, `CREATE SCHEMA`, `SET`, `TRUNCATE`, row-level security and policies, roles and default privileges, `OWNER TO`, `VALIDATE CONSTRAINT`, `EXCLUDE` constraints, domains, composite types, `ALTER TYPE ... RENAME VALUE` |
| Schema statements the parser cannot read | Syntax error for the whole file | `DO` blocks, `DROP TYPE`, `COMMENT ON TYPE`/`INDEX`, `SET search_path`, `ALTER COLUMN ... SET DATA TYPE`, unnamed `ADD CHECK`, `NOT VALID` |
| Silent mis-models | Wrong model, no error | `CREATE TABLE ... (LIKE t INCLUDING ALL)` is modeled with no columns, so `SELECT *` generates an empty record; tables of other schemas collapse into the default namespace, so `SELECT * FROM reporting.users` compiles against `users` |
| Internal errors | Raw Java exception text | `CREATE TABLE ... PARTITION OF` (`NullPointerException`), `INSERT ... SELECT` and `UNION ALL` (`ClassCastException`) |
| Queries that compile as written | Accepted | named-parameter pagination, `FOR UPDATE [SKIP LOCKED]`, optimistic locking, `now()` writes, upserts, `= ANY`, three-table joins, `GROUP BY` with `COUNT(*)`, `DISTINCT`, `RETURNING` |
| Queries that compile with a cast | Accepted after `::type` | `lower(:email::text)`, `ILIKE '%' \|\| :q::text \|\| '%'`, JSONB path comparisons, `IS DISTINCT FROM`, keyset row comparison, array operators, `MAX`, `COUNT(col)`, `COALESCE(SUM(...))`, and window projections |
| Queries with no way through | Rejected even with casts | a join `ON` with more than one condition (composite keys, `deleted_at IS NULL` filters), any placeholder inside a subquery (`EXISTS`, `IN (SELECT ...)`, scalar), any placeholder in `ORDER BY` (so no dynamic sort) or a cast `LIMIT`, a CTE used as a source, `ON CONFLICT DO NOTHING` without a target, `ON CONFLICT ON CONSTRAINT`, multi-row `VALUES`, `INSERT ... SELECT`, `UPDATE ... FROM`, aliased `RETURNING` items, `UNION`, `RIGHT`/`USING`/`LATERAL` joins |
| Generated API shape | Poor | `SELECT t.*, p.* FROM tasks t JOIN projects p ...` returns one flat record with components `id1`, `organizationId1`, ..., `id2`, `createdAt2` |

The conclusion then was that an ordinary service hits a dead end or a silent
mis-model on its first day, in its schema history and in a handful of very
common query shapes. Milestones 22 to 25 closed every row of the table except
the constructs the [`0.1.0` non-goals](#010-non-goals) keep out.
The reference service's unsupported catalogue records each of those with the
diagnostic sqlcj reports. Most of the gap closed by making two existing
principles hold everywhere — the cast as the universal escape hatch, and
ignoring what cannot change the model — plus one mechanism, nested analysis
scopes, that the subquery, CTE, and multi-row write shapes share.

JSqlParser 5.4 (2026-09-13) is still the latest release on Maven Central,
rechecked on 2026-10-17.

## Who sqlcj Is For

Whether a company can run sqlcj in production depends on the stage:

| Stage | Who can adopt it | What it promises |
| --- | --- | --- |
| Today | Nobody outside the project: there is no artifact and no build integration | Nothing |
| `0.1.0` | A team adopting it on one PostgreSQL service built with Maven or Gradle, pinning the exact version | Every documented shape is tested against PostgreSQL. A `0.x` minor release may still change the generated API or the configuration, and its release notes state the change |
| `0.x` after `0.1.0` | Services that keep their tables in a schema of their own, and Quarkus, Micronaut, and Jakarta EE services, as the [post-release tracks](#after-010) land | The same, over a wider verified surface |
| `1.0` | An organization standardizing on it across services | The compatibility, verification, and support commitments of [Long-Term Direction](#long-term-direction-and-10) |

- **A new PostgreSQL service written in Java 17 or later.** This is the primary
  target: SQL migrations, named SQL, generated repositories, plain Java or
  Spring, and no ORM.
- **An existing small or medium PostgreSQL service that adopts sqlcj
  incrementally.** sqlcj reads the schema the service's migration tool already
  maintains, or a `pg_dump --schema-only` snapshot. Generated repositories run
  on the same `DataSource` and inside the same Spring-managed transaction as
  the code they replace, so a team moves one query group at a time. There is no
  big-bang migration and no conversion tool.
- **Larger organizations** can evaluate `0.1.0` on one service. Its exit cost
  is low: generated code is plain Java over a runtime of a few classes with no
  dependencies, so a team that stops using sqlcj keeps the generated sources and
  maintains them by hand. What such an organization needs before standardizing
  on it — schemas of its own, nullness annotations for Kotlin callers,
  streaming, and a compatibility promise — belongs to the post-release tracks
  and to `1.0`.

Moving existing data access to sqlcj:

| From | What moves | What stays |
| --- | --- | --- |
| `JdbcTemplate`, `NamedParameterJdbcTemplate`, `JdbcClient` | The SQL, nearly as written: `:name` parameters keep their names, `?` becomes `$N` or `:name`, and hand-written row mappers are deleted | SQL assembled at runtime from string fragments |
| MyBatis mappers | Each statement: `#{x}` becomes `:x`, an `<if>` filter becomes a guarded criterion `(:x::type IS NULL OR ...)`, a `<foreach>` list becomes `= ANY(:ids)`, and result maps become generated records | Statements whose clauses, joins, or result shape change with their arguments |
| JPA and Hibernate | Native queries as written, and JPQL or Criteria queries where SQL is clearer | Entities, their lifecycle, and their writes. Both run in one Spring transaction; JPA must flush a pending change before a sqlcj read sees it |
| jOOQ | Rarely worth it, because jOOQ already checks its queries; only plain-SQL strings | The DSL |

Reading the schema from the migration tool a service already uses (D-N):

| Migration tool | What sqlcj reads |
| --- | --- |
| Flyway SQL migrations | The migration directory, in Flyway's version order, with undo files ignored |
| Liquibase formatted SQL | The changelog files, whose `--changeset` and `--rollback` lines are comments (Milestone 33) |
| Liquibase XML, YAML, or JSON | The SQL Liquibase's offline `update-sql` writes for the changelog without a database (Milestone 33), or a `pg_dump --schema-only` snapshot |
| MyBatis Migrations | The scripts directory, each script up to its `-- //@UNDO` line (Milestone 33) |
| Java-based migrations, other tools, or a hand-managed database | A `pg_dump --schema-only` snapshot |

**Hand-written SQL beside generated code (D-L).** No tool types every query a
project writes, so sqlcj never owns the whole data layer. Regeneration deletes
only the files it generated before, so a hand-written class survives anywhere,
even in the generated package directory. It composes generated repositories
rather than extending them, may run its own SQL through the runtime's
`QueryExecutor` or any other JDBC tool, and joins the same transaction through
the framework's `DataSource` or the transaction helper's connection.

**Criteria queries (D-M).** A search by any combination of optional criteria is
one query in which a `null` argument switches its filter off:

```sql
-- name: SearchUsers :many
SELECT *
FROM users
WHERE (:email::text IS NULL OR email = :email)
  AND (:roles::user_role[] IS NULL OR role = ANY(:roles))
  AND (:createdFrom::timestamptz IS NULL OR created_at >= :createdFrom)
ORDER BY id
LIMIT :pageSize OFFSET :pageOffset;
```

A service whose tables live in a PostgreSQL schema other than `public` can be
modeled before the configurable-schema track lands only when its migrations and
its queries both write unqualified names, as they do under Flyway's default
schema. Its `pg_dump` snapshot qualifies every name and models nothing.

sqlcj is not for applications whose main query style is runtime-built dynamic
SQL, for databases other than PostgreSQL, or for teams that want entity
lifecycle management. Those are deliberate boundaries, not missing features.

## What the Comparable Tools Teach Us

The goal of this research is to adopt durable interaction patterns, not feature
lists.

### sqlc

sqlc (1.31.1, 2026-04) parses PostgreSQL with the real PostgreSQL parser, builds
a catalog from schema files or migration directories, and generates one typed
method per named query on a `Queries` object bound to one database handle.
`WithTx` rebinds the same API to a transaction. Enums become generated types,
arrays become slices, and `JSON`/`JSONB` become raw bytes unless overridden.
Where sqlc cannot infer a type it relies on SQL itself: a cast such as
`$1::text`, the `sqlc.arg`/`sqlc.narg` macros, optional filters written as
`(sqlc.narg('x')::text IS NULL OR col = sqlc.narg('x'))`, and
`= ANY($1::bigint[])` instead of dynamic `IN` lists.

Three lessons matter for sqlcj's plan:

- **Static analysis has a ceiling.** Even with the real PostgreSQL parser, sqlc
  documents that its built-in analyzer "can't handle some complex queries and
  type-inference", and it added an optional database-backed analyzer, against
  a user-supplied or managed PostgreSQL, that caches its results. sqlcj's
  answer for `0.1.0` is the cast escape hatch in every clause; a database-backed
  analyzer stays a long-term option.
- **What users ask for.** Ranked by reactions on sqlc's issue tracker, the
  PostgreSQL-relevant requests are user-supplied result types, nullability
  annotations for parameters, dynamic queries and dynamic `ORDER BY`,
  composite types, observability, one-to-many result nesting, `UNNEST`-based
  bulk inserts, views, and multiple schemas. Dynamic queries remain
  deliberately unsupported: one SQL text is one typed method.
- **Embedding needs left-join nullability from day one.** `sqlc.embed` is
  popular, and a whole cluster of its open issues is about embedded rows from
  `LEFT JOIN`s that cannot be null. sqlcj embeds a joined table row only with a
  defined absence rule (Milestone 25).

Adopt: schema input from the migrations the application already has, a
cohesive API bound once to an execution boundary, explicit cardinality,
generated enums and reusable table models, casts as the user's type
statement, `= ANY` instead of dynamic `IN` expansion, and one SQL text per
method.

Already covered differently: `:exec` returns the affected-row count (sqlc
`:execrows`), `RETURNING` replaces `:execlastid`, and `:optional` replaces a
"no rows" error sentinel with `Optional`.

Do not copy for `0.1.0`: multiple engines and languages, plugins, cloud and
database-backed analysis, `vet`/`verify`, `COPY` and row-returning batch
commands, macros, type overrides, renames, and `emit_*` switches.

References: [sqlc configuration](https://docs.sqlc.dev/en/latest/reference/config.html),
[query annotations](https://docs.sqlc.dev/en/latest/reference/query-annotations.html),
[macros](https://docs.sqlc.dev/en/latest/reference/macros.html),
[schema and migrations](https://docs.sqlc.dev/en/latest/howto/ddl.html),
[analyzers](https://docs.sqlc.dev/en/latest/howto/generate.html),
[changelog](https://docs.sqlc.dev/en/latest/reference/changelog.html),
[dynamic queries request](https://github.com/sqlc-dev/sqlc/issues/3414), and
[issues ranked by reactions](https://github.com/sqlc-dev/sqlc/issues?q=is%3Aissue+sort%3Areactions-%2B1-desc).

#### sqlc, feature by feature

Rechecked on 2026-10-17: sqlc 1.31.1 (2026-04-22) is still its latest release.
Two facts matter for the questions this plan answers. By default sqlc generates
a model for every table and enum of the schema, and its `omit_unused_structs`
option exists to generate only those that queries use. And sqlc leaves
transactions to the application, which calls `Begin`, rebinds the queries with
`WithTx`, and calls `Commit` or `Rollback` itself.

| sqlc | sqlcj |
| --- | --- |
| PostgreSQL, MySQL, and SQLite engines | PostgreSQL only; another database is a long-term option |
| Go, plus Python, Kotlin, and TypeScript through plugins | Java only, with no plugin protocol |
| A catalog from schema files or migration directories | Adopted, plus `pg_dump` snapshots |
| A model for every table and enum, opted out with `omit_unused_structs` | Only used tables and types, always, and no entities (D-I) |
| `:one`, `:many`, `:exec` | Adopted, plus `:optional` |
| `:execrows`, `:execlastid`, `:execresult` | Covered: `:exec` returns the count and `RETURNING` the ids |
| `:batchexec` | Adopted as JDBC batch writes (Milestone 31) |
| `:copyfrom`, `:batchone`, `:batchmany` | After `0.1.0`, by evidence |
| `sqlc.arg`, `sqlc.narg`, and `@name` | Covered by `:name` placeholders, casts, and guarded criteria (D-M) |
| `sqlc.slice` | Covered by `= ANY(:ids)` |
| `sqlc.embed` | Covered by embedded rows, with left-join absence |
| A parameter struct beyond `query_parameter_limit` | Not adopted for a single call: Java has no named arguments, so a parameter record's constructor is as positional as the method. A batch takes a list of a generated parameter record (Milestone 31) |
| `emit_*` switches, `rename`, JSON tags, generated interfaces | Not adopted; one output style |
| Type `overrides` | After `0.1.0`, as controlled customization |
| `WithTx` over the `DBTX` interface | Covered by `QueryExecutor` over a caller-owned connection, plus a transaction helper (D-H) |
| `diff` and `compile` | After `0.1.0`, as a build check |
| Database-backed analyzer, `vet`, `verify`, managed databases | Database-backed analysis is a long-term option; the rest is not adopted |

References: [sqlc transactions](https://docs.sqlc.dev/en/latest/howto/transactions.html)
and [releases](https://github.com/sqlc-dev/sqlc/releases).

### SQL-first and compile-time-checked tools on the JVM and elsewhere

- [sqlc-gen-java](https://github.com/tandemdude/sqlc-gen-java) is a community
  sqlc WASM plugin generating Java records and a `Queries` class, with JSpecify
  nullness annotations. It needs the Go sqlc toolchain and sqlc's
  configuration, and it is early (0.0.7, 2025-07).
- [SQLDelight](https://sqldelight.github.io/sqldelight/latest/jvm_postgresql/)
  generates Kotlin query classes from `.sq` files and `.sqm` migrations as a
  Gradle plugin: a build plugin and migration-derived schema are the expected
  JVM workflow.
- [Manifold SQL](https://github.com/manifold-systems/manifold/tree/master/manifold-deps-parent/manifold-sql)
  types native SQL through a `javac` plugin and a live database: full coverage,
  at the cost of a database and a compiler plugin in every build.
- [sqlx](https://github.com/launchbadge/sqlx/blob/main/sqlx-cli/README.md)
  (Rust) checks queries against a live database at compile time and commits the
  results (`sqlx prepare`) so that ordinary builds need no database. It is the
  model to study if sqlcj ever adds database-backed analysis.

### Jakarta Persistence, Hibernate, and Spring Data

Jakarta Persistence manages entities through a persistence context; Spring Data
adds repository interfaces, derived queries, and runtime proxies; Spring Data
JDBC keeps aggregate mapping without a persistence context. Hibernate 7's
[Data Repositories](https://docs.hibernate.org/orm/7.4/repositories/html_single/)
generate repository implementations at compile time and fully type-check HQL,
but state that "native SQL queries cannot be validated at compile time".

That sentence is sqlcj's niche: build-time checking of native PostgreSQL SQL,
without entities. sqlcj adopts the call-site clarity of
`AuthorRepository.find...()` and runs inside Spring-managed transactions
without depending on Spring, through Spring's
[`TransactionAwareDataSourceProxy`](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/datasource/TransactionAwareDataSourceProxy.html),
which exists for code that takes a plain `DataSource`. Coexisting with JPA in
one transaction is sqlcj's migration path for JPA services. Do not adopt entity
state, derived queries, proxies, implicit CRUD, or a mandatory container.

### MyBatis, Jdbi, and Doma

MyBatis groups mapped statements on mapper interfaces; Jdbi SQL Objects bind a
declared interface to a handle; Doma generates DAO implementations at compile
time and checks that SQL templates and DAO parameters agree, but not against a
schema. All three bind parameters by name, so Java developers expect a named
placeholder. MyBatis's dynamic SQL is its most used and most complex feature,
and it is exactly what makes its statements impossible to type-check ahead of
time. A MyBatis user moving to sqlcj rewrites `<if>` filters as the
`(:x::type IS NULL OR ...)` idiom and `<foreach>` lists as `= ANY(:ids)`.

References: [MyBatis dynamic SQL](https://mybatis.org/mybatis-3/dynamic-sql.html),
[Jdbi SQL Objects](https://jdbi.org/releases/3.49.6/), and
[Doma](https://docs.domaframework.org/).

### jOOQ and MyBatis Generator

jOOQ and MyBatis Generator generate reusable table records and run as Maven and
Gradle plugins. jOOQ's [`DDLDatabase`](https://www.jooq.org/doc/latest/manual/code-generation/codegen-meta-sources/codegen-ddl/)
generates from DDL scripts by replaying them in an in-memory H2 database, so
PostgreSQL-specific DDL does not always translate, and the common PostgreSQL
setup instead applies Flyway migrations to a Testcontainers database during
the build. They also show how quickly a schema metamodel, a SQL DSL, generated
CRUD, and naming options become a product of their own. sqlcj generates a
table's row type once per package and plugs into the Maven build, but generates
only methods backed by user-written named SQL, with no DSL and no CRUD.

### Transactions and frameworks

| Tool | How a transaction is run |
| --- | --- |
| sqlc | The application begins, commits, and rolls back; `WithTx` rebinds the generated queries |
| Plain JDBC | `setAutoCommit(false)`, `commit` or `rollback` on every path, then restoring auto-commit and closing the connection |
| Jdbi | A callback, `jdbi.inTransaction(handle -> ...)` |
| jOOQ | A callback, `ctx.transactionResult(configuration -> ...)`, with an optional thread-bound provider |
| Spring | `@Transactional` or `TransactionTemplate`; plain JDBC code joins the transaction through `DataSourceUtils` or a `TransactionAwareDataSourceProxy` |
| Quarkus | The Agroal `DataSource` is integrated with the JTA transaction manager by default (`quarkus.datasource.jdbc.transactions=enabled`) |
| Micronaut | With `micronaut-data-tx-jdbc`, a connection obtained from the injected `DataSource` inside a transactional method takes part in its transaction |
| Jakarta EE | A container-managed `DataSource` enlists its connections in the JTA transaction |

Two lessons follow. Without a framework, the Java libraries closest to sqlcj
converged on a callback, because the manual JDBC sequence is long and fails
silently when one path forgets to roll back or to restore auto-commit. Go's
`defer tx.Rollback()` keeps sqlc's manual form short; Java has no equivalent.
With a framework, the transaction already reaches plain JDBC code through the
`DataSource`: Quarkus, Micronaut, and Jakarta EE hand out a transaction-aware
one, so a generated repository that takes a `DataSource` joins their
transactions without an adapter, and Spring's `TransactionAwareDataSourceProxy`
makes Spring's `DataSource` transaction-aware in one line.

JetBrains' State of Java 2025 reports Maven as the build tool of 67% of Java
developers and Spring as the framework of 65%, which is why Maven and Spring
come first, Gradle follows before `0.1.0`, and the other frameworks after it.

References: [Jdbi transactions](https://jdbi.org/#_transactions),
[jOOQ transaction management](https://www.jooq.org/doc/latest/manual/sql-execution/transaction-management/),
[Quarkus datasources](https://quarkus.io/guides/datasource),
[Micronaut SQL](https://docs.micronaut.io/5.1.x/sql/), and
[The State of Java 2025](https://lp.jetbrains.com/the-state-of-java-2025).

## Definition of the `0.1.0` Release

`0.1.0` is the first public, useful SQL-first Java data-access release. It does
not need broad PostgreSQL parity. It needs one coherent workflow that an
ordinary PostgreSQL service can adopt without rewriting its schema, without
dead ends in its everyday queries, and without fighting the generated API.

"Enough" is defined by evidence, not by a feature count: `0.1.0` is ready when
the [reference service](#the-reference-service) works end to end and the
[release gates](#release-gates) pass. It is not ready before that, and it does
not wait for anything beyond it (D-K).

A user must be able to:

1. Point a query group at the application's existing Flyway-style migration
   directory, Liquibase formatted SQL, MyBatis Migrations scripts, the SQL
   Liquibase writes offline for another changelog format, a schema snapshot, or
   a `pg_dump --schema-only` snapshot, and have a realistic history load: sqlcj
   stops only at a statement that changes a table, enum, or domain in a way it
   cannot model.
2. Map common PostgreSQL column types, including `TIMESTAMPTZ`, enums, `JSONB`,
   one-dimensional arrays, `INTERVAL`, `citext`, network addresses, and
   domains, to known Java types, and share one generated row record and enum per
   table and type across all repositories of a package.
3. Write the reference service's queries in SQL with named parameters: CRUD,
   exact and optional lookups, search, criteria queries whose `null` arguments
   switch filters off, dynamic sorting, offset and keyset pagination, counts and
   common aggregates, grouping, upserts, id lists, membership checks with
   `EXISTS`, CTEs and recursive CTEs, set operations, every join kind
   PostgreSQL has, `LATERAL` subqueries included, multi-row and
   `SELECT`-sourced inserts, and joined updates. A cast is needed only where no
   column gives the type, and once per parameter.
4. Receive a joined table's whole row as its shared row record, and explicit
   zero/one/many cardinality without nullable top-level results.
5. Receive, for any statement sqlcj does not accept, a diagnostic that names
   the query and says what to write instead, and never an internal error.
6. Execute one write statement for many rows as one JDBC batch.
7. Generate repositories from the Maven build, the Gradle build, or the CLI,
   deterministically and without stale Java.
8. Run several generated operations in one transaction through the runtime's
   transaction helper, without a framework and without hand-written commit or
   rollback; keep full control through a caller-owned `Connection` when needed;
   and use repositories inside a Spring-managed transaction, including one
   shared with JPA, without sqlcj depending on Spring.
9. Keep hand-written SQL beside generated code, untouched by regeneration and
   in the same transaction.
10. Consume documented runtime, CLI, Maven plugin, and Gradle plugin artifacts
    under `org.sqlcj` and the Apache License 2.0, on Java 17 or later, without
    cloning this repository.

The first release targets PostgreSQL through blocking JDBC, Maven, and Gradle.
That narrow target is a strength, not unfinished portability work.

### The reference service

The reference service is a sqlcj-owned, original, realistic application used as
the acceptance fixture of Milestones 22 through 36: a small multi-tenant
work-tracking service (organizations, users, projects, memberships, tasks,
comments, and an audit log). It grows out of the 2026-10-05 probe.

- **Schema history.** Flyway migrations that evolve the schema the way a real
  team does: identity and serial keys, enums and an enum replacement, `JSONB`
  and arrays, a column default changed later, a view, a function and trigger, a
  `DO` block, row-level security, indexes and comments, a partitioned table,
  and a table in a second schema that no query uses.
- **Query catalogue.** Named queries covering requirement 3 of the definition
  above, written as a Java team would write them, with casts only where no
  column gives the type.
- **Unsupported catalogue.** The probe's constructs that stay outside `0.1.0`,
  each with the diagnostic sqlcj reports. Milestones 28 and 29 move the set
  operation, recursive CTE, and join entries into the query catalogue.

Milestones 22 to 25 added the parts of the reference service they made work and
proved them against PostgreSQL, and Milestones 27 to 32 added theirs the same way.
Milestone 33 proves its schema inputs on a small fixture of its own instead,
because an equivalent Liquibase XML changelog of the reference history would
wrap its views, policies, triggers, and `DO` block in `<sql>` changes and so
test Liquibase's pass-through rather than the DDL Liquibase writes.
Milestone 34 generates it through the Maven plugin, and Milestone 36 runs it as
an application in plain Java, through the transaction helper, and in a
Spring-managed transaction. A query outside the catalogue is not a `0.1.0`
requirement. Like every fixture, it is original SQL; nothing is copied or
fetched from sqlc.

## `0.1.0` Non-Goals

The following are explicitly out of scope for the first release.

### Persistence model

- JPA entities, persistence contexts, dirty checking, cascades, lazy loading,
  relationship management, or automatic unit-of-work behavior.
- Derived queries from Java method names, automatic CRUD repositories, runtime
  repository proxies, classpath scanning, or generated interface/implementation
  pairs.
- Generated models for tables or enum types that no query uses (D-I).
- Generated base classes or fragments for hand-written repository methods
  (D-L).
- A requirement on Spring, Jakarta, Quarkus, Micronaut, CDI, or another
  container.

### Schema input

- MySQL, SQLite, Oracle, SQL Server, or a generic multi-dialect abstraction.
- Executing, rolling back, diffing, or verifying migrations; database
  provisioning; live database introspection; or database-backed analysis at
  generation time.
- Parsing Liquibase XML, YAML, or JSON changelogs, whose SQL Liquibase writes
  offline (D-N); Liquibase property substitution, contexts, and labels; Flyway
  Java migrations and placeholder substitution; and down/undo migrations.
- PostgreSQL schemas (namespaces) other than the default: their tables are
  ignored, not modeled. Views and materialized views as query sources, and
  table inheritance.
- DDL executed inside `DO` blocks or function bodies, which sqlcj does not see.

### SQL breadth

- `MERGE`, data-modifying CTEs, the `SEARCH` and `CYCLE` clauses of recursive
  CTEs, set-returning functions as sources, `LATERAL` function calls included,
  and expression type inference without a cast beyond the aggregates Milestone
  25 lists.
- Dynamic SQL, optional-clause markers, dynamic `IN` expansion, macros,
  generated query variants, or a Java query DSL (D-M). Criteria use guarded
  predicates, and dynamic sorting is written in SQL with `CASE` and a cast
  placeholder.
- `COPY`, and batches that return rows. Batches of one write statement are in
  scope (Milestone 31).

### Types and customization

- Composites, ranges, multiranges, geometric types, `hstore`, `MONEY`, `XML`,
  `TIMETZ`, multidimensional arrays, and domains over an unmapped type.
- Type overrides, custom codecs, mapping `JSONB` to a JSON library type, naming
  overrides, user-supplied result classes, generated parameter objects,
  nullness annotations, and output style switches.

### Runtime and integrations

- Reactive or asynchronous APIs, streaming cursors, connection pooling,
  retries, tracing, metrics, or statement caching. Logging and metrics are
  added by decorating `QueryExecutor` or the `DataSource`, which the
  documentation shows.
- Transaction propagation, savepoints, isolation or read-only options,
  thread-bound transactions, and transaction annotations. The runtime's one
  transaction helper (D-H) is the only transaction API, and a caller-owned
  `Connection` covers everything else.
- A check/verify goal or task, IDE integration, watch mode, native images,
  installers, or package-manager distribution.
- A Spring Boot starter, a Spring module, framework adapters, or generated
  framework annotations; verified Quarkus, Micronaut, or Jakarta EE
  participation.
- A documentation website; the repository's documentation serves `0.1.0`.
- Other output languages, generator plugins, hosted services, telemetry, or
  cloud features.

Unsupported features must be rejected when they enter an analyzed location;
they are not release defects merely because the underlying SQL parser can
represent them.

## Owner Decisions

The owner decided D-A to D-E on 2026-10-05, accepting each recommendation, and
D-F was decided on 2026-10-09 as recommended, by the orchestrator to whom the
owner delegated the open decisions. D-G to D-K were decided on 2026-10-17 as
recommended, when the owner asked for the plan after Milestone 25 and delegated
the choices; after the owner's review the same day, D-K was revised and D-L to
D-N were added, again as recommended. They bind the milestones below; like
every pre-release decision, they may be revisited by evidence (see
[Roadmap Update Workflow](#roadmap-update-workflow)).

- **D-A — Java 17 baseline.** The runtime, the generated code, the CLI, and the
  Maven plugin target Java 17, built with `--release 17` and tested on Java 17
  and Java 21. Spring Framework 7 and Spring Boot 4 keep a Java 17 baseline, and
  2026 surveys put Java 17 at roughly a quarter to a third of production use.
  The runtime artifact and the Maven plugin are loaded by the application's JVM
  and the build's JVM, so a Java 21 baseline would exclude those teams entirely
  for no product benefit: generated code uses only records, text blocks, and
  `Optional`. Compiling the sources at `8c8637e` with `--release 17` fails only
  on two record patterns in the runtime and on twelve Java 21 library calls and
  one pattern in the CLI; Jackson 3, JSqlParser, and Picocli all support Java
  17. Milestone 22 applies it first, so no later milestone adds Java 21-only
  code.
- **D-B — Statement boundaries in schema files.** sqlcj finds statement
  boundaries in a schema file with JSqlParser's own lexer and parses each
  statement separately, so that one statement the parser cannot read fails only
  itself, not the whole file. This supersedes the part of Milestone 19's D2
  that forbids splitting SQL. D2 waited for a JSqlParser release that reads more
  `COMMENT ON` targets; none exists, and the probe found seven unreadable
  everyday forms, including every `DO` block and `DROP TYPE`. An applied Flyway
  migration cannot be edited without breaking its checksum, so without this
  decision such a history has no way through. Using the parser's own lexer
  keeps one SQL grammar in the project; the parser's error-recovery mode stays
  rejected for the reasons D2 gives.
- **D-C — Apache License 2.0**, the Java ecosystem norm, with an explicit patent
  grant. Milestone 26 adds the `LICENSE` file; Milestone 37 adds the license
  metadata of the published artifacts.
- **D-D — Maven Central namespace `dev.sqlcj`. Superseded by D-G on
  2026-10-17.** It was chosen because it matched the existing `groupId` and Java
  packages, so no rename was needed.
- **D-E — Publisher identity.** The owner publishes from a Central Portal
  account they control and is the project's developer in the published POM.
  Artifacts are signed with a dedicated project signing key. Credentials and
  key material stay outside the repository and its CI logs.
- **D-F — Unreadable `ALTER TABLE` statements classified by their tokens.**
  An `ALTER TABLE` statement the parser cannot read is classified with the
  lexer D-B adopts, without rewriting it. It is ignored when each of its
  top-level actions begins with `ADD CONSTRAINT <name>`, with `ADD` followed by
  `CHECK`, `UNIQUE`, `PRIMARY KEY`, `FOREIGN KEY`, or `EXCLUDE`, or with
  `ALTER [COLUMN] <name> ADD GENERATED`; every other unreadable
  `ALTER TABLE`, including `ALTER COLUMN ... SET DATA TYPE`, stays rejected
  naming its file and line. JSqlParser 5.4 cannot read the identity form
  `pg_dump` writes for every identity column
  (`ADD GENERATED ... AS IDENTITY (SEQUENCE NAME ...)`), nor constraint
  additions with `EXCLUDE`, `DEFERRABLE INITIALLY DEFERRED`,
  `UNIQUE USING INDEX`, or `NOT VALID`, nor an unnamed `ADD CHECK`, so without
  this decision no `pg_dump` snapshot with an identity column loads. None of
  the ignored forms can change a modeled column's existence, name, type, or
  nullability: nullability comes from `NOT NULL` alone and never changes a
  generated Java type, and PostgreSQL requires a column to be `NOT NULL` before
  `ADD GENERATED ... AS IDENTITY`. Reusing the D-B lexer adds no second
  grammar. Milestone 22's requirement 7 applies it.
- **D-G — Namespace `org.sqlcj`.** Supersedes D-D. The Java packages, the Maven
  `groupId`, and the Maven Central namespace become `org.sqlcj`, verified with a
  DNS `TXT` record on `sqlcj.org`. The owner prefers it, and `.org` is the
  convention of community-run Java projects (`org.apache`, `org.hibernate`,
  `org.jooq`, `org.mybatis`, `org.flywaydb`, `org.jdbi`). The cost decides the
  timing: before publication the rename is one mechanical change of the Java
  sources, the generated code, and the documentation; after it, the rename
  breaks every user's imports and generated sources. Milestone 26 therefore
  renames first, before any later milestone creates code or coordinates under
  the old name. The owner
  registers `sqlcj.org` before Milestone 37 verifies the namespace, so the
  project never publishes under a namespace it does not control; `sqlcj.dev` is
  not needed. Revised on 2026-10-17: the registration was an entry condition of
  Milestone 26 until the owner waived it there, and the GitHub organization
  `sqlcj-dev` becomes `sqlcj`, so the organization, the domain, and the
  namespace share one name. The owner renames the organization on GitHub, and
  Milestone 26 changes the repository's references to it. Before publication
  the rename changes only references inside the repository; once Milestone 37
  writes SCM and project URLs into the published POMs, it would leave the old
  name in every published release.
- **D-H — Transactions in three layers, none of them a framework.**
  - *Framework-managed:* a framework's transaction reaches sqlcj through the
    `DataSource` it manages (D-J).
  - *Plain Java:* the runtime gains one transaction helper, constructed from a
    `DataSource`. It runs a callback on one connection with auto-commit
    disabled and hands the callback a `QueryExecutor` bound to that
    connection, over which the callback constructs the repositories it needs,
    and the connection itself, so that hand-written JDBC takes part (D-L).
    A normal return commits; any exception or error rolls the transaction
    back and is rethrown, with a failed rollback attached as suppressed;
    auto-commit is restored and the connection closed on every path. A call
    inside another call is an independent transaction on its own connection.
    There is no propagation, savepoint, isolation, read-only, or retry
    option.
  - *Manual:* the caller-owned `Connection` path stays unchanged for
    everything else, such as an isolation level, a savepoint, or
    `SET LOCAL`.

  The helper belongs in the runtime's JDBC implementation, which already owns
  connections; generated code does not change, because a transaction spans
  repositories, so a generated `withTransaction` method on one repository is
  the wrong owner. A thread-bound transaction that singleton repositories join
  implicitly is rejected, because that is the job frameworks already do through
  the `DataSource`, and so are transaction annotations, which need a container.
  sqlc offers no helper, because Go's `defer` keeps the manual form short; in
  Java the manual form is long, and the quickstart's own example omits the
  rollback on failure, which is why Jdbi, jOOQ, and Spring's
  `TransactionTemplate` all offer a callback. The class and method names are
  decided when Milestone 27 is activated. Revised on 2026-10-17, when
  Milestone 27 was activated: the helper is `JdbcTransactions`, and its
  callback receives only the connection, binding repositories with
  `new JdbcQueryExecutor(connection)`, so that a decorated `QueryExecutor`
  also works inside a transaction; and auto-commit is restored only after a
  successful commit or rollback, because JDBC's `setAutoCommit` commits an
  open transaction.
- **D-I — Generate only what a query uses, and never entities.** A
  `<TableName>Row` record or an enum is generated because a query returns,
  embeds, reads, or binds it, as today. sqlcj never generates a record for every
  table of the schema, never generates JPA entities, and adds no switch to do
  either. sqlc generates every table and enum by default, and its
  `omit_unused_structs` option exists because users wanted less. A table no
  query uses may hold a column type sqlcj does not map, so generating it would
  either fail a schema no query needs or produce a record with a missing
  component, which the principle against silent mis-models forbids. An unused
  record is dead code, and a row record is a result shape, not an entity with
  identity or lifecycle. An application that wants a generated enum in its
  domain code receives it as soon as one query reads or binds it.
- **D-J — Frameworks integrate through `DataSource`.** sqlcj ships no Spring,
  Quarkus, Micronaut, or Jakarta EE module, starter, or extension. Spring is
  verified for `0.1.0` with its `TransactionAwareDataSourceProxy`; Quarkus,
  Micronaut, and Jakarta EE, whose managed `DataSource` already takes part in
  their transactions, are verified after `0.1.0` with an external example each,
  and documented only once verified. A framework module is added only if such a
  verification finds a gap that a plain bean definition cannot close, or users
  show a need a few lines of configuration do not meet.
- **D-K — What must land before `0.1.0`.** Revised on 2026-10-17 after the
  owner's review. Four kinds of work: a change to a public surface that would
  break users if made after publication, such as the namespace or the
  `QueryExecutor` contract; what a user needs to consume and adopt sqlcj at
  all, which is artifacts, a license, the Maven and Gradle plugins,
  transactions, Spring participation, and the schema their migration tool
  maintains; the SQL and column types an existing service writes as a matter
  of course — set operations, recursive CTEs, every join kind, `citext`,
  `inet`, `interval`, and domains — with the JDBC batches a Java developer
  expects for repeated writes and the criteria idiom of D-M; and the release
  mechanics. The owner was right that the third kind belongs in the first
  release, and the first version of this decision was wrong to treat it as
  guesswork: the first-contact probe is evidence from an ordinary service,
  several of these constructs have no way through, and moving SQL nearly as
  written is the adoption path. Gradle moves in for the same reason, because
  its audience is known, and Milestone 34 settles the plugin contract on Maven
  first. Everything else waits for evidence from users and follows
  [After `0.1.0`](#after-010). `MERGE` stays out despite the owner's proposal:
  `INSERT ... ON CONFLICT`, which sqlcj already analyzes, is PostgreSQL's idiom
  for upserts; a merge that also deletes runs as an upsert and a `DELETE` in
  one transaction; `MERGE` returns rows only from PostgreSQL 17; and its
  analysis surface, several `WHEN` branches each with a write of its own, is
  the largest of any statement. A `0.x` release makes no compatibility promise,
  and every feature added before release enlarges the surface `1.0` must keep
  compatible.
- **D-L — Hand-written SQL lives beside generated code.** No tool types every
  query a project writes, so sqlcj never owns a whole data layer and adds no
  extension layer of its own: what the owner asked for, hand-written queries
  that survive regeneration and work alongside generated repositories, already
  holds or needs one small rule. Regeneration deletes only the files its
  previous manifest lists, so a hand-written class survives anywhere, the
  generated package directory included. A hand-written class composes
  generated repositories, which stay `final`, instead of extending them, so
  sqlcj generates no abstract base classes or custom-method fragments. The
  runtime's `QueryExecutor` is a documented API that hand-written code may call
  with its own SQL and row mapper, gaining the same binding, cardinality checks,
  and failure messages; any other JDBC tool, or jOOQ for queries built at
  runtime, works as well. Hand-written JDBC joins the same transaction through a
  framework's `DataSource` or the transaction helper's connection, which
  Milestone 27 exposes for that reason (D-H). Milestone 36 documents the rest.
- **D-M — Criteria queries are one static SQL text.** A query that filters by
  any combination of optional criteria is written once, each criterion guarded
  by its parameter: `(:email::text IS NULL OR email = :email)`,
  `(:roles::user_role[] IS NULL OR role = ANY(:roles))`, or
  `(:from::timestamptz IS NULL OR created_at >= :from)`. A `null` argument
  switches its criterion off, and sorting and paging use `CASE` and
  placeholders. This is the owner's proposal — nullable fields, where a `null`
  field adds no filter — written in SQL instead of generated code, and it works
  today. sqlcj never builds SQL at runtime, adds no optional-clause markers,
  macros, or query variants, and never makes the executable SQL differ from the
  SQL a user wrote, apart from its placeholders. One text means that every
  combination is type-checked at build time and that the statement that runs is
  the statement a developer reads and `EXPLAIN`s. Spring Data Specifications,
  the Criteria API, MyBatis dynamic SQL, and jOOQ build criteria at runtime and
  give up exactly that checking; sqlc, the closest tool, uses the same guard
  pattern with `sqlc.narg` and builds no query at runtime either. The
  pattern has one known cost: once the driver prepares the statement on the
  server, PostgreSQL may switch to a generic plan that cannot drop a switched-off
  criterion and so may miss its index, which the `plan_cache_mode` setting
  prevents; the documentation states both. Criteria that change the joined
  tables or the result shape are separate queries, or hand-written SQL (D-L). A
  call with many `null` arguments is positional, like every generated method; a
  generated parameter object is an additive option after `0.1.0` if users ask
  for it. A 2026-10-17 probe found that the idiom still needs a cast at every
  occurrence of a parameter outside an analyzed clause, as in
  `'%' || :q::text || '%'`; Milestone 32 removes that repetition and documents
  the pattern. Revised on 2026-10-24, when Milestone 32 was activated: an
  occurrence that nothing types takes the type another occurrence of its
  parameter states, and sqlcj writes that type at its placeholder, as
  `?::text`, because the driver sends a `null` argument without a type, which
  PostgreSQL cannot type as the operand of `IS NULL` or of an overloaded
  function.
- **D-N — Migration tools are read, never run or reimplemented.** sqlcj reads the
  SQL a migration tool applies and never runs a tool, connects to a database, or
  parses a tool's own change format. Flyway SQL migrations are read as they are
  today. Liquibase formatted SQL is SQL whose `--changeset` and `--rollback`
  lines are comments, so its files are read as any schema file, in the order the
  configuration lists them or, for a directory, by name, which is the order
  Liquibase's `includeAll` uses. A Liquibase XML, YAML, or JSON changelog is read
  through the SQL Liquibase itself writes for it with `update-sql` against an
  `offline:postgresql` URL, which needs no database, or through a
  `pg_dump --schema-only` snapshot; parsing Liquibase's change types would
  reimplement Liquibase. A MyBatis Migrations script is read up to its
  `-- //@UNDO` line, below which its undo statements stand as plain SQL that
  sqlcj applies today, dropping tables and adding columns the database does not
  have. Java-based migrations and every other tool are covered by
  `pg_dump --schema-only`, the one-command bridge that keeps generation
  database-free; a `pg_dump` snapshot already loads today. Milestone 33 proves
  the Liquibase paths and adds the MyBatis Migrations rule. Revised on
  2026-10-24, when Milestone 33 was activated: the undo marker is every line
  MyBatis Migrations 3.6.0 reads as one, not only the exact `-- //@UNDO`,
  because its reader also accepts blanks around the `//`, text after `@UNDO`,
  and a `//` comment prefix, and an undo section sqlcj applied where MyBatis
  skips it would mis-model silently; `update-sql` writes the
  `DATABASECHANGELOG` statements only when the offline URL sets
  `outputLiquibaseSql`, never a `DATABASECHANGELOGLOCK` statement, and
  records the changesets it wrote in a CSV file whose presence makes a later
  run write only newer ones; and a directory is read non-recursively, while
  `includeAll` also descends into subdirectories. Revised again on 2026-10-24,
  when the owner approved Milestone 33's requirement 5: the changelog script
  every `migrate init` project holds is read as written, its `CREATE TABLE`
  of a `${...}` variable leaving that table invisible, as another schema's
  table is, and its constraint-only `ALTER TABLE` of that name ignored;
  MyBatis's variables are otherwise never substituted.

## Milestone Status

| Milestone | Status | Outcome |
| --- | --- | --- |
| 1–9 — Initial compiler and JDBC path | Completed | Named SQL becomes typed, executable Java for the first CRUD subset |
| 10 — Trustworthy compiler boundary | Completed | Accepted parameters and generated JDBC bindings agree |
| 11 — PostgreSQL schema/type baseline | Completed | The supported schema and scalar types run against PostgreSQL |
| 12 — Returning writes | Completed | Supported writes can return typed rows |
| 13 — Transaction-ready runtime | Completed | Generated operations run through a `DataSource` or caller-owned `Connection` |
| 14 — Packaging and adoption baseline | Partially completed | Runtime/CLI split, docs, and external sample are complete; publication (M14-W4) moved to Milestone 37 |
| 15 — Repository-shaped generated API | Completed | One configured query group becomes one generated repository |
| 16 — Result and cardinality contract | Completed | Reusable row types and explicit exact/optional/many behavior |
| 17 — Everyday PostgreSQL read slice | Completed | A bounded set of common read operations becomes practical |
| 18 — Generation workflow hardening | Completed | Configuration and generated output are repeatable and clean |
| 19 — Schema from migrations | Completed | sqlcj reads the schema the application already maintains |
| 20 — Shared models and everyday PostgreSQL types | Completed | Common column types map to Java, and table rows and enums are generated once per package |
| 21 — Everyday SQL expressions | Completed | Common write, upsert, filter, grouping, and id-list idioms compile as written, with named parameters |
| 22 — Real migration histories load | Completed | The build targets Java 17, a realistic migration history or `pg_dump` snapshot loads, and nothing is mis-modeled silently |
| 23 — A way through for every query | Completed | Casts work in every clause, common join and conflict forms compile, and no internal error reaches the user |
| 24 — Nested queries and multi-row writes | Completed | Subqueries, CTEs, multi-row and `SELECT`-sourced inserts, and joined writes are analyzed |
| 25 — Result shapes settled | Completed | Joined table rows embed their shared row record, and common aggregates need no cast |
| 26 — Project identity | Completed; the `sqlcj.org` entry condition was waived by the owner (D-G) | The project is named `org.sqlcj` everywhere, its GitHub references name `sqlcj`, and it is licensed under Apache 2.0 |
| 27 — Transactions in plain Java and Spring | Completed | A transaction is correct by default without a framework, hand-written JDBC joins it, and repositories take part in Spring-managed transactions |
| 28 — Set operations and recursive CTEs | Completed | `UNION`, `INTERSECT`, `EXCEPT`, and `WITH RECURSIVE` are analyzed |
| 29 — Every join kind | Completed | `RIGHT`, `FULL`, `CROSS`, comma, `USING`, `NATURAL`, and `LATERAL` subquery joins are analyzed |
| 30 — Everyday PostgreSQL types | Completed | `INTERVAL`, `citext`, network types, domains, and `FLOAT(p)` map to exact Java types |
| 31 — Batch writes | Completed | One write statement executes for many rows as a JDBC batch |
| 32 — Criteria queries | Completed | Optional-criteria queries need at most one cast per parameter and are documented and verified |
| 33 — Migration tool inputs | Completed | Liquibase and MyBatis Migrations schemas load as written |
| 34 — Maven build | Planned | Generation runs in the Maven build |
| 35 — Gradle build | Planned | Generation runs in the Gradle build with the Maven plugin's contract |
| 36 — `0.1.0` release candidate | Planned | The reference service runs end to end, and the public surfaces are frozen and documented |
| 37 — `0.1.0` publication | Planned; needs `sqlcj.org` registered and the verified `org.sqlcj` namespace (D-G) | Verified public artifacts on Maven Central |

The milestones are ordered by where an adopting user fails first: loading the
schema history (22), getting each query through at all (23), writing nested and
multi-row SQL naturally (24), and settling the result API before it becomes a
contract (25). The project's permanent name comes next, because every later
milestone creates code, coordinates, or documentation under it (26), and then a
correct transaction (27), which the owner ranks first among the remaining
gaps. The SQL, type, batch, and criteria milestones (28 to 32) change the
generated API and the runtime contract, so they land before either is frozen;
set operations come first because recursive CTEs and several join shapes reuse
their branch analysis. The schema inputs of other migration tools (33) follow,
then generation in the build, Maven first so that its contract is settled once
(34) and Gradle copies it (35), the whole proven on the reference service (36),
and publication (37). Each milestone is decomposed into work items only when it
is activated.

## Milestone 33 — Migration Tool Inputs

### Goal

Load the schema from the migration tools Java services use, as D-N defines,
without running or reimplementing them.

### Work items

Activated on 2026-10-24 at the owner's request, after Milestone 32 was
completed. Reassessed against the working tree at `dfc52bb`:
`DefaultSourceLoader`'s directory order, `SchemaSourceSplitter`'s statement
boundaries, tagged dollar quoting, and psql meta-command blanking,
`DefaultSchemaParser`'s ignore rules, `SqlcjCompiler`'s per-file diagnostics,
their tests, the reference service, and `docs/configuration.md` and
`docs/postgresql.md`; and probed outside the repository with the CLI built from
`dfc52bb`, Liquibase 5.0.4 and 4.33.0 in their official Docker images, MyBatis
Migrations 3.6.0 from Maven Central, and PostgreSQL 16.15 in
`postgres:16-alpine`. Nothing was fetched from sqlc. Facts that correct or
shape the plan:

- Liquibase formatted SQL already loads, because each of its directives is a
  comment. A sqlcj-owned changelog with `--liquibase formatted sql`,
  `--changeset` lines with and without `splitStatements:false`, `--comment`,
  `--preconditions` with `--precondition-sql-check`, one-line and
  `/* liquibase rollback */` rollbacks, and a `$body$` function generated the
  same tree as the offline output of its equivalent XML changelog, and
  PostgreSQL applied both. Requirement 1 needs no production change.
- A changeset with `endDelimiter:/` ends its statements with `/`, which
  Liquibase's offline output keeps. sqlcj ends a statement only at `;`, so such
  a statement merges with the next one into one unreadable statement, ignored
  when it opens with no table or type words; in the probe a following
  `DROP TABLE` was swallowed. Any statement written without its `;` behaves so
  today, and a PostgreSQL changelog needs `endDelimiter` only where dollar
  quoting already keeps a body whole. `--ignoreLines`, `dbms`, `context`,
  `labels`, preconditions, and a formatted SQL root's `--include` and
  `--includeAll` are comments to sqlcj as well, so every changeset of a listed
  file is applied.
- `update-sql` against `offline:postgresql` needs no database. For an XML
  changelog using `createTable` with `autoIncrement`, an enum created by
  `<sql>`, `addColumn`, `renameColumn`, `dropColumn`, `addNotNullConstraint`,
  `addDefaultValue`, `dropDefaultValue`, `dropNotNullConstraint`,
  `addUniqueConstraint`, `addForeignKeyConstraint`, `createIndex`,
  `createSequence`, `renameTable`, `createView`, `createProcedure` with a
  `$body$` function, `dropTable`, and `tagDatabase`, it wrote unqualified
  statements, identity columns as `GENERATED BY DEFAULT AS IDENTITY`, under a
  commented header, and sqlcj loaded them unchanged. Liquibase 4.33.0, the
  last Apache-licensed release, and 5.0.4, the latest, licensed under the
  Functional Source License, wrote the same statements apart from the version
  and deployment id.
- Correcting requirement 2: by default the output has no `DATABASECHANGELOG`
  statement, as Liquibase's offline documentation states. The URL parameter
  `outputLiquibaseSql=data_only` adds an `INSERT INTO databasechangelog` after
  each changeset, and `all` also a `CREATE TABLE databasechangelog` first. No
  mode writes a `DATABASECHANGELOGLOCK` statement. sqlcj loads all three; the
  `databasechangelog` table generates nothing unless a query uses it (D-I).
- The offline connection records the changesets it wrote in
  `databasechangelog.csv` in the working directory, or in the file the URL's
  `changeLogFile` names, and a second run writes only the header, so each run
  needs a fresh file.
- Liquibase's `includeAll` applies files alphabetically and descends into
  subdirectories; a sqlcj directory puts every file not named
  `V<version>__...` in name order and is not read recursively. MyBatis
  Migrations applies its `scripts` directory by numeric id and leaves out
  `bootstrap.sql` and `onabort.sql`; its default ids are 14-digit timestamps,
  whose name order is their id order. sqlcj applies `bootstrap.sql` and
  `onabort.sql` last; the `bootstrap.sql` `migrate init` writes holds only
  comments.
- MyBatis Migrations 3.6.0's `MigrationReader` reads line by line, without
  lexing SQL, and ends a script's up part at a line whose first non-blank
  characters are `--` or `//`, then optional blanks, `//`, optional blanks,
  and `@UNDO`, case-sensitive, with any text after. Run through it,
  `--//@UNDO`, `-- // @UNDO`, an indented marker, one with trailing words,
  `// //@UNDO`, and `-- //@UNDOING` were markers, and `-- //@undo`,
  `-- @UNDO`, `--- //@UNDO`, `-- / /@UNDO`, and `/* -- //@UNDO */` were not.
  sqlcj applies the undo section today: a scripts directory whose undo section
  drops the table its migration creates fails its query with
  `Table not found in schema`.
- `migrate init` also writes `<id>_create_changelog.sql`, whose
  `CREATE TABLE ${changelog}` and
  `ALTER TABLE ${changelog} ADD CONSTRAINT PK_${changelog} PRIMARY KEY (id)`
  MyBatis fills from the environment's `changelog` property, `CHANGELOG` by
  default. sqlcj rejects both (`Encountered unexpected token: "{"` and
  `Table not found in schema: $`), so no scripts directory `migrate init`
  created loads, with or without the undo marker rule. MyBatis records no
  checksum, so editing that script changes nothing MyBatis checks.

Decided by the architect on 2026-10-24, under the rule that pre-release
decisions are revisable by evidence:

- **Undo marker (amends requirement 3).** sqlcj ends a schema file at the first
  line MyBatis Migrations 3.6.0 reads as its undo marker, by MyBatis's own
  line-based rule, so nothing from that line on is read; an exact
  `-- //@UNDO` match would apply an undo section MyBatis skips. Line ends are
  `\n`, `\r\n`, and `\r`, as the splitter counts them. The rule applies to every
  schema file and sits in schema parsing beside the psql meta-command rule,
  because it decides which text of a loaded source is SQL; the lines and
  columns of earlier statements do not change.
- **Offline fixture (settles requirement 2).** The fixture is the output of
  Liquibase 5.0.4's official Docker image running `update-sql` against
  `offline:postgresql?outputLiquibaseSql=all` on the sqlcj-owned XML
  changelog, in a fresh directory outside the repository, committed byte for
  byte as Liquibase wrote it; the XML changelog states that command in a
  comment. Liquibase becomes a dependency of no module, test scope included,
  which keeps a Functional Source License artifact out of the build, and the
  schema-loading test needs no Docker or network. Unlike a `pg_dump`, whose
  `\restrict` key changes each run, the output varies between runs only in its
  header comments and `DATABASECHANGELOG` values, so a committed copy stays
  exact. `all` is chosen because its statements include the other modes'.
- **A fixture of its own.** Requirements 1 and 2 use one small sqlcj-owned
  schema, written once as formatted SQL and once as XML, with one query file,
  not the reference service; see [The reference service](#the-reference-service).
  The formatted SQL is a directory of changelog files named in `includeAll`
  order, which also proves the directory path.
- **Changelog script (adds requirement 5).** Recommended by the architect
  and approved by the owner on 2026-10-24, which widens the milestone: a
  `CREATE TABLE` whose table name is a MyBatis `${...}` variable leaves that
  table invisible, and an `ALTER TABLE` of such a name whose every action adds
  a constraint is ignored. Every project `migrate init` creates has the
  script, the table is MyBatis's bookkeeping, which no query uses, and an
  invisible table is what sqlcj already makes of another schema's table: a
  query naming it fails as an unknown table and nothing modeled changes.
  Refined by the architect when M33-W3 started, from the recheck recorded
  there: every other statement that names a variable is treated as today, so
  a table statement is rejected and a statement of any other kind, such as an
  `INSERT` or a `CREATE INDEX`, stays ignored; "rejected" in the approved
  wording held only for the former.
- **Implementation order:** W1, W2, W3, W4, so the documentation states W3's
  outcome.

- **M33-W1 — MyBatis Migrations' undo marker ends a schema file —
  Completed.** Implements requirement 3 by the undo marker decision. A line
  MyBatis Migrations reads as its undo marker ends what sqlcj reads of any
  schema file. Boundary: schema parsing and one sentence of
  `docs/configuration.md`; the loader, directory order, `${...}` variables,
  `bootstrap.sql`, and `onabort.sql` are unchanged. Acceptance evidence is
  schema parser tests of a script whose undo section drops its table, of the
  probed marker and non-marker lines, of a `\r\n` script, of an unreadable
  statement after the marker that is no longer reported and of one before it
  still reported at its line; a compiler test generating the same repository
  from a MyBatis-shaped scripts directory and from a snapshot; the
  documentation sentence; `mvn spotless:check`; and `mvn test`.
  Review evidence: `SchemaSourceSplitter.split` ends the source at the start
  of the first line matching `[ \t]*(?:--|//)[ \t]*//[ \t]*@UNDO` from its
  beginning, scanning `\n`, `\r\n`, and `\r` lines before psql meta-command
  blanking and lexing, so earlier lines and columns are unchanged; every
  schema file reaches it through `DefaultSchemaParser.parse`, once per file.
  Parser tests keep a table its undo section drops, end at each probed marker,
  apply the statements after each probed non-marker, cut a `\r\n` script, no
  longer report an `ALTER FOREIGN TABLE` after the marker, and still report
  one before it at line 2.
  `shouldGenerateTheSameRepositoryFromAMyBatisScriptsDirectoryAndASnapshot`
  generates a byte-identical repository from two timestamp-named scripts with
  undo sections and a comment-only `bootstrap.sql` and compiles it.
  `docs/configuration.md` states the rule in one sentence. Existing tests are
  unchanged; `mvn spotless:check` passed and `mvn test` passed, with 101
  runtime and 1935 CLI tests and no failures.
- **M33-W2 — Liquibase changelogs generate one tree — Completed.** Implements
  requirements 1 and 2 by the fixture decisions. The test resources gain the
  formatted SQL directory, the XML changelog, its committed offline output,
  and one query file; a compiler test generates from the formatted SQL
  directory and from the offline output and asserts one byte-identical tree
  that compiles. Boundary: test resources and tests; a production change is
  expected nowhere, and a failure found is reported rather than designed
  around. Acceptance evidence is that test, the provenance comment,
  `mvn spotless:check`, and `mvn test`.
  Decided by the architect on 2026-10-24, rechecked against `04cb65b`: the
  fixture is a book catalogue under `sqlcj-cli/src/test/resources/liquibase/`,
  five changesets written once as five formatted SQL files named
  `001-` to `005-` and once as `changelog.xml`: an enum, two identity-keyed
  tables with a named foreign key and an index, a changeset adding, renaming,
  and dropping columns and adding a unique constraint, and a `$body$`
  plpgsql function in a `splitStatements:false` changeset; the formatted files
  carry `--rollback` lines and a `/* liquibase rollback */` block, several of
  which drop, rename, or re-add what the schema keeps, so applying one would
  change the tree. Probed outside the repository, Liquibase 5.0.4's `update-sql`
  wrote the offline output, which holds a `CREATE TABLE databasechangelog`
  and five `INSERT`s and no lock statement; the CLI built from `04cb65b`
  generated the same four files and manifest from the directory and from that
  output, and they compiled; Liquibase's `update` applied the XML changelog
  and, through `includeAll`, the directory to PostgreSQL 16, psql applied the
  offline output, and the three `pg_dump --schema-only` schemas were equal;
  and `rollback-count` reverted all five formatted changesets, so Liquibase
  reads every rollback form as one. No production change is needed. An XML
  comment cannot contain `--`, so the provenance comment states the command
  through Liquibase's environment variables, which need no `--` option; the
  output's `Ran at` header holds a narrow no-break space, so the file is
  copied, never retyped. The test compares every file of both output
  directories and names the expected file set, which shows the
  `databasechangelog` table generates nothing.
  Review evidence: `sqlcj-cli/src/test/resources/liquibase/` holds the five
  formatted files, `changelog.xml` with its environment-variable provenance
  comment, `offline-update.sql`, and `queries.sql`, each byte-identical to the
  architect's probe files; `offline-update.sql` is 3865 bytes with sha256
  `9009d3a1...7ca623d` and U+202F on line 7.
  `shouldGenerateOneTreeFromALiquibaseFormattedSqlDirectoryAndItsOfflineUpdateSql`
  generates from the directory and from the offline output with one query
  file reading `full_name` and `isbn`, asserts exactly the manifest,
  `AuthorsRow`, `BookFormat`, `BooksRow`, and `UsersRepository`, compares
  every file byte for byte, and compiles the tree. No production, build, or
  documentation file changed, and existing tests are unchanged;
  `mvn spotless:check` passed and `mvn test` passed, with 101 runtime and
  1936 CLI tests and no failures.
- **M33-W3 — MyBatis Migrations' changelog script — Completed.**
  Implements requirement 5 by the changelog script decision, approved by the
  owner on 2026-10-24. The changelog script `migrate init` writes loads
  unchanged and models nothing. Boundary: schema parsing; the splitter, the
  loader, queries, generation, and documentation are unchanged, and W4
  documents the outcome. Acceptance evidence is schema parser tests of the
  script's two statements and of variable statements kept rejected, the W1
  scripts directory gaining the changelog script, `mvn spotless:check`, and
  `mvn test`.
  Rechecked by the architect on 2026-10-24 against `3c75f90`, with the CLI
  built from it outside the repository and MyBatis Migrations 3.6.0, whose
  jar matched Maven Central's SHA-1: `migrate init` writes `bootstrap.sql`,
  `<id>_create_changelog.sql`, and a comment-only `<id>_first_migration.sql`.
  The changelog script holds a commented header, `CREATE TABLE ${changelog}`
  with three `NOT NULL` columns, `ALTER TABLE ${changelog}` with the one
  action `ADD CONSTRAINT PK_${changelog} PRIMARY KEY (id)`, each on several
  lines, and after `-- //@UNDO` a `DROP TABLE ${changelog}`, which W1 already
  leaves unread. MyBatis substitutes a variable only where `${` is written
  together and the name runs to the next `}`. The lexer reads `${changelog}`
  as `$`, `{`, `changelog`, `}` and glues `PK_$`. The parser rejects the
  `CREATE TABLE` at its `{`, but reads the `ALTER TABLE` as an alteration of
  a table named `$`, which fails as `Table not found in schema: $`; so the
  rule is decided from a statement's tokens before it is parsed, which also
  keeps the readable form from reaching the table it does not name. A
  prototype of that rule outside the repository loaded the `migrate init`
  scripts directory and passed the schema parser and compiler tests.
  Review evidence: `DefaultSchemaParser.apply` first returns for a statement
  whose tokens are `CREATE TABLE [IF NOT EXISTS]`, a `$ { ... }` variable
  ended by the first `}`, then `(`, or `ALTER TABLE [IF EXISTS] [ONLY]`, such
  a variable, then top-level actions that each begin with `ADD` and add a
  constraint; every other statement takes the unchanged path, so its position
  and diagnostic are as before. `shouldApplyNothingOfTheMyBatisChangelogScript`
  leaves `parser.parse(BASE_SCHEMA)` unchanged with the script's header,
  statements, and undo section, and
  `shouldRejectEveryOtherStatementThatNamesAVariable` keeps `ADD COLUMN`, a
  mixed action list, `${schema}.audit`, `audit_${suffix}`, and
  `DROP TABLE ${changelog}` rejected, the `ALTER TABLE`s with
  `IllegalArgumentException` and the rest with `SchemaParseException`.
  `shouldGenerateTheSameRepositoryFromAMyBatisScriptsDirectoryAndASnapshot`
  gains `20261024115500_create_changelog.sql` and still generates a
  byte-identical, compiling repository. Existing tests are unchanged;
  `mvn spotless:check` passed and `mvn test` passed, with 101 runtime and
  1942 CLI tests and no failures.
- **M33-W4 — Migration tool documentation — Completed.** Implements
  requirement 4. `docs/configuration.md` and `docs/postgresql.md` give each
  path: Flyway as today; Liquibase formatted SQL as files or a directory, its
  directives read as comments, and statements ended by `;`; Liquibase XML,
  YAML, or JSON through the offline `update-sql` command with a fresh changelog
  CSV; a MyBatis Migrations `scripts` directory with its undo marker,
  timestamp ids, and its changelog script loading unchanged (W3); and
  `pg_dump --schema-only` for every other tool. Acceptance evidence is the
  sections, each command in the form the probes ran, `mvn spotless:check`, and
  `mvn test`.
  Rechecked by the architect on 2026-10-25 against `b2bf4d6`: the two
  documents mention the tools only in the `sql[].schema` directory order and
  W1's undo marker sentence of `docs/configuration.md` and in
  `docs/postgresql.md`'s snapshot sentence that names a Liquibase changelog as
  a `pg_dump` case; W3's rule is documented nowhere, and no M33 test is listed
  under `Verified by`. Probed outside the repository with Liquibase 5.0.4's
  official Docker image: the fixture's provenance command fails in a
  directory the image's user cannot write, and on a read-only mount, because
  the output file and the changelog CSV are written into the mounted
  directory, and the image does not start under `--user`. Run with `--rm`,
  the changelog directory mounted read-only, no output file, and
  `&changeLogFile=/tmp/databasechangelog.csv` added to the URL, it writes the
  SQL alone to standard output, its banner to standard error, the CSV inside
  the discarded container, so every run writes every changeset; for
  `changelog.xml` its statements equal `offline-update.sql`'s apart from the
  `Ran at` and `Against` comments and the deployment id, and it wrote the same
  way for a YAML and a JSON changelog and for a YAML root that includes a
  YAML file and, through `includeAll`, a formatted SQL file.
  Decided by the architect on 2026-10-25: `docs/configuration.md` gains, under
  `sql[].schema`, one table of what to configure for each tool and a link;
  `docs/postgresql.md` gains a `Migration Tool Schemas` section after
  `Schema Snapshot Input` that owns each tool's reading rules and both
  commands, an `Ignored Statements` bullet for W3's two statements, a
  `Verified by` group naming the M33 tests, and a snapshot sentence that no
  longer names Liquibase as a `pg_dump` case. The documented Liquibase
  command is that probed standard-output form, pinned to 5.0.4 with
  `outputLiquibaseSql=all`, because it is the tested mode and keeps the CSV
  fresh by construction; the fixture's provenance comment stays unchanged,
  because it reproduces the committed file's header byte for byte. The
  `pg_dump` command is the reference service test's
  `pg_dump --schema-only -U <user> -d <database>`. Boundary: the two
  documents only; no production, test, or fixture file changes.
  Review evidence: `docs/configuration.md`'s `sql[].schema` gains a five-row
  table of what each tool configures and a link to `docs/postgresql.md`'s
  new `Migration Tool Schemas`, whose Flyway, Liquibase formatted SQL,
  Liquibase XML, YAML, or JSON, MyBatis Migrations, and other-tool sections
  own the reading rules and both commands; the snapshot sentence names
  Java-based migrations, `Ignored Statements` gains W3's two statements, and
  `Verified by` names the M33 tests, each of which exists with the coverage
  stated. A first review found the Liquibase directory order equated with
  `includeAll`'s, which an offline 5.0.4 probe disproved for `V<version>__`
  and `U<version>__` names; both places now state the `sql[].schema` order,
  matching `includeAll` only without such names, as
  `DefaultSourceLoader.migrationFiles` orders. The documented `update-sql`
  command, run offline on a copy of `changelog.xml`, wrote statements equal to
  `offline-update.sql`'s apart from the run comments and deployment ids, and
  left the read-only mount unchanged. Every link resolves. No production,
  test, or fixture file changed; `mvn spotless:check` passed and
  `mvn -Dsqlcj.test.requireDocker=true test` passed, with 101 runtime and
  1942 CLI tests and no failures.

### Required

1. A sqlcj-owned Liquibase formatted SQL changelog loads as a schema source,
   with changesets, rollback comments, and a dollar-quoted function.
2. The SQL Liquibase writes with `update-sql` against an
   `offline:postgresql?outputLiquibaseSql=all` URL for a sqlcj-owned XML
   changelog loads, its `DATABASECHANGELOG` statements included, and generates
   the same repositories as the equivalent formatted SQL. Liquibase never
   becomes a dependency of the runtime or the CLI; the fixture is that output,
   committed as Liquibase wrote it.
3. A line that MyBatis Migrations reads as its undo marker, such as
   `-- //@UNDO`, ends what sqlcj reads of a schema file, so a script's undo
   section is never applied.
4. The configuration and PostgreSQL documentation give each tool's path and the
   `pg_dump --schema-only` fallback.
5. The changelog script MyBatis Migrations' `migrate init` writes loads
   unchanged: a `CREATE TABLE` whose table name is a MyBatis `${...}`
   variable leaves that table invisible, and an `ALTER TABLE` of such a name
   whose every action adds a constraint is ignored. Every other statement that
   names a variable is treated as today.

### Out of scope

- parsing Liquibase XML, YAML, or JSON; Liquibase property substitution,
  contexts, and labels,
- Flyway placeholders and Java migrations,
- running any migration tool,
- a Liquibase `endDelimiter` other than `;`, `--ignoreLines`, `dbms` and
  precondition filtering, and a formatted SQL changelog's `--include` and
  `--includeAll`,
- reading a directory recursively, ordering MyBatis Migrations ids otherwise
  than by file name, and treating `bootstrap.sql` or `onabort.sql` apart from
  other files,
- MyBatis Migrations variable substitution, and every statement naming a
  `${...}` variable other than the two requirement 5 names,
- the reference service written for Liquibase or MyBatis Migrations.

### Acceptance

- Schema-loading tests of each input; the formatted SQL and offline outputs
  generate one byte-identical tree.
- `mvn spotless:check` and `mvn test` pass.

## Milestone 34 — Maven Build

### Goal

Run generation as part of an ordinary Maven build.

### Required

1. Add an `org.sqlcj:sqlcj-maven-plugin` with a `generate` goal bound by
   default to `generate-sources`. It reads the same `sqlcj.yaml` (default
   `${project.basedir}/sqlcj.yaml`, overridable), writes the same output
   through the same compiler, registers `java.out` as a compile source root,
   and fails the build with the same one-line diagnostics. It is released at
   the runtime's version, runs on Java 17, and publishes no public compiler
   library API.
2. The CLI stays supported and documented for other builds.
3. The external sample and quickstart generate through the plugin with no
   manual generation step.

### Out of scope

- a check/verify goal, incremental or watch generation, or IDE integration,
- plugin configuration that duplicates `sqlcj.yaml` settings.

### Acceptance

- `mvn package` on the external sample, from a clean checkout and with no
  prior generation, generates, compiles, and runs it against PostgreSQL.
- A configuration or query error fails the Maven build with the CLI's
  diagnostic and no stack trace.
- Plugin tests, `scripts/verify-release.sh`, `mvn spotless:check`, and
  `mvn test` pass.

## Milestone 35 — Gradle Build

### Goal

Run generation as part of an ordinary Gradle build, with the contract
Milestone 34 settled for Maven.

### Required

1. Add a Gradle plugin, with the id `org.sqlcj`, whose generation task runs
   before Java compilation, reads the same `sqlcj.yaml` (default the project
   directory's, overridable), writes the same output through the same compiler,
   adds `java.out` to the main source set, fails the build with the same
   one-line diagnostics, and is up to date when neither the configuration nor a
   source it names changed.
2. It is released at the runtime's version and runs on Java 17. The supported
   Gradle versions, how the plugin is built, and where it is published, the
   Gradle Plugin Portal or a plugin marker on Maven Central, are decided at
   activation.
3. An external Gradle sample generates, compiles, and runs with no manual
   generation step, and the quickstart shows both builds.

### Out of scope

- a check task, watch generation, IDE integration, and Android,
- plugin configuration that duplicates `sqlcj.yaml` settings.

### Acceptance

- `gradle build` on the external Gradle sample, from a clean checkout and with
  no prior generation, generates, compiles, and runs it against PostgreSQL.
- A configuration or query error fails the Gradle build with the CLI's
  diagnostic and no stack trace.
- Plugin tests, `mvn spotless:check`, and `mvn test` pass.

## Milestone 36 — `0.1.0` Release Candidate

### Goal

Prove the whole workflow on the reference service as a real consumer would
use it, and freeze the public surfaces.

### Required

1. Make the reference service a runnable example project: Flyway migrations,
   generation through the Maven plugin, and application flows in plain Java
   through the transaction helper and in a Spring-managed transaction,
   covering every query of its catalogue, against the oldest and the newest
   PostgreSQL major versions in community support.
2. Fix only release-blocking findings; record the rest for post-release
   selection.
3. Freeze and document the `0.1.0` public surfaces: configuration, query
   annotations and placeholder forms, the generated API, the runtime API
   including the transaction helper, batches, and `QueryExecutor` for
   hand-written SQL, CLI and plugin behavior, artifacts, and Java, Gradle, and
   PostgreSQL requirements.
4. Write the adoption guide: moving query groups from `JdbcTemplate`/
   `JdbcClient`, MyBatis mappers, and JPA native queries; coexisting with JPA in
   one transaction; reading the schema from each migration tool or a `pg_dump`
   snapshot; keeping hand-written SQL beside generated code (D-L); criteria
   queries (D-M); adding logging or metrics by decorating `QueryExecutor`; and
   what sqlcj deliberately does not do.
5. State what `0.1.0` promises: the readiness stages of
   [Who sqlcj Is For](#who-sqlcj-is-for) and how a `0.x` release reports a
   change to a public surface.

### Acceptance

- The reference service builds from a clean checkout with `mvn package` and
  runs every catalogue query against both PostgreSQL versions, in plain Java and
  in a Spring-managed transaction.
- The [release gates](#release-gates) other than publication pass for the
  release-candidate build.

## Milestone 37 — `0.1.0` Publication

### Goal

Publish the first immutable artifacts after the release candidate passed.

### Entry conditions

- Milestone 36 is completed and reviewed.
- The owner has registered `sqlcj.org`, and the `org.sqlcj` namespace is
  verified on Maven Central through its DNS `TXT` record (D-G).

### Required

1. Add the license metadata (D-C), release notes, project/developer/SCM
   metadata, source and Javadoc JARs, signatures, and Central publication
   configuration.
2. Produce the runtime, the dependency-inclusive CLI, the Maven plugin, and the
   Gradle plugin at one version. Publish no compiler library API and no
   redundant distribution artifact.
3. Build and validate a non-snapshot Central bundle without automatically
   publishing it. Keep credentials and signing material outside the
   repository.
4. Run the external Maven and Gradle samples and the reference service from a
   clean temporary project against the staged release artifacts and
   PostgreSQL, then rerun all gates and publish `0.1.0` with its checksums and
   release notes.

Maven Central requires main, source, and Javadoc artifacts, signatures,
checksums, and project metadata. The official publishing plugin creates a bundle
but does not supply every prerequisite. See the
[Central requirements](https://central.sonatype.org/publish/requirements/) and
[Central Maven publishing guide](https://central.sonatype.org/publish/publish-portal-maven/).

`0.1.0` is an initial-development release, not a declaration that every future
change will be compatible. Nevertheless, its public API must be explicit and
useful; major version zero is not a reason to publish a knowingly poor API. See
[Semantic Versioning 2.0.0](https://semver.org/).

### Release gates

#### Product gate

- The reference service loads its Flyway migrations and its `pg_dump`
  snapshot, and every catalogue query compiles and runs without editing
  generated code or the schema.
- Repositories share table row records and enums, and joined table rows are
  embedded row records.
- Generated writes commit and roll back through the transaction helper and
  inside a Spring-managed transaction, and a batch writes all of its rows or
  none.
- Every construct of the unsupported catalogue fails with a located diagnostic
  that says what to write instead.

#### Correctness gate

- Accepted SQL has complete type, cardinality, result-order, logical-parameter,
  and textual-binding evidence.
- No schema input is modeled with missing columns or in the wrong namespace.
- Unsupported nearby SQL fails before output replacement, and no input
  produces an internal error.
- Generated source compiles independently against only the runtime and JDK.
- PostgreSQL integration is required in release CI and cannot silently skip.

#### Distribution gate

- A clean consumer resolves staged release artifacts without a source checkout,
  through Maven and through Gradle, on Java 17 and on Java 21.
- The CLI and both plugins report or use the release version, honor default and
  explicit config paths, and have documented failure behavior.
- Runtime consumers receive no parser, YAML, CLI, plugin, or compiler
  dependency.
- The validated release bundle contains only intended artifacts and no secrets.

#### Verification gate

- `mvn spotless:check` passes.
- `mvn test` passes with the PostgreSQL tests required to run.
- Packaged CLI smoke tests, Maven and Gradle plugin tests, and
  `scripts/verify-release.sh` pass for the exact release version.
- The final documentation and artifact contents are reviewed against the
  release candidate, not against an earlier snapshot.

## After `0.1.0`

Post-release work is selected from observed usage: issues, the reference
service, and queries users report as rejected. Adoption blockers that users
report always come first. When none outranks it, the default order below
applies; the order is not permission to implement every track. Each chosen
item becomes its own narrow, typed, end-to-end milestone and, usually, its own
minor release. "Support PostgreSQL" and "support expressions" are not
acceptable work items.

The first two tracks widen who can adopt sqlcj without changing what it does,
so they are expected for `0.2.0` unless users report something more severe:

1. **One configurable schema.** A query group models one named PostgreSQL
   schema in place of `public`, so a service whose tables all live in a schema
   of its own, and that service's `pg_dump` snapshot, load. Queries across
   several schemas wait until a user asks for them.
2. **Framework verification.** One external example each for Quarkus,
   Micronaut, and Jakarta EE, proving that a repository over the framework's
   managed `DataSource` takes part in its transaction, then documented (D-J).
   No code is added unless a gap is found.

The remaining tracks are chosen by evidence:

3. **Nullness annotations.** JSpecify annotations on generated parameters and
   components, so Kotlin and null checkers see what sqlcj already knows. This
   is additive, but it adds a dependency to generated code, which must be
   decided deliberately.
4. **SQL breadth by evidence.** `MERGE`, data-modifying CTEs, set-returning
   functions as sources (`unnest`, `jsonb_array_elements`, `generate_series`,
   and `LATERAL` calls of them), window and common scalar functions without a
   cast, views as sources (reusing Milestone 24's derived sources), and the
   `SEARCH` and `CYCLE` clauses, in the order users report them rejected.
5. **More types by evidence.** Ranges, composites, multidimensional arrays,
   `hstore`, `TIMETZ`, `MONEY`, `XML`, and `tsvector`, each only with an exact
   Java representation.
6. **Controlled customization.** Type overrides for concrete interoperation
   needs (for example `JSONB` to a JSON library type, or typed ids),
   user-supplied result types, generated parameter objects for calls with many
   optional arguments, and query comments carried into method Javadoc. Prefer a
   small explicit mapping over templates, `emit_*` switches, or a generator
   plugin protocol.
7. **Throughput.** Streaming for large reads, `COPY`, and batches that return
   rows, once a user shows a workload that paging and Milestone 31's batches do
   not serve.
8. **Transaction options.** An isolation level or read-only mode on the
   transaction helper, once a user shows a need the caller-owned connection
   serves poorly. Propagation and savepoints stay with frameworks.
9. **Build checks.** A check goal and task that fail when committed generated
   output is stale (sqlc's `diff`), for teams that commit generated sources.
10. **Framework convenience** only if users ask, such as Spring Boot
    configuration. It must not make plain Java second-class.
11. **Documentation site** on `sqlcj.org`, once there are users to serve beyond
    what the repository's documentation does.

## Long-Term Direction and `1.0`

`1.0` is a promise, not a feature level: it is the release an organization can
standardize on. It means that real services outside this project run sqlcj in
production, their reported blockers are closed, and sqlcj commits to:

- semantic-versioning stability of the configuration format, the named-query
  syntax, the generated API, and the runtime API, with a documented
  deprecation policy and release notes that state every change,
- Maven and Gradle builds, every Java LTS from the baseline up, and every
  PostgreSQL major version in community support, verified in CI,
- generated code that states nullness and supports the type overrides users
  showed they need,
- streaming execution and a documented observability approach,
- documented, verified transaction participation in the frameworks its users
  run,
- a supported release line that receives security fixes.

Three strategic options are evaluated only when evidence demands them:

- **Database-backed analysis.** The static analyzer will never type all of
  PostgreSQL. If cast-required or rejected SQL becomes the dominant complaint,
  evaluate an optional analyzer that asks PostgreSQL to describe each
  statement, as sqlc's database analyzer, sqlx, Manifold SQL, and jOOQ's
  Testcontainers setup do, with its results committed so that ordinary builds
  stay database-free. It must stay optional.
- **PostgreSQL's own parser.** sqlcj reads SQL through JSqlParser, whose gaps it
  has had to work around (D-B, D-F) and report upstream. If parser gaps, rather
  than analysis rules, become the main source of rejected input, evaluate
  PostgreSQL's own grammar, which sqlc uses through `libpg_query`, against the
  cost of a native dependency in every build.
- **Another database or output language.** Each multiplies parsing, type
  mapping, runtime, documentation, and testing contracts. Treat it as a product
  expansion after `1.0`, not a generic-core refactor performed in advance.
  MySQL is the likeliest second database; Kotlin callers are served by Java
  records with nullness annotations rather than by a second generator.

Permanent non-goals: ORM features, derived queries, runtime-built dynamic SQL,
a query DSL, runtime reflection, a mandatory framework, and generated models for
tables or enum types that no query uses.

## Roadmap Update Workflow

- Only one product milestone is Active at a time.
- `Required` defines the milestone; `Out of scope` is an explicit exclusion and
  is not a review finding.
- Activate a milestone only after rechecking its assumptions against the
  implementation, tests, examples, and current user evidence, and record that
  reassessment under the milestone's `Work items`.
- Decompose an active milestone into small work items (`M<milestone>-W<n>`)
  with observable behavior, boundaries, acceptance evidence, and scoped review.
- Use `Planned`, `In progress`, `Completed`, `Deferred`, and `Blocked` honestly.
- Do not activate the next milestone automatically. Reassess and simplify the
  plan after each completed milestone.
- A feature discovered during implementation belongs in a later milestone
  unless it is required to make the active behavior correct.
- Until `0.1.0` is published, no earlier decision, baseline, or generated-API
  shape is binding. When evidence shows a better choice, change it, and record
  the change and its reason under [Plan Changes](#plan-changes); never keep a
  worse design only because it was decided earlier.
- When a milestone is completed, move its section into the
  [Completed Milestone Record](#completed-milestone-record) unchanged.

## Definition of Done for Any Milestone

A milestone is complete only when its required behavior is implemented,
documented where public, covered by focused tests, and reviewed against its
stated boundary. Generated Java must compile when relevant; runtime behavior
must run against PostgreSQL when relevant; `mvn spotless:check` and `mvn test`
must actually pass; and the final diff must contain no machinery justified only
by an out-of-scope feature.

## Plan Changes

- **2026-10-05.** After Milestone 21, a first-contact probe of an original
  task-tracking service found dead ends and silent mis-models (see
  [Where sqlcj Stands](#where-sqlcj-stands)). The previously planned Milestone
  22 (Maven build and framework use) became Milestone 26, with JPA coexistence
  added to its Spring requirement; the previously planned Milestone 23 (release
  candidate and publication) was split into Milestones 27 and 28, with the
  reference service replacing the earlier unspecified dogfood service and its
  JSqlParser recheck moved to Milestone 22. Milestones 22 to 25 are new.
  `pg_dump` snapshots moved from a non-goal into Milestone 22, and the
  enumerated ignored-statement list of Milestone 19's requirement 4 gives way
  to Milestone 22's single rule.
- **2026-10-05.** The owner decided D-A to D-E as recommended. The release
  targets Java 17 instead of Java 21, applied first in Milestone 22; D-B
  supersedes the part of Milestone 19's D2 that forbids splitting schema SQL;
  and the owner set the rule that pre-release decisions are revisable by
  evidence.
- **2026-10-09.** Milestone 22 was activated and decomposed into M22-W1 to
  M22-W9. Its reassessment found that JSqlParser 5.4, still the latest release,
  cannot read the identity form `pg_dump` writes for every identity column, so
  requirement 7 as written made the `pg_dump` acceptance unreachable. D-F,
  decided the same day, ignores an unreadable `ALTER TABLE` whose every action
  only adds a constraint or an identity, and requirement 7 was amended to apply
  it. Requirement 9 now prepares the upstream reports, which the owner files,
  because filing publishes content. The first acceptance item now excludes
  `ALTER COLUMN ... SET DATA TYPE`, which requirements 7 and 9 keep rejected.
  The architect's decisions of the same date, including the implementation
  order, are recorded under Milestone 22's work items.
- **2026-10-09.** At the owner's request, M22-W2 moved ahead of M22-W3. A
  JSqlParser 5.4 probe showed that only a function or procedure body that is
  not dollar-quoted captures later statements in a whole-file parse, so W2
  keeps rejecting such a body until W3 parses each statement alone, and no
  statement is ignored silently in the meantime.
- **2026-10-10.** M22-W3 started. A JSqlParser 5.4 recheck found that a
  tagged dollar-quote delimiter is a token of its own only when whitespace
  separates it: `AS $_$SELECT` and `END$fn$` each lex as one identifier. A
  tagged body is therefore kept whole by finding its closing delimiter in the
  source text and resuming the token manager after it, instead of by pairing
  delimiter tokens. Positioning every statement at its file offset for parsing
  doubled the parse time of a 14,000-line file and grows with the square of
  its length, so only a rejected statement is reparsed that way.
- **2026-10-10.** M22-W4 started. A JSqlParser 5.4 recheck found that every
  action it parses without a form of its own, such as `OWNER TO`, a trigger
  switch, or `VALIDATE CONSTRAINT`, captures the rest of the statement, so
  `OWNER TO app, ADD COLUMN x int` is one action. Ignoring such an action by
  its first words would drop the `ADD COLUMN` silently, so the captured text
  is split into its actions and each one is classified.
- **2026-10-10.** At the owner's request, M22-W5 moved ahead of M22-W7. W7 is
  not implemented: no schema or analyzer path handles a `public` or other
  qualifier, and JSqlParser 5.4 reports an `ALTER TYPE` or `CREATE TYPE` name
  with its qualifier, so `public.mood` and `mood` are distinct names today.
  W5's type lookups therefore keep that literal name, as `ADD VALUE` already
  does, and W7 changes them with every other lookup. The one silent mis-model
  the order could cause, ignoring `ALTER TYPE public.mood RENAME TO ...` or
  `SET SCHEMA` of a modeled `mood` as an unmodeled type, is closed by an
  interim rule: such a qualified, unmodeled name fails as `Type not found`
  until W7 replaces the rule. `SET SCHEMA` of an unmodeled type is ignored,
  like `RENAME TO` of one.
- **2026-10-10.** At the owner's request, M22-W6 moved ahead of M22-W7, giving
  W1 to W6, W7, W9, then W8. Every table lookup still drops the schema
  qualifier, so a `LIKE` or `PARTITION OF` source qualified with another
  schema would silently copy a modeled table of the same unqualified name. An
  interim rule closes that: `public.t` resolves to `t`, and a table name W6
  resolves that is qualified with any other schema fails as `Table not found`
  until W7 replaces the rule. A JSqlParser 5.4 recheck also found that it
  reports a `CREATE TABLE` element list in written order, each `LIKE` with
  its own source, so the 2026-10-09 decision to reject a `LIKE` beside other
  column sources, made for a parser limitation that does not exist, gives way
  to modeling the columns in written order, as PostgreSQL does. Planning W6
  found that a partition copied once would silently miss a column its parent
  gains later, because PostgreSQL applies a partitioned table's column changes
  to its partitions; W6 therefore keeps a partition linked to its parent,
  which revises M22-W4's ignoring of `ATTACH` and `DETACH PARTITION` for a
  modeled partition.
- **2026-10-10.** M22-W7 started. A JSqlParser 5.4 recheck found that it
  reports a qualified type name, in `CREATE` and `ALTER TYPE`, a column type,
  or a cast, as one text with its qualifier and delimiters, and a qualified
  type's array brackets inside that text, so `public.mood[]` reaches the type
  mapping as no array at all; the namespace rule therefore also reads those
  brackets. Planning W7 found that removing a partitioned table moved by
  `SET SCHEMA` would leave its partitions in `public` following a parent sqlcj
  no longer models, so such a move is rejected, as M22-W5 rejects moving a
  modeled enum whose columns keep it; and that an unreadable `DROP TABLE` may
  name a modeled table beside one of another schema, so only an unreadable
  statement naming a single table or type is ignored by its qualifier.
- **2026-10-10.** At the owner's request, M22-W8 moved ahead of M22-W9, giving
  W1 to W7, W8, then W9. W9 was ordered first so that the acceptance would run
  on the parser version the milestone closes with, but a recheck of Maven
  Central the same day found JSqlParser 5.4 still the latest release, so the
  order cannot change W8's parser version. W9 still makes the closing recheck,
  and an upgrade it adopts runs the full suite, W8's reference-service test
  included.
- **2026-10-11.** M22-W9 started. Maven Central still lists JSqlParser 5.4 as
  the latest release, so no upgrade is possible. A 5.4 recheck widened the
  report from the activation's seven `ALTER TABLE` forms to eleven table or
  type forms, adding `DROP TYPE`, `PRIMARY KEY USING INDEX` beside
  `UNIQUE USING INDEX`, `DROP EXPRESSION IF EXISTS`, the `settings` column
  M22-W8 avoided, and the `E'...\'...'` escape string, and found that the
  identity form fails only with its parenthesized sequence options.
- **2026-10-11.** Milestone 22 was completed and its section moved into the
  [Completed Milestone Record](#completed-milestone-record). Milestone 23 was
  activated and decomposed into M23-W1 to M23-W8. Its reassessment found that
  the analyzer treats an array subscript such as `labels[1]` as its whole
  array column, so such a query compiles to a `List` where PostgreSQL reads or
  compares one element and fails only when executed; requirement 7 was added,
  because the milestone's goal and the principle that sqlcj never models
  silently wrong require such a query to need a cast or fail with a
  diagnostic. `UPDATE ... FROM` and `DELETE ... USING`, which an `:exec` write
  carried unanalyzed and the query documentation listed as not analyzed, are
  now rejected on every write until Milestone 24 analyzes them, because an
  analyzed clause that names their sources failed with a misleading
  unknown-qualifier diagnostic. The second acceptance item now requires a
  diagnostic that says what to write only of the probe inputs that stay
  outside `0.1.0`, and one that names the construct of the shapes Milestone 24
  analyzes, whose only fix is that milestone. The architect's decisions of the
  same date, including the implementation order, are recorded under Milestone
  23's work items.
- **2026-10-11.** M23-W1 was completed and M23-W2 started. A JSqlParser 5.4
  recheck found that it reports `ON TRUE` as a boolean literal, a composite or
  filtered condition as one expression, and `JOIN a JOIN b ON x ON y` as a
  first join with no condition and a second with two. The `WHERE` rules
  resolve only a column that binds a placeholder or is null-tested, so they
  would neither catch a reference to a later source nor keep today's check of
  every `ON` column; W2 therefore resolves every column reference of the
  condition outside a nested `SELECT` against the join's scope, while its
  placeholders follow the `WHERE` rules unchanged.
- **2026-10-11.** M23-W2 was completed and M23-W3 started. The conflict-clause
  decision named the scopes but not how `EXCLUDED` and the inserted table
  share the `DO UPDATE ... WHERE` scope, nor the wording of the `DO UPDATE`
  rejection; W3's refinement records both, following PostgreSQL, which makes
  an unqualified column of both sources ambiguous there.
- **2026-10-11.** M23-W3 was completed and M23-W4 started. A JSqlParser 5.4
  recheck found that it reads `RETURNING * AS x` as an aliased wildcard, which
  PostgreSQL rejects and today's blanket alias check happened to reject, so
  lifting that check alone would accept it and return the shared row record;
  W4's refinement keeps it rejected with its own message.
- **2026-10-11.** M23-W4 was completed and M23-W5 started. A JSqlParser 5.4
  recheck found that its parse tree gives no node of its own to an
  `ORDER BY`, `GROUP BY`, `WHERE`, `HAVING`, or `FETCH` item, nor to some
  operators such as a `||` chain in a `LIKE` pattern, so the
  untyped-placeholder decision's smallest enclosing expression is the
  smallest one the parse tree reports, and a placeholder standing alone is
  named by the keyword that opens its clause. W5's refinement records both,
  and that the SQL parser, which alone knows the query as written, reports
  them with each placeholder occurrence.
- **2026-10-11.** M23-W5 was completed and M23-W6 started. A recheck found
  three more inputs that report a raw `ClassCastException`, a `TABLE`
  statement, a doubly parenthesized `SELECT`, and a parenthesized set
  operation; a CTE named like a schema table compiling against the table; and
  multi-table `UPDATE` and `DELETE` forms compiling on `:exec`. W6 closes each
  inside its boundary. The internal-error decision names a query, schema file,
  or query group, but the shared row records and enums belong to no group, so
  their internal-error line names the package; and a diagnostic is recognized
  by its exact exception class and a non-blank message, so that a JDK
  exception of a diagnostic class is never passed through as one.
- **2026-10-11.** M23-W6 was completed and M23-W7 started. A recheck
  confirmed every subscripted form the reassessment recorded and found the
  same whole-array typing in write targets such as `SET labels[1] = $1`,
  which W7 already names. M23-W1's decision that no placeholder accepted
  today changes its name gives way for one shape: an indexed cast placeholder
  beside a subscripted column, as in `labels[1] = $1::text`, was named after
  the array column and is now `param<N>`, because it binds one element, not
  the column's value. W7's refinement also adds the array section of the
  PostgreSQL support documentation to its boundary.
- **2026-10-11.** M23-W7 was completed and M23-W8 started. A recheck of every
  shape W8 names, through the M23-W7 analyzer and PostgreSQL 16, found no gap
  in W1 to W7. W8's query list omitted the probe's placeholder inside a scalar
  subquery, which requirement 1 makes compile and the first acceptance item
  therefore covers, so W8 adds it; and W8's boundary adds the reference
  service's `Verified by` documentation lines, because the PostgreSQL
  documentation counts its migrations.
- **2026-10-12.** M23-W8 and Milestone 23 were completed, and its section moved
  into the [Completed Milestone Record](#completed-milestone-record).
  Milestone 24 was activated and decomposed into M24-W1 to M24-W5, derived
  sources first. Its reassessment found that PostgreSQL's bare `RETURNING *` of
  `UPDATE ... FROM` and `DELETE ... USING` returns every source's columns, so
  requirement 4's wording, that `RETURNING` still returns target-table columns
  or the target's full row, either forbade returning an added source's column,
  which its own scope sentence allows and PostgreSQL supports, or let a bare
  `*` return the target's row record over a wider result; requirement 4 now
  types a returned column of any source in scope and returns the target's full
  row only as `RETURNING <target>.*`. Analyzing nested queries renames one
  accepted shape: an indexed cast placeholder in a `WITH` body or subquery is
  named after its compared column, as at top level, instead of `param<N>`,
  superseding M23-W1's decision that no accepted placeholder changes its name
  for that shape. Every CTE body is now analyzed, so a recursive or
  data-modifying CTE, and a CTE body outside the supported subset, which
  compiled while no source read it, is rejected; the query documentation never
  made those shapes contract. The architect's decisions of the same date,
  including the implementation order, are recorded under Milestone 24's work
  items.
- **2026-10-12.** M24-W1 was completed and M24-W2 started. A JSqlParser 5.4
  recheck at `1a2c271` confirmed the predicate shapes the reassessment
  recorded, that the join-condition column walk does not enter a nested
  `SELECT`, and that 5.4 reports `IN ((SELECT ...))` as a list holding a
  subquery and `= ANY (SELECT ...)` as a comparison of its own; requirement 1
  names neither, so both keep their current handling. W1 analyzes a write's
  `WITH` bodies without putting them in scope, because no write clause read a
  source beside its target; a predicate subquery of a write's `WHERE` or
  conflict predicate reads them, so W2 puts them in scope there, as the CTE
  decision requires. Two consequences follow from decisions already made and
  are recorded so they are not mistaken for regressions. Descending into `NOT`
  renames one more accepted shape: an indexed cast placeholder under `NOT`, as
  in `NOT (id = $1::bigint)`, is named after its compared column instead of
  `param<N>`. A predicate subquery outside the supported subset, which compiled
  while it was not analyzed, is now rejected as a top-level `SELECT` is: a set
  operation or `VALUES` body, a view, a table of another schema, and a function
  or `FROM`-less source. The query documentation listed predicate subqueries as
  not analyzed and never made those shapes contract.
- **2026-10-14.** M24-W2 was completed and M24-W3 started. A JSqlParser 5.4
  recheck at `aac4232`, with PostgreSQL 16 run outside the repository,
  confirmed the insert shapes the reassessment recorded: one `VALUES` row is
  one parenthesized list, several rows are a list of parenthesized rows even
  for one column, a set operation source is a set operation, and a `WITH`
  after the column list belongs to the row source's `SELECT`, `VALUES`
  included. Four decisions refine the insert decision, each because the
  evidence leaves no other correct reading. The row source is classified
  through every parenthesis level, as the nested-query decision classifies a
  body, so `((VALUES ($1)))`, which PostgreSQL accepts and sqlcj rejected as
  `INSERT ... SELECT is not supported.`, is accepted, and `(VALUES ...)` with
  `RETURNING` is no longer rejected. The insert's `SELECT` may omit `FROM`,
  because requirement 3's own example, `SELECT :projectId,
  unnest(:userIds::bigint[])`, has none; no other `SELECT` gains that form.
  A `WITH` list after the column list of a `VALUES` insert was carried
  unanalyzed, so its placeholders failed as untyped and a recursive or
  data-modifying body there compiled; it is now analyzed as every other `WITH`
  list is, which the CTE decision already requires. A `VALUES` element that is
  not a parenthesized row and a column list with no row source are input
  PostgreSQL cannot parse, and the second raised an internal
  `NullPointerException`; both now fail as `INSERT requires parenthesized
  VALUES rows or a SELECT.`, replacing the two single-row messages that no
  longer describe the subset.
- **2026-10-14.** M24-W3 was completed and M24-W4 started. A JSqlParser 5.4
  recheck at `1f0210b` confirmed the joined-write shapes the reassessment
  recorded. Its table-typed accessor of a `USING` list casts every element to
  a table, so `DELETE ... USING (SELECT ...) AS s` raised an internal
  `ClassCastException`; W4 reads the list as sources. PostgreSQL binds `JOIN`
  more tightly than a comma, so in `FROM a, b JOIN c ON ...` the condition
  cannot read `a`, and the joined-write decision's join-condition scope starts
  at the comma-separated item a join attaches to. 5.4 reports `RETURNING
  old.*` without a table, so a qualified wildcard resolves by its written
  qualifier. The two remaining multi-table rejections are reworded, because
  their messages named the forms W4 accepts.
- **2026-10-14.** M24-W4 was completed and M24-W5 started. A recheck of every
  shape W5 names, through the analyzer at `dbcb95b` and PostgreSQL 16, found
  no gap in W1 to W4. The catalogue decision's moved shapes are adapted to
  the reference tests' shared database: an `INSERT ... SELECT` that archived
  the whole audit log would break another test's assertion that the archive
  is empty, so the reference service copies a project's open tasks instead,
  which is the same `INSERT ... SELECT` from a table.
- **2026-10-14.** M24-W5 and Milestone 24 were completed, and its section
  moved into the [Completed Milestone Record](#completed-milestone-record).
  The reference service runs its nested queries and multi-row writes against
  PostgreSQL 16 without the subquery casts, and its catalogue rejects a
  recursive and a data-modifying CTE; no production change was needed.
- **2026-10-16.** Milestone 25 was activated and decomposed into M25-W1 to
  M25-W4. Its reassessment found that `SELECT <source>.*` over joined sources,
  which reads exactly one table's row, returns a query-specific record, because
  M16-W2 recognized a full row only over a single source, while M24-W4 returns
  the target's shared row for `RETURNING <target>.*` on a joined write. Such a
  projection now returns the table's shared row record, superseding M16-W2's
  rule that a joined wildcard keeps a query-specific record, so requirement 1's
  embedding applies to every other result. Requirement 1 also covers
  `RETURNING`, whose `<target>.*`, or `*` on a single-table write, beside other
  items embeds the target's row, because one result contract should not
  change with the clause; a derived source's wildcard stays flat columns,
  because a derived source has no row record. Requirement 3 types `MIN` and
  `MAX`, and `SUM` and `AVG`, only for the column types PostgreSQL 16 defines
  them for, so that no generated method always fails, and it types
  `COUNT(t.*)`, which the query documentation listed as rejected, as a
  `COUNT(expr)`. The architect's decisions of the same date, including the
  implementation order, are recorded under Milestone 25's work items.
- **2026-10-17.** M25-W3 started. The aggregate decision typed `COUNT` of any
  argument with or without `DISTINCT` or `ALL`, which would have accepted
  `COUNT(DISTINCT *)` and `COUNT(ALL *)`: JSqlParser 5.4 parses both, but
  PostgreSQL's grammar admits `*` only alone, so each generated method would
  always fail. Both keep today's computed-projection diagnostic. W3 also
  revises the quickstart's invalid-query example, which names a count of a
  column as invalid.
- **2026-10-17.** M25-W3 was completed and M25-W4 started. A recheck of every
  shape W4 names, through the CLI at `4159651`, found no gap in W1 to W3. W4's
  left-joined `SELECT t.*, p.*` read joins tasks onto projects, because its
  acceptance needs a project without tasks to read a `null` row, which no
  task's project can produce.
- **2026-10-17.** M25-W4 and Milestone 25 were completed. The reference
  service runs its list-with-count, latest-activity, and inner- and left-joined
  `SELECT t.*, p.*` reads against PostgreSQL 16 without casts, a project
  without tasks reading a `null` task row; no production change was needed.
  The milestone's section stays in place until the architect moves it into the
  [Completed Milestone Record](#completed-milestone-record); no next milestone
  was activated.
- **2026-10-17.** At the owner's request, the plan after Milestone 25 was
  reassessed against the implementation at `0828606`, sqlc 1.31.1, and the
  transaction models of plain JDBC, Jdbi, jOOQ, Spring, Quarkus, Micronaut, and
  Jakarta EE. The owner judged that sqlcj is not ready for `0.1.0`; the
  reassessment agrees, for the four reasons
  [Where sqlcj Stands](#where-sqlcj-stands) gives, and rejects adding SQL
  breadth or other additive features before publication (D-K). The owner
  delegated the choices, and D-G to D-K were decided as recommended. D-G
  supersedes D-D, so the namespace becomes `org.sqlcj`. The planned Milestones
  26 to 28 became Milestones 28 to 30, and Milestones 26 (project identity) and
  27 (transactions in plain Java and Spring) are new: the Spring requirement
  moved from the Maven milestone into 27, and the `LICENSE` file moved from
  publication into 26. The runtime gains one transaction helper (D-H), which
  the `0.1.0` non-goals excluded as transaction orchestration; it stays the
  only transaction API. [After `0.1.0`](#after-010) now names one configurable
  schema and framework verification beside Gradle as the tracks expected for
  `0.2.0`, and adds transaction options and a documentation site. `Who 0.1.0
  Is For` became [Who sqlcj Is For](#who-sqlcj-is-for), with readiness by
  stage and the paths for moving existing data access, and
  [Where sqlcj Stands](#where-sqlcj-stands) now lists what is and is not
  supported today. Milestone 25's section moved into the
  [Completed Milestone Record](#completed-milestone-record).
- **2026-10-17.** The owner reviewed the plan above, accepted the project
  identity, license, Maven, Spring, and publication milestones and the
  post-release frameworks and schemas, and asked for a Gradle plugin, more SQL,
  more types, and batches before `0.1.0`, for a way to keep hand-written queries
  beside generated code, for criteria queries, and for migration tools other
  than Flyway. D-K was revised and D-L to D-N were added as recommended: Gradle,
  set operations and recursive CTEs, every join kind, `INTERVAL`, `citext`,
  network types, domains, `FLOAT(p)`, batch writes, the criteria idiom, and
  Liquibase and MyBatis Migrations inputs now land before `0.1.0`, because the
  first-contact probe recorded most of them in an ordinary service and several
  have no way through; `MERGE` does not, for the reasons D-K gives. Hand-written
  SQL beside generated code needs no new layer (D-L), but the transaction
  helper now also hands its callback the connection. Criteria stay one static
  SQL text (D-M), and a probe at `0828606` found that a parameter repeated in a
  `LIKE` pattern needs its cast again, which Milestone 32 removes. Milestones 26
  and 27 keep their numbers, the SQL, type, batch, criteria, and migration-tool
  milestones are new as Milestones 28 to 33, Maven became Milestone 34, the
  Gradle milestone is new as 35, and the release candidate and publication
  became Milestones 36 and 37. `After 0.1.0` loses Gradle and batches, and
  gains more types and parameter objects as evidence-driven tracks. A
  JSqlParser 5.4 probe the same day parsed every construct the new milestones
  analyze, `CREATE DOMAIN` and `ALTER DOMAIN` included, and `MERGE` as well, so
  parser support is not what keeps `MERGE` out.
- **2026-10-17.** Milestone 26 was activated and decomposed into M26-W1. The
  owner waived its entry condition, the registration of `sqlcj.org`, and
  instructed the milestone to proceed as if the domain were registered; that
  day the Public Interest Registry's RDAP service returned no registration for
  it, and it had no DNS. Registration stays a hard prerequisite of Milestone
  37, whose entry conditions now name it. The owner also changed D-G: the
  GitHub organization `sqlcj-dev` becomes `sqlcj`, a name GitHub reported free
  the same day, so the organization, the domain, and the namespace share one
  name. Before publication the rename changes only references inside the
  repository; once Milestone 37 writes SCM and project URLs into the published
  POMs, it would leave the old name in every published release. Renaming the
  organization moved out of Milestone 26's exclusions into its new requirement
  3, which the owner carries out on GitHub; a website on `sqlcj.org` stays
  excluded.
- **2026-10-17.** Milestone 26 was completed: the change merged at `a105ad6`
  passed its acceptance, rechecked by the architect against the tracked files
  and the pull request's checks, and its section moved into the
  [Completed Milestone Record](#completed-milestone-record). At the owner's
  request, Milestone 27 was activated and decomposed into M27-W1 to M27-W3.
  Its reassessment revised D-H twice: the helper's callback receives only the
  connection, because a `QueryExecutor` the helper built would bypass a
  decorated executor, and auto-commit is restored only after a successful
  commit or rollback, because Java's `Connection.setAutoCommit` commits an
  open transaction, which after a failed rollback would keep the work the
  helper failed to discard. Requirement 1 and the first acceptance item were
  amended to match. The helper is
  named `JdbcTransactions`. [Where sqlcj Stands](#where-sqlcj-stands) now
  describes `a105ad6` and no longer lists the license or the namespace among
  the reasons not to publish.
- **2026-10-17.** M27-W3 and Milestone 27 were completed. Generated
  repositories over a `TransactionAwareDataSourceProxy` commit and roll back
  with `DataSourceTransactionManager` and with `JpaTransactionManager` beside
  a JPA write against PostgreSQL 16, a plain-`DataSource` repository stays
  outside, and a pending JPA change is visible only after a flush; Spring and
  Hibernate are test-scoped only. Every requirement and acceptance item is met
  by M27-W1 to M27-W3. The milestone's section stays in place until the
  architect moves it into the
  [Completed Milestone Record](#completed-milestone-record); no next milestone
  was activated.
- **2026-10-17.** Milestone 27's section moved unchanged into the
  [Completed Milestone Record](#completed-milestone-record), and
  [Where sqlcj Stands](#where-sqlcj-stands) now describes `04c98f7`, with the
  transaction helper and Spring participation supported and no longer among
  the reasons not to publish. At the owner's request, Milestone 28 was
  activated and decomposed into M28-W1 to M28-W4. Its reassessment against
  JSqlParser 5.4 and the PostgreSQL 16 documentation amended its requirements:
  requirement 1 now states that `INTERSECT` binds first, that an `ORDER BY`
  column must name a result column, because PostgreSQL allows nothing else
  there, and that a set operation not returning one table's whole row returns
  columns of its own, not embedded rows; requirement 2 now states PostgreSQL's
  recursive form, in which only the branch after the last `UNION` reads the
  CTE, where it said that every later branch may, and that the last branch's
  types must resolve to the others'. The exclusions gain a set operation as an
  `INSERT` row source, which requirement 1 never named, embedded rows in a set
  operation's result, and a `WITH` item reading a later one, which
  `RECURSIVE` permits; each is rejected rather than mis-modeled.
- **2026-10-18.** M28-W2 started. Its recheck against the working tree at
  `ce84ef0` found that a predicate subquery's `SELECT` list states no result
  column today: its items reach PostgreSQL as written, so `SELECT 1` is
  accepted there. A set operation in a predicate therefore has no resolved
  columns for requirement 1's type, column-count, and `ORDER BY` checks to
  read, and no generated type reads its result, so its branches follow the
  predicate-subquery rules and PostgreSQL checks the rest, rather than
  rejecting `SELECT 1` branches that requirement 1's projection rules would
  reject. The work item's boundary now states this, that a write's nested
  locations share the analysis, and that the `INSERT` diagnostic's rewrite is
  analyzed.
- **2026-10-18.** M28-W4 started. Its recheck against the working tree at
  `3eaf01f` found that a read `SELECT` without `FROM` is rejected, so the
  2026-10-17 finding that the reference service's recursive entry needs only
  casts was incomplete: its first term reads no table. Because that seed-row
  form is how a recursive series is written, and requirement 3 runs the entry
  as written apart from casts, a plain `SELECT` branch of a recursive CTE's
  first term may omit `FROM`; a `SELECT` without `FROM` elsewhere stays
  rejected. The boundary also decides that an item of a recursive list is
  recursive only when its last branch reads it as a source, so an item that
  does not read itself keeps W1's type and nullability rules, as PostgreSQL
  treats it.
- **2026-10-18.** M28-W4 and Milestone 28 were completed. `WITH RECURSIVE` is
  analyzed for reads and writes, a hierarchy walk and a `FROM`-less seed
  series run against PostgreSQL 16, and the reference service's recursive
  entry runs from its query catalogue beside the `UNION` and `UNION ALL`
  entries W1 moved. Every requirement and acceptance item is met by M28-W1 to
  M28-W4. The milestone's section stays in place until the architect moves it
  into the [Completed Milestone Record](#completed-milestone-record); no next
  milestone was activated.
- **2026-10-18.** Milestone 28's section moved unchanged into the
  [Completed Milestone Record](#completed-milestone-record), and
  [Where sqlcj Stands](#where-sqlcj-stands) now describes `1d15c73`, with set
  operations and recursive CTEs supported and no longer among the reasons not
  to publish. At the owner's request, Milestone 29 was activated, decomposed
  into M29-W1 to M29-W3, and M29-W1 started. Its reassessment against
  JSqlParser 5.4 and the PostgreSQL 16 documentation amended its requirements:
  requirement 1 now states that a comma binds more loosely than `JOIN`, that a
  `RIGHT JOIN` null-extends only the sources of its own `FROM` item, and that
  the join kinds apply in `UPDATE ... FROM`, which already shares the join
  rules; requirement 2 now states that a merged column takes the pair's common
  type, as PostgreSQL resolves it, and the nullability of each join kind,
  where it named only `FULL`, and that a bare `*` is flat per `FROM` item that
  merges columns; requirement 3 now excludes the left side of a `RIGHT` or
  `FULL` join and a write's target, which PostgreSQL does not let a `LATERAL`
  item read; requirement 4 rewrites the `USING` and `NATURAL` catalogue
  entries, whose joins match unrelated rows. The exclusions gain a
  parenthesized join tree, an alias on a join, and the `USING` join alias,
  which JSqlParser 5.4 cannot read or no join kind needs, and a `WITH` list
  inside a `LATERAL` subquery seeing the sources before it.
- **2026-10-18.** M29-W1 was completed and M29-W2 started. Its recheck
  against the working tree at `fecfe98` and JSqlParser 5.4, probed outside the
  repository, found that the parser reports a bare `NATURAL JOIN` as no inner
  join at all, so it is recognized explicitly; that it also reads
  `NATURAL CROSS JOIN`, `CROSS JOIN ... USING`, and a `NATURAL` join with `ON`
  or `USING`, none of which PostgreSQL has, so each is rejected rather than
  read as a join kind; and that `INSERT ... SELECT *` counts a bare `*`'s
  columns per source, so it shares the merged expansion. The work item's
  boundary now states these, PostgreSQL's order of a merging group's columns,
  and the two reference service rewrites: `USING` on two tables whose shared
  name means one thing, and `NATURAL` against a subquery source, because no
  two reference service tables share only such names.
- **2026-10-18.** M29-W2 was completed and M29-W3 started. Its recheck
  against the working tree at `2019d7a` and JSqlParser 5.4, probed outside the
  repository, confirmed that the parser reads `LATERAL (<select>) <alias>` as
  one `LateralSubSelect` whose body is the enclosed query — a plain
  `SELECT`, a set operation, a `WITH` query, or a further parenthesized one —
  as a read's first item, after a comma, after every join keyword, and in
  `UPDATE ... FROM` and `DELETE ... USING`, and that a derived source's body
  is analyzed today with no enclosing level, so the enclosing scope is passed
  into it. The work item's boundary now states that `LATERAL is not
  supported.` is retired in favor of the join-kind and subquery-source
  diagnostics, that a reference the body cannot see is rejected as an unknown
  qualifier or missing column, that the body's wildcards expand its own
  sources only, and that `lateral_join.sql` moves verbatim.
- **2026-10-18.** M29-W3 and Milestone 29 were completed. `LATERAL`
  subqueries are analyzed in every `SELECT`, `UPDATE ... FROM`, and
  `DELETE ... USING`, and an inner and a left `LATERAL` join run against
  PostgreSQL 16, beside the outer, cross, comma, `USING`, and `NATURAL` joins
  of W1 and W2; every reference service join entry runs from its query
  catalogue. Every requirement and acceptance item is met by M29-W1 to
  M29-W3. The milestone's section stays in place until the architect moves it
  into the [Completed Milestone Record](#completed-milestone-record); no next
  milestone was activated.
- **2026-10-18.** Milestone 29's section moved unchanged into the
  [Completed Milestone Record](#completed-milestone-record), and
  [Where sqlcj Stands](#where-sqlcj-stands) now describes `b6fa9bf`, with every
  join kind supported and no longer among the reasons not to publish. At the
  owner's request, Milestone 30 was activated, decomposed into M30-W1 to
  M30-W5, and M30-W1 started. Its reassessment against JSqlParser 5.4,
  pgjdbc 42.7.13, and PostgreSQL 16.15, probed outside the repository,
  amended its requirements: requirement 1 now lets a `LIKE` pattern
  placeholder take a `citext` column; requirement 3 now states the interval
  text sqlcj writes and reads, because the driver reads an interval only as
  text in the session's `IntervalStyle`, and that a `Duration` takes a day as
  24 hours, because an interval between two timestamps carries whole days;
  requirement 4 now names `pg_dump`'s `CREATE DOMAIN`, which the parser cannot
  read past a `citext` operator check, and `ALTER TYPE ... RENAME TO`, which
  renames a domain too; requirement 6 now states that an array of a domain
  stays unmapped, because the driver cannot read its elements as their base
  type. Requirement 7 is new: the reference service drops the casts its
  `citext` and domain columns needed and runs queries over those, `inet`, and
  `interval` columns, which the first-contact probe recorded as unmapped. The
  exclusions gain other interval styles, the columns `DROP DOMAIN ... CASCADE`
  drops, extension types outside `public`, and resolving two different new
  types in a set operation.
- **2026-10-20.** M30-W2 started. Its recheck against the working tree at
  `60021ef`, JSqlParser 5.4, and pgjdbc 42.7.13 with PostgreSQL 16.15, probed
  outside the repository, confirmed that every interval spelling, fields and
  precision included, reads as `interval` in a column and a cast, and that a
  cast placeholder inside `now() - :age::interval` is a cast operand. Text
  with an explicit sign on months, days, and microseconds reads alike under
  all four `IntervalStyle` settings over the whole range, while the
  `postgres` style's own `-2562047788:00:54.775808` is rejected on input; the
  driver keeps returning the server's text after a statement is
  server-prepared, an `interval` array binds from such text and reads its
  elements as `postgres`-style text, and `SUM`, `AVG`, and `MIN` of an
  interval are an interval. The work item's boundary now states the text
  written, the `Duration` and `Period` shapes, and the new unmapped-type
  example.
- **2026-10-20.** M30-W3 started, after M30-W2 was completed. Its recheck
  against the working tree at `d09aaf1` and JSqlParser 5.4, probed outside
  the repository, confirmed that `FLOAT` reads as its bare name and
  `FLOAT(p)`, in a column, a `CAST`, and a `::` cast, any case and spacing
  included, reads as the type text `float (p)` with no separate argument
  list, its array dimensions reported apart as for every type; that
  `float(0)` and `float(54)` parse although PostgreSQL rejects them; and that
  today the sqlcj type spelling drops the precision, so every `FLOAT(p)` is
  recorded as `FLOAT`. No generated binding or reading changes, because the
  spellings map to the existing `REAL` and `DOUBLE PRECISION`. The work
  item's boundary now states the `ALTER TABLE` column, the cast placeholder,
  and the array, and that an unmapped precision is recorded with it, so that
  a diagnostic never names the mapped `FLOAT`.
- **2026-10-20.** M30-W4 started, after M30-W3 was completed. Its recheck
  against the working tree at `a3421ad`, JSqlParser 5.4, and pgjdbc 42.7.13
  with PostgreSQL 16 in `postgres:16-alpine`, probed outside the repository,
  confirmed that every `CREATE`, `ALTER`, and `DROP DOMAIN` is still ignored
  and a cast to a domain rejected; that the parser reads `CREATE DOMAIN` with
  its name and qualifier as one text and its base as a `ColDataType`, an
  array, a precision, a `public`-qualified base, and a domain base included,
  reads every probed `ALTER DOMAIN ... RENAME TO` as `AlterDomain`, and reads
  no `DROP DOMAIN` or `DROP TYPE`; and that the tokens of an unreadable
  `CREATE DOMAIN` before its first constraint word, rejoined with spaces,
  read back as the same name and base. It corrected two facts: `pg_dump` also
  writes `NOT NULL DEFAULT`, which the parser cannot read either, and
  PostgreSQL 16 defines no `=`, `= ANY`, `MIN`, or `MAX` for a domain over an
  enum, though it writes, reads, and orders one. A domain over `citext`, a
  chain included, compares case-insensitively through untyped text and a
  `citext` array; a domain over `text[]` reads its elements as text; and an
  array of a domain still reads no element as text. The work item's boundary
  now states that `CREATE DOMAIN` and a rename onto a modeled domain's name
  replace that domain, that an unreadable `ALTER DOMAIN` stays ignored because
  every rename form reads, that a `DROP DOMAIN` its words cannot classify
  fails, and that a domain over an enum is only written and read.
- **2026-10-22.** M30-W5 started, after M30-W4 was completed. Its recheck
  against the working tree at `762502d`, with the reference service's query
  catalogue changed as planned, generated outside the repository, and run
  against PostgreSQL 16 in `postgres:16-alpine`, confirmed that the `::text`
  casts of `email` and `handle` in `CreateUser` and `ClaimHandle` remain only
  from when those columns were unmapped, and that without them both bind
  through untyped text with unchanged method signatures; that `email = :email`
  over the domain and `handle = :handle` over `citext` match in any case,
  while the stored spelling reads back unchanged; that `last_login_ip` and
  `retention_period` bind and read back exactly, a cleared interval as
  `null`; and that the migration history, the hand-written snapshot, and the
  server's `pg_dump` still generate one byte-identical tree of the same
  files. No production code changes, and the `pg_dump` input and generated
  trees are produced inside the test, so no checked-in artifact other than
  the query catalogue, its test, and the documentation changes. The work
  item's boundary now names the queries, which list their columns so that no
  shared row record is added, and keeps the unsupported `lower(:email)`
  entry, which still states no type for its placeholder.
- **2026-10-22.** M30-W5 and Milestone 30 were completed. The reference
  service writes its domain and `citext` columns without a cast and runs
  queries over its domain, `citext`, `inet`, and `interval` columns against
  PostgreSQL 16 from its query catalogue, beside the `citext` and network
  types of W1, the interval of W2, `FLOAT(p)` of W3, and the domains of W4.
  Every requirement and acceptance item is met by M30-W1 to M30-W5, and
  `mvn spotless:check` and `mvn -Dsqlcj.test.requireDocker=true test` pass.
  The milestone's section stays in place until the architect moves it into
  the [Completed Milestone Record](#completed-milestone-record); no next
  milestone was activated.
- **2026-10-22.** Milestone 30's section moved unchanged into the
  [Completed Milestone Record](#completed-milestone-record), and
  [Where sqlcj Stands](#where-sqlcj-stands) now describes `7b78b07`, with the
  everyday PostgreSQL types supported and no longer among the reasons not to
  publish. At the owner's request, Milestone 31 was activated, decomposed into
  M31-W1 to M31-W3, and M31-W1 started. Its reassessment against pgjdbc
  42.7.13 and PostgreSQL 16.15, with Spring Framework 7.0.9, probed outside
  the repository, amended its requirements: requirement 1 now names the
  annotation `:batchexec`, accepts every write `:exec` accepts, and states the
  `RETURNING` diagnostic, because the driver reports `0` for every row of a
  returning batch; requirement 2 now runs a `DataSource` batch as its own
  transaction only on an auto-commit connection, because `commit()` on the
  connection a `TransactionAwareDataSourceProxy` hands out inside a Spring
  transaction commits Spring's transaction, while a batch in auto-commit mode
  is not all or nothing in the driver; requirement 3 now also documents a
  failed batch on a caller-owned auto-commit connection, which left 1,785 of
  1,999 earlier rows committed. Requirement 4 is new: the reference service
  gains batch writes, as Milestones 27 to 33 each add their part to it. The
  exclusions gain `:batchone`, `:batchmany`, `:copyfrom`, generated keys,
  continuing past a failing row, `long` counts, parameter records for other
  annotations, and an atomic batch on a caller-owned auto-commit connection;
  the acceptance gains the Spring transaction and the reference service.
- **2026-10-24.** M31-W1 is completed at `4455624`, and M31-W2 started.
  Rechecked against `QueryType`, `QueryAnalyzer`'s write-annotation checks,
  `JavaNames`, `JavaCodeGenerator`, `QueryExecutor`, `PostgresIntegrationTest`,
  `SpringTransactionIntegrationTest`, `docs/queries.md`, and
  `docs/postgresql.md`, the milestone's annotation, generated-method, runtime,
  transaction, and counts decisions stand unchanged. The work item's boundary
  is refined on four points. `docs/postgresql.md` already states the counts,
  the rewrite pattern, and the caller-owned auto-commit caveat of requirement
  3, written by W1 beside `executeBatch`, so W2 links `:batchexec` to that
  section rather than writing it again. The rewritten-insert evidence is the
  probed three-row batch reporting `-2, -2, 1`, which the documentation
  states, rather than an unprobed size. A `Params` component avoids `Object`'s
  member names, which a record cannot declare and a method parameter may use,
  beside the names a method parameter already avoids, so a `hash_code`
  parameter is the component `hashCode1`. Only enums need collision tracking
  against the record's name: `<QueryName>Params` cannot equal a `Result`,
  `Row`, or `Repository` type of the package, and two of one group that differ
  only by case are already rejected, because their result types differ the
  same way. The diagnostic of a write without `RETURNING` changes to name
  `:batchexec` beside `:exec`; the runtime tests of W1 run on H2, so the
  PostgreSQL tests are the first evidence of the batch against the driver.
- **2026-10-24.** M31-W2 is completed at `33a5d0e`, and M31-W3 started.
  Rechecked against the reference service's query catalogue, snapshot, and
  migrations, `ReferenceServiceIntegrationTest`, the batch method W2 generates,
  and `docs/queries.md`, the milestone's decisions and W3's two queries stand
  unchanged: `RecordAudit` and `MoveTask` already run as `:exec` queries, a
  `:batchexec` write analyzes to the `:exec` model and binds its arguments as a
  single execution does, so no production code changes, and each `Params`
  record is nested in `TrackerRepository.java`, so the file set the three
  schema inputs must generate identically is unchanged. The work item's
  boundary is refined on three points. The tests of
  `ReferenceServiceIntegrationTest` share one database in no fixed order, and
  `shouldExecuteTheGeneratedReferenceServiceQueries` asserts that
  `audit_log_2026` holds exactly its one entry, so the batch test reads back
  only the entries carrying its own request ids, and that assertion is
  narrowed to the entry carrying its test's request id, rather than relying on
  test order or writing audit entries no generated query reads. The stale row
  repeats the version the batch's own first row already advanced, so the
  counts `1, 0, 1` also prove that the rows execute in batch order. The
  documentation is the reference service entry of `docs/queries.md`, where
  each earlier reference service test is listed.
- **2026-10-24.** M31-W3 and Milestone 31 were completed. The reference
  service's `RecordAuditEntries` and `MoveTasks` batch writes run against
  PostgreSQL 16 from its query catalogue, beside the runtime batch execution
  of W1 and the `:batchexec` analysis, generation, and PostgreSQL batch,
  failure, rewrite, and Spring tests of W2. Every requirement and acceptance
  item is met by M31-W1 to M31-W3, and `mvn spotless:check` and
  `mvn -o -Dsqlcj.test.requireDocker=true test` pass. The milestone's section
  stays in place until the architect moves it into the
  [Completed Milestone Record](#completed-milestone-record); no next
  milestone was activated.
- **2026-10-24.** Milestone 31's section moved unchanged into the
  [Completed Milestone Record](#completed-milestone-record), and
  [Where sqlcj Stands](#where-sqlcj-stands) now describes `3daafd7`, with
  batch writes supported and no longer among the reasons not to publish. At
  the owner's request, Milestone 32 was activated, decomposed into M32-W1 to
  M32-W3, and M32-W1 started. Its reassessment against pgjdbc 42.7.13 and
  PostgreSQL 16.15, probed outside the repository, amended requirement 1: an
  occurrence that neither a clause nor a cast types takes the type another
  occurrence of its parameter states, sqlcj writes that type at its
  placeholder in the executable SQL, and the diagnostic for an occurrence at
  which PostgreSQL could not type the bound value is dropped. The driver sends
  a `null` argument without a type, which PostgreSQL rejects as the operand of
  `IS NULL` and as the argument of an overloaded function such as
  `date_trunc` while it accepts the same occurrence for a non-null value, so
  an occurrence left as a bare `?` would fail only for the `null` argument a
  criteria query exists for, in contexts sqlcj cannot recognize; with its
  type written, every probed occurrence ran. The requirement now also names
  `(:email IS NULL OR email = :email)`, and the goal says at most one cast
  per parameter. The probe confirmed requirement 2's planning facts:
  pgjdbc's server-side prepare at the fifth execution, PostgreSQL's switch to
  a generic plan that cannot drop a switched-off criterion, observed on a
  table that was small when the statement was prepared, and
  `plan_cache_mode = force_custom_plan` preventing it. The exclusions gain
  typing a placeholder from the expression around it, a projected placeholder
  typed by another occurrence, and driver or session settings applied by
  sqlcj; the acceptance gains the executable SQL and a PostgreSQL test with a
  `null` argument. D-M records the revision.
- **2026-10-24.** M32-W3 and Milestone 32 were completed. `docs/queries.md`
  presents criteria queries with the reference service's `SearchTasks` and
  `CountSearchedTasks` as its worked example and states PostgreSQL 16 and
  pgjdbc 42.7.13 planning behavior with both remedies, beside W1's typing of
  an occurrence from another occurrence and W2's reference service criteria
  search. Every requirement and acceptance item is met by M32-W1 to M32-W3,
  and `mvn spotless:check` and `mvn -Dsqlcj.test.requireDocker=true test`
  pass. The milestone's section stays in place until the architect moves it
  into the [Completed Milestone Record](#completed-milestone-record); no next
  milestone was activated.
- **2026-10-24.** Milestone 32's section moved unchanged into the
  [Completed Milestone Record](#completed-milestone-record), and
  [Where sqlcj Stands](#where-sqlcj-stands) now describes `dfc52bb`, with
  criteria queries supported and no longer among the reasons not to publish.
  At the owner's request, Milestone 33 was activated, decomposed into M33-W1
  to M33-W4, and M33-W1 started. Its reassessment, probed outside the
  repository with Liquibase 5.0.4 and 4.33.0, MyBatis Migrations 3.6.0, and
  PostgreSQL 16.15, found that Liquibase formatted SQL and offline
  `update-sql` output already load, and amended two requirements:
  requirement 2 now names `outputLiquibaseSql=all`, because without it the
  offline output has no `DATABASECHANGELOG` statement, and settles the
  fixture as that output committed as Liquibase wrote it, so that no module
  depends on Liquibase, whose current releases are under the Functional Source
  License; requirement 3 now ends a file at every line MyBatis Migrations reads
  as its undo marker, because its reader also accepts blanks around the `//`
  and text after `@UNDO`. The exclusions gain the Liquibase directives sqlcj
  reads as comments, recursive directories, MyBatis id and special-file
  ordering, MyBatis variable substitution, and the reference service in
  either tool's form; [The reference service](#the-reference-service) records
  that Milestone 33 proves its inputs on a fixture of its own. The probe also
  found that the changelog script `migrate init` writes, `CREATE TABLE
  ${changelog}`, is rejected, so no `migrate init` project loads; the
  architect recommends a new requirement 5 that leaves such a variable-named
  table invisible, which widens the milestone and so awaits the owner as
  M33-W3. D-N records the revision.
- **2026-10-24.** M33-W1 is completed at `8eaaa5a`, and M33-W2 started.
  Rechecked against `04cb65b` and probed again outside the repository with
  Liquibase 5.0.4 and PostgreSQL 16 on W2's own fixture, requirements 1 and 2
  hold without a production change, so the work item's boundary stands; it
  gains the fixture's content and the provenance comment's form, because an
  XML comment cannot hold the `--` of a command-line option. M33-W3 still
  awaits the owner.
- **2026-10-24.** M33-W2 is completed at `0be0379`. The owner approved the
  architect's recommended requirement 5, which widens Milestone 33 so that the
  changelog script `migrate init` writes loads: its variable-named table is
  invisible and its constraint-only `ALTER TABLE` ignored. M33-W3 moved from
  Blocked to In progress. Its recheck against `3c75f90` found that the parser
  reads that `ALTER TABLE` as an alteration of a table named `$`, so the rule
  is decided from tokens before parsing, and that a statement naming a
  variable which is not table or type DDL is ignored today; the decision now
  says every other variable statement is treated as today rather than
  rejected. The exclusion of MyBatis variable substitution now excepts
  requirement 5's two statements, and M33-W4 documents the outcome. D-N
  records the revision.
- **2026-10-25.** M33-W3 is completed at `890532c`, merged as `b2bf4d6`, and
  M33-W4 started. Its recheck against `b2bf4d6` found that the offline
  `update-sql` command as the fixture's provenance comment states it needs a
  writable changelog directory and a CSV removed before each run, so the
  documentation states the probed form that writes to standard output and
  keeps the CSV inside the discarded container, whose statements are the
  fixture's; the work item records where each detail is documented.
- **2026-10-25.** M33-W4 and Milestone 33 were completed.
  `docs/configuration.md` and `docs/postgresql.md` give each migration tool's
  schema path and the `pg_dump --schema-only` fallback, beside W1's undo
  marker, W2's Liquibase fixtures, and W3's changelog script. Every
  requirement and acceptance item is met by M33-W1 to M33-W4, and
  `mvn spotless:check` and `mvn -Dsqlcj.test.requireDocker=true test` pass.
  The milestone's section stays in place until the architect moves it into
  the [Completed Milestone Record](#completed-milestone-record); no next
  milestone was activated.

## Completed Milestone Record

The sections below are the record of completed milestones 15 through 32,
kept as they were accepted. Milestones 1 through 14 predate this record; their
outcomes are summarized in [Milestone Status](#milestone-status). The
per-query API and nested row records described here are the state at the time
of each milestone; later milestones supersede them where stated. References
in the records of Milestones 15 through 21 to Milestones 22 and 23 mean the
plan in force when those records were written; that plan was replaced on
2026-10-05, as [Plan Changes](#plan-changes) records. Likewise, references in
the records of Milestones 22 through 25 to Milestones 26 to 28 mean the plan
in force before 2026-10-17, when they became Milestones 34, 36, and 37.

### Milestone 15 — Repository-Shaped Generated API

#### Goal

Replace the fragmented per-query API with one generated concrete repository per
configured query group before any public release makes the old shape a contract.

#### Work items

- **M15-W1 — Repository-shaped generated API — Completed.** Implements
  requirements 1–7 as one coordinated vertical slice: each explicitly named SQL
  entry generates one concrete repository containing its typed named-query
  methods and one executor constructor, and the documented author sample uses
  that API. The boundary is configuration group identity, grouped analysis and
  generation, repository-scoped query/method collisions, removal of per-query
  output, and corresponding sample/documentation updates; all Milestone 15
  exclusions and Milestone 16 result/cardinality changes remain out of scope.
  Acceptance evidence is the milestone's focused configuration, compiler,
  generator, generated-compilation, PostgreSQL, formatting, and full-suite
  verification.
  Review evidence: each `sql` entry requires a Java-safe `name` and generates
  one final `<Name>Repository` with one `QueryExecutor` field, one constructor,
  and one method per query in declared order, with nested result records and
  row mappers; per-query output is removed. Query and method collisions,
  including result types that differ only by case, are rejected per repository
  before any file is written, while two repositories may reuse a query name;
  repository path collisions stay global. `mvn spotless:check` and
  `mvn test -Dsqlcj.test.requireDocker=true` passed (332 tests, 0 skipped,
  including 11 PostgreSQL tests through both executor construction paths), and
  `scripts/verify-release.sh` generated one `AuthorRepository` for the external
  sample and ran it against PostgreSQL 16.

#### Required

1. Add a required, Java-safe `name` to each `sql` configuration entry. An entry
   named `Author` generates `AuthorRepository`.
2. Analyze all queries in an entry before generation and pass an explicit query
   group model to Java generation. The generator must not rediscover grouping
   from file paths or tables.
3. Generate one final repository class with one `QueryExecutor` field, one
   constructor, and one public method per named query, preserving method
   parameter types and logical order.
4. Nest query-specific result records and row mappers in the repository until a
   result qualifies for the reusable row model in Milestone 16.
5. Scope query-name uniqueness and method-collision checks to a repository;
   repository class/path collisions remain global within one output package.
6. Remove the per-query class output. Do not carry both APIs or add a
   compatibility mode; there is no public release to remain compatible with.
7. Update the sample and user documentation so application code creates one
   repository per execution context and never constructs a type per query.

#### Out of scope

- generated repository interfaces, implementation suffixes, annotations,
  scanning, proxies, service locators, or a repository factory,
- automatic grouping by table or filename,
- inferred CRUD methods not present as named SQL,
- new SQL constructs, types, or transaction orchestration.

#### Acceptance

- The five-query author sample generates one `AuthorRepository` with five
  methods and one executor constructor.
- Two repositories may contain the same query name without collision, while
  duplicate method names inside one repository fail before output is written.
- The same generated repository compiles and executes through both existing
  executor construction paths.
- Generated source remains readable and contains no compiler or parser
  dependency.
- Focused configuration, compiler, generator, generated-compilation, and
  PostgreSQL tests pass, followed by `mvn spotless:check` and `mvn test`.

### Milestone 16 — Result and Cardinality Contract

#### Goal

Make method return types predictable and reusable, and remove `null` as the
top-level signal that a query returned no row.

#### Work items

Reassessed on activation against the implementation, tests, sample, and
documentation: requirements 1–6 and 9 are unimplemented as assumed, `SELECT`
aliases are still accepted and ignored, `RETURNING` aliases are rejected, and
requirement 7 already holds and must be preserved.

- **M16-W1 — Projection aliases and conventional Java names — Completed.**
  Implements requirements 3 and 4. An explicit `SELECT` projection alias names
  its result component instead of being ignored, and repository, result, and
  method names, record components, and method parameters follow one documented
  deterministic camel-case rule set covering word splitting, acronyms, quoted
  names, keywords, and collisions. The boundary is alias capture in analysis,
  Java naming in generation, and the naming documentation; `RETURNING` aliases
  stay rejected, and row types, cardinality, and runtime changes remain out of
  scope. Acceptance evidence is focused naming and analyzer tests, compiled
  generated source for aliased partial and join projections, updated
  integration expectations, `mvn spotless:check`, and `mvn test`.
  Review evidence: an explicit alias on a direct `SELECT` column names its
  result column while type and nullability stay the column's; `RETURNING`
  aliases stay rejected. Repository and result types are upper camel case and
  methods, record components, and parameters lower camel case, with documented
  word-splitting, acronym, quoted-name, digit-initial, keyword, and no-word
  rules; same-method and case-only result-type rejection and numeric
  disambiguation are unchanged, and row mapping stays positional. The aliased
  join `u.id AS user_id, p.id AS profile_id, p.nickname` generates and executes
  `userId`, `profileId`, `nickname`. `mvn spotless:check` and
  `mvn test -Dsqlcj.test.requireDocker=true` passed (363 CLI and 18 runtime
  tests, 0 skipped, including 11 PostgreSQL tests).
- **M16-W2 — Reusable table-row records — Completed.** Implements requirements 1
  and 2. A single-source `SELECT *` or `<source>.*` and a `RETURNING *` return
  the repository's nested `<TableName>Row`, generated once per table used, while
  every other shape keeps its `<QueryName>Result`, including an explicit list of
  every column and a wildcard combined with another item. The boundary is analyzed
  full-row identity, row records and mappers with their name collisions, and
  the sample and documentation changes that make create, get, and list share
  `AuthorRepository.AuthorsRow`; cardinality remains out of scope. Acceptance
  evidence is focused analyzer and generator tests, generated-source
  compilation, PostgreSQL execution, the external sample, `mvn spotless:check`,
  and `mvn test`.
  Review evidence: a single-source `SELECT *` or `<source>.*` (any alias or
  table spelling) and a `RETURNING *` on `INSERT`, `UPDATE`, or `DELETE` return
  one nested `<TableName>Row` per table, generated once after the constructor
  with one positional mapper in schema column order; explicit column lists
  (including every column), a wildcard plus another item, joined wildcards,
  and `RETURNING` column lists keep `<QueryName>Result`. A query named
  `Authors` keeps `authorsRowMapper` while the row mapper becomes
  `authorsRowMapper1`, and `user_data`/`userdata` row types are rejected naming
  both tables and types with no output written. Binding order is unchanged.
  `mvn spotless:check` and `mvn test -Dsqlcj.test.requireDocker=true` passed
  (383 CLI and 18 runtime tests, 0 skipped, including 12 PostgreSQL tests), and
  `scripts/verify-release.sh` ran the external sample, whose `createAuthor`,
  `getAuthor`, and `listAuthors` return `AuthorRepository.AuthorsRow`, against
  PostgreSQL 16.
- **M16-W3 — Exact, optional, and many cardinality — Completed.** Implements
  requirements 5–9. `:one` returns exactly one row or fails with a focused
  runtime exception, the new `:optional` returns `Optional<T>`, `:many` and
  `:exec` keep their contracts, runtime execution and cardinality failures name
  the repository and query, and the documentation separates row absence from
  nullable components. The boundary is annotation parsing, analyzer annotation
  checks, the runtime executor contract, generated calls, the sample, and
  documentation. Acceptance evidence is runtime unit tests, generated-source
  compilation, PostgreSQL zero/one/multiple-row execution through both executor
  paths, the external sample, `mvn spotless:check`, and `mvn test`.
  `:optional` is accepted wherever `:one` is, and the external sample gains an
  optional lookup replacing its null missing-row checks.
  Review evidence: `:one` returns its row directly and raises
  `QueryCardinalityException`, a `QueryExecutionException` subclass, for no row
  and for more than one; `:optional` is accepted for a `SELECT` and a returning
  write, returns `Optional<T>`, `Optional.empty()` for no row, and fails for more
  than one; `:many` keeps `List<T>` with an empty list and `:exec` its `int`
  count. Every generated executor call passes the repository class name and
  query name, so failures read `Query 'GetAuthor' in AuthorRepository returned
  no row; expected exactly one` and `Failed to execute query 'GetAuthor' in
  AuthorRepository`; statements, result sets, and acquired connections close on
  a cardinality failure, and binding order is unchanged. `java.util.Optional`
  is imported only for an `:optional` query, and the documentation states that
  a component is `null` only for a nullable column while row absence is
  expressed only by the annotation. `mvn spotless:check` and
  `mvn -Dsqlcj.test.requireDocker=true test` passed (391 CLI and 25 runtime
  tests, 0 skipped, including 13 PostgreSQL tests exercising zero, one, and two
  rows through both executor paths), and `scripts/verify-release.sh` ran the
  external sample, whose `findAuthor` returns an empty `Optional` and whose
  `getAuthor` raises the cardinality exception for a missing row, against
  PostgreSQL 16.

#### Required

1. Generate one nested `<TableName>Row` record per repository for every complete
   table-row shape the repository uses. Use deterministic Java normalization,
   not English singularization (`authors` becomes `AuthorsRow`).
2. Reuse that row type for a direct full-table projection or `RETURNING *` in
   schema column order. Keep a `<QueryName>Result` record for partial
   projections, joins, and other query-specific shapes.
3. Honor an explicit projection alias as the query-specific result component
   name. Do not continue accepting an alias and silently ignoring it.
4. Generate conventional Java names: SQL `snake_case` columns and aliases
   become lower-camel-case record components and parameters, while repository,
   row, and result types use upper camel case. Define acronym, quoted-name,
   keyword, and collision behavior deterministically; do not add a configurable
   naming strategy for `0.1.0`.
5. Redefine `:one` as exactly one row. It returns the result directly and fails
   with a focused runtime exception when zero or more than one row is returned.
6. Add `:optional` for zero-or-one queries. It returns `Optional<T>`, returns
   `Optional.empty()` for no row, and fails when more than one row is returned.
7. Keep `:many` as `List<T>` and `:exec` as the affected-row count. Use empty
   collections rather than `null`.
8. Preserve the distinction between row absence and a nullable column. Keep the
   current Java scalar mappings for `0.1.0` and document which record components
   may be `null`; do not add a nullness-annotation dependency solely for code
   generation.
9. Include the repository and query name in runtime execution/cardinality
   errors without exposing compiler implementation types to the runtime.

#### Out of scope

- domain entities, identity maps, record mutation, relationship mapping, or
  converting rows into application domain objects,
- configurable model names, user-supplied result classes, reflection mapping,
  or a general structural type-deduplication algorithm,
- optional wrappers for every nullable column or a new nullness type system.

#### Acceptance

- Create, get, and list queries that return `authors.*` share
  `AuthorRepository.AuthorsRow`.
- A partial projection and a join have stable query-specific records whose
  components honor aliases, Java naming conventions, and selected-column order.
- Exact-one, optional, and many methods exercise zero, one, and multiple rows
  against PostgreSQL and enforce their documented contracts.
- Existing parameter and JDBC binding order is unchanged.
- Focused runtime/generator tests, generated-source compilation,
  PostgreSQL integration, `mvn spotless:check`, and `mvn test` pass.

### Milestone 17 — Everyday PostgreSQL Read Slice

#### Goal

Add the smallest high-value read features needed by an ordinary CRUD service,
without attempting a general SQL expression type system.

#### Work items

Reassessed on activation against the implementation, tests, sample, and
documentation: requirements 1–5 are unimplemented as assumed. `LIKE $N`,
`ILIKE $N`, `BETWEEN $N AND $M`, and `LIMIT`/`OFFSET $N` fail only through the
generic unanalyzed-placeholder rejection, `COUNT(*)` fails as an unsupported
`Function` projection, and `LEFT JOIN` fails as a non-inner join. Forms that bind
no placeholder — `IS NULL`/`IS NOT NULL`, a literal `LIKE` pattern or `BETWEEN`
range, and literal `LIMIT`/`OFFSET` — are accepted unanalyzed, so an unknown
column in a null test, literal pattern, or literal range passes analysis. The external sample already has the optional
lookup (`FindAuthor`) but no search, count, page, or join, and the quickstart
uses `CountAuthors` as its invalid-query example.

- **M17-W1 — Text and null predicates — Completed.** Implements requirement 1.
  A predicate `<column> LIKE $N` or `<column> ILIKE $N` on a direct `VARCHAR` or
  `TEXT` column binds one `String` parameter typed from that column, and
  `<column> IS NULL` / `IS NOT NULL` on a direct column resolves that column
  against the query sources, so an unknown or ambiguous column is rejected; both
  apply wherever the read predicate forms are accepted. The boundary is
  predicate analysis and the query documentation: a `LIKE`-family placeholder
  that is negated, uses another keyword such as `SIMILAR TO`, has an `ESCAPE`
  clause, targets a non-text column, or stands on the left is rejected with a
  focused diagnostic; a placeholder inside a pattern expression and `$N IS NULL`
  stay rejected as unanalyzed placeholders; literal patterns stay unanalyzed.
  The sample is M17-W6. Acceptance evidence is focused analyzer tests,
  generated-source compilation and PostgreSQL execution of `LIKE`, `ILIKE`,
  `IS NULL`, and `IS NOT NULL` reads, `mvn spotless:check`, and `mvn test`.
  Review evidence: `QueryAnalyzer.resolveParameters` gained a `LikeExpression`
  and an `IsNullExpression` branch, so the shared `WHERE` analysis of `SELECT`,
  `UPDATE`, and `DELETE` types a pattern placeholder from the tested `VARCHAR`
  or `TEXT` column and resolves a null test's column through the existing
  column resolution without adding a parameter; `name ILIKE $2 AND id > $1`
  yields `[id BIGINT, name VARCHAR]` with binding order `[2, 1]`, `bio LIKE $1`
  yields a `TEXT` parameter, and `missing IS NULL` is rejected with `Column not
  found in sources users: missing`. `NOT LIKE`, `NOT ILIKE`, `SIMILAR TO`,
  `ESCAPE`, `BINARY`, a non-text tested column, a placeholder as the tested
  value, and `name LIKE :pattern` each fail before generation with their own
  message, while `'%' || $1 || '%'` and `$1 IS NULL` stay rejected by the
  placeholder accounting check and a literal `LIKE` pattern stays unanalyzed.
  `QueryModel`, the generator, and the runtime are unchanged.
  `mvn spotless:check` and `mvn -Dsqlcj.test.requireDocker=true test` passed
  (407 tests, 0 skipped, including 14 PostgreSQL tests; the new
  `PostgresIntegrationTest.shouldExecuteGeneratedTextAndNullPredicatesAgainstPostgres`
  compiles and runs `LIKE`, case-insensitive `ILIKE`, `IS NULL`, and
  `IS NOT NULL` reads against PostgreSQL 16), and `docs/queries.md` documents
  the new predicate forms, the text-column requirement, and the caller-supplied
  wildcards.
- **M17-W2 — Range predicates — Completed.** Implements requirement 2.
  `<column> BETWEEN $a AND $b` and `NOT BETWEEN` on a direct column bind each
  placeholder bound with that column's type in textual order, named by the
  existing column-derived naming and numeric disambiguation rules. A
  placeholder as the tested operand or inside a bound expression stays
  rejected. Acceptance evidence is analyzer tests including out-of-order
  indexes, PostgreSQL execution, `mvn spotless:check`, and `mvn test`.
  Reassessed before implementation: both forms currently fail only through the
  generic placeholder-accounting rejection, while a named bound such as
  `id BETWEEN :lo AND :hi` is accepted and reaches the executable SQL unbound.
  The boundary is predicate analysis and the query documentation, applied
  wherever the read predicate forms are accepted: a placeholder bound beside a
  literal bound is typed the same way, a named bound is rejected with the
  existing named-parameter diagnostic, a range that binds no placeholder stays
  unanalyzed like a literal `LIKE` pattern, and `BETWEEN SYMMETRIC` already
  fails to parse. The sample is M17-W6.
  Review evidence: `QueryAnalyzer.resolveParameters` gained a `Between` branch,
  so the shared `WHERE` analysis of `SELECT`, `UPDATE`, and `DELETE` types each
  `$N` bound from the resolved tested column and records the start bound before
  the end bound; `id BETWEEN $1 AND $2` and `id NOT BETWEEN $1 AND $2` yield
  `[id BIGINT, id BIGINT]` with binding `[1, 2]` and executable
  `BETWEEN ? AND ?`, `name = $1 AND id NOT BETWEEN $3 AND $2` binds `[1, 3, 2]`,
  `id BETWEEN $1 AND 10` yields one parameter, a literal-only range yields none,
  `missing BETWEEN $1 AND $2` fails column resolution, and
  `id BETWEEN :lo AND :hi` fails with the named-parameter diagnostic, while
  `$1 BETWEEN id AND id` and `id BETWEEN $1 + 1 AND $2` stay rejected by the
  placeholder accounting check. A qualified tested column and the `UPDATE` and
  `DELETE` paths were also probed and bind in textual order. `QueryModel`, the
  generator, and the runtime are unchanged. `mvn spotless:check` and
  `mvn -Dsqlcj.test.requireDocker=true test` passed (417 CLI and 25 runtime
  tests, 0 skipped, including 15 PostgreSQL tests; the new
  `PostgresIntegrationTest.shouldExecuteGeneratedRangePredicatesAgainstPostgres`
  compiles and runs a `BETWEEN` read and an out-of-order `NOT BETWEEN` read
  against PostgreSQL 16), and `docs/queries.md` documents the range forms, bound
  typing and order, and the rejected nearby forms.
- **M17-W3 — Pagination — Completed.** Implements requirement 3. A read
  accepts a literal or `$N` `LIMIT` and `OFFSET`; each placeholder is an `Integer`
  parameter named `limit` or `offset` (sqlc types PostgreSQL limit/offset
  parameters as `integer`, plain `int` arguments autobox, and PostgreSQL widens
  the value to `bigint`), bound in textual order including `OFFSET ... LIMIT
  ...`. Values must be non-negative; PostgreSQL rejects a negative value at
  execution, and generated code adds no validation. `LIMIT ALL`, computed
  values, `LIMIT a, b`, and `FETCH` stay rejected when they carry a
  placeholder. Acceptance evidence is analyzer tests, PostgreSQL execution of
  a paged list, `mvn spotless:check`, and `mvn test`.
  Reassessed before implementation: `LIMIT $N` and `OFFSET $N` fail only through
  the generic placeholder-accounting rejection, while a named `LIMIT :n` or
  `OFFSET :n` is accepted and reaches the executable SQL unbound. The SQL
  parser stores `OFFSET a LIMIT b` exactly like `LIMIT b OFFSET a`, so textual
  order must come from the placeholders' source positions. The boundary is
  `SELECT` analysis and the query documentation: the `LIMIT` row count and the
  `OFFSET` value are analyzed independently after the `WHERE` parameters, so
  `LIMIT ALL OFFSET $N` binds its offset; a named value is rejected with the
  existing named-parameter diagnostic; a value that binds no placeholder stays
  unanalyzed; and a placeholder in a computed value, in `LIMIT a, b`, or in
  `FETCH` stays rejected by the accounting check. The sample is M17-W6.
  Review evidence: `QueryAnalyzer.resolveBindingParameters` now resolves the
  `WHERE` parameters when present and then the pagination parameters, so a
  `LIMIT` row count or `OFFSET` value that is a `$N` placeholder binds one
  `INTEGER` parameter named `limit` or `offset`, ordered by source position when
  both are placeholders; `name = $1 ... LIMIT $2 OFFSET $3` yields
  `[name VARCHAR, limit INTEGER, offset INTEGER]` with binding `[1, 2, 3]`,
  `OFFSET $2 LIMIT $1` yields `[limit INTEGER, offset INTEGER]` with binding
  `[2, 1]`, `LIMIT 10 OFFSET $1` and `LIMIT ALL OFFSET $1` each yield one
  `offset` parameter, and `LIMIT 10 OFFSET 5` yields none. `LIMIT :n` and
  `OFFSET :n` fail with the named-parameter diagnostic, while `LIMIT $1 + 1`,
  `OFFSET $1 + 1`, `LIMIT 5, $1`, `LIMIT $1, $2`, and `FETCH FIRST $1 ROWS ONLY`
  stay rejected by the placeholder accounting check. `QueryModel`, the
  generator, and the runtime are unchanged. `mvn spotless:check` and
  `mvn -Dsqlcj.test.requireDocker=true test` passed (430 CLI and 25 runtime
  tests, 0 skipped, including 16 PostgreSQL tests; the new
  `PostgresIntegrationTest.shouldExecuteGeneratedPaginationAgainstPostgres`
  compiles both clause orders as `(Integer, Integer)` methods and returns the
  same page from each against PostgreSQL 16), and `docs/queries.md` documents
  the pagination forms, their `Integer` parameters, binding order, and the
  rejected nearby forms.
- **M17-W4 — Scalar count — Completed.** Implements requirement 4. A
  projection that is exactly `COUNT(*) AS <alias>` produces a
  `<QueryName>Result` with one non-null `Long` component named after the alias;
  `COUNT(*)` without an alias or beside another projection item,
  `COUNT(column)`, `COUNT(DISTINCT ...)`, `FILTER`/`OVER`, and every other
  function stay rejected. The quickstart's invalid-query example changes
  because `CountAuthors` becomes valid.
  Acceptance evidence is analyzer and generator tests, PostgreSQL execution,
  `mvn spotless:check`, and `mvn test`.
  Reassessed before implementation: every `COUNT(*)` spelling reaches `SELECT`
  projection analysis as a parser `Function` and fails with `Unsupported SELECT
  expression: Function`, while the `FILTER` and `OVER` forms parse as an
  `AnalyticExpression` and fail the same way under that name; no test covers a
  function projection. `COUNT(t.*)` parses its argument as a subtype of the
  `COUNT(*)` argument, so recognition must exclude it explicitly. The generator
  needs no change: a `BIGINT` result column already generates a `Long`
  component read by position, and an aliased projection is never a shared full
  row, so it generates `<QueryName>Result`; PostgreSQL's `count(*)` returns
  `bigint` and `0`, never null, for no rows. `CountAuthors` is not in the
  quickstart's `queries.sql` listing; it appears only in the sample diagnostic,
  which is the only quickstart change. The boundary is `SELECT` projection
  analysis and the query documentation: the count is an unqualified
  case-insensitive `COUNT` whose single argument is exactly `*`, with an alias
  written with or without `AS`, over any already-accepted source, predicate, and
  pagination shape and under any result annotation. A missing alias and a count
  beside another projection item each fail with a focused diagnostic; a
  qualified name such as `pg_catalog.count(*)`, `COUNT(t.*)`, and the other
  listed forms keep the existing unsupported-expression rejection. `RETURNING`
  is unchanged and `GROUP BY` stays unanalyzed. Because the generator is
  unchanged, the generator evidence is the pipeline's compiled record exposing a
  `Long` component. The sample is M17-W6.
  Review evidence: `QueryAnalyzer.resolveColumns` gained a `Function` branch
  that accepts only a one-part, case-insensitive, non-`DISTINCT` `count` whose
  single argument is exactly the `*` argument class, checks the item count
  before the alias, and resolves `QueryColumn(<alias>, BIGINT, false)`;
  `SELECT COUNT(*) AS total FROM users` yields that one column with no
  parameters and no row table, and `count(*) user_count ... WHERE name LIKE $1`
  keeps its one `VARCHAR` parameter. `COUNT(*)` without an alias fails with
  `COUNT(*) requires a result alias, such as COUNT(*) AS total.`, a count before
  or after `id` fails with `COUNT(*) must be the only SELECT item.`, and
  `COUNT(id)`, `COUNT(DISTINCT id)`, `COUNT(u.*)`, `pg_catalog.count(*)`, and
  `lower(name)` keep `Unsupported SELECT expression: Function` while the
  `FILTER` and `OVER` forms keep it as `AnalyticExpression`. `QueryModel`, the
  generator, and the runtime are unchanged. `mvn spotless:check` and
  `mvn -Dsqlcj.test.requireDocker=true test` passed (443 tests, 0 skipped,
  including 150 analyzer and 17 PostgreSQL tests; the new
  `PostgresIntegrationTest.shouldExecuteGeneratedScalarCountAgainstPostgres`
  compiles a `:one` count whose record exposes one `Long` component `total` and
  returns `2L` and `0L` against PostgreSQL 16), and `docs/queries.md` and the
  quickstart diagnostic document the accepted count and its rejected forms.
- **M17-W5 — Narrow left joins — Completed.** Implements requirement 5. `LEFT
  JOIN` (including its `LEFT OUTER JOIN` spelling) is accepted with exactly the
  inner-join `ON` shape, and every column of a left-joined source is analyzed
  as nullable; every other join kind stays rejected. Acceptance evidence is
  analyzer tests, PostgreSQL execution returning `null` components for an
  unmatched row, `mvn spotless:check`, and `mvn test`.
  Reassessed before implementation: both spellings parse as a join reporting
  `isLeft()` (`LEFT OUTER JOIN` also `isOuter()`) and fail the supported-join
  gate with `Only unmodified INNER JOIN clauses are supported.`; no test covers
  any outer join. A bare `OUTER JOIN` reports only `isOuter()`, while
  `NATURAL LEFT JOIN`, `LEFT SEMI JOIN`, and `LEFT APPLY` also report
  `isLeft()`, so recognition must exclude every other modifier explicitly.
  Result nullability comes only from the schema column today; the generator
  resolves Java types from the column type alone and reads every component with
  `ResultSet.getObject(position, Class)`, so generated code already returns
  `null` for an unmatched row and needs no change. The boundary is `SELECT`
  source and projection analysis and the query and null documentation: a left
  join keeps the existing `ON` equality rules and diagnostics and may be mixed
  with inner joins in any order; every projected column of a left-joined
  source, whether direct, aliased, `qualifier.*`, or `*`, is nullable even when
  declared `NOT NULL`, while the base and inner-joined sources keep schema
  nullability. Parameters, binding order, full-row identity (a join never
  shares a row type), the generator, and the runtime are unchanged; `RIGHT`,
  `FULL`, bare `OUTER`, `NATURAL`, `SEMI`, `APPLY`, `USING`, comma lists, and
  non-table join sources stay rejected under an updated diagnostic. The sample
  is M17-W6.
  Review evidence: `QueryAnalyzer` records whether each `SELECT` source is
  left-joined and accepts a join only when it is an unmodified inner join or a
  `LEFT`/`LEFT OUTER` join with no other modifier, join hint, or `USING`; the
  table-source check and `ON` equality rules are unchanged. A column read from a
  left-joined source is nullable in direct, aliased, `qualifier.*`, and `*`
  projections, while base, inner-joined, and write sources keep schema
  nullability. `SELECT u.id, p.id AS profile_id, p.nickname` over either
  spelling yields `[id BIGINT false, profile_id BIGINT true, nickname VARCHAR
  true]`; `*` and `p.*, u.name` mark every `profiles` column nullable; an inner
  join followed by a left join and the reverse order each make only the
  left-joined source nullable; and `WHERE u.id = $1` keeps `[id BIGINT]` with
  binding `[1]`. `RIGHT`, `RIGHT OUTER`, `FULL OUTER`, bare `OUTER`,
  `NATURAL LEFT`, `LEFT SEMI`, and `LEFT JOIN ... USING` fail with `Only
  unmodified INNER JOIN and LEFT JOIN clauses are supported.`, and a
  multi-predicate or non-equality left-join `ON` fails with `A join requires
  exactly one ON equality.` `QueryModel`, the generator, and the runtime are
  unchanged. `mvn spotless:check` and `mvn -Dsqlcj.test.requireDocker=true test`
  passed (460 CLI and 25 runtime tests, 0 skipped, including 166 analyzer and 18
  PostgreSQL tests;
  the new `PostgresIntegrationTest.shouldExecuteGeneratedLeftJoinAgainstPostgres`
  compiles a `:many` left join and returns a matched row and an unmatched
  customer whose `orderId` and `quantity` are `null` against PostgreSQL 16), and
  `docs/queries.md` and `docs/postgresql.md` document the accepted join, its
  nullability, and the rejected forms.
- **M17-W6 — External sample read slice — Completed.** Satisfies the sample
  acceptance line after M17-W1 and M17-W3 through M17-W5: the external sample and
  quickstart add a text search, count, paged list, and left-join projection
  beside the existing optional lookup, through generated repository methods.
  Acceptance evidence is `scripts/verify-release.sh` against PostgreSQL 16,
  `mvn spotless:check`, and `mvn test`.
  Reassessed before implementation: the sample and quickstart still hold only
  the six `authors` queries, and the one-table schema cannot express a
  meaningful left join, so the sample schema gains a `books` table referencing
  `authors` and one `:exec` insert that gives the join a matched row. The
  acceptance line lists no range predicate, so M17-W2 is deliberately not
  demonstrated in the sample. The boundary is the external sample's schema,
  queries, and application, the quickstart listings, output, and diagnostic line,
  and the release script's schema reset and generated-method check; the
  compiler, generator, runtime, `sqlcj.yaml`, and the single generated
  `AuthorRepository` are unchanged.
  Accepted: the sample schema adds `books`, and its eleven queries generate one
  `AuthorRepository` exposing `searchAuthors(String name)`,
  `countAuthors()` returning `CountAuthorsResult`,
  `listAuthorPage(Integer limit, Integer offset)`,
  `createBook(Long authorId, String title)` returning `int`, and
  `listAuthorBooks()` returning `List<ListAuthorBooksResult>`. The sample checks
  that `ILIKE '%lovelace%'` returns only Ada, the count is `2`, page `(1, 1)`
  returns only Grace, the book insert affects one row, and the left join returns
  Ada with a `null` title before Grace with the inserted title, then still ends
  with one author after the delete. `scripts/verify-release.sh` passed twice in
  a row against PostgreSQL 16, proving the `books, authors` reset is rerunnable;
  the quickstart schema and query listings are byte-identical to the sample, its
  `App.java` listing compiled against the generated repository and printed
  exactly the documented output against PostgreSQL 16, and appending
  `CountAuthorBios` after one blank line reproduces the documented line-57
  diagnostic. `mvn spotless:check` and
  `mvn -Dsqlcj.test.requireDocker=true test` passed (460 CLI tests, 0 skipped).
  M17-W2 is intentionally not demonstrated in the sample.

#### Required

Implement these as separate reviewed work items so one does not silently widen
another:

1. Text and null predicates: typed `LIKE`/`ILIKE` parameters and direct-column
   `IS NULL`/`IS NOT NULL`.
2. Range predicates: `BETWEEN`/`NOT BETWEEN` with both bounds inferred from one
   direct column.
3. Pagination: literal or placeholder `LIMIT` and `OFFSET` with one documented
   non-negative integral Java type and correct placeholder accounting.
4. Scalar count: `COUNT(*)` with a required result alias and a documented
   non-null `Long` mapping. Do not generalize this into arbitrary function or
   aggregate inference.
5. Narrow left joins: `LEFT JOIN` with the same direct qualified equijoin shape
   already supported for inner joins. Columns from the right side remain
   nullable at runtime.

Every slice must fail clearly for a nearby unsupported form rather than passing
an untyped placeholder or inventing a generic `Object` result.

#### Out of scope

- arbitrary functions, arithmetic or computed projections,
- `GROUP BY`, `HAVING`, aggregate expressions other than the stated `COUNT(*)`,
- dynamic page/sort objects, keyset pagination helpers, or generated count
  companions,
- other join kinds, arbitrary join predicates, CTEs, subqueries, and set
  operations,
- write-expression or upsert expansion.

#### Acceptance

- The external sample includes a text search, optional lookup, count, paged
  list, and narrow left-join projection through generated repository methods.
- All new parameters have one known Java type and every accepted placeholder is
  bound exactly once per textual occurrence.
- Focused analyzer/generator tests, generated-source compilation, PostgreSQL
  execution, `mvn spotless:check`, and `mvn test` pass.

### Milestone 18 — Generation Workflow Hardening

#### Goal

Make repeated local and CI generation predictable without adding a build-system
plugin.

#### Work items

Reassessed on activation against the implementation, tests, external sample,
packaged-CLI smoke checks in `scripts/verify-release.sh`, and documentation.
Requirement 1 is unimplemented: `GenerateCommand` always loads `sqlcj.yaml` from
the working directory and `docs/configuration.md` states that no path option
exists, but `YamlConfigLoader` already resolves relative `schema`, `queries`,
and `java.out` against the configuration file's directory. Requirement 2 is
unimplemented: no manifest exists, `GeneratedFileWriter` creates or truncates
each file in place, and the documentation relies on `mvn clean` for stale
output. Since Milestone 15 one repository file is generated per `sql` entry, so
a renamed or removed query only changes methods inside that file; stale Java
arises when an entry is renamed or removed or `java.package` changes, and
requirement 6 and the stale-output acceptance line are proved with a query
group. Requirement 3 is half met: every file is generated before the first
write (`shouldReportLateFailureAndLeavePreviousOutputIntact`), but a write
failure escapes `GenerateCommand` as an `UncheckedIOException` through picocli's
unhandled-exception path instead of one `sqlcj:` diagnostic. Requirement 4 is
partly met: each repository's class Javadoc reads `Generated by sqlcj.` and
names the repository without timestamps or paths, but it follows the imports and
says nothing about editing. Requirement 5 already holds for configuration,
source, and query failures (one `sqlcj:` line, exit status `1`, covered by
`GenerateCommandTest` and the packaged smoke check); output failures do not, and
nothing detects a runtime-version mismatch because the CLI never sees the
application's runtime and the documentation only requires matching versions.
Requirement 6 is unimplemented: the release script generates once and expects
exactly one file. Generation emits content from ordered collections, but no test
compares two runs.

- **M18-W1 — Explicit configuration path — Completed.** Implements
  requirement 1. `sqlcj generate --config <path>` loads that file, a relative
  option value is resolved against the process working directory, and the
  file's own relative paths keep resolving against its directory, so generation
  works from another working directory; without the option `sqlcj.yaml` in the
  working directory stays the default. A missing or invalid explicit file
  reports the existing configuration diagnostics. The boundary is the `generate`
  command and the configuration, README, and quickstart statements about
  configuration discovery; no short alias, environment variable, or parent
  directory discovery is added. Acceptance evidence is `dev.sqlcj.Main`
  subprocess tests run from a different working directory, the unchanged
  default-path test, `mvn spotless:check`, and `mvn test`.
  Review evidence: `generate --config ../project/sqlcj-users.yaml` run as a
  `dev.sqlcj.Main` subprocess from a sibling directory exits 0, writes
  `project/generated/.../UsersRepository.java`, and leaves the working directory
  empty; `--config missing.yaml` beside a valid `sqlcj.yaml` exits 1 with
  `sqlcj: Cannot read configuration file: <workingDir>/missing.yaml`, no stack
  trace, and no output directory. The option defaults to `sqlcj.yaml`, the four
  default-path `GenerateCommandTest` cases are unchanged, and `generate` in an
  empty directory still prints one `sqlcj:` line and exits 1. The configuration,
  README, and quickstart documentation describe the option and its path
  resolution. `mvn spotless:check` and `mvn test` passed (462 tests, 0 failures,
  0 skipped).
- **M18-W2 — Focused output-failure reporting — Completed.** Implements the
  write half of requirement 3. A filesystem failure while creating an output
  directory or writing a generated file ends the run with one `sqlcj:`
  diagnostic naming the failing path and exit status `1`, with no stack trace,
  while analysis stays all-or-nothing before the first write. The boundary is
  the output layer, `GenerateCommand`, and the documented non-transactional
  write behavior; no rollback or staging directory is added. Decided on
  activation: the diagnostic reads `Cannot create output directory: <directory>`
  or `Cannot write generated file: <file>` with the resolved path, matching the
  existing `Cannot read ... source: <path>` form; adding the filesystem's reason
  or a corrective fact stays with M18-W5. Failures are forced portably, without
  permission changes, by a regular file where an output directory is needed and
  a directory where a generated file goes. Acceptance evidence is compiler and
  CLI tests that force a write failure as far as the filesystem permits, the
  existing previous-output test, `mvn spotless:check`, and `mvn test`.
  Review evidence: `GeneratedFileWriter` reports `Cannot create output
  directory: <resolved parent>` when `java.out` is a regular file and `Cannot
  write generated file: <resolved file>` when the target is a directory, keeping
  the original exception as the cause; `SqlcjCompiler` rethrows it as a
  `CompilationException` with that message, so `GenerateCommand`'s existing
  handler prints it. A `dev.sqlcj.Main` subprocess with `out: generated` naming
  a regular file exits 1 with exactly one stderr line, `sqlcj: Cannot create
  output directory: <workingDir>/generated/dev/example/generated`, no stack
  trace, and the file's content unchanged; `compile` with
  `UsersRepository.java` pre-created as a directory throws `Cannot write
  generated file: <absolute path>`. `shouldReportLateFailureAndLeavePreviousOutputIntact`
  and the existing `GenerateCommandTest` cases pass unmodified, and the
  configuration and README documentation describe the diagnostics and that
  already-written files stay in place. `mvn spotless:check` and `mvn test`
  passed (466 CLI tests, 0 failures, 0 skipped).
- **M18-W3 — Generated-output manifest and stale cleanup — Completed.**
  Implements requirement 2 and the cleanup half of requirement 3. Decided on
  activation as the smallest output-owned format: a successful run records
  `sqlcj-manifest.txt` in `java.out`, a UTF-8 file listing every generated file
  as a sorted `/`-separated path relative to `java.out`, one per line; after
  every generated file is written, a file listed by the previous manifest and
  not produced by this run is deleted, and nothing else is. Entries that are not
  a normalized relative path inside `java.out`, directories, and unlisted files
  are never deleted; a missing previous manifest deletes nothing; empty
  directories are left in place; a cleanup or manifest failure is reported like
  M18-W2. Changing `java.out` leaves the previous directory untouched. Acceptance
  evidence is compiler tests for renamed and removed entries and a changed
  package, an unlisted user file that survives, a failed compilation that
  leaves files and manifest unchanged, byte-identical manifests from two runs,
  documentation, `mvn spotless:check`, and `mvn test`.
  Refined on activation: every manifest line ends with `\n` and entries match
  produced paths exactly; the previous manifest is read after generation and
  before the first write, and stale files are deleted before the new manifest
  is written, so a failed write or cleanup keeps the previous manifest and the
  next successful run retries. Only a listed regular file is deleted, never one
  that is the same file as one this run wrote, which a case-only rename
  produces on a case-insensitive filesystem. Failures read `Cannot read output
  manifest: <file>`, `Cannot delete stale generated file: <file>`, or `Cannot
  write output manifest: <file>`; the manifest-read failure is forced portably
  by a directory at the manifest path. Documentation covers the configuration
  guide and README, and in the quickstart only the sentence stating that sqlcj
  never deletes files; its regeneration steps stay with M18-W6.
  Review evidence: `SqlcjCompiler` reads `sqlcj-manifest.txt` after generation
  and before the first write, writes every file, deletes stale entries, then
  writes the sorted, `\n`-terminated UTF-8 manifest through the output-layer
  `GeneratedOutputManifest`. Generating `Users` and `Orders` and then only
  `Customers` deletes both old repositories and leaves a manifest listing only
  `CustomersRepository.java`; a changed `java.package` deletes the old
  repository and keeps its package directory; a hand-written file and a file
  present before the first manifest survive; two runs of `Users` then `Orders`
  give byte-identical repositories and the manifest
  `dev/example/generated/OrdersRepository.java\ndev/example/generated/UsersRepository.java\n`.
  The late-failure test also keeps the manifest bytes unchanged, and a directory
  at the manifest path fails with `Cannot read output manifest: <path>` before
  any repository is written. Listed `../`, absolute, non-normalized, and empty
  entries, a directory, and a hard link to a produced file are never deleted.
  `mvn spotless:check` and `mvn test` passed (479 CLI and 25 runtime tests,
  0 failures, 0 skipped).
- **M18-W4 — Stable generated-code notice — Completed.** Implements
  requirement 4. Every generated Java file begins with one fixed notice stating
  that sqlcj generated it and that it must not be edited, with no version,
  timestamp, or machine-specific path; the repository Javadoc stays. The
  boundary is Java generation and the documented generated-source listings.
  Acceptance evidence is generator tests, compiled generated source, a
  byte-identical second generation, `mvn spotless:check`, and `mvn test`.
  Refined on activation: the notice is the single line
  `// Code generated by sqlcj. DO NOT EDIT.`, the first line of every
  generated repository, followed by one blank line and the `package`
  declaration. It is rendered by Java generation, not the output layer, and
  the output manifest is not Java source and gets no notice. The only
  generated-source listing is in the query guide; the quickstart and README
  list none.
  Review evidence: `JavaCodeGenerator` emits the constant
  `// Code generated by sqlcj. DO NOT EDIT.` as the first template element,
  followed by one blank line and the `package` declaration, with no version,
  time, path, or environment-derived text; everything after the package line,
  including the `Generated by sqlcj.` Javadoc, is unchanged. Generator tests
  assert the notice-plus-package prefix for the default and a configured
  package, a single notice occurrence, and the retained Javadoc, and the
  configured-package source compiles; `SqlcjCompiler` and CLI `generate` tests
  assert the same prefix, and the unmodified
  `shouldGenerateByteIdenticalRepositoriesAndManifestForTwoRuns` passes. The
  query guide's `AuthorRepository` listing starts with the notice and a bullet
  describes it. `mvn spotless:check` and `mvn test` passed (480 CLI tests,
  0 failures, 0 skipped).
- **M18-W5 — Actionable CLI diagnostics — Completed.** Implements
  requirement 5 after M18-W1 through M18-W3: configuration, source, analysis,
  and output failures each print one concise `sqlcj:` line naming the failing
  file and the corrective fact, exit with status `1`, and the documented exit
  behavior covers them. Decided by the owner on activation: the CLI cannot
  observe the application's runtime, so the documented rule that the CLI and
  the runtime are always used at the same version covers runtime-version
  mismatch; no CLI detection, version in generated source, or runtime version
  check is added. Acceptance evidence is CLI tests per failure category, the
  packaged smoke check, `mvn spotless:check`, and `mvn test`.
  Refined on activation: every such failure already prints one `sqlcj:` line
  naming its file and exits with status `1`, so the work adds the missing
  corrective fact while keeping each existing message prefix: the filesystem
  reason for an unreadable configuration file or schema or queries source; the
  YAML problem and its line and column for a malformed configuration file; the
  parser's reason without exception class names for a schema or query SQL
  syntax error, with line and column for the schema; and the filesystem reason
  for each output diagnostic, which M18-W2 deferred here. The diagnostics
  documentation states exit status `1` for every category.
  Review evidence: every existing message prefix is kept and ends with its
  corrective fact. A missing `--config` file or queries source ends with
  `: No such file or directory`, a configuration path that is a directory with
  `: Is a directory`, and malformed YAML with the parser's problem and one-based
  position, such as `expected <block end>, but found '<block mapping start>' at
  line 2, column 3`. Output directory, generated file, manifest read or write,
  and stale-delete failures append the filesystem reason (`Not a directory`,
  `Is a directory`) through `FileSystemReason`. A schema syntax error reads
  `Invalid schema source <file>: Encountered unexpected token: ";"
  <ST_SEMICOLON> at line 4, column 1` instead of `Failed to parse schema`, and a
  query syntax error reads `Invalid query 'GetUser' in <file> at line 1:
  Encountered unexpected token: "AND" "AND"`; a lexical error such as an
  unterminated string literal prints the parser's own `Lexical error at line L,
  column C. ...` wording, and no syntax diagnostic contains `net.sf.jsqlparser`.
  `GenerateCommandTest` asserts exactly one stderr line, exit status `1`, and no
  stack trace for the missing configuration, malformed YAML, schema syntax,
  query syntax, missing source, and output-directory cases, with no output
  written before the output stage and the blocking file left unchanged.
  The packaged CLI jar run in an empty directory exits 1 with one `sqlcj:` line
  and no exception text, and on a query and a schema with an unterminated string
  literal exits 1 with one clean line and writes nothing. The configuration,
  PostgreSQL, and README documentation state exit status `1` and show the
  facts. No runtime-version check was added. `mvn spotless:check` and
  `mvn test` passed (488 CLI tests, 0 failures, 0 skipped).
- **M18-W6 — External sample regeneration proof — Completed.** Implements
  requirement 6 and the sample acceptance lines after M18-W1 and M18-W3: the
  release script generates the external sample with `--config` from another
  working directory, generates twice and compares the source and manifest
  bytes, then renames and removes a query group and proves no stale repository
  remains before regenerating the sample's own configuration to compile and run
  it; the quickstart's regeneration guidance reflects the cleanup. Acceptance
  evidence is `scripts/verify-release.sh` against PostgreSQL 16,
  `mvn spotless:check`, and `mvn test`.
  Refined on activation: after copying the sample, the script runs
  `generate --config ../sample/sqlcj.yaml` twice from an empty sibling
  directory, which stays empty, and requires the second output tree, including
  `sqlcj-manifest.txt`, to match the first byte for byte. Two scratch
  configurations outside the sample, sharing its schema, queries, package, and
  `java.out`, then generate the groups `Author` and `Library` and next only
  `Writer`, after which the output holds only `WriterRepository.java` and a
  manifest listing it alone. The existing default-path generation from the
  sample directory then restores exactly `AuthorRepository.java`, identical with
  the manifest to the first run, before the unchanged compile and run. The
  quickstart keeps `mvn clean`, because sqlcj never deletes compiled classes,
  lists the manifest among the generated files, attributes a stale repository to
  a renamed or removed `sql` entry rather than a query, and says in its re-run
  section that regeneration deletes that repository. No product code, sample
  source, or CI workflow changes.
  Review evidence: `scripts/verify-release.sh` against PostgreSQL 16 runs
  `generate --config ../sample/sqlcj.yaml` from an empty sibling directory,
  leaving it empty, and a second run's output tree, including
  `sqlcj-manifest.txt`, matches the first under `diff -r`. Scratch
  configurations generating `Author` and `Library` and then only `Writer`
  leave exactly one Java source, `WriterRepository.java`, and a manifest equal
  to `com/example/app/db/WriterRepository.java\n` under `cmp`. The unchanged
  default-path generation from the sample directory then leaves one source with
  all eleven methods and matches the first output tree, and the sample compiles
  and runs; the script exits 0 with `Release verification passed for version
  0.1.0-SNAPSHOT` and removes its workspace. Every new check aborts through
  `fail`. The quickstart keeps `mvn clean` for compiled classes, lists the
  manifest, attributes a stale repository to a renamed or removed `sql` entry,
  and its re-run section describes manifest-driven deletion with a link to the
  configuration rules. `mvn spotless:check` and `mvn test` passed (488 CLI and
  25 runtime tests, 0 failures, 0 skipped).

#### Required

1. Add `generate --config <path>` while retaining `sqlcj.yaml` in the working
   directory as the default.
2. Write a sqlcj-owned manifest of generated relative paths. After a fully
   successful compilation, remove only stale files listed by the previous
   manifest; never delete an unlisted user file.
3. Keep analysis all-or-nothing before output mutation. Define and test how a
   write or stale-cleanup failure is reported.
4. Mark generated sources consistently with a stable generated-code notice;
   do not add timestamps or machine-specific paths that break deterministic
   output.
5. Make CLI diagnostics concise and actionable for configuration, source,
   analysis, and output failures. Runtime-version mismatch is covered by the
   documented rule that the CLI and the runtime are always used at the same
   version, not by a CLI diagnostic.
6. Update the external sample and release verification to generate twice, then
   rename/delete a query and prove no stale class remains.

#### Out of scope

- Maven and Gradle plugins, daemon/watch mode, IDE integration, or incremental
  compiler protocols,
- `generate --check`, SQL formatting, schema diffing, or migration verification,
- deleting an entire output directory or any file sqlcj did not record as its
  own output.

#### Acceptance

- Generation works from another working directory with an explicit config path.
- Identical input produces byte-identical source and manifest output.
- Removed and renamed queries do not leave compilable stale Java behind.
- A compilation failure leaves the last successful generated set unchanged;
  output I/O failures are focused and covered as far as the filesystem permits.
- Packaged-CLI smoke tests, the external sample, `mvn spotless:check`, and
  `mvn test` pass.

### Milestone 19 — Schema From Migrations

#### Goal

Let a query group read the schema the application already maintains, so that
an existing PostgreSQL service loads without a hand-written snapshot and
without rewriting its DDL.

#### Work items

Reassessed on activation against the implementation, tests, external sample,
documentation, JSqlParser 5.3 and 5.4 run on the statements below, Flyway's
documentation and `MigrationVersion` source, and sqlc's `booktest` and `ondeck`
PostgreSQL schemas. Requirements 2–7 are unimplemented as assumed:
`sql[].schema` is one file, `DefaultSchemaParser` accepts only `CREATE TABLE`
and fails the whole schema on the first unknown type (`Unsupported SQL column
type: TIMESTAMPTZ`, `CHARACTER VARYING`), and the sample reads one
`schema.sql`. Both parser versions report `TIMESTAMPTZ`, `INT8`, `SERIAL8`,
`CHARACTER VARYING (20)`, and the other requirement 6 spellings verbatim, and
parse every requirement 3 `ALTER TABLE` form including `IF [NOT] EXISTS` (only
`SET DATA TYPE`, which is not required, fails), so requirements 2, 3, 5, and 6
do not depend on the upgrade. Corrections to the milestone's assumptions:

- An array column does not fail the schema, contrary to
  [Where sqlcj Stands](#where-sqlcj-stands): the parser reports `varchar[]` as
  type `varchar` with separate array dimensions, which `DefaultSchemaParser`
  ignores, so `tags varchar[]` is silently modeled as a `VARCHAR` `String`
  today. Requirement 5 must record any array column as unsupported.
- JSqlParser 5.4 (Maven Central, 2026-09-13) parses `CREATE TYPE ... AS ENUM`,
  `CREATE EXTENSION IF NOT EXISTS`, and `ALTER TYPE ... ADD VALUE`, which 5.3
  rejects or returns as an opaque unsupported statement. But requirement 1's
  "every existing test unchanged" cannot hold: 5.4 removed
  `net.sf.jsqlparser.parser.SimpleNode` (merged into `Node`), which
  `SqlParameterCompiler` and the `SqlParameterCompilerTest` fixture use, and
  with that mechanical fix six existing tests still fail against 5.4. Four
  assert the parser's diagnostic wording, which 5.4 changed (`Encountered:
  <ST_SEMICOLON> / ";", at line 4, column 1, in lexical state DEFAULT.` with
  sqlcj's appended location repeated, `after:` instead of `after prefix`) and
  which `docs/postgresql.md` documents. `SqlParserTest.shouldRejectDollarQuotedText`
  fails because 5.4 lexes `$$...$$` as one literal and leaves a `$1` inside it
  unbound, and `shouldPreserveParameterTextInsideBlockCommentWithNestedDelimiter`
  fails because 5.4 nests block comments as PostgreSQL does and treats that
  test's unterminated nested comment as running to the end of the query.
- JSqlParser 5.4 parses `COMMENT ON` only for `TABLE`, `COLUMN`, and `VIEW`;
  `COMMENT ON TYPE`, `INDEX`, `CONSTRAINT`, `FUNCTION`, `SCHEMA`, and
  `EXTENSION` are syntax errors (support exists on JSqlParser's main branch
  after 5.4 but is unreleased), and `ALTER INDEX` parses only as an opaque
  unsupported statement. `ondeck`'s `0002_venue.sql` contains
  `COMMENT ON TYPE status IS ...`, so "sqlc's `ondeck` schema loads" cannot be
  met with 5.4 without the pre-processing requirement 1 forbids. Every
  statement of `booktest` parses with 5.4, including its dollar-quoted
  `CREATE FUNCTION`. `ondeck`'s migration files are named `0001_city.sql` to
  `0003_add_column.sql`, so they load in the lexical order.
- The parser reports no per-statement position, but the token chain of its
  parse tree carries lines, so requirement 4's rejected-statement line can be
  derived without splitting SQL text.

Owner decisions, decided by the owner on 2026-09-27:

- **D1 (M19-W4):** the upgrade may change exactly the parser-node fixture and
  nine expectations: the six above and three that assert the same syntax
  diagnostic through the CLI and compiler,
  `GenerateCommandTest.shouldFailWithConciseDiagnosticForSchemaSyntaxError`,
  `GenerateCommandTest.shouldFailWithConciseDiagnosticForQuerySyntaxError`, and
  `SqlcjCompilerIntegrationTest.shouldReportTheMigrationFileThatFailsToParse`
  (amended from six by the owner on 2026-09-27 after a 5.4 run of the suite
  failed those nine). 5.4's dollar quoting and block-comment nesting
  are accepted because they match PostgreSQL: a `$N` inside `$$...$$` is text,
  like a `$N` inside a quoted string, and the nested-comment test's input is an
  unterminated comment that PostgreSQL itself rejects. Syntax diagnostics stay
  one line without class names or parser-internal token kinds and with one
  location, composed from the parser's unexpected token (`Encountered
  unexpected token: ";" at line 4, column 1` for a schema, without a location
  for a query) rather than from the parser's message text; a syntax error at
  the end of input reports `Encountered unexpected end of input`, with the same
  location rule. Lexical errors keep the parser's own wording. Requirement 1 is
  amended accordingly.
- **D2 (M19-W6, M19-W8):** requirement 4's `COMMENT ON` is limited to the
  `TABLE`, `COLUMN`, and `VIEW` targets 5.4 parses, and the `ondeck` acceptance
  is amended: its schema fails only on its `COMMENT ON TYPE` statement, with a
  located syntax diagnostic, and loads without that statement. A syntax error
  fails the whole schema, so `ondeck` as published does not load. sqlcj does
  not split or pre-process SQL to skip the statement, and the parser's
  unsupported-statement mode is not a substitute: it drops the statement's
  leading keywords and turns every syntax error into an opaque statement.
  Because an applied Flyway migration cannot be edited without breaking its
  checksum, this limitation blocks a user whose history contains another
  `COMMENT ON` target; Milestone 23 therefore rechecks for a JSqlParser release
  that parses those targets.
- **D3 (M19-W8; Milestones 20 and 21):** decided by the owner on 2026-09-28.
  sqlc's example files are not copied into the repository. An acceptance item
  that names sqlc's example schemas or queries is proven by sqlcj-owned
  fixtures, original SQL reproducing every construct of those files that bears
  on the item, together with a one-off probe of the published upstream files
  whose outcome is recorded in this roadmap. The same policy applies to later
  milestones whose acceptance names sqlc examples. Milestone 19's probe ran the
  files of sqlc commit `bdbe55db3ee08745827090d4aad1781bea2ee4ee` through sqlcj
  at `2652c19`: `booktest`'s `schema.sql` loads, recording `books.book_type` as
  `BOOK_TYPE` and `books.tags` as `VARCHAR[]`; the `ondeck` directory fails as
  `Invalid schema source <0002_venue.sql>: Encountered unexpected token:
  "TYPE" at line 2, column 12`, and without that statement it loads into
  `city` and the renamed `venue`, without the dropped `dropped` and with the
  added `created_at`, recording `status` as `STATUS`, `statuses` as
  `STATUS[]`, and `tags` as `TEXT[]`.
  Amended by the owner on 2026-10-04, effective for Milestone 21's M21-W7 and
  every later milestone: sqlc's example files are neither copied into the
  repository nor fetched, cloned, or run, in or outside it. An acceptance item
  that names sqlc's example schemas or queries is proven only by sqlcj-owned
  fixtures of original SQL reproducing the constructs that bear on the item,
  and the one-off upstream probe is no longer part of the policy. The probe
  outcomes already recorded, this milestone's above, Milestone 20's D2, and
  Milestone 21's activation reassessment, stay as historical record.

- **M19-W1 — Schema input from files and migration directories —
  Completed.** Implements requirement 2. `sql[].schema` accepts one path or a
  non-empty list of paths, each resolved against the configuration file's
  directory; a file contributes itself and a directory contributes its regular
  `.sql` files, non-recursively. Flyway versioned files
  (`V<version>__<description>.sql`, version parts separated by `.` or `_`,
  compared numerically part by part with missing parts as zero) come first in
  version order, then every other `.sql` file in lexical file-name order;
  undo files (`U<version>__...`) are ignored. Two versioned files with equal
  versions, or a directory that contributes no file, fail naming the
  directory. Files are parsed in order into one schema, and a schema failure
  names the file that contains it. The boundary is configuration, source
  loading, schema assembly, and the configuration documentation; the single
  file form and its diagnostics are unchanged, and no new statement or type is
  accepted. Acceptance evidence is configuration, source-loading, and compiler
  tests, `mvn spotless:check`, and `mvn test`.
  Review evidence: `SqlConfig.schema` is a list that also accepts a single
  YAML string, and the built CLI reports a missing, null, blank, numeric,
  boolean, or mapping scalar `schema` with the same message as before the
  change. `DefaultSourceLoader` expands a directory in place into its regular,
  case-sensitive `.sql` files, pre-sorts them by file name and stably sorts the
  versioned ones by `BigInteger` version parts; the loader test orders a file
  and a directory as `V1`, `V1_1`, `V2`, `V10`, `a_extra.sql`, `baseline.sql`
  while ignoring `U1__undo.sql`, `V3__upper.SQL`, `notes.txt`, and the
  subdirectory `nested.sql`. `SqlcjCompiler` parses each `SchemaFile`
  separately and concatenates the tables; a two-file directory generates a
  repository byte-identical to the one-file snapshot that also compiles, and a
  syntax error in `V2__orders.sql` names that file and writes no output. Run
  through the built CLI, `V01__a.sql` and `V1__b.sql`, a directory of only
  `readme.md` and a `sub.sql` subdirectory, and an unlistable directory fail
  with the documented one-line diagnostics, exit 1, and create no output
  directory. `mvn spotless:check` and `mvn test` passed (474 CLI and 25
  runtime tests, 0 skipped, including 18 PostgreSQL tests).
- **M19-W2 — PostgreSQL spellings of supported types — Completed.**
  Implements requirement 6: `TIMESTAMPTZ`, `TIMESTAMP WITHOUT TIME ZONE`,
  `INT2`/`INT4`/`INT8`, `SMALLSERIAL`/`SERIAL2`/`SERIAL4`/`SERIAL8` (never
  nullable, like `SERIAL`), `CHARACTER VARYING`, and `CHAR`/`CHARACTER` map to
  the existing Java types. "Every spelling" means the names and aliases
  PostgreSQL's data-type table documents for the supported types, which is
  this list; JSqlParser 5.3 reports each verbatim, with any type arguments,
  so the existing case-insensitive, argument-stripping match applies.
  `CHAR`/`CHARACTER` is a `String` character column like `VARCHAR`, read back
  blank-padded as PostgreSQL stores it. Grammar-only synonyms (`CHAR VARYING`,
  `DEC`, `NCHAR`, `BPCHAR`), quoted or schema-qualified type names, and the
  distinct `"char"` type stay unsupported. The boundary is the schema parser's
  type mapping, its tests, one PostgreSQL execution test, and the type
  documentation. Acceptance evidence is schema-parser tests, PostgreSQL
  execution of the new spellings, the type documentation,
  `mvn spotless:check`, and `mvn test`.
  Review evidence: `DefaultSchemaParser` maps `INT4`/`SERIAL4` to `INTEGER`,
  `INT8`/`SERIAL8` to `BIGINT`, `INT2`/`SMALLSERIAL`/`SERIAL2` to `SMALLINT`,
  `CHARACTER VARYING`/`CHAR`/`CHARACTER` to `VARCHAR`,
  `TIMESTAMP WITHOUT TIME ZONE` to `TIMESTAMP`, and `TIMESTAMPTZ` to
  `TIMESTAMP_WITH_TIME_ZONE`, and models every serial spelling non-null; the
  rejection message is unchanged. Parser tests cover each spelling in upper
  and lower case, with type arguments where allowed, the integer aliases'
  `NOT NULL`-only nullability, and the rejection of `JSONB`,
  `DOUBLE PRECISION`, and `"char"`. A PostgreSQL 16 table declared through the spellings round trips
  through generated code with the mapped Java types, `ab` in `CHAR(3)` read
  back as `ab `, `TIMESTAMPTZ` equal by instant, and the serial columns filled
  by their sequences. `docs/postgresql.md` lists the spellings. The test source
  also compiles under ECJ 3.46.0. `mvn spotless:check` and
  `mvn -Dsqlcj.test.requireDocker=true test` passed (531 CLI and 25 runtime
  tests, 0 skipped, including 76 schema-parser and 19 PostgreSQL tests).
- **M19-W3 — Unsupported column types recorded until used — Completed.**
  Implements requirement 5. A column of an unmapped type, including any array
  column, is recorded with its declared type instead of failing the schema; a
  query that reads, binds, or expands it (`*` or `RETURNING *`) fails naming the
  query, column, and type, while other queries over the table compile.
  The recorded type is the canonical spelling the type match already uses
  (upper case, arguments removed) followed by `[]` per array dimension, so
  `varchar(20)[]` is `VARCHAR[]`. A query reads such a column when it is a
  `SELECT` or `RETURNING` item, binds it when a placeholder takes its type (a
  comparison, `IN`, range, or pattern bound, an `INSERT` column, or an
  `UPDATE` assignment), and expands it through `*`, `qualifier.*`, or
  `RETURNING *`; a reference that neither returns nor types anything, such as
  `IS NULL` or a join `ON` equality, compiles. The boundary is the schema
  column model, the schema parser's type mapping, the analyzer's column and
  parameter typing, and the type documentation; statements, table
  constraints, and syntax errors fail as before. Acceptance evidence is schema
  and analyzer tests for a reading and a non-reading query, a compiler test of
  the query-naming diagnostic and of generated code that compiles beside an
  unsupported column, `mvn spotless:check`, and `mvn test`.
  Review evidence: `Column` gains a nullable `unsupportedType`, and its
  three-argument constructor keeps every mapped column equal to before.
  `DefaultSchemaParser` records `JSONB`, `DOUBLE PRECISION`, `"CHAR"`,
  `VARCHAR[]`, `INTEGER[][]`, `NUMERIC[]`, and `INT[]` (the canonical spelling
  of `int[]`) beside unchanged mapped columns, with `NOT NULL` parsed as
  before. `QueryAnalyzer` types every selected, expanded, returned, and bound
  column through one check, so over a `VARCHAR[]` column `tags` each of
  `SELECT tags`, `SELECT *`, `SELECT b.*`, `tags = $1`, `tags IN ($1)`,
  `tags LIKE $1`, an `INSERT` column, an `UPDATE` assignment, `RETURNING tags`,
  and `RETURNING *` fails with `Column 'tags' has unsupported type VARCHAR[]`;
  a probe also confirmed `BETWEEN`, `ILIKE`, `$1 = tags`, `o.*` in a join, and
  a `JSONB` comparison fail, while `IS NULL`, a join `ON` equality, a literal
  `IN` list, and `ORDER BY tags` compile. Queries that do not use the column,
  including `COUNT(*)`, analyze to models equal to those over a table without
  it. Through `SqlcjCompiler` the reading query fails as
  `Invalid query 'ListTags' in <file> at line 5: ...` and writes no output, and
  a repository generated beside a `VARCHAR(20)[]` and a `JSONB` column
  compiles. `docs/postgresql.md` documents the recording and failure rules.
  `mvn spotless:check` and `mvn test` passed (550 CLI and 25 runtime tests,
  0 skipped, including 19 PostgreSQL tests).
- **M19-W4 — JSqlParser 5.4 — Completed.** Implements requirement 1 and the
  `SimpleNode` API change, with the diagnostic and lexing expectations decided
  in D1. The boundary is the dependency version, the parser-node use and its
  fixture, syntax-diagnostic composition, the nine expectations of D1, and the
  documented schema diagnostic; no new statement is accepted. Acceptance
  evidence is the unchanged remainder of the suite, PostgreSQL tests,
  `mvn spotless:check`, and `mvn test`.
  Review evidence: the build uses JSqlParser 5.4, and no `SimpleNode` remains;
  `SqlParameterCompiler` and its test fixture use `Node`, with the span
  guards and parameter order unchanged. `SqlParseReason` composes a syntax
  reason from the parser's unexpected token, `Encountered unexpected token:
  "<image>"` with line breaks escaped or `Encountered unexpected end of input`,
  followed by one location for a schema and none for a query, and keeps the
  lexical-error wording. Exactly the fixture and D1's nine expectations
  changed, now asserting `Encountered unexpected token: ";" at line 4,
  column 1` (schema), `Encountered unexpected token: "AND"` (query), `after:`
  (lexical), a bound `$1` beside an unchanged `$1` in `$$...$$`, and `$8` and
  `$9` preserved in a terminated nested comment. New tests assert the
  end-of-input reason with and without a location and a one-line escaped
  multi-line literal. A probe of twelve malformed queries and eight schemas
  produced no reason containing a token kind, a lexical state, or a class
  name, and each named the token the parser's own message names.
  `docs/postgresql.md` shows the new schema diagnostic. `mvn spotless:check`
  and `mvn -Dsqlcj.test.requireDocker=true test` passed (553 CLI and 25
  runtime tests, 0 skipped, including 19 PostgreSQL tests).
- **M19-W5 — Ordered table DDL — Completed.** Implements requirement 3 across
  the ordered files of M19-W1: `CREATE TABLE`, `DROP TABLE`, and the listed
  `ALTER TABLE` forms with their `IF [NOT] EXISTS` variants update one schema
  model, and a reference to a missing table or column fails naming the file.
  Each statement applies to the schema left by the statements and files before
  it. `CREATE TABLE IF NOT EXISTS` and `ADD COLUMN IF NOT EXISTS` do nothing
  when the name exists; `DROP TABLE IF EXISTS`, `ALTER TABLE IF EXISTS`, and
  `DROP COLUMN IF EXISTS` do nothing when it is missing. Otherwise a missing
  table or column fails, and so does a statement that would give two tables, or
  two columns of one table, the same name, as PostgreSQL rejects it; a repeated
  `CREATE TABLE`, which today loads silently, therefore fails. Names match
  case-insensitively, as query analysis looks them up. An added column is
  appended and typed like a `CREATE TABLE` column; a renamed or retyped column
  keeps its position, and a retyped one keeps its nullability; recorded
  primary-key and unique constraints follow a renamed column and are removed
  with a dropped one. Every other `ALTER TABLE` action and `DROP` object is
  rejected as before; M19-W6 owns the ignored list and its diagnostics. The
  boundary is the schema parser, schema assembly in the compiler, and the
  schema documentation. Acceptance evidence is schema tests per form and per
  failure, a migration directory that renames, adds, drops, and alters columns
  generating the same compilable repository as the equivalent snapshot, a
  missing reference naming its migration file, `mvn spotless:check`, and
  `mvn test`.
  Review evidence: `SchemaParser.parse(Schema, String)` applies one source to
  the schema the earlier sources left, and `SqlcjCompiler` threads one schema
  through an entry's files in order; `parse(String)` applies to an empty
  schema. A probe against the built parser confirmed `ADD [COLUMN]`,
  `DROP [COLUMN]` (with `CASCADE`), `RENAME [COLUMN]`, `RENAME TO`,
  `ALTER [COLUMN] ... TYPE` with `USING` and `COLLATE` (position and
  nullability kept, `INET` recorded), `SET`/`DROP NOT NULL`, several actions of
  one statement, multi-name `DROP TABLE`, and every `IF [NOT] EXISTS` variant,
  with unique constraints following a rename and removed with a drop. `SET`/
  `DROP DEFAULT`, `SET STATISTICS`/`STORAGE`/`COMPRESSION`, `ADD`/`DROP`/
  `SET GENERATED` identity, `RESTART WITH`, `DROP EXPRESSION`,
  `ADD`/`DROP`/`RENAME CONSTRAINT`, `ADD PRIMARY KEY`, `ADD UNIQUE`, and a
  supported action beside an unsupported one each fail with exactly
  `Unsupported schema statement: Alter`, and `DROP INDEX`/`DROP VIEW` with
  `Unsupported schema statement: Drop`. Missing and repeated names fail with
  the four documented messages. A migration directory that renames, adds,
  drops, and alters columns generates a repository byte-identical to its
  snapshot's that compiles, and a missing table fails naming its migration
  file with no output written. `docs/postgresql.md` and
  `docs/configuration.md` document the forms and messages.
  `mvn spotless:check` and `mvn test` passed (580 CLI and 25 runtime tests,
  0 skipped, including 19 PostgreSQL tests).
- **M19-W6 — Ignored and rejected statements — Completed.** Implements
  requirement 4 as limited by D2; its M19-W4 dependency is completed. The
  documented list is accepted and leaves the schema model unchanged:
  `CREATE INDEX`, `ALTER INDEX`, and `DROP INDEX`; `COMMENT ON` `TABLE`,
  `COLUMN`, or `VIEW`; `CREATE EXTENSION`; `CREATE`, `ALTER`, and
  `DROP SEQUENCE`; `GRANT` and `REVOKE`; `CREATE FUNCTION` with a
  dollar-quoted body, `DROP FUNCTION`, `CREATE TRIGGER`, and `DROP TRIGGER`;
  the `ALTER TABLE` actions that add a `PRIMARY KEY`, `UNIQUE`,
  `FOREIGN KEY`, or `CHECK` constraint, `DROP CONSTRAINT`, and
  `RENAME CONSTRAINT`, alone or beside modeled actions; `INSERT`, `UPDATE`,
  and `DELETE`; and `CREATE TYPE ... AS ENUM`. An ignored statement is not
  resolved against the schema, while an `ALTER TABLE` still resolves its
  table. A function body that is not dollar-quoted is rejected, because the
  parser then captures the function through the end of the file. Every other
  statement, including every other `ALTER TABLE` action and `ALTER TYPE`,
  fails as `Unsupported schema statement: <kind> at line <n>`, with the
  statement kind named as today and the line of the statement's first token;
  missing-reference and syntax diagnostics are unchanged. The boundary is the
  schema parser, its tests, one compiler test, and the schema documentation.
  Acceptance evidence is schema tests per listed kind and per rejection, the
  line of a rejected statement after comments and a dollar-quoted body, a
  compiler test naming the migration file and line with no output written,
  `mvn spotless:check`, and `mvn test`.
  Review evidence: `DefaultSchemaParser` ignores one spelling of every listed
  statement, including lower-case `ALTER INDEX` (an opaque unsupported
  statement matched by its opening words) and
  `CREATE OR REPLACE FUNCTION ... $$ ... $$ LANGUAGE plpgsql`, without
  resolving it against the schema, and ignores the named and unnamed
  `PRIMARY KEY`, `UNIQUE`, and `FOREIGN KEY` actions, the named `CHECK`
  action, `DROP CONSTRAINT [IF EXISTS]`, and `RENAME CONSTRAINT` while still
  resolving the table and applying a modeled action beside them. A
  `CREATE FUNCTION` is ignored only when a part before its first `;` part is
  dollar-quoted, so a single-quoted body is rejected at its own line even
  when it captures a later `$$` function. The line is derived from the parse
  tree's token chain, so a rejected `CREATE VIEW` after comments containing
  `;` and a multi-line `$$` body reports line 15. Rejected kinds and lines
  cover `CreateView`, a composite `CreateType`, `AlterType`, `CreateDomain`,
  `CreateSchema`, `DROP VIEW` as `Drop`, `PlainSelect`, `SET DEFAULT` as
  `Alter`, `CreateFunction`, and `ALTER FUNCTION` as `UnsupportedStatement`.
  A probe of the built parser confirmed statements after a `$$` function are
  applied or rejected at their own lines, and a tagged `$body$` delimiter
  fails with the documented `Encountered unexpected token: "$body$" at line 1,
  column 74`. Through `SqlcjCompiler`, a `CREATE VIEW` after an ignored
  `CREATE INDEX` fails as `Invalid schema source <V2__views.sql>:
  Unsupported schema statement: CreateView at line 3` with no output written.
  `docs/postgresql.md` documents the ignored list, the three parser
  limitations, and the new diagnostic. `mvn spotless:check` and `mvn test`
  passed (631 CLI and 25 runtime tests, 0 skipped, including 19 PostgreSQL
  tests).
- **M19-W7 — Sample and quickstart from a migration directory —
  Completed.** Implements requirement 7; its M19-W1, M19-W5, and M19-W6
  dependencies are completed. The external sample and the quickstart replace
  their `sql/schema.sql` snapshot with a `sql/migrations` directory named by
  `sql[].schema`: a `V1` migration creates `authors` without `created_at` and
  `books`, and a `V2` migration adds `authors.created_at` with
  `ALTER TABLE ... ADD COLUMN` and creates an index on `books (author_id)`.
  The migrations describe the tables the snapshot did, so the generated
  repository, the sample application's behavior, and the documented output are
  unchanged. `scripts/verify-release.sh` generates from the directory, checks
  that the generated row carries the added column, and applies both migrations
  with `psql` in version order before running the sample. The boundary is the
  sample, the quickstart, and the release script; no compiler behavior,
  migration tool, or other document changes. Acceptance evidence is
  `scripts/verify-release.sh` passing against PostgreSQL 16, a generated
  repository byte-identical to the one the former snapshot generated, the
  quickstart, `mvn spotless:check`, and `mvn test`.
  Review evidence: the sample's `sqlcj.yaml` names `sql/migrations`, whose
  `V1__create_authors_and_books.sql` creates `authors (id, name, bio)` and
  `books`, and whose `V2__add_author_created_at_and_book_index.sql` adds
  `created_at TIMESTAMP` and creates `books_author_id_idx`; no sample file,
  quickstart text, or script line references `sql/schema.sql`. The packaged
  CLI generated the sample from the directory and from the former
  `schema.sql` taken from `HEAD`, and the two outputs are identical and carry
  `LocalDateTime createdAt`. `scripts/verify-release.sh` passed against
  `postgres:16-alpine`, applying `V1` then `V2` with `ON_ERROR_STOP`, and
  the sample printed the documented output; `\d authors` then listed `id`,
  `name`, `bio`, and `created_at` and `books_author_id_idx` existed. The
  quickstart's two migration listings and `sqlcj.yaml` match the sample's
  files, and its two `docker exec ... psql` commands applied both files
  against the same server. `mvn spotless:check` and `mvn test` passed (631
  CLI and 25 runtime tests, 0 skipped, including 19 PostgreSQL tests).
- **M19-W8 — sqlc schemas and migrated-schema execution — Completed.**
  Proves the milestone acceptance as amended by D2: `booktest` loads, `ondeck`
  fails only on its `COMMENT ON TYPE` statement and loads without it, enum and
  array columns are recorded as unsupported, and the migrated schema runs
  against PostgreSQL through generated code. The other acceptance items are
  proven by M19-W1 through M19-W7. The probe recorded in D3 confirmed the
  behavior already holds, so the item is acceptance evidence only: no
  production code or public documentation changes. Per D3, the evidence is
  sqlcj-owned inline fixtures. A single-file schema declares an enum type, an
  enum column, a `varchar[]` column, indexes, and a dollar-quoted function
  followed by another statement, and loads with the enum and array columns
  recorded as unsupported. A migration directory of non-Flyway file names
  creates tables with an enum, an enum-array, and a `text[]` column, states
  `COMMENT ON TYPE`, `TABLE`, and `COLUMN`, and later renames a table, drops a
  column, and adds one; its file fails only on the `COMMENT ON TYPE` statement
  with a located syntax diagnostic and loads once the test removes that
  statement, since sqlcj does not pre-process SQL. Acceptance evidence is
  schema-parser tests of both fixtures, a PostgreSQL test that applies the
  loadable migration files in order and executes generated code over the
  migrated tables from the same directory, `mvn spotless:check`, and
  `mvn -Dsqlcj.test.requireDocker=true test`.
  Review evidence: only `DefaultSchemaParserTest` and
  `PostgresIntegrationTest` changed; no production code, documentation, or
  test resource did. The single-file fixture, with an enum type, a
  `varchar[] NOT NULL DEFAULT '{}'` column, two indexes, and a `$$` function
  followed by a `CREATE INDEX`, parses to `shelves` and `records` with `state`
  recorded as `SHELF_STATE`, `genres` as `VARCHAR[]`, `released_on` as
  `TIMESTAMP_WITH_TIME_ZONE`, and their primary-key and unique constraints.
  After `001`, the second migration file fails with exactly
  `Encountered unexpected token: "TYPE" at line 14, column 12`, the line of
  its `COMMENT ON TYPE`; with that statement removed, the three files applied
  in order leave `region` and the renamed `stage` without `legacy_code` and
  with `opened_at` appended, recording `setting` as `STAGE_SETTING`,
  `past_settings` as `STAGE_SETTING[]`, and `keywords` as `TEXT[]`, after the
  `COMMENT ON TABLE` and `COLUMN` statements are ignored. Against
  PostgreSQL 16, `001_region.sql` to `003_reshape_stage.sql` written to a
  temporary directory are applied in file-name order, and a repository
  generated and compiled from that directory creates a region and reads a
  stage row as `Integer`, `String`, `String`, `String`, and `LocalDateTime`
  with the inserted values. `mvn spotless:check` and
  `mvn -Dsqlcj.test.requireDocker=true test` passed (635 CLI and 25 runtime
  tests, 0 skipped, including 159 schema-parser and 20 PostgreSQL tests).

#### Required

1. Upgrade JSqlParser from 5.3 to 5.4, which parses `CREATE TYPE ... AS ENUM`,
   `CREATE EXTENSION IF NOT EXISTS`, and `ALTER TYPE ... ADD VALUE`, with every
   existing test unchanged except the parser-node fixture and the nine
   expectations decided in D1. Do not split or pre-process SQL to work around
   either parser version.
2. `sql[].schema` accepts a file, a directory, or a list of files and
   directories, resolved relative to the configuration file as today. A
   directory contributes its `.sql` files, non-recursively, in one documented
   deterministic order that matches Flyway versioned migrations
   (`V1__`, `V1_1__`, `V2__`, `V10__` by version) and is lexical otherwise.
   Flyway undo (`U...__`) files are ignored.
3. Apply statements in order to one schema model. Model `CREATE TABLE`,
   `DROP TABLE`, and the `ALTER TABLE` forms `ADD COLUMN`, `DROP COLUMN`,
   `RENAME COLUMN`, `RENAME TO`, `ALTER COLUMN ... TYPE`, and
   `ALTER COLUMN ... SET/DROP NOT NULL`, including their `IF [NOT] EXISTS`
   variants. A statement that refers to a missing table or column fails.
4. Accept and ignore a documented list of statements that cannot change a
   table's columns, types, or nullability: index statements, `COMMENT ON`
   `TABLE`, `COLUMN`, or `VIEW`,
   `CREATE EXTENSION`, sequence statements, grants, functions and triggers,
   table-constraint `ALTER TABLE` forms, and data statements such as seed
   `INSERT`s. `CREATE TYPE ... AS ENUM` is accepted and ignored until
   Milestone 20 models it. Every other statement is rejected naming the file,
   line, and statement kind.
5. Record an unsupported column type instead of failing the schema. The type
   fails only when an analyzed query reads, binds, or expands that column, with
   a diagnostic naming the query, column, and type.
6. Accept every PostgreSQL spelling of the already supported types, including
   `TIMESTAMPTZ`, `TIMESTAMP WITHOUT TIME ZONE`, `INT2`/`INT4`/`INT8`,
   `SMALLSERIAL`/`SERIAL2`/`SERIAL4`/`SERIAL8`, `CHARACTER VARYING`, and
   `CHAR`/`CHARACTER`, mapped to the existing Java types.
7. The external sample and quickstart read a Flyway-style migration directory
   whose later migration adds an index and alters a table.

#### Out of scope

- executing, ordering against a database, or verifying migrations,
- Liquibase formats, Flyway Java migrations, placeholders, repeatable-migration
  semantics beyond the documented order, down migrations, and `pg_dump` output,
- recursive directory scanning or glob patterns,
- modeling views, enums (Milestone 20), non-default schema namespaces,
  inheritance, or partitions,
- multiple query files per group.

#### Acceptance

- sqlc's `booktest` PostgreSQL schema loads, and its `ondeck` schema fails
  only on its `COMMENT ON TYPE` statement and loads without it, with their enum
  and array columns recorded as unsupported until Milestone 20, proven as D3
  decides.
- A migration directory whose later files rename, add, drop, and alter columns
  produces the same generated repository as the equivalent single snapshot.
- A query that reads an unsupported-type column fails naming the query,
  column, and type, while a query over the same table that does not read it
  compiles.
- Focused schema, configuration, and compiler tests, PostgreSQL execution of
  the migrated schema, the external sample, `mvn spotless:check`, and
  `mvn test` pass.

### Milestone 20 — Shared Models and Everyday PostgreSQL Types

#### Goal

Give the column types that services commonly use a known Java type, and
generate each schema-derived Java type once per package so every repository
shares it.

#### Work items

Reassessed on activation on 2026-09-28 against the implementation at
`7ed5547`, its tests, the external sample and `scripts/verify-release.sh`, the
documentation, the sources of the PostgreSQL JDBC driver 42.7.13 the tests use,
and sqlc's `booktest` files at commit
`bdbe55db3ee08745827090d4aad1781bea2ee4ee`, as Milestone 19's D3 directs. Every
requirement is unimplemented as assumed. Facts that correct or shape the plan:

- Requirement 1: `CodeGenerator.generate` returns one file per entry, and each
  repository nests the `<TableName>Row` of every table whose full row it
  returns, built from that entry's schema; `JavaNames` rejects row types equal
  ignoring case only within one entry. `scripts/verify-release.sh` already
  generates the entries `Author` and `Library` over one migration directory,
  which today yields two unrelated `AuthorsRow` types. The requirement governs
  the record only: each repository keeps its private row mapper, so the shared
  record stays a plain record with no runtime or JDBC dependency. "Define the
  same table differently" is read as two entries that both return the full row
  of a table, named alike ignoring case, whose analyzed row columns differ in
  order, name, type, or nullability. An entry that never returns that full row
  generates no shared model and is not compared.
- Requirement 2: the driver reads `REAL`, `DOUBLE PRECISION`, `BYTEA`, and
  `TIME` as `Float`, `Double`, `byte[]`, and `LocalTime` through the generated
  `getObject(position, Class)` call and binds them through `setObject`, so they
  need only a type mapping. `JSON` and `JSONB` do not: the driver reports them as
  `Types.OTHER`, its `getObject(position, String.class)` throws for every type
  but `CHAR` and `VARCHAR`, and a `String` bound through `setObject` is sent as
  `varchar`, which PostgreSQL does not assign to a `json` or `jsonb` column.
  They need a runtime binding change and a different generated read, so they
  are a separate work item.
- Requirement 3: `CREATE TYPE ... AS ENUM` is ignored and `ALTER TYPE` rejected
  (M19-W6), the schema model holds no types, and `ColumnType` is a closed Java
  enum that cannot name a user-defined type, so an enum column needs a type
  representation carried through schema, analysis, and generation. The driver
  reports an enum column as `VARCHAR`, so its label reads as a `String`, but a
  label bound as `varchar` is rejected for an enum column or comparison; enums
  need the same binding change as `JSON`. `DROP TYPE` and `ALTER TYPE ...
  RENAME` stay rejected as enum removals and renames.
- Requirement 4: binding a list needs `Connection.createArrayOf` with the
  element's server type name, which the runtime's per-value `setObject` cannot
  do, and the driver's `getArray` decodes `date`, `time`, and `timestamptz`
  elements as `java.sql.Date`, `Time`, and `Timestamp`, which must become the
  mapped `java.time` types. This is the largest item.
- Requirement 5 is met by the items that introduce each binding.
  `setObject(position, value, Types.OTHER)` and `createArrayOf` are standard
  JDBC, so neither the runtime nor generated code needs the driver.
- `booktest`: `schema.sql` declares the enum `book_type ('FICTION',
  'NONFICTION')` and the columns `books.book_type book_type` and
  `books.tags varchar[]`, both `NOT NULL`. `GetBook`, `BooksByTitleYear`,
  `CreateBook`, `UpdateBook`, and `UpdateBookISBN` read or bind them.
  `BooksByTags` (`&&`) and `SayHello` (a set-returning function) are outside
  this milestone and belong to Milestone 21's accounting of sqlc's queries.

Owner decision proposed on 2026-09-28 and decided by the owner on 2026-09-29:

- **D1 (M20-W5):** accepted by the owner on 2026-09-29. Requirement 4's
  element types are limited to enums and the mapped scalar types other than
  `BYTEA`, `JSON`, and `JSONB`, whose arrays stay recorded as unsupported.
  `List<byte[]>` and arrays of JSON text are rare in services, and each adds an
  element encoding and tests that the everyday tag and id-list cases, and
  Milestone 21's `= ANY`, do not need. Requirement 4 is amended accordingly.

Owner decision proposed on 2026-09-29 and decided on 2026-09-29:

- **D2 (M20-W6):** decided on 2026-09-29 as proposed, by the orchestrator to
  whom the owner delegated the choice. The one-off probe
  Milestone 19's D3 requires ran sqlc's published `booktest` `schema.sql` and
  `query.sql` at commit `bdbe55db3ee08745827090d4aad1781bea2ee4ee` through the
  sqlcj CLI sources at `c3655a6`, compiled outside the repository. The schema
  loads. The query file fails first on `BooksByTitleYear` with `SQL
  placeholders [1, 2] are not the analyzed parameters [1]; a placeholder is in
  an unsupported location`: JSqlParser 5.4 parses the unqualified `year` of
  `year = $2` as a `DateUnitExpression`, not a column, so the predicate is not
  analyzed. `"year" = $2` and `books.year = $2` compile, `SELECT year` fails as
  `Unsupported SELECT expression: DateUnitExpression`, and `year` as an
  `INSERT` column or `UPDATE` target compiles. Without that query the file
  fails on `BooksByTags` (a placeholder in an unsupported location), then on
  `SayHello` (`Only table sources are supported.`). Without those three, the
  other seven queries generate the enum `BookType` (`FICTION`, `NONFICTION`),
  `BooksRow` with `BookType bookType`, `OffsetDateTime available`, and
  `List<String> tags`, and a repository whose `createBook` binds the enum label
  and a `varchar` array, and the output compiles against the runtime.
  Proposed: M20-W6's `booktest` fixtures reproduce `GetBook`, `CreateBook`,
  `UpdateBook`, and `UpdateBookISBN`, which read the full row and bind both the
  enum and the array column, and `BooksByTitleYear` moves to Milestone 21's
  accounting of sqlc's queries, recorded as unsupported for its unqualified
  `year` until a work item resolves a column named like a date unit. That
  resolution is query analysis, not a type mapping, and the milestone
  acceptance names only `booktest`'s enum and array columns, which the four
  queries cover. The rejected alternative was to add that analysis to M20-W6.
  M20-W6 is amended accordingly, and `BooksByTitleYear` is recorded as
  unsupported for its unqualified `year` in Milestone 21's accounting of sqlc's
  queries.

- **M20-W1 — Shared top-level row records — Completed.** Implements
  requirement 1. Every `<TableName>Row` becomes one top-level `public record`
  file in `java.package`, generated once per table whose full row any entry
  returns, and every repository that returns that row uses it; query-specific
  `<QueryName>Result` records and every row mapper stay nested in their
  repository. A row's name, components, component order, and types are
  unchanged. Two entries whose returned rows share a row type but whose
  analyzed row columns differ fail, and row types equal ignoring case are
  rejected across the package rather than within one entry; both fail before
  output is written, reported against the later entry with the existing query
  group diagnostic prefix. Row files are listed in the manifest and deleted as
  stale once no entry returns them. The boundary is Java generation, the
  compiler's generation step, the external sample, `scripts/verify-release.sh`,
  and the documentation of the generated API, naming, and diagnostics; there is
  no analysis, type-mapping, or runtime change. Acceptance evidence is
  generator and compiler tests, including two entries over one migration
  directory whose compiled repositories return one `AuthorsRow` class, the
  conflict and stale-row cases, PostgreSQL tests, `scripts/verify-release.sh`,
  `mvn spotless:check`, and `mvn test`.
  Review evidence: `JavaCodeGenerator` records each group's rows and
  `SqlcjCompiler` appends one `<TableName>Row.java` per recorded table after the
  repositories. The file is built by the former record renderer, imports only
  the JDK types of its components, and no repository nests a row record any
  more; each repository keeps its row mapper. A second group returning a
  recorded row with different analyzed columns, or a row type equal ignoring
  case to another table's, fails under the later entry's
  `Invalid query group` prefix, and nothing is written. Compiler tests compile
  two entries over one migration directory into `AuthorRepository` and
  `LibraryRepository`, whose `getAuthor` methods return the one top-level
  `dev.example.generated.AuthorsRow`. They also list that file in the manifest,
  delete it once no query returns the full row, and cover both cross-entry
  diagnostics. `scripts/verify-release.sh` passed against a throwaway
  PostgreSQL 16 container. It generated `AuthorRepository.java` and
  `AuthorsRow.java`, one `AuthorsRow` shared by `Author` and `Library`, and
  `AuthorsRow.java` beside `WriterRepository.java` after the rename, and the
  sample ran. `mvn spotless:check` and `mvn -Dsqlcj.test.requireDocker=true
  test` passed (643 CLI and 25 runtime tests, 0 skipped, including 20
  PostgreSQL tests).
- **M20-W2 — Floating-point, binary, and time types — Completed.** Implements
  requirement 2 for `REAL`/`FLOAT4` as `Float`, `DOUBLE PRECISION`/`FLOAT8` as
  `Double`, `BYTEA` as `byte[]`, and `TIME`/`TIME WITHOUT TIME ZONE` as
  `java.time.LocalTime`, with "spelling" defined as in M19-W2. `FLOAT` and
  `FLOAT(p)`, whose precision selects the type, `TIMETZ`, and
  `TIME WITH TIME ZONE` stay recorded as unsupported. No runtime change.
  Acceptance evidence is schema-parser tests per spelling, generated-source
  compilation, a PostgreSQL round trip of `null` and non-null values as
  parameters and result components, the type documentation,
  `mvn spotless:check`, and `mvn test`.
  Review evidence: `DefaultSchemaParser` maps `REAL`/`FLOAT4`,
  `DOUBLE PRECISION`/`FLOAT8`, `BYTEA`, and `TIME`/`TIME WITHOUT TIME ZONE` to
  the new `ColumnType` constants, which `DefaultTypeResolver` resolves to
  `Float`, `Double`, `byte[]`, and `LocalTime`; the generator imports
  `java.time.LocalTime` and reads each with `getObject(position, Type.class)`.
  No runtime or analyzer change. Schema-parser tests cover every spelling in
  upper and lower case plus `TIME(3)` and `TIME(3) WITHOUT TIME ZONE`, and
  record `FLOAT`, `float(24)`, `TIMETZ`, `time with time zone`, and `real[]` as
  unsupported. A generated repository with all four types as parameters and
  result components, including a query whose only parameter is `byte[]`,
  compiles without Docker. `PostgresIntegrationTest` writes a non-null and a
  null row over every in-scope spelling through generated code and reads both
  back with the expected component types. `docs/postgresql.md` documents the
  mappings and the unmapped spellings. `mvn spotless:check` and
  `mvn -Dsqlcj.test.requireDocker=true test` passed (674 CLI and 25 runtime
  tests, 0 skipped, including 21 PostgreSQL tests).
- **M20-W3 — JSON text and typed binding — Completed.** Implements
  requirement 2 for `JSON`/`JSONB` as a `String` holding the JSON text, and the
  part of requirement 5 it needs: the runtime binds a JSON value for PostgreSQL
  to type from its context, and generated code reads the text through an API the
  driver supports for these types. The runtime API change is coordinated with
  the generator and proven by compiled generated source; no JSON library is
  added, and sqlcj neither parses nor normalizes the text. `JSON` reads back the
  text as written and `JSONB` as PostgreSQL normalizes it. The equality
  predicate is proven on `JSONB` only, because PostgreSQL defines no `json =
  json` operator. Arrays of either type stay recorded as unsupported, and a
  placeholder index repeated across a JSON and a non-JSON column is not
  specially handled. Acceptance evidence is runtime and generator tests,
  schema-parser tests per spelling, a PostgreSQL round trip of `null` and
  non-null values as an `INSERT` value and a result component for both types
  and as an equality predicate for `JSONB`, the type and runtime
  documentation, `mvn spotless:check`, and `mvn test`.
  Review evidence: `DefaultSchemaParser` maps `JSON` and `JSONB` in any case to
  new `ColumnType` constants through the one type mapping shared by
  `CREATE TABLE`, `ADD COLUMN`, and `ALTER COLUMN TYPE`, and
  `DefaultTypeResolver` resolves both to `String`; `json[]` and `jsonb[]` stay
  recorded as `JSON[]` and `JSONB[]`. The new runtime record
  `dev.sqlcj.runtime.UntypedText(String value)` carries any text, and
  `JdbcQueryExecutor` binds it with `setObject(position, value, Types.OTHER)`
  and every other argument with `setObject(position, value)` as before. The
  generator renders `new dev.sqlcj.runtime.UntypedText(<name>)` at every
  binding position of a JSON placeholder index, keeps `VARCHAR` and `TEXT`
  arguments bare, reads JSON columns with `resultSet.getString(position)`, and
  adds no import; output for other types is unchanged. Runtime tests record
  `setObject` calls for a null and a non-null `UntypedText` beside an ordinary
  argument; generator tests cover a repeated JSON index and compile the
  generated source against the runtime.
  `PostgresIntegrationTest.shouldRoundTripJsonValues` writes and reads null and
  non-null `JSON` and `JSONB` through generated code, reads `JSON` back as
  written and `JSONB` as PostgreSQL normalizes it, finds a row by `JSONB`
  equality for reordered and respaced text, and returns no row for a null
  argument. `docs/postgresql.md` and `docs/queries.md` document the mapping,
  reads, and binding.
  `mvn spotless:check` (on a copy without the Spotless cache: 7 runtime and 68
  CLI files checked) and `mvn -Dsqlcj.test.requireDocker=true test` passed (27
  runtime and 687 CLI tests, 0 skipped, including 22 PostgreSQL tests).
- **M20-W4 — PostgreSQL enums — Completed.** Implements requirement 3 and
  its part of requirement 5. `CREATE TYPE ... AS ENUM` and `ALTER TYPE ...
  ADD VALUE` with its `IF NOT EXISTS`, `BEFORE`, and `AFTER` forms are applied
  in order to the schema; a column of a declared enum type maps to one
  top-level Java enum per enum used by any query of the package, whose
  constants follow documented naming rules and keep their exact labels, and
  which binds through M20-W3's mechanism. Two entries defining one used enum
  differently, and an enum name colliding with another generated type, fail
  before output is written. Every other `ALTER TYPE` action and `DROP TYPE`
  stay rejected, an array of an enum stays recorded as unsupported until
  M20-W5, and the sample and `booktest` fixtures belong to M20-W6. The runtime
  is unchanged, because `UntypedText` already binds a label for PostgreSQL to
  type from its context. The boundary is the schema model and parser,
  analysis, Java generation, the compiler's generation step, and the type,
  DDL, generated-API, and naming documentation. Acceptance evidence is schema,
  analyzer, generator, and compiler tests, a PostgreSQL round trip of `null`
  and non-null enum values as parameters and result components and of the
  label order `BEFORE` and `AFTER` leave, `mvn spotless:check`, and
  `mvn test`.
  Review evidence: `DefaultSchemaParser` adds the new `EnumType` to `Schema`
  on `CREATE TYPE ... AS ENUM` and inserts an `ADD VALUE` label at the end or
  directly before or after its neighbour. `IF NOT EXISTS` with an existing
  label is a no-op checked before the neighbour, and the four type and label
  diagnostics are reported. A non-array column whose type names a declared
  enum, matched case-insensitively, becomes `ColumnType.ENUM` with the enum's
  declared name through the shared column path of `CREATE TABLE`,
  `ADD COLUMN`, and `ALTER COLUMN TYPE`, and a rename or nullability change
  keeps it. `DROP TYPE`, every other `ALTER TYPE` action, composite
  `CREATE TYPE`, enum arrays, and undeclared types stay rejected or recorded as
  unsupported. `QueryAnalyzer` carries the enum name into columns and
  parameters and rejects a repeated `$N` across an enum and another type, or
  across two enums, naming each enum by its PostgreSQL name. `JavaCodeGenerator`
  generates one top-level enum per enum a query uses, with `label()` and
  `fromLabel`, binds `UntypedText` around the constant's label, and reads the
  label with `getString` and `fromLabel`. Two entries defining one used enum
  differently, enums equal ignoring case, an enum colliding with another
  generated type, and a label that cannot name a constant all fail before
  output is written; enum files are listed in the manifest and deleted once
  unused. Compiler tests compile two entries over one migration directory
  whose repositories use one `StageSetting`.
  `PostgresIntegrationTest.shouldRoundTripEnumValues` matches the generated
  labels to `pg_enum` order after `BEFORE`, `AFTER`, and `IF NOT EXISTS`, and
  round trips null and non-null values as `INSERT` values, as an equality
  predicate (a null argument matches no row), and as result components.
  `mvn spotless:check` (also on a copy without the Spotless cache: 7 runtime
  and 69 CLI files checked) and `mvn -Dsqlcj.test.requireDocker=true test`
  passed (27 runtime and 729 CLI tests, 0 skipped, including 23 PostgreSQL
  tests).
- **M20-W5 — One-dimensional arrays — Completed.** Implements requirement 4,
  as D1 decides, and its part of requirement 5: a column declared as a
  one-dimensional array, `type[]` or `type[n]` (PostgreSQL ignores the size),
  of an enum or of a mapped scalar type other than `BYTEA`, `JSON`, and `JSONB`
  maps to `java.util.List<T>` of the element's Java type for parameters and
  result components, and elements may be `null`. The runtime binds a list as a
  server array of the element type through `Connection.createArrayOf` and
  reads each element as a scalar of the element type is read, without
  depending on the driver. Declared multidimensional arrays and arrays of
  `BYTEA`, `JSON`, `JSONB`, or an unmapped element type stay recorded as
  unsupported, and no array operator, including Milestone 21's `= ANY`, is
  added. The boundary is the schema model and parser, analysis, Java
  generation, the runtime's argument and read API, and the type, generated-API,
  and runtime documentation; the sample and `booktest` fixtures belong to
  M20-W6. Acceptance evidence is schema, analyzer, generator, and runtime
  tests, generated-source compilation, a PostgreSQL round trip of `null`,
  empty, and non-empty lists, including a `null` element, for every allowed
  element type, `mvn spotless:check`, and `mvn test`.
  Review evidence: `DefaultSchemaParser` models a column of exactly one array
  dimension of a mapped type other than `BYTEA`, `JSON`, and `JSONB`, or of a
  declared enum, as `Column.array` of its element type through `CREATE TABLE`,
  `ADD COLUMN`, and `ALTER COLUMN TYPE`, keeps it through a rename and a
  nullability change, and records multidimensional, `BYTEA`, `JSON`, `JSONB`,
  and unmapped arrays as before. A `CHAR`/`CHARACTER` column also carries
  `Column.blankPadded`, which the analyzer passes into its parameters.
  `QueryAnalyzer` carries the array shape into selected, wildcard, and
  returning columns and into parameters, rejects a repeated `$N` across an
  array and its element and a `LIKE` pattern on an array column.
  `JavaCodeGenerator` renders `List<T>`, binds
  `new dev.sqlcj.runtime.SqlArray("<element>", p)` with PostgreSQL's element
  name, `bpchar` for a `CHAR`/`CHARACTER` element as the owner decided on
  2026-09-29 and `varchar` for the varying spellings, binds an enum array as
  `SqlArray.of("<enum>", p, E::label)`, reads with `SqlArray.getList`, and
  imports `java.util.List` into a row record with an array component; scalar
  `CHAR` generation is unchanged. The new runtime record
  `dev.sqlcj.runtime.SqlArray` is bound by `JdbcQueryExecutor` through
  `Connection.createArrayOf` and `setArray`, a null list through
  `setNull(position, Types.ARRAY)`, and read through the JDBC `Array` API
  alone. `PostgresIntegrationTest.shouldRoundTripArrayValues` round trips a
  `null`, an empty, and a non-empty list with a `null` element for all 14
  scalar element types, an enum, and `CHAR(3)`, reads `CHAR(3)` elements blank
  padded, and finds the row by a placeholder equality on the `CHAR(3)` array;
  with the `bpchar` branch disabled that query failed with
  `operator does not exist: character[] = character varying[]`.
  `docs/postgresql.md` and `docs/queries.md` document the mapping, binding,
  reads, and tests. `mvn spotless:check` (also on a copy without the Spotless
  cache: 9 runtime and 69 CLI files checked) and
  `mvn -Dsqlcj.test.requireDocker=true test` passed (32 runtime and 744 CLI
  tests, 0 skipped, including 24 PostgreSQL tests).
- **M20-W6 — Sample and `booktest` evidence — Completed.** Implements
  requirement 6 and proves the milestone acceptance: the external sample adds
  an enum, a `JSONB`, and an array column used by its application and checked
  by `scripts/verify-release.sh`, and sqlcj-owned fixtures reproducing
  `booktest`'s schema constructs and, as D2 decides, its queries `GetBook`,
  `CreateBook`, `UpdateBook`, and `UpdateBookISBN` generate typed Java and run
  against PostgreSQL. Per Milestone 19's D3, the one-off probe of the published
  `booktest` files is the one recorded in D2. The boundary is the sample, the
  release script, and test fixtures; there is no production, runtime, or
  public documentation change. Acceptance evidence is the sample and
  `scripts/verify-release.sh`, the fixtures in a PostgreSQL test, the probe
  record, `mvn spotless:check`, and `mvn test`.
  Review evidence: the sample's new migration
  `V3__add_book_format_tags_and_details.sql` declares the enum `book_format`
  and adds the nullable columns `books.format book_format`, `books.tags
  TEXT[]`, and `books.details JSONB`. Its new queries `CreateCatalogedBook`
  and `ListBooksByFormat` generate `BooksRow` with `BookFormat format`,
  `List<String> tags`, and `String details`, and the top-level enum
  `BookFormat`; the application inserts a cataloged book and reads it back by
  a `BookFormat` parameter, checking the enum, the tag list, and the `JSONB`
  text as PostgreSQL normalizes it. `scripts/verify-release.sh` drops
  `book_format` in its reset, applies `V3` after `V2`, and expects one shared
  `BookFormat.java` for the entries `Author` and `Library`, four generated
  sources and a four-file manifest after the `Writer` rename, four sources
  from the sample, and both new methods. It passed against a throwaway
  `postgres:16-alpine` container, listing `AuthorRepository.java`,
  `AuthorsRow.java`, `BookFormat.java`, and `BooksRow.java`, and the sample
  printed `cataloged book: 2 HARDCOVER [compilers, history] {"pages": 320}`;
  the reset and three migrations also reapplied cleanly over the populated
  database. `PostgresIntegrationTest` reproduces `booktest`'s constructs with
  original names (`SERIAL` keys, an inline reference, a unique defaulted
  `text`, an enum column with a label default, an integer `year`, a
  `timestamptz`, a `varchar[]` defaulting to `'{}'`, single- and two-column
  indexes, and a dollar-quoted `plpgsql` function followed by an index) and
  the four D2 query shapes.
  `shouldExecuteGeneratedCatalogQueriesOverBooktestSchemaConstructs`
  generates the enum `AlbumKind` with labels in `pg_enum` order and a full row
  of `Integer`, `Integer`, `String`, `AlbumKind`, `String`, `Integer`,
  `OffsetDateTime`, and `List` components, binds the seven-column insert,
  exposes `UpdateAlbumCatalogNo`'s parameters in placeholder order around the
  `$3` key, and round trips the enum, the tag list, and the timestamp through
  both updates. No production, runtime, or documentation file changed.
  `mvn spotless:check` and `mvn -Dsqlcj.test.requireDocker=true test` passed
  (32 runtime and 777 CLI tests, 0 skipped, including 25 PostgreSQL tests).

#### Required

1. Generate each `<TableName>Row` record as a top-level type in `java.package`,
   once per table, shared by every repository that returns that full row.
   Query-specific `<QueryName>Result` records stay nested in their repository.
   Two entries whose schemas define the same table differently, or whose model
   names collide, fail before output is written. Shared models are listed in
   the output manifest.
2. Map `REAL` to `Float`, `DOUBLE PRECISION` to `Double`, `BYTEA` to `byte[]`,
   `TIME` to `java.time.LocalTime`, and `JSON`/`JSONB` to `String` holding the
   JSON text. sqlcj adds no JSON library dependency.
3. Model `CREATE TYPE ... AS ENUM` and `ALTER TYPE ... ADD VALUE`, and generate
   one top-level Java enum per enum type used by a query, whose constants follow
   the documented naming rules and keep their exact PostgreSQL labels. A column
   of that type maps to the enum.
4. Map one-dimensional arrays of supported scalar or enum element types, other
   than `BYTEA`, `JSON`, and `JSONB` (D1), to `java.util.List<T>` for
   parameters and result components.
5. The runtime binds and reads enums, `JSON`/`JSONB`, and arrays correctly
   against PostgreSQL. Neither generated code nor the runtime depends on the
   PostgreSQL JDBC driver; any runtime API change is coordinated with the
   generator and verified with compiled generated source.
6. The external sample adds an enum, a `JSONB`, and an array column.

#### Out of scope

- type overrides, custom codecs, or mapping `JSONB` to a JSON library type,
- domains, composites, ranges, `INTERVAL`, `MONEY`, network, geometric, or
  `XML` types, and multidimensional arrays,
- primitive Java types, nullness annotations, or `Optional` components,
- enum renames or removals, and array operators other than Milestone 21's
  `= ANY`,
- structural deduplication of query-specific result records.

#### Acceptance

- Two repositories over one migration directory return the same top-level
  `AuthorsRow` and the same generated enum.
- Every new type round-trips `null` and non-null values as a parameter and as
  a result component against PostgreSQL through generated code.
- sqlc's `booktest` schema generates its enum and array columns as typed Java,
  proven as Milestone 19's D3 decides.
- Focused schema, analyzer, generator, runtime, and PostgreSQL tests,
  generated-source compilation, the external sample, `mvn spotless:check`, and
  `mvn test` pass.

### Milestone 21 — Everyday SQL Expressions

#### Goal

Let the common write and filter idioms of a CRUD service compile as written,
with one principle for types sqlcj cannot infer: the SQL states them with a
cast.

Carried from Milestone 20's D2 for reassessment on activation: sqlc's
`booktest` query `BooksByTitleYear` is recorded as unsupported, because
JSqlParser 5.4 parses its unqualified column `year` as a date unit rather than
a column.

#### Work items

Reassessed on activation on 2026-09-29 against the implementation at
`9ade6ca`, its tests, the external sample, `docs/queries.md`, JSqlParser 5.4
(the version in `pom.xml`) run on every form below, and sqlc's `authors`,
`booktest`, `jets`, and `ondeck` PostgreSQL query files at commit
`bdbe55db3ee08745827090d4aad1781bea2ee4ee`, as Milestone 19's D3 directs. All
six requirements are unimplemented as assumed, and 5.4 parses every form they
name. Facts that correct or shape the plan:

- Requirement 1: `QueryAnalyzer` rejects every named parameter as
  `Named parameter ':x' is not supported`, and `SqlParameterCompiler` compiles
  only `$N` tokens. 5.4 lexes `:name` as a `:` token followed by a name token
  whose kind depends on the word (`:limit`, `:user`, `:from`, `:type`, and
  `:year` are keyword tokens), and it also parses `:a.b`, `:"quoted"`, and
  `&name` as named parameters, so the accepted name form must be stated rather
  than taken from the parser. A bare named placeholder carries a parse-tree
  node; inside a cast only the cast does.
- Requirement 2: an `INSERT` value must be a placeholder (`INSERT values must be
  indexed placeholders.`) and an `UPDATE` assignment a single placeholder. 5.4
  parses `DEFAULT`, literals, `now()`, and `version + 1` in both.
- Requirement 3: `$1::text`, `:name::text`, `CAST($1 AS bigint)`, and
  `$1::bigint[]` each parse as one cast around the placeholder, which analysis
  never visits, so the query fails the placeholder accounting. "Wherever it
  appears" is read as anywhere inside an expression of a clause whose
  placeholders are analyzed: `WHERE`, `INSERT` values, `UPDATE` assignments, and
  requirement 5's `DO UPDATE` assignments. A placeholder in a projection,
  `ORDER BY`, `GROUP BY`, `HAVING`, or a join condition stays rejected. "A
  supported type" is read as any type a column may be declared with and mapped,
  including declared enums and one-dimensional arrays, so `booktest`'s
  `BooksByTags` (`tags && $1::varchar[]`) compiles through this requirement
  without an array-operator item.
- Requirement 4: `COUNT(*)` must be the only `SELECT` item and aliased, and every
  other function or cast projection fails as `Unsupported SELECT expression`.
  `GROUP BY` and `HAVING` are not analyzed and pass as written, which stays so.
- Requirement 5: `ON CONFLICT` is rejected only on a returning insert. On an
  `:exec` insert the clause is not analyzed, so `ON CONFLICT ON CONSTRAINT`, a
  conflict-target predicate, and a `DO UPDATE` that binds no placeholder compile
  today and reach the database unchecked; `docs/queries.md` lists this as not
  analyzed rather than supported. The requirement therefore also rejects the
  out-of-scope forms on `:exec`. 5.4 parses every in-scope form, with
  `EXCLUDED.name` as a column qualified by `EXCLUDED`. The requirement names a
  conflict target, so a target-less `ON CONFLICT DO NOTHING` is rejected.
- Requirement 6: 5.4 parses `id = ANY($1)` as an equality whose right side is a
  function named `ANY` with one argument, not as an `ANY` comparison
  expression, and the placeholder is unaccounted today. M20-W5 supplies the list
  binding, so the dependency on Milestone 20 is met, with the element types
  Milestone 20's D1 allows.
- Order: requirement 1 comes first because it establishes the placeholder form
  every later item accepts; landed later, it would reopen each of their
  locations. Requirement 3's optional filter needs requirement 1, and its
  `COALESCE(:bio::text, bio)` assignment needs requirement 2. Requirement 4's
  cast projection reuses requirement 3's cast-type rule, requirement 5 needs
  requirement 2 for `EXCLUDED` and non-binding assignments, and requirement 6
  depends only on requirement 1.
- sqlc examples: the published query files, run one query at a time through the
  sqlcj CLI packaged from `9ade6ca`, with `ondeck`'s schema loaded without its
  `COMMENT ON TYPE` statement as Milestone 19's D2 decides, compile 21 of 27
  queries: all four of `authors`, seven of `booktest`, `jets`' `ListPilots` and
  `DeletePilot`, and eight of `ondeck`. `ondeck`'s `CreateVenue` fails on
  `NOW()` in `VALUES` (requirement 2) and `booktest`'s `BooksByTags` on its cast
  placeholder (requirement 3). Four remain outside the milestone and are to be
  recorded as unsupported: `BooksByTitleYear`, carried from Milestone 20's D2,
  because 5.4 parses its unqualified `year` as a date unit (`books.year` and
  `"year"` compile); `SayHello`, which reads a set-returning function; and
  `jets`' `CountPilots` and `ondeck`'s `VenueCountByCity`, whose `COUNT(*)` has
  no alias, which Milestone 17 requires and requirement 4 keeps. Resolving a
  column named like a date unit is not required by this milestone and is not
  planned.

- **M21-W1 — Named parameters — Completed.** Implements requirement 1. A
  query may write `:name` placeholders, where the name directly follows the
  colon and is an unquoted identifier of ASCII letters, digits, and underscores
  that does not start with a digit; names are compared as written. Each
  distinct name is one parameter, ordered by its first textual occurrence and
  typed as a `$N` placeholder in the same location is, whose method parameter
  is named after the placeholder by the existing naming rules, including a
  `LIMIT` or `OFFSET` placeholder; every occurrence becomes a JDBC `?` bound to
  that parameter. A named placeholder is accepted wherever `$N` is accepted
  today. A query mixing `$N` and `:name`, a qualified, quoted, or `&name`
  placeholder, one name with conflicting types, and a named placeholder in an
  unanalyzed location are rejected with diagnostics that write the placeholder
  as `:name`. `$N` queries and their output are unchanged. The boundary is SQL
  parameter compilation, analysis, and the query documentation; the analyzed
  model keeps logical indexes, so generation and the runtime do not change.
  Acceptance evidence is parameter-compiler and analyzer tests, the existing
  named-placeholder rejection tests turned into acceptance or updated
  rejections, a compiled generated repository, PostgreSQL execution of named
  queries that repeat a name and order parameters by first occurrence,
  `mvn spotless:check`, and `mvn test`.
  Review evidence: `SqlParameterCompiler` replaces a `:` token directly
  followed by a name matching `[A-Za-z_][A-Za-z0-9_]*` with `?`, numbers each
  distinct name by first occurrence, leaves `: x` and placeholder text in
  literals and comments uncompiled, and reports every parse-tree `:a.b`,
  `:"x"`, or `&x` it did not compile. `QueryAnalyzer` resolves named
  placeholders at every `$N` location (comparison on either side, `IN`,
  `LIKE`/`ILIKE` pattern, `BETWEEN` bounds, `INSERT` values, `UPDATE`
  assignments, `LIMIT`, `OFFSET` in either order), names the parameter after
  the placeholder, and rejects mixed forms, conflicting types, unanalyzed
  locations, and `:a.b`, `:"x"`, and `&x` anywhere in the statement, including
  `ORDER BY` and function arguments, writing the placeholder as spelled. A
  standalone analyzer probe confirmed every acceptance query, and 21 `$N`-only
  queries produced models and messages identical to the pre-change analyzer.
  `mvn -o -q spotless:check` passed and
  `mvn -o -Dsqlcj.test.requireDocker=true test` ran 805 tests with 0 failures,
  0 errors, and 0 skipped, including 26 PostgreSQL integration tests.
- **M21-W2 — Non-binding write expressions — Completed.** Implements
  requirement 2. An `INSERT` value or `UPDATE` assignment that contains no
  placeholder, such as `DEFAULT`, a literal, `now()`, or `version + 1`, is
  accepted, contributes no parameter, and reaches the database as written. Its
  target column must exist; nothing binds it, so its type need not be mapped.
  Placeholders beside it keep their `$N` or `:name` numbering and textual
  binding order. A value that contains a placeholder without being one stays
  rejected until M21-W3, as a placeholder in an unanalyzed location. A
  multi-column `SET (a, b) = ...` assignment, multi-row `VALUES`, and
  `INSERT ... SELECT` stay rejected. The boundary is write analysis and the
  query documentation; SQL parameter compilation, generation, and the runtime
  do not change. Acceptance evidence is analyzer tests, PostgreSQL execution of
  each listed form, the write documentation, `mvn spotless:check`, and
  `mvn test`.
  Review evidence: `QueryAnalyzer` accepts an `INSERT` value or single-column
  `UPDATE` assignment that is not a placeholder after resolving only its target
  column, which must exist in the written table and need not have a mapped
  type; placeholders still resolve through the existing parameter path, so
  `VALUES ($2, now(), $1)` keeps parameters `[id, version]` with binding
  `[2, 1]`, named placeholders number by first occurrence, and assignment
  placeholders bind before `WHERE` placeholders. `$2 || 'x'` and
  `:n || name` fail the placeholder accounting, `SET (a, b) = ...` fails as
  `UPDATE assignments must set one column at a time.`, and multi-row `VALUES`
  stays rejected. `docs/queries.md` documents the accepted values and the
  updated rejections. `mvn -o -q spotless:check` passed and
  `mvn -o -Dsqlcj.test.requireDocker=true test` ran 820 `sqlcj-cli` tests with
  0 failures, 0 errors, and 0 skipped, including 27 PostgreSQL integration
  tests, one of which executes `DEFAULT`, a literal, `NULL`, `now()`, and
  `code + 1` in an `:exec` insert, a returning named insert, and an `:exec`
  update.
- **M21-W3 — Cast-typed placeholders — Completed.** Implements requirement 3
  as read above; depends on M21-W1 and M21-W2. A placeholder that is the direct
  operand of `::type` or `CAST(... AS type)` anywhere inside an analyzed clause
  takes the cast's type, resolved by the schema's column type mapping; an
  unmapped cast type fails naming the placeholder and type, and a repeated
  placeholder whose occurrences differ in type fails as today. A named
  placeholder keeps its own name. A cast `$N` that is the direct value of a
  location that names an uncast `$N` today — a compared column, the tested
  column of `IN`, `BETWEEN`, or `LIKE`, or an inserted or assigned column —
  keeps that column's name, so `name = $1::text` names `name` as `name = $1`
  does; every other cast `$N` is named `param<N>`, and colliding names take the
  existing numeric suffix. Decided by the owner on 2026-10-01. Reassessed on
  2026-10-01: 5.4 gives a `::` operand no parse-tree node, so `:"x"::int` and
  `&x::int` are accepted today with the placeholder left in the executable SQL;
  this item rejects a qualified, quoted, or `&name` cast operand anywhere in the
  statement, as M21-W1 rejects the bare forms. The boundary is SQL parameter
  compilation, analysis, a column type mapping shared with the schema parser,
  and the query documentation; generation and the runtime do not change.
  Acceptance evidence is analyzer tests of `(:name::text IS NULL OR name = :name)`,
  `'%' || :term::text || '%'`, `COALESCE(:bio::text, bio)`, the `CAST` form, an
  enum and an array cast, the naming rule, and the rejections, PostgreSQL
  execution of the optional filter with null and non-null arguments, the
  documentation, `mvn spotless:check`, and `mvn test`.
  Review evidence: `QueryAnalyzer` types a placeholder that is the direct
  operand of `::type` or `CAST(... AS type)` anywhere inside `WHERE`, `INSERT`
  values, and `UPDATE` assignments through `ColumnTypeMapping`, the mapping now
  shared with `DefaultSchemaParser`, including declared enums, `char(n)`, and
  one-dimensional arrays; an unmapped cast fails as
  `Placeholder $1 has unsupported cast type INTERVAL`. A cast `$N` keeps a
  column's name only where an uncast `$N` would be named after it, so
  `name = $1::text`, `name IN ($1::text, 'a')`, `name BETWEEN $1::text AND 'z'`,
  `name LIKE $1::text`, and `name ILIKE $1::text` name `name`, while
  `name SIMILAR TO $1::text`, `name NOT LIKE $1::text`,
  `name LIKE BINARY $1::text`, `name LIKE $1::text ESCAPE '!'`, and
  `tags && $1::varchar[]` name `param1` with the cast's type and binding `[1]`;
  `name NOT LIKE :p::text` names `p`. The uncast `LIKE` restrictions keep their
  exact messages. A cast in an unanalyzed location such as
  `ORDER BY $2::int` and the `&x`, `:a.b`, and `:"x"` cast operands are
  rejected, and `SqlParameterCompiler` reports uncompiled cast operands. `mvn -o -q spotless:check` passed and
  `mvn -o -Dsqlcj.test.requireDocker=true test` ran 854 `sqlcj-cli` tests with
  0 failures, 0 errors, and 0 skipped, including 28 PostgreSQL integration
  tests, one of which executes `(:name::text IS NULL OR name = :name)` with
  null and non-null arguments.
- **M21-W4 — Grouped and computed projections — Completed.** Implements
  requirement 4; depends on M21-W3. An aliased `COUNT(*)` beside direct columns
  is a non-null `BIGINT` component in its position, and
  `<expression>::type AS alias` is a nullable component of the cast type. An
  unaliased count or cast, any other projected expression, and a placeholder in
  a projection stay rejected. Acceptance evidence is analyzer and generator
  tests, PostgreSQL execution of a grouped count and a cast `SUM`, the
  documentation, `mvn spotless:check`, and `mvn test`.
  Reassessed on 2026-10-03 against `e110322`: `QueryAnalyzer.resolveColumns`
  rejects a count beside any other item as
  `COUNT(*) must be the only SELECT item.` and every cast projection as
  `Unsupported SELECT expression: CastExpression`. 5.4 parses
  `SUM(amount)::numeric AS total` and `CAST(SUM(amount) AS numeric) AS total`
  as one `CastExpression` with the alias on the item, so, as M21-W3 reads
  requirement 3, both spellings are the cast and take the type
  `ColumnTypeMapping` maps, including declared enums and one-dimensional
  arrays; an unmapped cast type fails naming the alias and the type. The cast
  operand is not analyzed and reaches the database as written, as an M21-W2
  write value does, so PostgreSQL rather than sqlcj checks its column
  references; a placeholder inside it, including a cast placeholder such as
  `$1::int AS x`, stays rejected by the placeholder accounting. Removing the
  sole-item rule also lets a count stand beside a wildcard or a cast. `GROUP BY`
  and `HAVING` stay unanalyzed, and `RETURNING` is unchanged. The generator
  already reads every result column by position as its mapped type,
  including enums and arrays, so the boundary is `SELECT` projection analysis
  and the query documentation; generation and the runtime do not change, and
  the generator evidence is compiled generated records.
  Review evidence: `QueryAnalyzer.resolveColumns` no longer applies the
  sole-item rule, so `SELECT name, COUNT(*) AS n FROM users GROUP BY name`
  resolves `[name VARCHAR nullable, n BIGINT non-null]` with no row table, a
  count written first keeps its position, and a grouped count keeps its
  `WHERE` parameter and binding. `SUM(id)::numeric AS total`,
  `CAST(SUM(id) AS numeric) AS total`, and `SUM(id)::numeric total` each
  resolve a nullable `DECIMAL` column `total`; an enum cast resolves `ENUM`
  with its enum type and `::text[]` an array column. An unaliased cast fails as
  `A cast projection requires a result alias, such as SUM(amount)::numeric AS total.`,
  `id::interval AS i` as `Result column 'i' has unsupported cast type INTERVAL`,
  `$1::int AS x` and `:n::int AS x` by the placeholder accounting, and an
  unaliased count and `lower(name) AS n` with their unchanged messages.
  PostgreSQL executes a generated `:many` grouped count with `Boolean` and
  `Long` components and a `:one` `SUM(balance)::numeric AS total` whose
  `BigDecimal` component is the sum and `null` when no row matches.
  `mvn -o -q spotless:check` passed and
  `mvn -o -Dsqlcj.test.requireDocker=true test` ran 868 `sqlcj-cli` tests with
  0 failures, 0 errors, and 0 skipped, including 30 PostgreSQL integration
  tests.
- **M21-W5 — Upsert — Completed.** Implements requirement 5; depends on
  M21-W2.
  `ON CONFLICT (columns) DO NOTHING` and `ON CONFLICT (columns) DO UPDATE SET`
  assignments of `EXCLUDED.column`, a placeholder, or a non-binding expression
  are accepted on `:exec` and returning inserts. Target, assigned, and
  `EXCLUDED` columns resolve against the inserted table, and a `DO UPDATE`
  placeholder is typed from its assigned column, or its cast, and bound after
  the `VALUES` placeholders in textual order. Every other conflict form is
  rejected on every insert, as read above. Acceptance evidence is analyzer
  tests, PostgreSQL execution of both actions with and without `RETURNING`
  over new and conflicting rows, the documentation, `mvn spotless:check`, and
  `mvn test`.
  Reassessed on 2026-10-03 against `36d4028`: a returning insert rejects every
  conflict clause as `INSERT ... ON CONFLICT is not supported.`, while an
  `:exec` insert never reads it, so a target-less, `ON CONSTRAINT`, or
  predicated conflict, an unknown target column, and an unknown `EXCLUDED`
  column compile, and a `DO UPDATE` placeholder fails the placeholder
  accounting. 5.4 represents the target as index elements, each a possibly
  quoted column name or an expression with an optional collation or operator
  class, plus a target predicate or a constraint name, and the action as
  `DO NOTHING` or `DO UPDATE` with `SET` assignments and an optional `WHERE`;
  `EXCLUDED.name` is a column qualified by `EXCLUDED`. Every other conflict
  form therefore also covers an expression, collated, or operator-class target
  element and `DO UPDATE ... WHERE`. A `DO UPDATE` assignment is analyzed as an
  `UPDATE` assignment is: one column at a time, a placeholder typed and named
  after its column, a cast placeholder anywhere inside it typed by its cast,
  and any other value reaching the database as written. Only a value that is
  exactly `EXCLUDED.column` has that column resolved, as M21-W2 leaves the
  column references of a non-binding expression to PostgreSQL, which also
  checks that the target matches a unique index. The boundary is insert
  analysis and the query documentation; SQL parameter compilation,
  generation, and the runtime do not change.
  Review evidence: a returning insert no longer rejects every conflict clause,
  and `QueryAnalyzer.analyzeInsert` resolves the conflict clause after the
  `VALUES` row on `:exec` and returning inserts alike. On the write schema,
  `ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, active = $3,
  version = users.version + 1, updated_at = now()` resolves
  `[id BIGINT, name VARCHAR, active BOOLEAN]` with binding `[1, 2, 3]` and the
  clause kept as written, on `:exec` and with `RETURNING id` on `:one`; the
  named form with `name = :name, bio = COALESCE(:bio::text, bio)` resolves
  `[id, name, bio]` with binding `[1, 2, 2, 3]`; `DO NOTHING` keeps the
  `VALUES` parameters, a quoted target column resolves, and `RETURNING *` on
  `:optional` resolves row table `users`. An absent target, `ON CONSTRAINT`, and
  an expression, collated, or operator-class element fail as
  `ON CONFLICT requires a target of column names, such as ON CONFLICT (id).`, a
  target predicate and `DO UPDATE ... WHERE` with their own messages, and
  `SET (name, active) = ...` as an `UPDATE` assignment, each on `:exec` and on a
  returning insert; `version = version + $3` fails the placeholder accounting;
  and an unknown target, assigned, or `EXCLUDED` column fails as
  `Column not found in table users: missing`. PostgreSQL executes generated
  `DO NOTHING` and `DO UPDATE` upserts, each with and without `RETURNING`, over
  a new and then a conflicting row, including an empty `Optional` from
  `DO NOTHING ... RETURNING` on conflict. `mvn -o -q spotless:check` passed and
  `mvn -o -Dsqlcj.test.requireDocker=true test` ran 886 `sqlcj-cli` tests with
  0 failures, 0 errors, and 0 skipped, including 31 PostgreSQL integration
  tests, and 32 `sqlcj-runtime` tests with 0 failures, 0 errors, and 0
  skipped.
- **M21-W6 — Id lists — Completed.** Implements requirement 6.
  `column = ANY(p)`, where `p` is one placeholder, binds a `List<T>` of the
  column's element type through M20-W5's array binding, for a non-array column
  of an enum or a mapped scalar type other than `BYTEA`, `JSON`, and `JSONB`.
  `ANY` over an array column or any other operand, and other operators with
  `ANY`, stay rejected. Acceptance evidence is analyzer and generator tests,
  PostgreSQL execution with empty, one-element, and several-element lists, the
  documentation, `mvn spotless:check`, and `mvn test`.
  Reassessed on 2026-10-03 against `ce5416f`: 5.4 parses `id = ANY($1)`,
  `id = any($1)`, and `id = ANY(:ids)` as an `EqualsTo` whose right side is a
  function with the single-part name `ANY`, as written, and one placeholder
  argument, which no clause accounts, so each fails as
  `SQL placeholders [1] are not the analyzed parameters []`. A cast argument,
  `ANY($1::bigint[])`, `ANY(CAST($1 AS bigint[]))`, or `ANY(:ids::bigint[])`,
  already compiles through M21-W3 as a function-argument cast typed by its cast
  and named `param1` or after its name; so does a scalar cast such as
  `ANY($1::bigint)`, which PostgreSQL rejects when executed. `<> ANY($1)`,
  `= SOME($1)`, `= ALL($1)`, `"ANY"($1)`, `pg_catalog.any($1)`,
  `ANY(ARRAY[$1, $2])`, and `ANY($1) = id`, which PostgreSQL does not accept,
  fail the same accounting; `= ANY('{1,2}')` and `= ANY(SELECT ...)` bind no
  placeholder and pass unanalyzed. M20-W5 already generates `List<T>` and an
  `SqlArray` binding, `bpchar` for a `CHAR` element and `SqlArray.of` for an
  enum, for any parameter marked as an array of the column's type, enum, and
  blank padding, and binds a null list as SQL `NULL`; the generator has no
  element name for `BYTEA`, `JSON`, or `JSONB`, which the array rule of
  `ColumnTypeMapping` already excludes. The boundary is therefore `WHERE`
  analysis of `SELECT`, `UPDATE`, and `DELETE` and the query documentation;
  SQL parameter compilation, generation, and the runtime do not change. The
  uncast placeholder is named after the compared column, as `id = $1` names
  `id`, and a named placeholder keeps its name. By M21-W3's naming rule, a cast
  that is the direct argument of `ANY` in this shape now keeps the column's
  name too, so `id = ANY($1::bigint[])` names `id` rather than `param1`, while
  its cast still states its type; decided by the owner on 2026-10-03. A null
  and an empty list match no row.
  Review evidence: `QueryAnalyzer.resolveParameterComparison` binds the single
  placeholder argument of an unquoted, unqualified `ANY` on the right of `=`
  against a direct column as one list parameter of that column's type, enum
  type, and blank padding, and `ColumnTypeMapping.isArrayElementType` is shared
  rather than duplicated. `id = ANY($1)`, `id = any($1)`, and
  `u.id = ANY($1)` resolve `[id BIGINT array]` with binding `[1]` and
  `id = ANY(?)` as executable SQL; `id = ANY(:ids) OR parent_id = ANY(:ids)`
  resolves one `ids` with binding `[1, 1]`; an enum column carries its enum
  type and a blank-padded column `blankPadded`; `DELETE ... WHERE id = ANY($1)`
  and `UPDATE users SET name = $1 WHERE id = ANY($2)` resolve `[id]` and
  `[name, id]` with bindings `[1]` and `[1, 2]`. `ANY($1::bigint[])` and its
  `CAST` form name `id`, `ANY(:ids::bigint[])` names `ids`, and
  `id <> ANY($1::bigint[])` and `tags && $1::varchar[]` still name `param1`.
  Array, `BYTEA`, `JSON`, and `JSONB` columns fail with the `= ANY` message,
  and an unmapped column first with its unsupported-type message;
  `id <> ANY($1)`, `= SOME($1)`, `= ALL($1)`, `ANY($1) = id`, `"ANY"($1)`,
  `pg_catalog.any($1)`, `ANY($1, $2)`, `ANY(ARRAY[$1, $2])`,
  `ANY(DISTINCT $1)`, `ANY($1 ORDER BY id)`, and `NOT (id = ANY($1))` still fail
  the placeholder accounting; `= ANY('{1,2}')` and `= ANY(SELECT ...)` still
  pass unanalyzed; and `id = ANY(:ids) OR id = :ids` fails as
  `Placeholder :ids has conflicting types: List<Long> and Long`. A generated
  repository compiles with `List<Long> id` bound as
  `new dev.sqlcj.runtime.SqlArray("int8", id)`, and PostgreSQL returns no row
  for a null and an empty id list, one row for one id, the matching rows for
  several, and the matching rows of an enum-column list. `mvn spotless:check`
  passed and `mvn -o -Dsqlcj.test.requireDocker=true test` ran 912
  `sqlcj-cli` tests with 0 failures, 0 errors, and 0 skipped, including 33
  PostgreSQL integration tests, and 32 `sqlcj-runtime` tests with 0 failures,
  0 errors, and 0 skipped.
- **M21-W7 — Sample and sqlcj-owned fixture evidence — Completed.** Proves
  the milestone acceptance as amended on 2026-10-04; depends on M21-W1 through
  M21-W6. The external sample adds an `updated_at` column and queries for a
  named-parameter optional filter, an `updated_at = now()` update, a grouped
  count, an upsert, and an id-list read, used by its application and checked
  by `scripts/verify-release.sh`. Per Milestone 19's D3 as amended on
  2026-10-04, sqlcj-owned PostgreSQL fixtures of original SQL reproduce the two
  constructs of sqlc's example queries that this milestone makes compile, as
  recorded in the activation reassessment above: `now()` among `VALUES`
  placeholders of a returning insert, and a cast array placeholder compared
  with `&&` in a query over a `LEFT JOIN`. Nothing is fetched, cloned, copied,
  or run from the sqlc repository. The constructs that stay outside the
  milestone are recorded as unsupported in that reassessment with their
  reasons and need no new test. There is no production change. Acceptance
  evidence is the sample and `scripts/verify-release.sh`, the fixtures in a
  PostgreSQL test, `mvn spotless:check`, and `mvn test`.
  Reassessed on 2026-10-04 against `7087c48`: M21-W1 through M21-W6 are merged
  (#55 through #60) with their analyzer, compiler, and PostgreSQL tests and
  query documentation, and the sample's migrations stop at `V3`, with no
  `updated_at` column and none of the five query forms. The column is added to
  `authors` by a new `V4` migration, as a nullable `TIMESTAMP` like
  `created_at`, so the shared `AuthorsRow` gains it and each new query result
  is a nested record or that row, and `scripts/verify-release.sh` keeps its
  four generated sources and manifest. The sample's upsert targets
  `authors (id)`, the only unique column, and is exercised over an existing
  author; its insert path is already proven by M21-W5's PostgreSQL test. The
  two fixture constructs extend the existing migration-directory test, with an
  insert into `stage` that writes `now()` between placeholders and binds an
  enum, an enum array, and a `text[]` value before `RETURNING id`, and the
  existing enum-and-array catalog test, with a read of unqualified columns
  over `albums LEFT JOIN studios` filtered by `tags && $1::varchar[]`. The
  unsupported constructs are an unqualified column named like a date unit, a
  function as the `FROM` source, and an unaliased `COUNT(*)`; the last already
  has analyzer tests of its diagnostic, and a test pinning either of the other
  two failures would freeze a limitation rather than prove a requirement.
  Review evidence: a `V4` migration adds the nullable `updated_at TIMESTAMP`
  to `authors`, so the shared `AuthorsRow` gains `LocalDateTime updatedAt`, and
  the sample adds `FilterAuthors`, `RenameAuthor`, `CountBooksByAuthor`,
  `UpsertAuthor`, and `ListAuthorsByIds`. The generated repository binds
  `filterAuthors(String name)` as `Arrays.asList(name, name)` for
  `(?::text IS NULL OR name = ?)`, `renameAuthor(String name, Long id)` and
  `upsertAuthor(Long id, String name, String bio)` with `now()` left in the
  SQL, `listAuthorsByIds(List<Long> ids)` as one `SqlArray("int8", ids)`, and
  `countBooksByAuthor()` into `CountBooksByAuthorResult(Long authorId,
  Long books)`. The application checks a null and a named filter, two ids and
  an empty list, the grouped count, a rename that sets `updated_at`, and an
  upsert over the existing author, and `scripts/verify-release.sh` applies
  `V4`, checks the five methods and `LocalDateTime updatedAt`, and keeps its
  four-source and manifest checks. In `PostgresIntegrationTest`,
  `ListAlbumsByTags` reads unqualified columns over `albums LEFT JOIN studios`
  with one `List` parameter for `tags && $1::varchar[]`, returning the album
  with its studio name for an overlapping list and nothing otherwise, and
  `CreateStage` writes `NOW()` among `VALUES` placeholders, binding
  `String, String, String, StageSetting, List, List` and returning the id, with
  the stored enum, enum array, `text[]`, and timestamp read back.
  `scripts/verify-release.sh` against a throwaway `postgres:16-alpine` exited 0
  with `sqlcj sample verification passed`; the two extended PostgreSQL tests
  passed; `mvn spotless:check` passed; and
  `mvn -Dsqlcj.test.requireDocker=true test` ran 912 `sqlcj-cli` tests and 32
  `sqlcj-runtime` tests with 0 failures, 0 errors, and 0 skipped.

#### Required

Implement these as separate reviewed work items so one does not silently widen
another:

1. Named parameters: a query may use `:name` placeholders instead of `$N`, but
   not both. Each distinct name is one method parameter named after it, in
   order of first textual occurrence, bound at every occurrence. Existing `$N`
   behavior is unchanged.
2. Non-binding write expressions: an `INSERT` value or `UPDATE` assignment that
   binds no placeholder, such as `DEFAULT`, a literal, `now()`, or
   `version + 1`, is accepted and reaches the database as written.
3. Cast-typed placeholders: a placeholder written as `$1::type`, `:name::type`,
   or `CAST(... AS type)` for a supported type is typed by the cast wherever it
   appears. This makes `(:name::text IS NULL OR name = :name)`,
   `'%' || :term::text || '%'`, and `COALESCE(:bio::text, bio)` compile. A
   placeholder without a cast in an unanalyzed location stays rejected.
4. Grouped and computed projections: an aliased `COUNT(*)` may appear beside
   direct columns, and a projection written as `<expression>::type AS alias`
   produces a nullable component of the cast type, so
   `SELECT city, COUNT(*) AS venues ... GROUP BY city` and
   `SUM(amount)::numeric AS total` compile. Other expressions stay rejected.
5. Upsert: `INSERT ... ON CONFLICT (columns) DO NOTHING` and
   `ON CONFLICT (columns) DO UPDATE SET column = EXCLUDED.column`, a
   placeholder, or a non-binding expression, with or without `RETURNING`.
6. Id lists: `column = ANY($N)` binds a `List<T>` of the column's type, the
   PostgreSQL alternative to dynamic `IN` expansion.

Every item must fail clearly for a nearby unsupported form rather than passing
an untyped placeholder or inventing a generic `Object` type.

#### Out of scope

- named-parameter macros, nullable-parameter markers, or `@name` syntax,
- type inference for expressions without a cast, other than the listed
  `COUNT(*)`,
- subqueries, `EXISTS`, CTEs, window functions, and set operations,
- `ON CONFLICT ON CONSTRAINT`, conflict-target predicates, `MERGE`, multi-row
  or `SELECT`-sourced inserts,
- dynamic SQL, dynamic sorting, or generated query variants.

#### Acceptance

- The external sample adds a named-parameter optional filter, an
  `updated_at = now()` update, a grouped count, an upsert, and an id-list read.
- The constructs of sqlc's `authors`, `booktest`, `ondeck`, and `jets`
  PostgreSQL example queries that this milestone makes compile run against
  PostgreSQL in sqlcj-owned fixtures, and the constructs that stay outside it
  are recorded in the milestone's work items as unsupported with their
  reasons, as Milestone 19's D3, amended by the owner on 2026-10-04, decides.
  Nothing is fetched from or run against the sqlc repository.
- All new parameters have one known Java type, and every accepted placeholder
  is bound exactly once per textual occurrence.
- Focused analyzer and generator tests, generated-source compilation,
  PostgreSQL execution, `mvn spotless:check`, and `mvn test` pass.

### Milestone 22 — Real Migration Histories Load

#### Goal

Load an ordinary service's migration history, or a `pg_dump --schema-only`
snapshot, unchanged, and never model a table, column, or type silently wrong.

Before its schema work, the milestone applies D-A, because every later
milestone's code must build for Java 17.

#### Work items

Activated on 2026-10-09 at the owner's request. Reassessed against the
implementation at `8c8637e`, its tests, the CI workflows, the external sample,
the documentation, the dependency JARs the build resolves, and JSqlParser 5.4
run outside the repository on every form named below. Every requirement is
unimplemented or only partly implemented, as assumed. Facts that correct or
shape the plan:

- Requirement 1: the root `pom.xml` sets release 21 twice, as
  `maven.compiler.release` and as the compiler plugin's `<release>`; the
  external sample and the quickstart's POM set 21; and CI runs `mvn test` and
  `scripts/verify-release.sh` on Java 21 only. A source search finds the
  runtime's two record patterns in `JdbcQueryExecutor.bindParameters`, ten
  `List.getFirst()` calls in the CLI's main sources, and 86 `getFirst`/`getLast`
  calls in nine CLI test classes, which must also compile for the suite to run
  on Java 17. The search did not reproduce the two further library calls and
  the pattern that the 2026-10-05 compile counted, so `javac --release 17` is
  authoritative. Generated code and the sample use no Java 21 construct. Every
  dependency is Java 17 bytecode or older (Jackson 3.2.1 and JUnit 6.1.2 are
  Java 17), and Spotless 3.10.2 supports its Eclipse 4.40 formatter on Java 17,
  whose JDT core 3.46.0 requires JavaSE-17, so `spotless:check` in the
  `validate` phase runs on Java 17.
- Requirements 2, 3, and 8: `DefaultSchemaParser` ignores the enumerated M19-W6
  list and rejects every other statement and `ALTER TABLE` action as
  `Unsupported schema statement: <parser class> at line <n>`, such as `Alter`,
  `CreateView`, `Drop`, or `UnsupportedStatement`. A `CREATE TABLE` `EXCLUDE`
  constraint parses and is rejected. A composite `CREATE TYPE`, which the
  probe's second row requires to load, is rejected; ignoring it leaves a column
  of that type recorded as unmapped.
- Requirement 4: 5.4 parses `ALTER TYPE ... RENAME TO` and `RENAME VALUE`, which
  are rejected today. A column names its enum by type name, so a renamed enum's
  columns must follow it. 5.4 cannot parse `DROP TYPE`, so it is never seen;
  the replace-an-enum variant that drops the old type before renaming the new
  one onto its name therefore needs a `RENAME TO` or `CREATE TYPE` onto a
  modeled enum's name to replace that enum instead of failing as
  `Type already exists` does today.
- Requirement 5: for `(LIKE t ...)` 5.4 reports the source table and no column
  definitions, so the table is modeled with none of `t`'s columns, and it
  reports `(LIKE t, extra int)` and `(extra int, LIKE t)` alike. `PARTITION OF`,
  `AS SELECT`, and `OF type` report no column list, which reaches the column
  loop as a `NullPointerException`. `INHERITS (p)` is reported only as a table
  option, so the child is modeled without `p`'s columns, a further silent
  mis-model that the requirement's rejection covers.
- Requirement 6: the schema parser and the analyzer's table lookup both drop the
  schema qualifier, so `reporting.users` and `public.users` are both `users`.
  `pg_dump` qualifies every user table, type, and column type with `public.`,
  so a `public.`-qualified column type must resolve to the modeled enum. 5.4
  reports `ALTER TABLE ... SET SCHEMA` as an unspecific action that carries its
  text, as it does `OWNER TO`, `VALIDATE CONSTRAINT`, and trigger switches.
- Requirement 7 and D-B: 5.4's token manager, run alone over a file, reports
  separators outside quoted strings, quoted identifiers, nested block comments,
  and untagged `$$` bodies, which it lexes as one token. It reports a tagged
  delimiter such as `$body$` as a token of its own and separators inside that
  body, and it mis-lexes an `E'...\'...'` escape string. A lexical error,
  including the first character of a psql meta-command line such as
  `\restrict`, ends the token stream, so meta-command lines are skipped before
  lexing and a tagged body must be kept whole by pairing its delimiters.
- Requirement 9: JSqlParser 5.4 is still the latest release (Maven Central
  metadata last updated 2026-09-13; nothing newer on 2026-10-09), so no upgrade
  is possible. 5.4 fails on all three named forms and on four `ALTER TABLE`
  forms the requirement does not name:
  `ALTER COLUMN ... ADD GENERATED ... AS IDENTITY (SEQUENCE NAME ...)`, which
  `pg_dump` writes for every identity column, and `ADD CONSTRAINT` with
  `EXCLUDE`, with `DEFERRABLE INITIALLY DEFERRED`, or as `UNIQUE USING INDEX`.
  It also cannot read `DO`, `DROP TYPE`, `DROP POLICY`, `DROP DOMAIN`,
  `COMMENT ON` targets other than `TABLE`, `COLUMN`, and `VIEW`, `SET ... TO`
  with a list, `VACUUM`, `LOCK`, `BEGIN`, `COPY`, `CREATE RULE`, or
  `CREATE EVENT TRIGGER`, all of which requirement 7 ignores. Filing the
  upstream reports publishes content and is the owner's action.

D-F, proposed here on 2026-10-09, was decided the same day as recommended and
is recorded under [Owner Decisions](#owner-decisions); requirement 7 is amended
accordingly. Filing the upstream reports of requirement 9 stays the owner's
action; M22-W9 prepares them.

Decided by the architect on 2026-10-09, under the rule that pre-release
decisions are revisable by evidence:

- **Implementation order:** M22-W1, W2, W3, W4, W7, W5, W6, W9, then W8,
  revised the same day at the owner's request to take W2 first; the original
  order put W3 before W2. A probe of JSqlParser 5.4 on every second-row form
  and on further table, type, function, and session statements found that only
  a `CREATE FUNCTION` or `CREATE PROCEDURE` whose body is not dollar-quoted
  captures later statements when the whole file is parsed; every other parsed
  statement ends at its separator, and the token-chain lines of parsed
  statements are already exact. W2 therefore keeps rejecting such a function or
  procedure, as today, and W3 lifts that rejection once each statement is
  parsed alone. W2 fixes the SQL-term diagnostics and the table-or-type
  classification of an opening that W3 to W7 reuse.
  W7 follows W4, whose action classification its `SET SCHEMA` joins, and
  precedes W5 and W6, so their new type and table lookups resolve names in one
  namespace from the start. W9 precedes W8, so the acceptance runs on the
  parser version the milestone closes with. Revised on 2026-10-10 at the
  owner's request to take W5 before W7, giving W1, W2, W3, W4, W5, W7, W6, W9,
  then W8; see the `ALTER TYPE` decision below for the interim rule that keeps
  W5 free of silent mis-models until W7 lands. Revised again on 2026-10-10 at
  the owner's request to take W6 before W7, giving W1, W2, W3, W4, W5, W6, W7,
  W9, then W8; see the `LIKE` and `PARTITION OF` decision below for the interim
  rule that keeps W6 free of silent mis-models until W7 lands. Revised on
  2026-10-10 at the owner's request to take W8 before W9, giving W1 to W7, W8,
  then W9: Maven Central still lists JSqlParser 5.4 as the latest release
  (metadata last updated 2026-09-13, rechecked 2026-10-10), so W8 runs on the
  parser version W9 can close with unless a release appears first. W9 stays the
  milestone's last item and makes the closing recheck; if it adopts a newer
  release, the full suite it runs includes W8's reference-service test, which
  re-proves the acceptance on that version.
- **`SET DATA TYPE` stays rejected.** Requirement 7, D-F, and requirement 9
  keep the unreadable `ALTER COLUMN ... SET DATA TYPE` rejected, which the
  first acceptance item, read literally, contradicts. The reference service's
  history changes a column type with `ALTER COLUMN ... TYPE`, its unsupported
  catalogue records `SET DATA TYPE` with the diagnostic sqlcj reports, and the
  acceptance item is amended accordingly.
- **Probe inputs are its schema inputs.** The acceptance's "no probe input
  produces an internal error" covers the probe's schema inputs. Its query
  inputs that raise one, `INSERT ... SELECT` and `UNION ALL`, are Milestone
  23's requirement 6.
- **Opaque statements.** A statement the parser returns only as an opaque
  unsupported statement is treated as unreadable, so an opaque table or type
  statement is never ignored silently (M22-W2).
- **`ALTER TYPE` actions (M22-W5).** `OWNER TO` and the attribute actions of
  composite types are ignored without resolving the type, and so is
  `RENAME TO` of a type sqlcj does not model; `ADD VALUE` and `RENAME VALUE`
  still require a modeled enum. `SET SCHEMA` of a modeled enum is rejected,
  because its columns keep the type in a namespace sqlcj does not model. A
  `RENAME TO` or `CREATE TYPE` onto a modeled enum's name replaces that enum,
  because the `DROP TYPE` that freed the name is never seen, so
  `Type already exists` no longer fails. Refined on 2026-10-10: `SET SCHEMA`
  of a type sqlcj does not model is ignored, like `RENAME TO` of one. Until
  M22-W7, a type name is the parser's text with delimiters removed, as
  `CREATE TYPE` and `ADD VALUE` resolve it today, so `public.mood` and `mood`
  are different names; a `RENAME TO` or `SET SCHEMA` whose name contains a
  period and matches no modeled enum therefore fails as `Type not found`
  instead of being ignored, because it may target a modeled enum. M22-W7
  replaces that interim rule with its namespace rule.
- **`LIKE` and `PARTITION OF` sources (M22-W6).** Decided on 2026-10-09 to copy
  a `LIKE` only when it is the only column source of its element list, counted
  with the D-B lexer, because 5.4 was taken to report `(LIKE t, extra int)`,
  `(extra int, LIKE t)`, and `(LIKE a, LIKE b)` without their order or the
  second source. Revised on 2026-10-10: a recheck found that 5.4 reports every
  element of the list in written order, each `LIKE` with its own source table,
  so the columns are modeled in that order, as PostgreSQL creates them, each
  `LIKE` copying its source's columns at its position, and a repeated column
  name fails as `Column already exists`; the D-B lexer is not needed. A
  `LIKE`'s `INCLUDING` and `EXCLUDING` options cannot change a copied column's
  type or nullability and are ignored, and neither `LIKE` nor `PARTITION OF`
  copies the source's recorded constraints, which no generated code reads.
  A `LIKE` copy is independent of its source afterwards, as in PostgreSQL. A
  partition is not: PostgreSQL applies every column change of a partitioned
  table to its partitions, so a partition copied once would silently miss a
  column its parent gains later. A partition created by `PARTITION OF`, or
  attached by `ATTACH PARTITION` when its table is modeled, therefore stays
  linked to its parent: each modeled column action on the parent (`ADD`,
  `DROP`, and `RENAME COLUMN`, a new type, `SET` and `DROP NOT NULL`) applies
  to its partitions, recursively; renaming the parent keeps the link;
  `DROP TABLE` of the parent removes its partitions, as PostgreSQL does; and
  `DETACH PARTITION` ends the link. This revises M22-W4's ignoring of
  `ATTACH` and `DETACH PARTITION`, which stays for a table sqlcj does not
  model. Until M22-W7, a table W6 resolves, the source of `LIKE` or
  `PARTITION OF` or the partition of `ATTACH` or `DETACH PARTITION`, resolves
  by its unqualified name, as every table lookup does today, so `public.t` is
  `t`; such a name qualified with any other schema fails as `Table not found`
  instead of resolving to a modeled `t`, because the namespace rule leaves
  that table unmodeled. M22-W7 replaces that interim rule with its namespace
  rule.
- **One namespace ignores `search_path` (M22-W7).** Unqualified names resolve
  in `public` whatever `SET search_path` states, which the documentation
  states; `pg_dump` qualifies every name it writes.
- **Reference-service fixtures (M22-W8).** Its migrations, snapshot, and
  queries live under `sqlcj-cli/src/test/resources/reference-service/`, beside
  the tests that use them, until Milestone 27 makes the service a runnable
  project.
- **Statement splitting (M22-W3).** A token that begins with a tagged
  delimiter `$tag$` opens a body that ends at the next occurrence of that
  delimiter in the source, PostgreSQL's rule, and lexing resumes after it; an
  unterminated body fails the file with its location. Only a rejected
  statement is reparsed at its file position for its diagnostic. An
  `ALTER TABLE` that D-F ignores is not resolved against the schema, like
  every ignored statement.

- **M22-W1 — Java 17 baseline — Completed.** Implements requirement 1 and
  D-A. Every module compiles with `--release 17`; the runtime's record patterns
  and every Java 21 library call in main and test sources are replaced without
  changing behavior; the external sample and the quickstart compile at release
  17; CI runs `mvn test` and `scripts/verify-release.sh` on Java 17 and Java
  21; and the README, the quickstart, the development guide, and the release
  script state Java 17 or later. The boundary is the build configuration, those
  source replacements, the sample and quickstart POMs, the test workflow, and
  those requirement statements; generated code, the runtime API, diagnostics,
  and schema behavior do not change. Acceptance evidence is `mvn test` passing
  on JDK 17 and on JDK 21, runtime and CLI class files of major version 61, an
  unchanged test count and unchanged generator expectations,
  `mvn spotless:check`, and `scripts/verify-release.sh` on both JDKs where
  PostgreSQL is reachable.
  Review evidence: the root POM, the external sample, and the quickstart POM
  set release 17; the runtime's record patterns became type patterns with
  record accessors, `getFirst`/`getLast` became index access in main and test
  sources, `SourceVersion.RELEASE_21` became `RELEASE_17` (its keyword answers
  are identical for the probed words), and the parameter compiler's
  unconditional `instanceof` became a null check with the same message. The
  test diff changes no string literal or expectation. The test workflow runs
  on a Java 17 and 21 matrix. Reviewer runs: `mvn spotless:check` passed;
  `mvn -Dsqlcj.test.requireDocker=true clean test` passed with 32 + 912 = 944
  tests on JDK 17 and on JDK 21; every runtime and CLI class file is major
  version 61; `scripts/verify-release.sh` passed on JDK 17 against a
  disposable PostgreSQL 16.
- **M22-W2 — One rule for ignored statements, in SQL terms — Completed.**
  Implements requirements 2 and 8. Every parsed statement other than `CREATE`,
  `ALTER`, or `DROP TABLE` and `CREATE` or `ALTER TYPE` is ignored without being
  resolved against the schema, replacing the enumerated list, and a
  `CREATE TYPE` that is not an enum is ignored; an opaque statement that begins
  as table or type DDL is handled as unreadable and rejected at its line. A
  table opening is `CREATE`, `ALTER`, or `DROP`, then any of PostgreSQL's table
  modifiers (`GLOBAL`, `LOCAL`, `TEMP`, `TEMPORARY`, `UNLOGGED`, `FOREIGN`),
  then `TABLE`; a type opening is `CREATE` or `ALTER` then `TYPE`. Until M22-W3,
  a `CREATE FUNCTION` or `CREATE PROCEDURE` whose body is not dollar-quoted
  stays rejected, because parsed with the whole file it captures every later
  statement. Every schema diagnostic names the statement or action in SQL
  terms, never a parser class; a rejected `ALTER TABLE` or `ALTER TYPE` action
  names its statement, and M22-W4 and M22-W5 name the action. The boundary is
  statement classification, schema diagnostics, and the schema documentation,
  including that DDL inside `DO` blocks and function bodies and columns removed
  by `DROP TYPE ... CASCADE` are not seen; `ALTER TABLE` actions are M22-W4's
  and `ALTER TYPE` actions M22-W5's. Acceptance evidence is schema-parser tests
  for each parsed statement kind of the probe's second row and for a rejected
  statement's message, a compiler test naming the file and line,
  `mvn spotless:check`, and `mvn test`.
  Review evidence: `DefaultSchemaParser.apply` models only `CREATE`, `ALTER`,
  and `DROP TABLE`, `CREATE TYPE ... AS ENUM`, and `ALTER TYPE ... ADD VALUE`
  and ignores every other parsed statement, including views, materialized
  views, schemas, session, role, policy, domain, and non-enum `CREATE TYPE`
  statements, without resolving it; it rejects other `ALTER TYPE` actions, a
  function or procedure without a dollar-quoted body, and an opaque statement
  opening as table or type DDL with the SQL-term messages
  `Unsupported ALTER TABLE action`, `Unsupported ALTER TYPE action`,
  `CREATE FUNCTION`/`CREATE PROCEDURE without a dollar-quoted body`, and the
  upper-cased opening such as `ALTER FOREIGN TABLE`, each at its line; no
  schema diagnostic builds a message from a parser class. A reviewer probe
  against the built parser confirmed that an ignored opaque statement ends at
  its separator, so a following `CREATE TABLE` is still modeled and a later
  rejection keeps its line. Reviewer runs: `mvn spotless:check` passed; the
  focused `DefaultSchemaParserTest` and `SqlcjCompilerIntegrationTest` run
  passed with 298 tests; `mvn test` passed with 32 + 939 tests, including the
  33 `PostgresIntegrationTest` tests.
- **M22-W3 — Statement boundaries from the parser's lexer — Completed.**
  Implements requirement 7 with D-B and D-F. A line whose first non-blank
  character is a backslash, a psql meta-command such as `\restrict`, is
  blanked before lexing, keeping every line number. A schema file is split at
  the separators JSqlParser's own token manager reports, with a tagged
  dollar-quoted body kept whole, and each statement is parsed alone with its
  own line. A statement the parser cannot read, reports only as opaque text,
  or reads as several statements is ignored unless it opens as table or type
  DDL, as M22-W2 classifies an opening; such a statement is rejected with the
  parser's reason at its line and column in the file, or with M22-W2's message
  when the parser states none, except an `ALTER TABLE` whose every top-level
  action D-F names, which is ignored without resolving its table. M22-W2's
  rejection of a function or procedure body that is not dollar-quoted is
  lifted. A lexical error, or an unterminated tagged body, still fails the
  file with its location. The boundary is schema-source splitting,
  per-statement parsing and lines, D-F, and the schema documentation; query
  parsing and the handling of parsed statements are unchanged. Acceptance
  evidence is schema tests for every form of the probe's third row and the
  unreadable forms requirement 9 lists, statements around an unreadable one
  applied and a later rejection at its line, DDL inside a tagged body not
  modeled, a skipped `\restrict` line, every D-F form ignored, `SET DATA TYPE`
  rejected at its file line and column, `mvn spotless:check`, and `mvn test`.
  Review evidence: `SchemaSourceSplitter` blanks meta-command lines keeping
  line breaks, cuts statements at the token manager's separators, and resumes
  lexing after the next occurrence of a tagged delimiter, glued or not;
  `DefaultSchemaParser` parses each statement alone, applies a single parsed
  statement as before, ignores an unreadable statement that does not open as
  table or type DDL and a D-F `ALTER TABLE` without resolving its table, and
  reparses only a rejected statement at its file position. The function and
  procedure body rejection is removed, and the existing syntax, end-of-input,
  and lexical-error expectations are unchanged in the test diff. Reviewer
  probes confirmed file columns with tabs, `\r\n` and `\r` line breaks, a
  meta-command line mid-file, an empty and a glued tagged body, a `$tag$`
  inside a string or comment, and linear load time (8,000 and 16,000 lines in
  about 0.6 and 1.0 s). Reviewer runs: `mvn spotless:check` passed; `mvn test`
  passed with 32 + 983 tests, including the 33 `PostgresIntegrationTest`
  tests.
- **M22-W4 — `ALTER TABLE` actions and table constraints — Completed.**
  Implements requirement 3. Every parsed `ALTER TABLE` action that cannot
  change a column's existence, name, type, or nullability is ignored,
  including `SET`/`DROP DEFAULT`, `SET STATISTICS` and `STORAGE`, identity
  changes, `OWNER TO`, row-level-security and trigger switches,
  `VALIDATE CONSTRAINT`, partition attachment, and adding or dropping any
  constraint; a `CREATE TABLE` accepts a table constraint of any kind,
  including `EXCLUDE`. An action that would change columns in a way sqlcj does
  not model, or that sqlcj does not recognize, is rejected naming the file,
  line, and action in SQL terms. The boundary is action classification and
  table-constraint parsing; the M19-W5 modeled actions are unchanged, and
  `SET SCHEMA` is M22-W7's. Acceptance evidence is schema-parser tests per
  ignored and rejected action, alone and beside a modeled action,
  `mvn spotless:check`, and `mvn test`.
  Refined by the architect on 2026-10-10 against JSqlParser 5.4 and a
  PostgreSQL 16 `pg_dump`: the ignored set is every PostgreSQL 16 action 5.4
  parses that cannot change a column, which adds `SET COMPRESSION`,
  `DROP EXPRESSION`, rule switches, `REPLICA IDENTITY`, `CLUSTER ON`,
  `SET WITHOUT CLUSTER`, `SET WITHOUT OIDS`, storage parameters,
  `SET TABLESPACE`, `SET LOGGED`/`UNLOGGED`, `SET ACCESS METHOD`,
  `[NO] INHERIT`, `[NOT] OF`, and `DETACH PARTITION`. As for constraint
  actions today, an ignored action resolves its table but not its column. 5.4
  reports an action it has no form for as unspecific text that runs to the end
  of the statement, so that text is split into actions at top-level commas
  with the D-B lexer and every one must be ignored; a modeled action written
  after such an action is rejected. A rejection reads
  `Unsupported ALTER TABLE action: <action> at line <n>`, quoting the action's
  SQL. Unreadable forms, such as `ALTER COLUMN ... SET (...)` or
  `ALTER CONSTRAINT`, stay under D-F and requirement 9.
  Review evidence: `DefaultSchemaParser` returns the table unchanged for
  `SET`/`DROP DEFAULT`, for `ALTER COLUMN` entries that are all identity
  changes, `SET STATISTICS`, `STORAGE`, `COMPRESSION`, or `DROP EXPRESSION`,
  and for the row-level-security and partition operations, without resolving
  the column; it lexes unspecific text with `SchemaSourceSplitter.tokens`,
  splits it at top-level commas, and accepts it only when every action begins
  with one of the listed table-level words, otherwise rejecting the first
  other action, so `OWNER TO app, ADD COLUMN x int` and `SET SCHEMA` are
  rejected with the quoted action and the statement's line. An ignored
  parsed-form action written after such text, such as
  `OWNER TO app, ENABLE ROW LEVEL SECURITY`, is likewise rejected rather than
  ignored; written first, it is ignored. `CREATE TABLE` ignores a named or
  unnamed `EXCLUDE`. The test diff converts the three old rejection tests and
  leaves the D-F, unreadable-statement, and `ALTER TYPE` expectations
  unchanged. Reviewer probes against the built parser confirmed the ignored
  forms beside `ADD COLUMN` in either parsed order, `ALTER` without `COLUMN`,
  a missing column under `SET DEFAULT` and `DROP IDENTITY`, and
  `ATTACH PARTITION ... DEFAULT` and `DETACH PARTITION ... CONCURRENTLY`.
  Reviewer runs: `mvn spotless:check` passed on an uncached copy of the
  working tree; `mvn test` passed with 32 + 1041 tests, including the 33
  `PostgresIntegrationTest` tests.
- **M22-W5 — Enum renames — Completed.** Implements requirement 4.
  `ALTER TYPE ... RENAME TO` renames a modeled enum and the columns of that
  type follow it; `ALTER TYPE ... RENAME VALUE` renames a label in its
  position. A `RENAME TO` or `CREATE TYPE` onto a modeled enum's name replaces
  that enum, and the other `ALTER TYPE` actions follow the architect decision
  above. The boundary is enum and type-statement modeling in the schema parser
  and the enum documentation. Acceptance evidence is schema tests of both
  replace-an-enum variants and of each action, a PostgreSQL test that applies
  one variant and runs generated code over the replaced column,
  `mvn spotless:check`, and `mvn test`.
  Refined by the architect on 2026-10-10 against JSqlParser 5.4: it reports
  every named form as one `ALTER TYPE` statement whose action is `OWNER`,
  `RENAME`, `SET_SCHEMA`, `RENAME_ATTRIBUTE`, `ATTRIBUTES`, `ADD_VALUE`, or
  `RENAME_VALUE`, with the type's name as written, qualifier included. A
  qualified `RENAME TO` target, a combined action list, and `SET (...)` are
  unreadable and stay rejected as unreadable type DDL. A rejected `SET SCHEMA`
  reads `Unsupported ALTER TYPE action: SET SCHEMA <schema> at line <n>`.
  Columns, including enum-array columns, follow a rename by their recorded
  type name; a column still naming a replaced enum is the documented
  `DROP TYPE ... CASCADE` case.
  Review evidence: `DefaultSchemaParser.applyAlterType` ignores `OWNER`,
  `RENAME_ATTRIBUTE`, and `ATTRIBUTES` without resolving the type, keeps
  `ADD VALUE` as before, renames a label in its position, renames a modeled
  enum in its position and every scalar and array column recorded with its
  name, removes a modeled enum of the new name, rejects `SET SCHEMA` of a
  modeled enum with the recorded message, and ignores `RENAME TO` and
  `SET SCHEMA` of an unmodeled, unqualified type while a qualified one fails as
  `Type not found`; `CREATE TYPE ... AS ENUM` onto a modeled name replaces it
  in its position. Schema tests cover each action and both replace-an-enum
  variants, and a PostgreSQL test runs generated code over a replaced column.
  The enum documentation and the `renameEnumType` Javadoc state that a column
  still naming a replaced enum keeps that name and is modeled with the
  replacing enum's labels. Reviewer probes against the built parser confirmed
  that behavior after a `CREATE TYPE` replacement and after a `RENAME TO` onto
  the name, delimiters removed from both rename names, and a qualified
  `RENAME TO` target, `OWNER TO app, RENAME TO feel`, and `SET (...)` rejected
  as unreadable at their line and column. Reviewer runs:
  `mvn spotless:check` passed; `mvn test` passed with 32 + 1062 tests,
  including the 34 `PostgresIntegrationTest` tests.
- **M22-W6 — Tables whose columns come from another table — Completed.**
  Implements requirement 5. `CREATE TABLE ... (LIKE t ...)` and
  `CREATE TABLE ... PARTITION OF t` copy the columns, types, and nullability of
  the modeled table `t`, and an empty element list `()` is a table without
  columns; a `CREATE TABLE` whose columns sqlcj cannot determine, such as
  `AS SELECT`, `INHERITS`, or `OF type`, is rejected with its file and line.
  The boundary is `CREATE TABLE` modeling and the schema documentation.
  Acceptance evidence is schema tests per form and rejection, generated
  `SELECT *` records over a `LIKE` and a `PARTITION OF` table that compile,
  `mvn spotless:check`, and `mvn test`.
  Refined by the architect on 2026-10-10 against JSqlParser 5.4, taken before
  M22-W7 at the owner's request: a `LIKE` beside column definitions, table
  constraints, or further `LIKE` clauses is modeled in written order; a
  partition stays linked to its parent and follows its column changes, its
  rename, and its `DROP TABLE` until `DETACH PARTITION`; and the tables W6
  resolves follow the interim rule, all as the `LIKE` and `PARTITION OF`
  decision above states. The boundary therefore also covers the partition
  link in the schema model, propagation of the modeled `ALTER TABLE` column
  actions, `DROP TABLE`, and `ATTACH` and `DETACH PARTITION`. 5.4 reads
  `LIKE` with any `INCLUDING`/`EXCLUDING` options, `PARTITION OF t` with
  `FOR VALUES FROM ... TO`, `IN`, `WITH (MODULUS ...)`, or `DEFAULT` and an
  optional list of table constraints, which are recorded as in any
  `CREATE TABLE`, and `()`. Without an element list of column
  definitions, as for `PARTITION OF`, `AS SELECT`, `OF type`, and `()`, today's
  column loop fails as a `NullPointerException`; `INHERITS (p)` is reported
  only as a table option, so the child is modeled without `p`'s columns. A
  rejection reads `Unsupported CREATE TABLE clause: <clause> at line <n>`,
  naming `AS SELECT`, `INHERITS`, or `OF <type>`, and a `CREATE TABLE` with
  neither an element list, `()`, nor `PARTITION OF`, such as
  `CREATE TABLE c LIKE t`, reads
  `Unsupported schema statement: CREATE TABLE at line <n>`. Forms 5.4 cannot
  read, such as column options after `PARTITION OF` or `OF type`
  (`id WITH OPTIONS NOT NULL`) and `AS SELECT ... WITH NO DATA`, stay rejected
  as unreadable table DDL at their line and column. Acceptance evidence adds
  schema tests of each propagated column action, a nested partition, the
  parent's rename and `DROP TABLE`, `ATTACH` and `DETACH PARTITION`, a `LIKE`
  copy that does not follow its source, and the interim rule.
  Review evidence: `DefaultSchemaParser.parseTable` models a partition's
  parent columns first and then every element in written order, each `LIKE`
  appending its source's columns, fails a repeated name as
  `Column already exists`, records only the table's own constraints, models
  `()` as a table without columns, and rejects a table with no element list,
  `()`, or `PARTITION OF` as `Unsupported schema statement: CREATE TABLE`;
  `AS SELECT`, `OF <type>`, and `INHERITS` fail as
  `Unsupported CREATE TABLE clause: <clause> at line <n>`. `Table` records
  `partitionOf`; `ADD`, `DROP`, and `RENAME COLUMN`, a new type, and
  `SET`/`DROP NOT NULL` reach partitions recursively; a parent rename relinks
  them; `DROP TABLE` removes them recursively and resolves every name of one
  statement first, so `DROP TABLE parent, partition` loads; `ATTACH` and
  `DETACH PARTITION` link and unlink a modeled table and still ignore an
  unmodeled one; and a `LIKE`, `PARTITION OF`, `ATTACH`, or `DETACH` name
  qualified with a schema other than `public` fails as `Table not found`.
  Schema tests cover each form, rejection, propagated action, and the interim
  rule, and a compiler test compiles `SELECT *` repositories over a `LIKE` and
  a `PARTITION OF` table. Reviewer probes against the built parser confirmed
  `DROP TABLE events, events_2026` after `PARTITION OF` and after
  `ATTACH PARTITION`, and an unmodeled name failing or skipped with
  `IF EXISTS` as before. Reviewer runs: `mvn spotless:check` passed;
  `mvn test` passed with 32 + 1106 tests, including the 34
  `PostgresIntegrationTest` tests.
- **M22-W7 — One namespace — Completed.** Implements requirement 6. Unqualified
  and `public`-qualified table and type names, including column types and
  their arrays, are modeled alike; tables and types of any other schema, and
  statements that target them, readable or not, are ignored, and `SET SCHEMA`
  that moves a modeled table to another schema removes it. A query that names
  a table qualified with another schema is rejected as an unknown table. The
  boundary is name resolution in the schema parser and the analyzer and the
  schema documentation, including that `search_path` is not followed. It
  covers every `ALTER TYPE` action M22-W5 resolves and replaces W5's interim
  rule that rejects a qualified, unmodeled `RENAME TO` or `SET SCHEMA` type
  name as `Type not found`, and it covers the tables M22-W6 resolves, the
  sources of `LIKE` and `PARTITION OF` and the partitions of `ATTACH` and
  `DETACH PARTITION`, replacing W6's interim rule that rejects such a name
  qualified with a schema other than `public` as `Table not found`.
  Acceptance evidence is schema and analyzer tests showing `reporting.users`
  invisible to queries and `public.users` equal to `users`, a
  `public.`-qualified enum and enum-array column mapped, `mvn spotless:check`,
  and `mvn test`.
  Refined by the architect on 2026-10-10 against JSqlParser 5.4: it reports a
  table name's schema apart from its name, delimiters kept, in `CREATE`,
  `ALTER`, and `DROP TABLE`, `LIKE`, `PARTITION OF`, `ATTACH` and
  `DETACH PARTITION`, and every query source, but a type name in `CREATE` and
  `ALTER TYPE`, a column type, or a cast as one text with its qualifier and
  delimiters, such as `"public"."mood"`, with a qualified type's array brackets
  inside that text (`public.mood[]`) rather than as array dimensions. A schema
  qualifier is compared case-insensitively without delimiters, as every name
  is. A statement whose own table or type is qualified with another schema is
  ignored without resolving anything it names, and `DROP TABLE` skips such a
  name without failing; an unreadable `CREATE` or `ALTER TABLE` or `CREATE` or
  `ALTER TYPE` so qualified is ignored, while an unreadable `DROP TABLE` keeps
  today's handling, because another of its names may be modeled. A column or
  cast type qualified with another schema never resolves to a modeled enum and
  is recorded with its declared text, as today. `ALTER TABLE ... SET SCHEMA`,
  which PostgreSQL accepts only as the sole action, removes a modeled table,
  does nothing when the schema is `public`, and is rejected as
  `Unsupported ALTER TABLE action: SET SCHEMA <schema> at line <n>` when the
  table has modeled partitions, which would stay in `public` following a
  parent sqlcj no longer models, and beside another action, as today;
  `ALTER TYPE ... SET SCHEMA public` of a modeled enum does nothing. W6's
  interim rule gives way: a `LIKE` or `PARTITION OF` source of another schema
  is an unmodeled source and still fails as
  `Table not found in schema: <schema>.<t>`, and an `ATTACH` or
  `DETACH PARTITION` partition of another schema is an unmodeled partition and
  is ignored. W5's interim rule gives way: a `public`-qualified type name
  resolves as its unqualified name, and `RENAME TO` or `SET SCHEMA` of an
  unmodeled type is ignored however it is qualified. A query that qualifies a
  column or `qualifier.*` with a schema, and a `public` partition attached to a
  table of another schema, are namespaces as a feature and outside the
  boundary. Acceptance evidence adds schema tests of each modeled statement
  with a `public`, a quoted `"public"`, and another schema's qualifier, of
  `SET SCHEMA`, and of both replaced interim rules, and an analyzer test of a
  `public`-qualified cast type.
  Review evidence: `SchemaNamespace` states the rule once, comparing a
  qualifier case-insensitively without delimiters and splitting a type text at
  dots outside double quotes. `DefaultSchemaParser` ignores a readable
  `CREATE` or `ALTER TABLE` and `CREATE` or `ALTER TYPE` of another schema
  before resolving anything, and an unreadable one by the qualifier after the
  opening words, an optional `IF [NOT] EXISTS`, and `ONLY`; `DROP TABLE` skips
  such a name; a `LIKE` or `PARTITION OF` source of another schema fails as
  `Table not found in schema: <schema>.<t>` and an `ATTACH` or
  `DETACH PARTITION` partition of another schema is ignored; a sole
  `SET SCHEMA` removes the table, is a no-op for `public`, and is rejected for
  a table with modeled partitions and beside another action;
  `ALTER TYPE ... SET SCHEMA public` of a modeled enum is a no-op and
  `RENAME TO` or `SET SCHEMA` of an unmodeled type is ignored however
  qualified. `ColumnTypeMapping` reads a qualified type's inline array
  brackets as dimensions and resolves the enum in the one namespace, and
  `QueryAnalyzer` rejects a `FROM`, `JOIN`, `INSERT`, `UPDATE`, or `DELETE`
  source of another schema as `Table not found in schema: <schema>.<t>`.
  Reviewer probes against the built parser confirmed `SET SCHEMA` before
  `OWNER TO` or `ADD COLUMN` rejected, `IF EXISTS`, quoted-`public`, and
  multi-line `SET SCHEMA` removing the table, a moved partition leaving its
  parent, `public`-qualified `LIKE`, `PARTITION OF`, and `ATTACH PARTITION`
  resolving, `public.status RENAME TO` and `"public"."status" SET SCHEMA`
  ignored, and unmapped `public.citext[4]` and `public.mood[3][2]` recorded
  as `PUBLIC.CITEXT[]` and `PUBLIC.MOOD[][]`. Reviewer runs:
  `mvn spotless:check` passed; `mvn test` passed with 32 + 1173 tests,
  including the 34 `PostgresIntegrationTest` tests.
- **M22-W8 — Reference service history and `pg_dump` snapshot — Completed.**
  Proves the milestone acceptance. Adds the reference service's schema history
  as original, sqlcj-owned Flyway migrations covering every construct of the
  probe's first three rows except `SET DATA TYPE`, an equivalent hand-written
  snapshot, and a query group over its tables; a PostgreSQL test applies the
  history to a `postgres:16-alpine` Testcontainers server, runs
  `pg_dump --schema-only` inside that container, and generates byte-identical,
  compiling repositories from the history, the snapshot, and the dump, then
  runs generated queries against the migrated database. Every schema input of
  the probe either loads or fails with a located diagnostic that names no
  exception or parser class, and `SET DATA TYPE` is recorded as unsupported.
  The boundary is test fixtures, tests, and the `pg_dump` documentation;
  production changes only close a gap in M22-W2 to M22-W7's requirements that
  the fixture exposes. Acceptance evidence is that PostgreSQL test,
  `mvn spotless:check`, and `mvn test` on Java 17 and Java 21.
  Refined by the architect on 2026-10-10 against the sources at `f4f9ab1`,
  JSqlParser 5.4, and the `postgres:16-alpine` image's PostgreSQL and
  `pg_dump` 16.15: a draft ten-migration history covering every construct of
  the probe's first three rows except `SET DATA TYPE`, plus a `LIKE` copy, two
  partitions, an enum replacement, and a table of a second schema sharing a
  `public` table's name, was applied with `psql`, and the history, its
  `pg_dump` snapshot, and a hand-written snapshot generated byte-identical
  output trees that compile, so no gap in M22-W2 to M22-W7 was found and no production
  change is planned. `pg_dump` writes each partition as a plain
  `CREATE TABLE` followed by `ALTER TABLE ONLY ... ATTACH PARTITION`, which
  M22-W6's link covers. The fixture avoids one parser limit: 5.4 reads
  `settings` as a keyword, so an unquoted column of that name fails its
  `CREATE TABLE` and any query naming it; M22-W9's report includes it. The
  `pg_dump` output is produced at test time and not committed, because each
  run writes a new `\restrict` key. The unsupported catalogue is a fixture file
  applied after the history whose rejection the test asserts with its file,
  line, and column, and a query naming the second schema's table is asserted
  rejected as an unknown table. The documentation adds the `pg_dump` snapshot
  as a schema input.
  Review evidence: the reference service's ten sqlcj-owned migrations, its
  hand-written snapshot, its Tracker query group, and the unsupported
  `SET DATA TYPE` fixture live under
  `sqlcj-cli/src/test/resources/reference-service/`, with no production
  change. `ReferenceServiceIntegrationTest` applies the history with `psql`
  to PostgreSQL 16, runs `pg_dump --schema-only` against the migrated
  database, generates one byte-identical, compiling output tree, manifest
  included, from the history, the snapshot, and the dump, executes the
  generated repository against the migrated database, and asserts the located
  `SET DATA TYPE` diagnostic and the unknown-table rejection of a query naming
  the second schema's table, each writing no file. The configuration and
  PostgreSQL documentation add the `pg_dump` snapshot as a schema input, its
  ignored statements, and its partition and `public` handling, and limit
  dump-history equality to histories that change the schema only through
  statements sqlcj reads, linking the indirect-change caveats. Reviewer runs:
  `mvn spotless:check` passed; `mvn -Dsqlcj.test.requireDocker=true clean test`
  passed with 32 + 1177 tests on Java 17 and Java 21, including the four
  `ReferenceServiceIntegrationTest` tests, none skipped.
- **M22-W9 — JSqlParser recheck and upstream report — Completed.** Implements
  requirement 9. The activation recheck above found no release after 5.4.
  Maven Central is rechecked; a release that reads any rejected form is adopted
  with only parser-wording expectations changed, and otherwise the result is
  recorded. A ready-to-file report, kept outside the repository, gives for each
  table or type form 5.4 cannot read the minimal SQL and 5.4's failure; filing
  it is the owner's action and not part of the item. Acceptance evidence is the
  recorded recheck, the report handed to the owner, and, after an upgrade, the
  full suite.
  Refined by the architect on 2026-10-11: Maven Central still lists 5.4 as the
  latest release (metadata and the 5.4 directory last updated 2026-09-13), and
  the build uses 5.4, so no upgrade is made and the item changes no repository
  file besides this roadmap. A 5.4 probe with `CCJSqlParserUtil.parseStatements`
  confirmed eleven table or type forms it cannot read, each of which the report
  covers in its own entry: `ALTER [COLUMN] ... SET DATA TYPE`; an unnamed
  `ADD CHECK`; `NOT VALID` on a check or foreign key; `ADD GENERATED ... AS
  IDENTITY` with parenthesized sequence options, which `pg_dump` writes (the
  plain form parses); `ADD [CONSTRAINT name] EXCLUDE`; `DEFERRABLE INITIALLY
  DEFERRED` in `ADD CONSTRAINT`; `UNIQUE` and `PRIMARY KEY USING INDEX`;
  `ALTER COLUMN ... DROP EXPRESSION IF EXISTS`; `DROP TYPE`; an unquoted column
  named `settings` or `prior`, which PostgreSQL accepts unquoted, in
  `CREATE TABLE` and in a query; and an `E'...\'...'` escape string, a lexical
  error. Composite, range, and shell `CREATE TYPE`, every `ALTER TYPE` action,
  and `CREATE TABLE` with `EXCLUDE` or `DEFERRABLE INITIALLY DEFERRED` parse.
  The report is written to
  `/home/said/myprojects/sqlcj-reports/jsqlparser-5.4-upstream-report.md`, one
  ready-to-file issue per form, each statement checked to run on the
  `postgres:16-alpine` image the integration tests use.
  Review evidence: Maven Central's `jsqlparser` metadata, rechecked on
  2026-10-11, lists 5.4 as latest, release, and highest version (last updated
  2026-09-13), and the build uses 5.4, so no upgrade is made and no repository
  file changes. The report gives the environment, a compiling reproducer, and
  the eleven forms in their own entries with a summary table, and names no
  roadmap or local-only material. A 5.4 probe with `parseStatements` on
  Java 17 reproduced every quoted exception class and first message line for
  all 22 failing statements, including the lexer swallowing later statements
  after `E'it\'s'`, and parsed every stated closest form; every statement ran
  on PostgreSQL 16.15 from `postgres:16-alpine` with the stated catalog effect.
  Reviewer runs at the milestone's close: `mvn spotless:check` passed;
  `mvn -Dsqlcj.test.requireDocker=true clean test` passed with 32 + 1177 tests
  on Java 17 and Java 21, including the four `ReferenceServiceIntegrationTest`
  tests, none skipped, and the runtime and CLI class files are major
  version 61.

#### Required

1. Apply D-A as the first work item: build every module with `--release 17`,
   replace the Java 21-only constructs, run the test suite in CI on Java 17 and
   Java 21, and update the documented requirements. Generated code and runtime
   behavior do not change.
2. Replace the enumerated list of ignored statements with one rule. `CREATE`,
   `ALTER`, and `DROP TABLE` and `CREATE` and `ALTER TYPE` update the model as
   they do today; every other statement is ignored, because it cannot change a
   modeled table's columns, column types, or nullability. That includes views
   and materialized views, `CREATE SCHEMA`, `SET` and other session statements,
   `TRUNCATE` and every data statement including `SELECT`, `DO` blocks,
   functions, triggers, policies, roles and privileges, `COMMENT ON` of any
   target, extensions, sequences, indexes, and `DROP TYPE`. Document that DDL
   executed inside a `DO` block or a function body, and columns removed by
   `DROP TYPE ... CASCADE`, are not seen.
3. Within `ALTER TABLE`, ignore every action that cannot change a column's
   existence, name, type, or nullability, such as `SET`/`DROP DEFAULT`,
   `SET STATISTICS`, `OWNER TO`, row-level security switches, trigger switches,
   `VALIDATE CONSTRAINT`, and adding or dropping any constraint. Accept a table
   constraint of any kind, including `EXCLUDE`, in `CREATE TABLE` as well.
   Reject an action that would change columns in a way sqlcj does not model,
   naming the file, line, and action in SQL terms.
4. Model `ALTER TYPE ... RENAME TO` and `ALTER TYPE ... RENAME VALUE` for
   enums, so the common replace-an-enum migration loads.
5. Never model a table with missing columns. `CREATE TABLE ... (LIKE t ...)`
   and `CREATE TABLE ... PARTITION OF t` copy the columns, types, and
   nullability of the modeled table `t`. Any other `CREATE TABLE` whose columns
   sqlcj cannot determine, such as `AS SELECT`, `INHERITS`, or `OF type`, is
   rejected with its location.
6. Model one namespace: unqualified names and names qualified with `public`.
   Tables and types created in any other schema are ignored, and a query that
   names a table qualified with another schema is rejected as an unknown
   table, so `reporting.users` never resolves to `users`.
7. Statement boundaries per D-B: a statement the parser cannot read is ignored
   when it is not a `CREATE`, `ALTER`, or `DROP TABLE` or a `CREATE` or
   `ALTER TYPE`, and rejected naming its file and line when it is, except an
   unreadable `ALTER TABLE` that D-F ignores. psql meta-command lines, such as
   the `\restrict` lines that recent `pg_dump` releases write, are skipped.
8. Schema diagnostics name statements in SQL terms, never by parser class name
   (`Unsupported schema statement: Alter` becomes the statement or action
   kind).
9. Recheck JSqlParser for a release that reads the forms that remain rejected
   under requirement 7 (today `ALTER COLUMN ... SET DATA TYPE`, unnamed
   `ADD CHECK`, and `NOT VALID`), upgrade if one exists, and prepare the
   upstream reports of the rest, which the owner files.

#### Out of scope

- namespaces as a feature, views as query sources, table inheritance,
- domains, composite types, and other new type mappings,
- executing migrations, Liquibase XML/YAML/JSON, Flyway placeholders and Java
  migrations,
- reading DDL inside `DO` blocks or function bodies.

#### Acceptance

- The reference service's migration history, including every schema construct
  of the probe's first three rows except `ALTER COLUMN ... SET DATA TYPE`,
  which requirement 7 keeps rejected and the unsupported catalogue records,
  loads and generates the same repositories as an equivalent hand-written
  snapshot.
- A `pg_dump --schema-only` snapshot of that schema, produced by the PostgreSQL
  used in the integration tests, loads and generates the same repositories.
- `mvn test` passes on Java 17 and on Java 21, and the runtime and CLI class
  files target Java 17.
- A `LIKE` or `PARTITION OF` table has its source table's columns; a table in
  another schema is invisible to queries; no probe input produces an internal
  error or a parser class name.
- Focused schema, compiler, and PostgreSQL tests, `mvn spotless:check`, and
  `mvn test` pass.

### Milestone 23 — A Way Through for Every Query

#### Goal

Make every query of a supported statement kind either compile, compile once
the user states the missing types with casts, or fail with a diagnostic that
says what to write; and never report an internal error.

#### Work items

Activated on 2026-10-11 at the owner's request. Reassessed against the
implementation at `16a2bca`, its tests, the reference service fixtures, the
external sample, the query documentation, and JSqlParser 5.4 run outside the
repository. Every query shape of the probe table was rerun with original,
sqlcj-owned SQL through a CLI built from that commit, against the
reference service snapshot extended with an assignee column on `tasks` and a
`deleted_at` column on `users`; nothing was fetched from sqlc. Every
requirement is unimplemented, as assumed. Facts that correct or shape the plan:

- Requirement 1: a placeholder is typed only by the clause rules of `WHERE`, an
  `INSERT` value, an `UPDATE` or `DO UPDATE` assignment, and a bare `LIMIT` or
  `OFFSET` value, and the cast rule reaches only those clauses and does not
  descend into a subquery. Every other placeholder fails the accounting check
  as `SQL placeholders [...] are not the analyzed parameters [...]; a
  placeholder is in an unsupported location`, including
  `ORDER BY CASE WHEN :sort::text = 'title' THEN title END`, `LIMIT :limit::int`,
  `FETCH FIRST :n::int ROWS ONLY`, `HAVING COUNT(*) > :min::bigint`, a cast
  placeholder inside a cast projection, and a cast placeholder inside `EXISTS`,
  `IN (SELECT ...)`, or a scalar subquery. In 5.4's parse tree the operand of a
  `::` cast has no node of its own, nor does the inner cast of `::text::int`;
  the cast's node begins at the placeholder's token, one placeholder or cast
  can hang under two nodes, and a pre-order walk visits placeholders in textual
  order at every depth, `WITH` bodies and subqueries included. The query
  compiler already walks the tree that way, unwrapping casts, so textual
  binding order across clauses can come from that walk instead of from the
  order in which clauses are analyzed.
- Requirement 2: a join requires exactly one qualified `ON` equality between
  the joined source and an earlier one (`A join requires exactly one ON
  equality.`), so composite-key and filtered joins, `ON TRUE`, and any
  placeholder in `ON` are rejected. 5.4 reports the whole condition as one
  expression.
- Requirement 3: `ON CONFLICT DO NOTHING` without a target and
  `ON CONFLICT ON CONSTRAINT` fail as `ON CONFLICT requires a target of column
  names, such as ON CONFLICT (id).`, and a target predicate and
  `DO UPDATE ... WHERE` each fail with a rejection of their own;
  `ON CONFLICT (columns) DO NOTHING` already compiles. 5.4 also reads
  `DO UPDATE` without a target, which PostgreSQL rejects.
- Requirement 4: an aliased item fails as `RETURNING items must not be
  aliased.` on `INSERT`, `UPDATE`, and `DELETE` alike.
- Requirement 5: `lower(:email)`, `'%' || :q || '%'`, and
  `metadata->>'k' = :v` fail with the accounting message, which lists
  parameter sets instead of naming the placeholder and the fix.
- Requirement 6: `UNION`, `UNION ALL`, `INTERSECT`, a `VALUES` statement, and
  `INSERT ... SELECT` report a raw `ClassCastException` message. Other
  statement kinds report a parser class name (`Merge`, `Truncate`,
  `ExplainStatement`, `Execute`, `CreateTable`, `SetStatement`), as do
  unsupported projections (`Unsupported SELECT expression: AnalyticExpression`,
  `Concat`) and `RETURNING` items (`Unsupported RETURNING expression:
  Function`). The compiler passes the first message line of any exception
  through. A CTE used as a source fails as `Table not found in schema: recent`,
  and an `:exec` `UPDATE ... FROM` or `DELETE ... USING` whose analyzed clause
  names the added source fails as `Unknown source qualifier in query: p`: both
  are located and in SQL terms but name the wrong construct.
- Not in the plan, found by the probe: 5.4 reports an array subscript or slice
  such as `labels[1]` as the column `labels` with an index, and the analyzer
  treats it as the whole column, so `SELECT labels[1] AS first` generates a
  `List<String>` component and `WHERE labels[1] = :l` binds a `List<String>`
  where PostgreSQL reads `text`. Both compile and fail only when executed.
  Requirement 7 is added for it; see [Plan Changes](#plan-changes).
- Already compile as written: a `WHERE` placeholder beside a join, `EXISTS`
  and `IN (SELECT ...)` without a placeholder, keyset row comparisons and
  optional filters with casts, `FOR UPDATE OF ... SKIP LOCKED`,
  `ON CONFLICT (columns) DO NOTHING`, and `DISTINCT ON`. A `WITH` clause on a
  write without `RETURNING`, and a CTE referenced only inside a subquery, are
  carried unanalyzed, as the query documentation states.
- The 2026-10-05 probe's query file was not kept; the probe table is its
  record, and every shape it lists was reproduced. No Milestone 23 text or
  acceptance item calls for fetching anything from sqlc.

Decided by the architect on 2026-10-11, under the rule that pre-release
decisions are revisable by evidence:

- **Implementation order:** M23-W1 to M23-W8 as numbered. W1 accounts for
  placeholders across the whole statement, which W2 and W3 open new clauses
  to; W5 reports what that accounting leaves untyped; W7 follows W5, so a
  placeholder beside a subscripted column gets W5's diagnostic; and W8 proves
  the milestone on the reference service last.
- **Binding order.** Placeholder occurrences bind in textual order across every
  clause and nesting depth. The existing check that the analyzed occurrences
  equal the compiled placeholder tokens stays as a guard.
- **Nested `SELECT`s.** A cast placeholder is typed anywhere in the statement,
  including subqueries and `WITH` bodies, but a subquery or CTE is not
  otherwise analyzed until Milestone 24: an uncast placeholder there gets
  requirement 5's diagnostic, and a CTE used as a source stays rejected.
- **Names of newly accepted cast placeholders.** A named one keeps its name. An
  indexed cast placeholder that is the whole `LIMIT` or `OFFSET` value is named
  `limit` or `offset`, as an uncast one is; every other newly accepted indexed
  cast placeholder is `param<N>`. No placeholder accepted today changes its
  name, type, or binding position.
- **Join scope.** An `ON` condition sees the sources introduced up to and
  including its joined source, as in PostgreSQL. A join without `ON` stays
  rejected; `ON TRUE` is accepted.
- **Conflict clauses.** A constraint name is not checked against the schema,
  because PostgreSQL resolves it, as it resolves the unique index a column
  target matches today. A target predicate sees the inserted table, and
  `DO UPDATE ... WHERE` sees the inserted table under its name or alias and
  `EXCLUDED`. `DO UPDATE` without a target is rejected naming the fix, because
  PostgreSQL rejects it. A target element that is an expression or carries a
  collation or an operator class stays rejected; requirement 3 does not name
  it.
- **The untyped-placeholder diagnostic** reads
  `Cannot infer the type of <placeholder> in <expression>; state it with a
  cast, such as <placeholder>::text`, naming the first untyped placeholder in
  textual order as the query spells it, and the smallest expression that
  encloses it as written, or its clause when it stands alone there.
- **Located diagnostics.** A query diagnostic is located by its query source,
  query name, and header line, as today, and a schema diagnostic by its file
  and line, as Milestone 22 left it. Positions within a query are not
  required.
- **What to write.** A construct that stays outside `0.1.0` says what to write
  instead: one query per `SELECT` for a set operation, a `LEFT JOIN` with the
  sources swapped for `RIGHT JOIN`, an `ON` condition for `USING`, `NATURAL`,
  and a comma-separated `FROM` list, `JOIN ... ON TRUE` for `CROSS JOIN`, and
  the supported statement kinds for any other statement; `FULL JOIN` and
  `LATERAL` are named as unsupported. A shape Milestone 24 analyzes (a CTE
  source, a `FROM` subquery, multi-row `VALUES`, `INSERT ... SELECT`,
  `UPDATE ... FROM`, and `DELETE ... USING`) is named as unsupported, and
  `UPDATE ... FROM` and `DELETE ... USING` are rejected on every write, not
  only on a returning one (see [Plan Changes](#plan-changes)). No diagnostic
  names a milestone.
- **Internal errors.** sqlcj's own diagnostics are the unsupported-operation
  and illegal-argument exceptions that the analyzer, schema parser, and
  generator throw by convention, and the SQL parse, schema parse,
  configuration, and compilation exceptions. Any other exception is an
  internal error, reported as one line that names the query, schema file, or
  query group, says that it is a sqlcj internal error to report, and carries
  no exception message or class. Converting every diagnostic to a dedicated
  exception type is a broader refactor than the requirement needs.

- **M23-W1 — Cast placeholders in every clause — Completed.** Implements
  requirement 1. A placeholder written as the direct operand of `::type` or
  `CAST(... AS type)` is typed by its cast wherever it appears in the
  statement: projections, `GROUP BY`, `HAVING`, `ORDER BY`, `LIMIT`, `OFFSET`,
  `FETCH`, `WITH` bodies, and subqueries at any depth, as well as the clauses
  that accept it today; every occurrence binds in textual order across those
  clauses; and names follow the decision above. The boundary is placeholder
  accounting and ordering in the analyzer, the syntax-level placeholder order
  the SQL parser reports, the query documentation of casts, projections,
  ordering, pagination, parameters, and unsupported queries, and tests. Join
  conditions are opened by W2 and conflict clauses by W3, and an uncast
  placeholder keeps today's rejection until W5. Acceptance evidence is
  analyzer tests per clause in both placeholder forms, textual binding order
  across clauses (a projection placeholder before a `WHERE` one, `OFFSET`
  before `LIMIT`), one placeholder repeated across clauses with agreeing and
  with conflicting cast types, a subquery and a `WITH` body, the uncast and
  indirect forms (`ORDER BY :sort`, `(:x)::int`) still rejected,
  generated-source compilation, a PostgreSQL test that runs a dynamic
  `ORDER BY CASE` sort, a cast `LIMIT`/`OFFSET` page, and an `EXISTS`
  membership check through generated code, `mvn spotless:check`, and
  `mvn test`.
  Review evidence: the SQL parser's existing pre-order parse-tree walk now
  also reports each placeholder occurrence once, in textual order at every
  clause and depth, with the innermost cast whose direct operand it is
  (`PlaceholderOccurrence`, `ParsedSql.placeholders()`); `QueryAnalyzer`
  keeps its clause rules, records what they type by placeholder identity, and
  builds the occurrence list from the parser's order, typing every other cast
  occurrence by its cast and naming it after its placeholder, so the
  `OFFSET`-before-`LIMIT` source-position ordering and the clause-local cast
  visitor are gone. Verified: `ORDER BY CASE WHEN :sort::text ...` twice after
  `WHERE id = :projectId` gives `(projectId, sort)` binding `[1, 2, 2]`;
  `LIMIT $1::int OFFSET $2::int` and `OFFSET $2::int LIMIT $1::int` give
  `(Integer limit, Integer offset)`, the latter binding `[2, 1]`;
  `LIMIT :pageSize::int`, `LIMIT $1::bigint` (`Long limit`),
  `LIMIT LEAST($1::int, 100)` and `FETCH FIRST $1::int ROWS ONLY` (`param1`);
  `SELECT $1::text AS label, id ... WHERE id = $2` gives `(param1, id)`
  binding `[1, 2]`, and a named projected placeholder is the first logical
  parameter; `HAVING COUNT(*) > $1::bigint` gives `Long param1`; cast
  placeholders in `EXISTS`, `IN (SELECT ...)`, a scalar comparison subquery,
  a two-level nested subquery, a `DELETE` subquery, and a `WITH` body on a
  read and an `:exec` write are accepted; agreeing repeated casts share one
  parameter and conflicting ones fail with the conflict message; `ORDER BY $1`,
  an uncast `CASE` sort, `(:sort)::int`, and an uncast subquery placeholder
  keep the accounting rejection. A reviewer probe also confirmed unchanged
  results for chained casts, `CAST(CAST(:x AS text) AS int)`, mixed
  `WHERE`/`IN`/`LIKE`/`BETWEEN`/`= ANY`/pagination binding, `OFFSET $3
  LIMIT $2`, `DO UPDATE` cast assignments, and the still-rejected
  `LIMIT $1 + 1`, `FETCH FIRST $1 ROWS ONLY`, `RETURNING` cast, and join
  placeholder. `SqlcjCompilerIntegrationTest` compiles a repository over these
  clauses, and `PostgresIntegrationTest` runs a dynamic sort, a cast page in
  both clause orders, and an `EXISTS` membership check against PostgreSQL 16.
  `mvn spotless:check` and `mvn test -Dsqlcj.test.requireDocker=true` passed
  (1212 tests, 0 failures, 0 skipped).
- **M23-W2 — Join conditions of any boolean expression — Completed.**
  Implements requirement 2. The `ON` condition of an inner or left join may be
  any boolean expression over the sources the join-scope decision names; its
  column references resolve, and its placeholders are typed by the `WHERE`
  rules, against those sources, and the join kind alone decides nullability.
  Every column reference of the condition outside a nested `SELECT` resolves,
  qualified or not, whether or not it binds a placeholder, as the single
  equality's columns are resolved today; a nested `SELECT` in it is carried as
  the nested-`SELECT`s decision states. A join must carry exactly one `ON`
  condition, so a join without one, and the nested form
  `JOIN a JOIN b ON ... ON ...` that 5.4 reports as two conditions on one join,
  stay rejected. The boundary is join validation and `ON` analysis in the
  analyzer and the join documentation; the accepted join kinds do not change,
  and their what-to-write diagnostics stay W6's. Acceptance
  evidence is analyzer tests of a composite-key join, a filtered left join
  whose columns stay nullable, a placeholder in `ON` typed from its column and
  bound before the `WHERE` placeholders, `ON TRUE`, and a reference to a later
  source rejected, generated-source compilation, a PostgreSQL test of the
  filtered left join over matched, filtered-out, and unmatched rows,
  `mvn spotless:check`, and `mvn test`.
  Review evidence: `QueryAnalyzer` now requires exactly one `ON` expression
  per join (`A join requires one ON condition.`), resolves every column of it
  outside a nested `SELECT` against the sources up to and including the joined
  one, and types its placeholders with the `WHERE` rules over that scope;
  binding order still comes from the parser's textual placeholder order, and
  `Source.leftJoined`, the join-kind gate, and the generator are unchanged.
  Verified: a composite key, `ON p.user_id > u.id`, `ON p.user_id = p.id`, an
  unqualified `user_id = u.id`, and `ON TRUE` are accepted; a `LEFT JOIN`
  filtered by `p.nickname IS NOT NULL` reads the `NOT NULL` `p.id` as
  nullable while `u.id` stays `NOT NULL`; `ON ... AND p.nickname = $2
  WHERE u.id = $1` gives `(id BIGINT, nickname VARCHAR)` binding `[2, 1]`, and
  the named and `$1::text` forms are named `nickname`; a reference to a later
  source fails as `Unknown source qualifier in query: o`, an unknown column as
  `Column not found in table profiles: missing`, and a join without `ON` (inner
  and left) and the nested two-`ON` form as `A join requires one ON
  condition.` A reviewer probe also confirmed that functions, `NOT`, `CASE`,
  row comparisons, `CURRENT_DATE`, `OR`, `BETWEEN`, `LIKE`, and `IN` lists in
  `ON` work as in `WHERE`, that `EXISTS` and `IN (SELECT ...)` in `ON` are not
  analyzed, that a cast boolean placeholder is typed, and that
  `lower(p.nickname) = :n` keeps the accounting rejection.
  `SqlcjCompilerIntegrationTest` compiles a repository with a composite-key
  join, a filtered left join, and an `ON` placeholder bound before the `WHERE`
  one, and `PostgresIntegrationTest` runs a filtered left join over matched,
  filtered-out, and unmatched customers against PostgreSQL 16, reading the
  last two rows' joined components as `null`. `mvn spotless:check` and
  `mvn test -Dsqlcj.test.requireDocker=true` passed (1224 tests, 0 failures,
  0 skipped).
- **M23-W3 — Conflict clauses with nothing to type — Completed.** Implements
  requirement 3. `ON CONFLICT DO NOTHING` without a target,
  `ON CONFLICT ON CONSTRAINT name` with either action, a conflict-target
  predicate, and `DO UPDATE ... WHERE` compile on `:exec` and returning
  inserts; predicate placeholders follow the `WHERE` rules over the scopes the
  conflict-clause decision names and bind in textual order. `DO UPDATE`
  without a target is rejected naming the fix. The boundary is conflict-clause
  analysis and the upsert documentation. Acceptance evidence is analyzer tests
  per form and scope, including `EXCLUDED` and a placeholder in each
  predicate, the rejection, a PostgreSQL test running each form,
  `mvn spotless:check`, and `mvn test`.
  Refined by the architect on 2026-10-11 after tracing `QueryAnalyzer` at
  `2366ff5` and rerunning the forms through JSqlParser 5.4 outside the
  repository: 5.4 reports no target for `ON CONFLICT DO NOTHING` and for
  `ON CONFLICT DO UPDATE`, a constraint name with no index elements for
  `ON CONSTRAINT`, each predicate as one expression, and an `AS` alias on the
  inserted table; the parser's pre-order walk already reports every predicate
  placeholder in textual order, so binding order needs no change. Decided:
  the target predicate's scope is the inserted table under its exposed name
  (its alias when it has one), and the `DO UPDATE ... WHERE` scope adds
  `EXCLUDED` as a second source over the same table, so an unqualified column
  compared with a placeholder there is ambiguous, as PostgreSQL also reports;
  a column the predicates do not compare with a placeholder is left to
  PostgreSQL, as in `WHERE`. `DO UPDATE` without a target fails as
  `ON CONFLICT DO UPDATE requires a conflict target, such as ON CONFLICT (id)
  or ON CONFLICT ON CONSTRAINT name.` A target element that is an expression
  or carries a collation or an operator class keeps today's message. The
  PostgreSQL test runs the target predicate without a placeholder, because
  PostgreSQL infers the partial unique index from that predicate when it plans
  the statement; the analyzer tests type a placeholder in it.
  Review evidence: `QueryAnalyzer` accepts a targetless `DO NOTHING` and an
  `ON CONSTRAINT` target with either action without checking the name,
  resolves a target predicate's placeholders by the `WHERE` rules over the
  inserted table under its exposed name, and a `DO UPDATE ... WHERE`
  predicate's over that table and `EXCLUDED`; binding order still comes from
  the parser's textual order behind the unchanged accounting guard. Verified:
  `ON CONFLICT DO NOTHING` on `:exec` and with `RETURNING id` binds `[1, 2]`;
  `ON CONSTRAINT users_pkey` with `DO NOTHING` and with
  `DO UPDATE SET name = EXCLUDED.name, active = $3` is accepted on both forms;
  `ON CONFLICT (name) WHERE active = $3` adds `active BOOLEAN`, `:flag` keeps
  its name, and a bare `WHERE active` binds nothing; `users.version < $4 AND
  EXCLUDED.nickname <> $5` gives `version INTEGER` and `nickname VARCHAR`, and
  `u.version < $3` resolves through `INSERT INTO users AS u`;
  `WHERE active = $5 DO UPDATE SET bio = $3 WHERE users.version < $4` binds
  `[1, 2, 5, 3, 4]`; an unqualified `version = $3` in `DO UPDATE ... WHERE`
  fails as ambiguous between `users, EXCLUDED`, `EXCLUDED.active = $3` in a
  target predicate as an unknown source qualifier, a targetless `DO UPDATE`
  with the decided message on `:exec` and returning inserts, and the
  expression, `COLLATE`, and operator-class targets keep the column-names
  message. `PostgresIntegrationTest` runs every form through generated code
  against PostgreSQL 16, including a partial unique index matched by
  `ON CONFLICT (code) WHERE active` and an `:optional` `DO UPDATE ... WHERE`
  comparing a stored column with `EXCLUDED` and a named placeholder that
  updates once and then skips. A reviewer probe on PostgreSQL 16 showed that a
  prepared target predicate with a placeholder stops matching the partial
  index from its sixth execution under the default plan cache mode; the query
  documentation's examples therefore write the target predicate with literals
  and say why, while the analyzer still types such a placeholder.
  `mvn spotless:check` and `mvn test -Dsqlcj.test.requireDocker=true` passed
  (1230 cli and 32 runtime tests, 0 failures, 0 skipped).
- **M23-W4 — Aliased `RETURNING` items — Completed.** Implements
  requirement 4.
  An alias on a returned column names its result component, as on a `SELECT`
  item, for `INSERT`, `UPDATE`, and `DELETE`; a bare `RETURNING *` still
  returns the table's shared row record, and a returned column list never
  does. The boundary is returning analysis and its documentation. Acceptance
  evidence is analyzer tests for the three statements and both alias
  spellings, a generated result record named from the alias that compiles, a
  PostgreSQL test reading an aliased component, `mvn spotless:check`, and
  `mvn test`.
  Refined by the architect on 2026-10-11 after tracing `QueryAnalyzer` at
  `1b5e9d7` and rerunning the forms through JSqlParser 5.4 outside the
  repository: the only obstacle is the blanket alias check of the returning
  loop; 5.4 reports `AS alias` and a bare `alias` on a returned column alike,
  quoted aliases unquoted, and a qualified `u.id AS uid` through a `DELETE`
  target alias, and it also reads `RETURNING * AS x` and `RETURNING * x` as an
  aliased wildcard, which PostgreSQL rejects as a syntax error. Decided: a
  returned column is named by its alias exactly as a projected column is, with
  its type and nullability still from the schema column; an alias on the bare
  `*` stays rejected, now as `RETURNING * must not be aliased.`, so it can
  never return the shared row record; and an aliased computed item keeps the
  unsupported-expression rejection a computed item gets without an alias,
  because requirement 4 names a result component and does not add computed
  returning items. Generation and the runtime need no change, because a
  returned column reaches Java naming, collision handling, and positional row
  mapping through the same result-column model as a projection.
  Review evidence: `QueryAnalyzer` drops the blanket alias check and names a
  returned direct column through the same alias rule as a projected one,
  keeping the schema column's type, nullability, enum, and array facts; the
  row-table rule is unchanged, so only a bare unaliased `*` returns the shared
  row record. Verified: `RETURNING id AS user_id, name` gives `user_id BIGINT
  NOT NULL` and `name VARCHAR`, `RETURNING id new_id` gives `new_id` with
  binding order `[2, 1]`, `DELETE FROM users u ... RETURNING u.id AS uid`
  gives `uid`, and `AS "User Id"` gives `User Id`, each with no row table, as
  is an aliased list of every column; parameters and executed SQL keep the
  alias text unchanged. `RETURNING * AS x` and `RETURNING * x` fail as
  `RETURNING * must not be aliased.`, `users.* AS x` keeps the qualified
  wildcard message, and `id + 1 AS x` keeps `Unsupported RETURNING
  expression: Addition`. A generated repository with aliased `RETURNING` on
  all three statements compiles with components `Long userId`, `Long newId`,
  and `Long uid`/`String fullName`, and `PostgresIntegrationTest` reads
  `userId`, `newId`/`fullName`, and `uid`/`userCode` from PostgreSQL 16.
  `mvn spotless:check` and `mvn test -Dsqlcj.test.requireDocker=true` passed
  (1240 cli and 32 runtime tests, 0 failures, 0 skipped).
- **M23-W5 — A diagnostic for an untyped placeholder — Completed.**
  Implements requirement 5 with the wording decided above, for every
  placeholder that is neither typed by its clause nor the direct operand of a
  cast, in either placeholder form, in any clause or subquery. The boundary is
  the analyzer's report of an untyped placeholder, the documentation's
  rejected forms, and the test expectations of today's accounting message,
  which stays only as the guard. Acceptance evidence is analyzer tests for
  `lower(:email)`, a concatenated pattern, a JSON operator, `ORDER BY :sort`,
  `LIMIT :n + 1`, an uncast placeholder in a subquery, an indexed
  placeholder, and the first of two untyped placeholders, a compiler test of
  the whole one-line diagnostic, `mvn spotless:check`, and `mvn test`.
  Refined by the architect on 2026-10-11 after tracing `QueryAnalyzer` and the
  SQL parser's placeholder walk at `4da43cd` and rerunning the forms through
  JSqlParser 5.4 outside the repository: the analyzer already meets every
  placeholder occurrence in textual order and knows which ones no clause and
  no cast typed, but not where they stand, and the query as written is known
  only to the SQL parser, whose parse-tree walk already reports each
  occurrence. 5.4 gives a node of its own to most functions, operators, and
  predicates, such as `lower(:email)`, `metadata->>'k' = :v`, `$2 + 1`,
  `(:x)::int`, and `ANY($1)`, but none to some operators, such as a `||`
  chain in a `LIKE` pattern, nor to an `ORDER BY`, `GROUP BY`, `WHERE`,
  `HAVING`, or `FETCH` item; it reports an expression list such as the
  `(:a, :b)` of `IN` as an expression, and a `SELECT`, a subquery included,
  as one too. Decided: the SQL parser reports with each placeholder
  occurrence where it stands, and the boundary includes that report, because
  the query as written is syntax. Where it stands is the nearest enclosing
  parse-tree expression that is neither a query nor an expression list,
  written on one line from its tokens, with whatever whitespace or comment
  separates two tokens written as one space; so a placeholder in a
  concatenated pattern is named in its whole `LIKE` predicate, and one in an
  `IN` list in the `IN` predicate. A placeholder with no such expression
  between it and its clause stands alone there and is named by the keyword
  of that clause, the nearest one before it at its own parenthesis depth,
  in upper case: `SELECT`, `DISTINCT ON`, `ON`, `WHERE`, `GROUP BY`,
  `HAVING`, `ORDER BY`, `LIMIT`, `OFFSET`, `FETCH`, `VALUES`, `SET`, or
  `RETURNING`. The analyzer reports the first occurrence in textual order
  that no clause and no cast types, after the clause rules have run, so a
  clause's own rejection still comes first. A bare placeholder projection,
  which a cast makes supported, gets this diagnostic instead of the
  unsupported-expression rejection, while a bare `RETURNING` placeholder
  keeps that rejection, because a cast does not make a computed returning
  item supported. The suggested cast is always `::text`, as decided, since
  sqlcj infers no other type for such a placeholder, and the accounting check
  stays unchanged as the guard.
  Review evidence: the SQL parser reports with each placeholder occurrence
  where it stands, and `QueryAnalyzer` rejects the first occurrence no clause
  and no cast types, and a bare placeholder projection, as `Cannot infer the
  type of <placeholder> in <where>; state it with a cast, such as
  <placeholder>::text`, with the accounting guard unchanged. Verified:
  `lower(:email)`, `name LIKE '%' || :q || '%'`, `metadata->>'k' = :v`,
  `ORDER BY` for `ORDER BY :sort` and `ORDER BY name, $1`, `:n + 1`,
  `t.x = :x` in a subquery, `SELECT` for `SELECT $1 AS x`, `$2 + 1`,
  `lower(name) = :name`, `$2 || 'x'`, `:n || name`, `version + $3`,
  `(:sort)::int`, `ANY($1)`, and `ARRAY[$1, $2]`; `:a` of two untyped
  placeholders; a comment inside the expression written as one space; and
  `GROUP BY`, `HAVING`, `FETCH`, `ON`, `DISTINCT ON`, and `WHERE` for a
  placeholder standing alone. A negated `LIKE` pattern and a clause's
  unsupported cast type still reject first, and the compiler reports the
  whole diagnostic on one located line for a multi-line expression without
  writing files. `mvn spotless:check` and `mvn test` passed (1253 cli tests,
  0 failures, 0 skipped).
- **M23-W6 — No internal error, diagnostics in SQL terms — Completed.**
  Implements requirement 6 with the what-to-write and internal-error
  decisions above: every statement kind, set operation, projection,
  `RETURNING` item, join kind, and Milestone 24 shape the probe or the
  reassessment names fails with a located diagnostic in SQL terms, and an
  unexpected failure in schema parsing, query analysis, or generation is
  reported as the internal-error line. The boundary is the analyzer's
  statement, projection, returning, join, and source rejections, the
  compiler's classification of failures, and the unsupported-query
  documentation. Acceptance evidence is compiler tests asserting the exact
  diagnostic of each named input, a test that an unexpected exception at each
  of the three steps becomes the internal-error line without its message,
  `mvn spotless:check`, and `mvn test`.
  Refined by the architect on 2026-10-11 after tracing `QueryAnalyzer`,
  `SqlcjCompiler`, and the query documentation at `6882912` and rerunning
  every named input through a CLI built from that commit and through
  JSqlParser 5.4 outside the repository: each fails as the reassessment
  recorded, and three more inputs reach a raw `ClassCastException`, a `TABLE`
  statement, a doubly parenthesized `SELECT`, and a parenthesized set
  operation, because the analyzer takes the plain `SELECT` of any `SELECT`
  statement. A CTE named like a schema table, as in
  `WITH users AS (...) SELECT id FROM users`, compiles against the table
  PostgreSQL does not read, and `UPDATE a, b SET ...` and
  `DELETE a FROM a JOIN b ...`, which PostgreSQL rejects, compile on `:exec`,
  because the joined-write checks run only on a returning write. 5.4 reports a
  `LATERAL` subquery as a source of its own kind, a comma list, `CROSS`,
  `NATURAL`, and `USING` each by a flag of its own, and a set operation inside
  parentheses at any depth. Decided:
  - A `SELECT` is analyzed inside its parentheses, at any depth. A set
    operation fails as `UNION, INTERSECT, and EXCEPT are not supported; write
    one query per SELECT.`, and every other statement, a `VALUES` or `TABLE`
    statement included, as `Only SELECT, INSERT, UPDATE, and DELETE statements
    are supported.`, before the annotation is checked.
  - A computed projection fails as `Cannot infer the type of <expression> in
    SELECT; state it with a cast and an alias, such as SUM(amount)::numeric AS
    total`, because a cast makes it supported, and a computed `RETURNING` item
    as `Unsupported RETURNING item <expression>; return columns of the target
    table or *`, because a cast does not; each expression is written as 5.4
    deparses it, on one line.
  - Joins are checked in this order, so a construct with no way through is
    reported before one that can be rewritten: a `LATERAL` source
    (`LATERAL is not supported.`), a subquery source (`A subquery in FROM is
    not supported.`), `FULL JOIN is not supported.`, `RIGHT JOIN is not
    supported; write a LEFT JOIN with the two sources swapped.`,
    `CROSS JOIN is not supported; write JOIN ... ON TRUE.`,
    `NATURAL JOIN is not supported; write the join condition with ON.`,
    `JOIN ... USING is not supported; write the join condition with ON.`, and
    `A comma-separated FROM list is not supported; write JOIN ... ON with the
    join condition.` Every other join modifier, a non-table source, and a
    missing `ON` keep today's messages.
  - An unqualified `FROM` or `JOIN` source named, ignoring case, like a CTE of
    the statement fails as `A common table expression used as a source is not
    supported: <name>` before the schema is consulted, so a CTE shadows a
    table as in PostgreSQL. `INSERT ... SELECT is not supported.` on every
    insert, and the existing `UPDATE ... FROM` and `DELETE ... USING`
    rejections, which also cover the joined forms, apply to every write; a
    multi-row insert keeps `INSERT requires a single VALUES row.`
  - A diagnostic is a SQL parse, schema parse, configuration, or compilation
    exception, or an exception whose class is exactly the unsupported-operation
    or illegal-argument exception and whose message is not blank, so a JDK
    subclass such as `NumberFormatException`, or an empty-message exception of
    an immutable collection, is an internal error. The internal-error lines
    are `Internal error while parsing schema source <file>`, `... while
    analyzing query '<name>' in <file> at line <n>`, and `... while generating
    query group '<group>' in <file>`, each ending `; please report this sqlcj
    defect`; the shared row records and enums, which belong to no group, are
    classified too and named `... while generating the shared row records and
    enums of package '<package>'`.
  The compiler tests inject each unexpected exception through a test seam of
  the compiler, because no input is known to reach one once W6 lands.
  Review evidence: `QueryAnalyzer` analyzes a `SELECT` inside its parentheses
  at any depth and rejects set operations, every other statement kind,
  computed projections and `RETURNING` items, `LATERAL` and subquery sources,
  CTE-named sources, each join kind in the decided order, multi-row `VALUES`,
  `INSERT ... SELECT` with or without parentheses, and `UPDATE ... FROM`,
  comma-joined `UPDATE`, `DELETE ... USING`, and joined `DELETE` on every
  write, each with the decided diagnostic; a parenthesized single `VALUES` row
  still analyzes as at `6882912` (parameters `id`/`name`, binding `[1, 2]`,
  executable SQL unchanged) and keeps the returning rejection.
  `SqlcjCompiler` classifies diagnostics by exact class and non-blank message
  and reports the four internal-error lines without message or class.
  Verified by `SqlcjCompilerIntegrationTest.shouldRejectUnsupportedQueryBeforeWriting`
  per named input, the four internal-error tests through the package-private
  seam, and the analyzer tests per form. `mvn spotless:check` and `mvn test`
  passed (1301 cli and 32 runtime tests, 0 failures, 0 skipped).
- **M23-W7 — Subscripted array columns are not the column — Completed.**
  Implements requirement 7. A column reference with a subscript or a slice is
  an expression: as a projection or a `RETURNING` item it is rejected as other
  computed items are, unless a cast projection states its type; and a
  placeholder compared with, tested against, assigned to, or inserted into it
  is not typed from the column, so it needs a cast and otherwise gets W5's
  diagnostic. The boundary is column-reference resolution in the analyzer and
  the query documentation. Acceptance evidence is analyzer tests per location
  in both subscript and slice forms, a PostgreSQL test of
  `labels[1]::text AS first` and `labels[1] = :label::text`,
  `mvn spotless:check`, and `mvn test`.
  Refined by the architect on 2026-10-11 after tracing `QueryAnalyzer` at
  `8919f88` and running every location through the analyzer compiled from
  that commit and through JSqlParser 5.4, all outside the repository: each
  form still compiles as the reassessment recorded. A projection or
  `RETURNING` item of `labels[1]` or `labels[1:2]` is the whole `List`
  column, and a placeholder beside one in a comparison of either order, an
  `IN` list, `BETWEEN` bounds, a join `ON` condition, a write's `WHERE`, a
  `DO UPDATE ... WHERE`, an `UPDATE` or `DO UPDATE` assignment to
  `labels[1]`, or an `INSERT` into `labels[1]` binds the whole array type;
  `labels[1] LIKE $1` and `labels[1] = ANY($1)` are rejected as if the array
  column were the tested one. 5.4 reports a subscript or slice on a column
  reference as that column carrying an index, in every clause and as an
  `INSERT` or `SET` target, and `(labels)[1]` and `labels[1][2]` as an
  array-access expression, which is already a computed expression; it does
  not parse an open slice such as `labels[2:]`. The executable SQL keeps the
  subscript of an `INSERT` target, because it is copied from the source
  tokens. Decided:
  - Every analyzer rule that takes its type or its name from a column, a
    projection, a `RETURNING` item, a compared, `IN`-, `LIKE`-, `BETWEEN`-, or
    `= ANY`-tested column, and an assigned or inserted column, applies only to
    a column reference without a subscript or slice; a subscripted one falls
    to the rule of any other expression. A rule that only checks that a column
    exists, of a null test, a join condition, an `EXCLUDED` value, or a write
    target, still checks the subscripted column's name, because the array
    column itself must exist.
  - A cast placeholder beside a subscripted column is named after its own
    placeholder, so an indexed one is `param<N>`, because the parameter is
    one element rather than the column's value. Only an indexed cast
    placeholder beside a subscript changes name, so `labels[1] = $1::text`
    names `param1` instead of `labels`.
  - The boundary includes the array section of the PostgreSQL support
    documentation, which lists the analyzed array operations.
  Review evidence: `QueryAnalyzer` treats a column reference as a direct
  column only without a subscript or slice, at every type- or name-taking
  site (projection, `RETURNING` item, both comparison orders, `IN`, `LIKE`,
  `BETWEEN`, `= ANY`, and `INSERT`, `SET`, and `DO UPDATE` targets), while
  the existence-only rules are unchanged. `tags[1]`, `tags[1:2]`, and
  `s.tags[1]` projections and subscripted `RETURNING` items fail with W6's
  computed-item diagnostics; `tags[1]::text AS first` is a non-array `TEXT`
  column and `tags[1:2]::varchar[]` an array column; an uncast placeholder
  beside a subscript or slice gets W5's diagnostic in each location; a cast
  one is `param<N>` or keeps its own name; an unknown subscripted write target
  is still `Column not found`, and a literal assigned to `tags[1]` compiles
  with the subscript kept in the executable SQL. Verified by eight new
  `QueryAnalyzerTest` tests and
  `PostgresIntegrationTest.shouldReadAndFilterASubscriptedArrayElement`; no
  existing test changed. `mvn spotless:check` and `mvn test` with Docker
  required passed (1333 cli and 32 runtime tests, 0 failures, 0 skipped).
- **M23-W8 — Reference service queries and unsupported catalogue — Completed.**
  Proves the milestone acceptance. Adds to the reference service a migration
  that gives `tasks` an assignee and `users` a `deleted_at` column, with any
  unique index the conflict queries need, the equivalent snapshot change, and
  queries for every shape of the probe's "no way through" row that is not
  Milestone 24's or outside `0.1.0`: a dynamic sort, a cast `LIMIT`/`OFFSET` page, `EXISTS` and
  `IN (SELECT ...)` membership checks and a scalar subquery with casts, a
  composite-key join and a filtered left join, `ON CONFLICT DO NOTHING`,
  `ON CONSTRAINT`, a
  conflict-target predicate, `DO UPDATE ... WHERE`, and an aliased
  `RETURNING`, each run against PostgreSQL through the generated repository;
  and an unsupported query catalogue whose every entry (set operations,
  `RIGHT`, `FULL`, `USING`, `NATURAL`, `CROSS`, comma, and `LATERAL` joins,
  the Milestone 24 shapes, an uncast `lower(:email)`, and a placeholder beside
  a subscripted column) is asserted with its exact diagnostic and no file
  written. The history, the snapshot, and the dump still generate one
  byte-identical tree. The boundary is test fixtures and tests; production
  changes only close a gap in W1 to W7 that the fixture exposes. Acceptance
  evidence is `ReferenceServiceIntegrationTest` with Docker required on
  Java 17 and Java 21, `mvn spotless:check`, and `mvn test`.
  Refined by the architect on 2026-10-11 against `edc5a0a`: a draft eleventh
  migration, its snapshot change, the draft queries, and the draft catalogue,
  all sqlcj-owned, were run outside the repository through the analyzer
  compiled from the M23-W7 sources and against `postgres:16-alpine`. The
  migration applies with `psql`; the history, the hand-written snapshot, and
  the `pg_dump --schema-only` snapshot generate one byte-identical tree that
  adds only an `assigneeId` component to `TasksRow` and no new file; every
  draft query compiles and runs as intended, including `ON CONFLICT DO
  NOTHING` affecting zero rows on its second run and the filtered left join
  reading a soft-deleted assignee as `null`; and every catalogue entry fails
  with the diagnostic M23-W5 to M23-W7 decided, writing nothing. No gap in W1
  to W7 was found, so no production change is planned. The probe row names a
  placeholder inside a scalar subquery, which requirement 1 makes compile, so
  the query list adds one. The `email`, `handle`, and `last_login_ip` columns
  of `users` keep unmapped types, so the queries name only its mapped columns.
  Decided:
  - The conflict-target predicate uses a partial unique index that the new
    migration adds, `users_live_handle_key ON users (organization_id, handle)
    WHERE deleted_at IS NULL`, and is written with literals, as the upsert
    documentation advises.
  - The unsupported catalogue is one sqlcj-owned query file per entry under
    `reference-service/unsupported/queries/`, a comment line followed by one
    named query over the reference service's tables. Each entry is asserted
    with its whole located line against the migration history, and the
    directory is asserted to hold exactly the catalogued files, as the
    migration directory is today.
  - The boundary adds the reference service's lines in the `Verified by`
    sections of the query and PostgreSQL documentation, because the latter
    counts the migrations.
  Review evidence: the eleventh migration adds `users.deleted_at`,
  `tasks.assignee_id`, and the partial `users_live_handle_key` index, the
  snapshot matches it, and the history, snapshot, and dump still generate one
  byte-identical, compiling tree with the same file set. The reference service
  gains the eleven queries, and
  `ReferenceServiceIntegrationTest.shouldExecuteTheGeneratedMilestoneQueryShapes`
  runs them through the generated repository against PostgreSQL 16 in its own
  organization, writing no audit entry: the dynamic sort by title and by
  priority and an offset page, the `EXISTS`, `IN (SELECT ...)`, and
  scalar-subquery results, the role read through the composite-key join, the
  filtered left join naming only the live assignee, `JoinOrganization` 1 then
  0, `ChangeMemberRole` 1 then 0 for an unchanged role, `ClaimHandle` present
  then empty for the same live handle, and `AssignTask` `taskId` and
  `newVersion` then empty on a stale version.
  `shouldRejectEachUnsupportedCatalogueQuery` asserts the whole located line
  at line 2 and no output for each of the 17 catalogue files, and
  `shouldCatalogueExactlyTheUnsupportedQueryFiles` the directory's file set.
  No production code changed. `mvn spotless:check` passed, and `mvn test` with
  Docker required passed on Java 17 and Java 21 (1352 cli and 32 runtime
  tests, 23 in `ReferenceServiceIntegrationTest`, 0 failures, 0 skipped).

#### Required

1. A placeholder written as the direct operand of `::type` or
   `CAST(... AS type)` is typed by its cast in every clause of the statement:
   projections, join conditions, `ORDER BY`, `LIMIT`/`OFFSET`, and nested
   `SELECT`s, as well as the clauses where it is accepted today. This makes
   dynamic sorting, as in
   `ORDER BY CASE WHEN :sort::text = 'title' THEN title END`, and placeholders
   inside `EXISTS` and `IN (SELECT ...)` subqueries compile.
2. A join's `ON` condition may be any boolean expression. Its placeholders
   follow the `WHERE` rules, so composite-key joins and filtered joins such as
   `LEFT JOIN users u ON u.id = t.assignee_id AND u.deleted_at IS NULL`
   compile. Nullability still follows the join kind.
3. Accept the conflict clauses that bind nothing sqlcj must type:
   `ON CONFLICT DO NOTHING` without a target, `ON CONFLICT ON CONSTRAINT name`,
   conflict-target predicates, and `DO UPDATE ... WHERE`, whose placeholders
   follow the `WHERE` rules.
4. A `RETURNING` item may carry an alias that names its result component, as a
   `SELECT` item does.
5. A placeholder sqlcj cannot type is reported with its name and the fix, for
   example `Cannot infer the type of :email in lower(:email); state it with a
   cast, such as :email::text`, instead of listing analyzed parameter sets.
6. No internal error reaches the user: set operations, `INSERT ... SELECT`
   (until Milestone 24), and every other probe input produce a located
   diagnostic in SQL terms. An unexpected failure is reported as a sqlcj
   internal error naming the query or schema source, never as a raw exception
   message.
7. A column reference with an array subscript or slice, such as `labels[1]`,
   is an expression rather than the column: it never types a result column or
   a placeholder as the whole array column, so a projection of it needs a cast
   and a placeholder beside it needs a cast or gets requirement 5's
   diagnostic. Added on 2026-10-11 (see [Plan Changes](#plan-changes)).

#### Out of scope

- type inference beyond today's rules (Milestones 24 and 25), including typing
  an array subscript as its element type,
- subqueries, CTEs, and multi-row writes as analyzed shapes (Milestone 24),
- set operations, `LATERAL`, and other join kinds.

#### Acceptance

- Every "no way through" query of the probe that is not Milestone 24's either
  compiles as written or with casts, against PostgreSQL through generated code,
  and the reference service adds those queries.
- Every probe input that stays outside `0.1.0` fails with a located diagnostic
  that says what to write, and every Milestone 24 shape with a located
  diagnostic that names it; none shows an exception class or analyzer
  internals.
- No query compiles to a Java type of a whole array column where it reads or
  binds one subscripted element.
- Focused analyzer and generator tests, generated-source compilation,
  `mvn spotless:check`, and `mvn test` pass.

### Milestone 24 — Nested Queries and Multi-Row Writes

#### Goal

Analyze the subquery, CTE, and multi-row write shapes of an ordinary service
with the same rules as a top-level query, so that their placeholders and
results are typed from columns without casts.

#### Work items

Activated on 2026-10-12 at the owner's request. Reassessed against the
implementation at `3f7c0fa`, its tests, the reference service fixtures, the
external sample, the query documentation, JSqlParser 5.4, which Maven Central
still lists as the latest release on 2026-10-12, and PostgreSQL 16, all run
outside the repository. Every shape below was run with original, sqlcj-owned
SQL over the reference service snapshot through the analyzer built from that
commit, and its PostgreSQL meaning was checked against `postgres:16-alpine`
with the same snapshot; nothing was fetched from sqlc. Every requirement is
unimplemented, as assumed. Facts that correct or shape the plan:

- Requirement 1: an uncast placeholder inside `EXISTS`, `NOT EXISTS`,
  `IN (SELECT ...)`, `NOT IN (SELECT ...)`, a scalar comparison subquery, a
  subquery in a join `ON` condition, and a subquery in a write's `WHERE` fails
  with M23-W5's diagnostic, such as `Cannot infer the type of :userId in
  m.user_id = :userId; state it with a cast, such as :userId::text`, and a
  subquery naming an unknown table or column compiles, because no nested
  `SELECT` is analyzed. 5.4 reports `EXISTS` as an expression whose operand is
  the parenthesized query, `NOT EXISTS` as `NOT` around it, `[NOT] IN` with the
  query as its right operand and the negation as a flag, and a scalar
  comparison with the query as either operand. The `WHERE` rules do not
  descend into `NOT`, so `NOT (id = :id)` fails with the same diagnostic.
- Requirement 2: a CTE read as a source fails as `A common table expression
  used as a source is not supported: <name>`, whatever its form, a `FROM` or
  `JOIN` subquery as `A subquery in FROM is not supported.`, and a returning
  write with a `WITH` clause as `Common table expressions are not supported in
  a returning write.` A CTE that no source reads is carried unanalyzed, so a
  recursive CTE and a data-modifying one, `WITH moved AS (UPDATE ... RETURNING
  id)`, compile today when no source reads them, and an uncast placeholder in
  any CTE body gets M23-W5's diagnostic. 5.4 reports each `WITH` item with a
  recursive flag, a materialized flag that it sets for `MATERIALIZED` and
  `NOT MATERIALIZED` alike, an optional column list, and its body as a
  parenthesized statement; reading a data-modifying body as a `SELECT` throws a
  `ClassCastException`. It reports a subquery source with its alias, the
  alias's column list, or no alias, which PostgreSQL 16 accepts; a doubly
  parenthesized subquery source is a source kind of its own.
- Requirement 3: several `VALUES` rows fail as `INSERT requires a single VALUES
  row.`, and an `INSERT ... SELECT`, parenthesized or not, with a conflict
  clause, and with its `WITH` before the `INSERT` or after the column list,
  fails as `INSERT ... SELECT is not supported.`; a returning insert also
  requires a single unparenthesized row. 5.4 reports one `VALUES` row as one
  parenthesized list of values and several rows as a list of parenthesized
  rows, one row per element even for one column; it reports an
  `INSERT ... SELECT` with a set operation as a set operation, a `WITH` before
  the `INSERT` on the insert, and one after the column list on its `SELECT`.
- Requirement 4: `UPDATE ... FROM`, a comma-joined `UPDATE`,
  `DELETE ... USING`, and a joined `DELETE` keep M23-W6's rejections. 5.4
  reports `UPDATE ... FROM` as one source followed by joins, a comma-separated
  source as a simple join, and `DELETE ... USING` as a list of sources, typed
  as tables even when an element is a parenthesized subquery; it cannot read a
  join inside `USING`. In PostgreSQL 16, a bare `RETURNING *` of
  `UPDATE ... FROM` or `DELETE ... USING` returns every source's columns after
  the target's, `RETURNING t.*` returns the target's row alone, a column of an
  added source may be returned, and neither a join condition inside `FROM` nor
  a subquery source there may reference the target (`invalid reference to
  FROM-clause entry`).
- Requirement 5: binding order already comes from the SQL parser's textual
  walk of every clause and depth (M23-W1), so each newly analyzed scope only
  types its placeholders; the accounting guard stays.
- PostgreSQL resolves an unqualified column in the innermost query level that
  has it and reports it ambiguous only within that level; a derived source
  with two columns of one name makes a reference to that name ambiguous while
  `*` returns both; and a CTE column list may name fewer columns than its
  body.
- The reference service's `ListVisibleProjects` and `ListOrganizationTasks`
  cast their subquery placeholders only because subqueries are not analyzed.
  `ListTasksWithRecentComments` reads a scalar subquery in its projection,
  which requirement 1 does not name, so its cast stays. The catalogue entries
  `cte_source`, `from_subquery`, `multi_row_values`, `insert_select`,
  `update_from`, and `delete_using` are this milestone's shapes. The external
  sample uses none of them, no documentation outside the query documentation
  describes them, and no Milestone 24 text calls for fetching anything from
  sqlc.

Decided by the architect on 2026-10-12, under the rule that pre-release
decisions are revisable by evidence:

- **Implementation order:** M24-W1 to M24-W5 as numbered. Derived sources come
  first because analyzing `WHERE` subqueries first would reject a CTE read
  inside a subquery, which compiles today, until derived sources land, and
  because W2's correlated scopes reuse W1's analysis of a nested `SELECT`; W3
  and W4 analyze nested `SELECT`s and sources through W1 and W2; W5 proves the
  milestone on the reference service last.
- **Nested queries.** A nested `SELECT` that sqlcj analyzes — a CTE body, a
  `FROM` or `JOIN` subquery, a subquery requirement 1 names, and the `SELECT`
  of an `INSERT` — is analyzed by the rules of a top-level `SELECT`: its
  sources resolve, its joins follow the join rules, its `ON`, `WHERE`, `LIMIT`,
  and `OFFSET` placeholders are typed by those clause rules, and its own `WITH`
  list is analyzed. Its body is classified inside its parentheses as a
  top-level `SELECT` is, so a set operation or another statement form fails
  with that diagnostic. Each nested query is analyzed exactly once. An indexed
  cast placeholder there is named as its clause names it at top level, so
  `orders.total = $1::numeric` in a `WITH` body becomes `total` instead of
  `param1`. A subquery anywhere else — a projection, `GROUP BY`, `HAVING`,
  `ORDER BY`, a written value, `RETURNING`, or a function argument — keeps
  today's handling, in which only a cast types its placeholders.
- **Derived sources.** A CTE or a subquery source is a source whose columns
  are its body's result columns, in order, with their names, types, enum,
  array, and blank-padding facts and their nullability, a left join of the
  derived source making every column nullable. Its body's projection follows
  the top-level rules, including the rejection of a computed item without a
  cast and of an unmapped column, `*` included. A derived source never returns
  a shared row record, so `SELECT * FROM <cte>` returns a query-specific
  result even when the CTE is named like a table. A subquery source requires an
  alias, `A subquery in FROM requires an alias, such as (SELECT ...) AS
  recent.`, and sees no source of the query around it, as PostgreSQL scopes a
  subquery that is not `LATERAL`. A derived source whose columns repeat a name,
  ignoring case, fails as `Derived source <name> has more than one column
  named <column>; give each a distinct alias.`, and a column list on a CTE or a
  subquery alias fails as `A column list on <name> is not supported; alias the
  columns in its SELECT instead.`, because ignoring either would mis-model the
  source silently.
- **CTEs.** Every CTE of every statement, `INSERT`, `UPDATE`, and `DELETE`
  included, is analyzed in order, whether or not a source reads it, so its
  placeholders are typed from columns. It sees the CTEs before it in its own
  list and those of every enclosing query, and an unqualified source named
  like a CTE in scope, ignoring case and innermost first, reads that CTE
  instead of the schema table. `MATERIALIZED` and `NOT MATERIALIZED` are
  accepted. `WITH RECURSIVE` fails as `WITH RECURSIVE is not supported.` and a
  data-modifying CTE as `A data-modifying statement in WITH is not supported;
  run it as a query of its own.`, read as a source or not. The rejection of a
  CTE in a returning write is lifted, because its body is now analyzed.
- **Scopes and correlation.** A subquery requirement 1 names is the operand of
  `EXISTS` or of `NOT EXISTS`, the query of an `IN` or `NOT IN`, whatever its
  left operand, or an operand of a comparison, wherever the `WHERE` rules reach
  it through `AND`, `OR`, `NOT`, and parentheses: in a read's or a write's
  `WHERE`, a join `ON` condition, a conflict predicate, and the `WHERE` of
  another analyzed nested query. The `WHERE` rules therefore descend into
  `NOT`, so `NOT (id = :id)` types its placeholder. The subquery sees its own
  sources first and then each enclosing scope outward, a join condition's
  scope being the sources up to its joined source. An unqualified column
  resolves in the innermost level that has it and is ambiguous only within
  that level; a qualified one resolves through the innermost source of that
  exposed name. An unknown column fails as `Column not found in sources
  <sources>: <column>`, naming every visible source from the innermost
  outward, and an ambiguous one keeps today's message. The subquery's select
  list types no result column: a direct column there resolves, while its type
  need not be mapped, and every other item reaches the database as written.
  The `WHERE` rules still resolve only the columns they resolve today.
- **Multi-row and `SELECT`-sourced inserts.** Each `VALUES` row pairs with the
  column list by position and must match its count, and each value is typed by
  its target column as a single row's is; a named placeholder repeated across
  rows is one parameter. The `SELECT` of an `INSERT`, parenthesized or not,
  pairs its items with the column list by position, a wildcard standing for
  its source's columns in order, and must match its count: a placeholder item
  is typed and named by its target column, a cast placeholder item is typed by
  its cast and named by its target column, a direct column resolves against
  the `SELECT`'s sources, and any other item reaches the database as written;
  a subscripted target types nothing, as in `VALUES`. A set operation source
  fails with the set-operation diagnostic. Both forms accept `ON CONFLICT` and
  `RETURNING`, so a returning insert no longer requires a single row.
- **Joined writes.** `UPDATE ... FROM` takes a source followed by inner or left
  joins or by further comma-separated sources, and `DELETE ... USING` a
  comma-separated list, because 5.4 cannot read a join there; each source is a
  table or a derived source. A comma list is accepted here, unlike in a
  `SELECT`, because it is PostgreSQL's documented form of these clauses and
  the only multi-source form 5.4 reads in `USING`. A join condition inside
  `FROM` sees only the sources of `FROM` up to its joined source. `SET`
  values, `WHERE`, and `RETURNING` see the target and then the added sources,
  whose exposed names must differ. A returned direct column of any of them is
  typed from its column, nullable when its source is left-joined;
  `RETURNING <target>.*` alone returns the target's shared row record on any
  write, and beside other items its columns; a bare `RETURNING *` on a joined
  write fails as `RETURNING * is not supported with UPDATE ... FROM or
  DELETE ... USING; write RETURNING <target>.* for the target's row.`, and a
  wildcard of another source as `RETURNING <source>.* is not supported; return
  the columns of <source> by name.` `UPDATE a, b` and `DELETE a FROM a JOIN b`
  keep their rejections.
- **Catalogue.** Each work item deletes the unsupported-catalogue entries it
  makes compile, with their expected diagnostics, and W5 moves those shapes
  into the reference service's queries.

- **M24-W1 — CTEs and `FROM` subqueries as derived sources — Completed.**
  Implements requirement 2, and requirement 5 for its scopes, with the
  nested-query, derived-source, and CTE decisions above: a CTE read by a
  `FROM` or `JOIN` source, and a subquery source, compile, their columns typed
  from their bodies, and every CTE body's placeholders are typed by its clause
  rules. The boundary is source resolution and nested `SELECT` analysis in the
  analyzer, the read, write, unsupported, and parameter sections of the query
  documentation, the M23-W6 rejection tests of these shapes, and the
  `cte_source` and `from_subquery` catalogue entries. Subqueries in
  predicates stay W2's, and `INSERT ... SELECT` and joined writes stay
  rejected. Acceptance evidence is analyzer tests of a CTE source whose
  placeholder is typed from its column, chained CTEs, a CTE shadowing a table,
  a CTE inside a subquery source, a `JOIN` subquery and a left-joined derived
  source with their nullability, `COUNT(*)` and cast columns through a derived
  source, `SELECT *` over a derived source returning a query-specific result,
  textual binding order across a CTE body, a subquery source, and the outer
  query, a returning write with a CTE, and each decided rejection, including a
  data-modifying CTE reported without an internal error;
  generated-source compilation; a PostgreSQL test running a CTE report and a
  subquery source through generated code; `mvn spotless:check`; and
  `mvn test`.
  Review evidence: a `FROM` or `JOIN` source naming a CTE in scope, matched
  ignoring case and innermost first, and an aliased subquery source resolve as
  derived sources whose columns come from their analyzed bodies, nullable when
  left-joined, with `SELECT *` over one returning a query-specific result; a
  CTE body reads the schema table it is named like, a subquery source sees no
  outer source, and every CTE of a `SELECT`, `INSERT`, `UPDATE`, and `DELETE`
  is analyzed once in order, typing and naming its placeholders by its clause
  rules (`orders.total = $1::numeric` in a body names `total`). `WITH
  RECURSIVE`, a data-modifying body (as a diagnostic, not an internal error),
  a missing subquery alias, a column list on a CTE or subquery alias, and a
  repeated derived column name are rejected with the decided messages; a set
  operation or `VALUES` body keeps its existing diagnostic; `LATERAL` and a
  doubly parenthesized source keep theirs; the returning-write CTE rejection
  and the `cte_source` and `from_subquery` catalogue entries are removed.
  Placeholders bind in textual order across CTE bodies, subquery sources, and
  the outer query, and executable SQL is unchanged. `mvn spotless:check` and
  `mvn test -Dsqlcj.test.requireDocker=true` passed (1384 tests, 0 skipped),
  including `SqlcjCompilerIntegrationTest.shouldGenerateCompilableJavaForDerivedSources`
  and `PostgresIntegrationTest.shouldExecuteGeneratedDerivedSourcesAgainstPostgres`.
- **M24-W2 — Subqueries in predicates — Completed.** Implements requirement 1,
  and requirement 5 for its scopes, with the scopes-and-correlation decision:
  `EXISTS`, `NOT EXISTS`, `[NOT] IN (SELECT ...)`, and scalar comparison
  subqueries type their placeholders from columns of their own and of
  enclosing sources. The boundary is predicate analysis and column resolution
  across scopes in the analyzer and the predicate, parameter, write, and
  unsupported sections of the query documentation. Refined when started: the
  `WITH` clauses of a write are in scope of the predicate subqueries of its
  `WHERE` and conflict predicates, as the CTE decision requires; the join
  conditions and select-list columns of a predicate subquery resolve through
  the same scopes as its `WHERE`; and `= ANY (SELECT ...)`,
  `IN ((SELECT ...))`, and a `WITH` body or subquery source nested in a
  predicate subquery, which still sees no source around it, keep their current
  handling. Acceptance evidence is analyzer tests
  per form in a read's `WHERE`, a join condition, and a write's `WHERE`, a
  correlated placeholder typed from an outer column, inner shadowing, an
  unknown and an ambiguous column in a subquery naming the visible sources, a
  nested subquery, a placeholder repeated across scopes, textual binding order
  across scopes, generated-source compilation, a PostgreSQL test of a
  membership check and a `NOT EXISTS` filter without casts,
  `mvn spotless:check`, and `mvn test`.
  Review evidence: an uncast placeholder of `EXISTS`, `NOT EXISTS`,
  `[NOT] IN (SELECT ...)` with a column or computed tested value, and a
  comparison subquery as either operand is typed and named from the subquery's
  own column in a read's `WHERE`, and of a subquery in a join `ON` condition,
  an `UPDATE` and a `DELETE` `WHERE`, and a `DO UPDATE` predicate over the
  target and `EXCLUDED`, with the executable SQL unchanged. A column resolves
  in its own level and then each enclosing level outward, qualified and
  unqualified, a join condition seeing only the sources up to its joined
  source; an inner unqualified column and a reused alias shadow the enclosing
  ones; one name in two levels is accepted while two sources of one level are
  ambiguous; an unknown column names every visible source innermost outward.
  Nested subqueries type from every level, a named placeholder repeated across
  levels is one parameter, indexed placeholders bind in textual order, and
  `NOT (id = :id)` is typed. The subquery's select list accepts `1`, `*`, and
  an unmapped column and rejects an unknown one; a set operation inside it
  keeps the set-operation diagnostic; a write's `WITH` clause is read by its
  `WHERE` subquery; `= ANY (SELECT ...)` keeps its untyped rejection.
  `mvn spotless:check` and `mvn test -Dsqlcj.test.requireDocker=true` passed
  (1418 tests, 0 skipped), including
  `SqlcjCompilerIntegrationTest.shouldGenerateCompilableJavaForPredicateSubqueries`,
  `PostgresIntegrationTest.shouldExecuteGeneratedUncastPredicateSubqueriesAgainstPostgres`,
  and `ReferenceServiceIntegrationTest` unchanged.
- **M24-W3 — Multi-row and `SELECT`-sourced inserts — Completed.**
  Implements requirement 3, and requirement 5 for its scopes, with the insert
  decision. The boundary is insert analysis and the write, upsert,
  `RETURNING`, parameter, and unsupported sections of the query documentation,
  the M23-W6 rejection tests of these shapes, and the `multi_row_values` and
  `insert_select` catalogue entries. Refined when started: the insert's row
  source is classified through every parenthesis level, as a top-level
  `SELECT` is, so a `VALUES` list at any depth is the rows and a returning
  insert accepts every accepted source; the insert's `SELECT` may omit `FROM`
  and then has no sources, as requirement 3's own example does, while every
  other analyzed `SELECT` keeps requiring one; the `WITH` list of each
  parenthesis level of the row source, `VALUES` included, is analyzed with the
  insert's own `WITH` clauses in scope; the `SELECT`'s wildcards and direct
  columns resolve without requiring a mapped type, since they bind nothing;
  each row and the `SELECT` keep `INSERT column and value counts must match.`;
  and a `VALUES` element that is not a parenthesized row, and a column list
  with no row source (`DEFAULT VALUES`), fail as `INSERT requires
  parenthesized VALUES rows or a SELECT.` instead of today's stale message and
  internal error. Acceptance evidence is analyzer tests
  of several rows with a repeated named placeholder and with indexed
  placeholders, `INSERT ... SELECT :projectId, unnest(:userIds::bigint[])`, an
  `INSERT ... SELECT` from a table with a `WHERE` placeholder and a wildcard,
  both `WITH` placements, a count mismatch, a set operation, `ON CONFLICT` and
  `RETURNING` on both forms, generated-source compilation, a PostgreSQL test of
  a multi-row insert and an `unnest` bulk insert, `mvn spotless:check`, and
  `mvn test`.
  Review evidence: several `VALUES` rows pair with the column list by position,
  a named placeholder repeated across rows is one parameter bound at each
  position, and indexed rows repeat `$1` beside `DEFAULT`; `VALUES` rows inside
  parentheses at any depth are accepted, `(VALUES ($1)) RETURNING` included.
  `INSERT ... SELECT :projectId, unnest(:userIds::bigint[])` types `projectId`
  as `BIGINT` from its target and `userIds` as a `BIGINT` array from its cast;
  `SELECT $1::bigint` is named after its target column and a subscripted
  target types nothing. A `SELECT` over a table types its join `ON`, `WHERE`,
  `LIMIT`, and `OFFSET` placeholders by the clause rules, a predicate subquery
  in its `WHERE` and a subquery source included, counts `*` and `t.*` per
  source column, and sees no target (`memberships.project_id` is an unknown
  qualifier). Both `WITH` placements, and a `WITH` on a `VALUES` source, are
  analyzed with their bodies typed from columns. A count mismatch, a set
  operation plain or parenthesized, `VALUES ROW(1)`, `VALUES (1), 2`, and
  `DEFAULT VALUES` fail with the decided messages; `ON CONFLICT DO NOTHING`
  and `DO UPDATE ... WHERE`, `RETURNING *` with the row table, and a returned
  column list work on both forms; placeholders bind in textual order across
  the insert's `WITH`, the `SELECT` items and `WHERE`, and the conflict clause,
  with executable SQL unchanged. `mvn spotless:check` and
  `mvn test -Dsqlcj.test.requireDocker=true` passed (1436 tests, 0 skipped),
  including
  `SqlcjCompilerIntegrationTest.shouldGenerateCompilableJavaForMultiRowAndSelectSourcedInserts`
  and
  `PostgresIntegrationTest.shouldExecuteGeneratedMultiRowAndSelectSourcedInsertsAgainstPostgres`.
- **M24-W4 — `UPDATE ... FROM` and `DELETE ... USING` — Completed.**
  Implements
  requirement 4, and requirement 5 for its scopes, with the joined-write
  decision. The boundary is write source analysis and returning analysis in
  the analyzer, the write, `RETURNING`, and unsupported sections of the query
  documentation, and the `update_from` and `delete_using` catalogue entries.
  Refined when started: a join condition inside `FROM` sees only the sources
  from the comma-separated item it joins onto up to its joined source, because
  PostgreSQL binds `JOIN` more tightly than a comma; a qualified `RETURNING`
  wildcard resolves its qualifier among the write's sources, an aliased one
  failing as `RETURNING <qualifier>.* must not be aliased.`; the remaining
  multi-table forms fail as `UPDATE of several tables is not supported; write
  the other sources in UPDATE ... FROM.` and `A joined DELETE is not supported;
  write the other sources in DELETE ... USING.`; and the M23-W6 rejection
  tests of these shapes and the query documentation's `Verified by` lines
  naming them are in the boundary.
  Acceptance evidence is analyzer tests of a `WHERE` placeholder typed from an
  added source, a `FROM` join and a comma list, a derived and a CTE source, a
  join condition that names the target rejected, returned columns of the
  target and of a left-joined source, `RETURNING <target>.*`, the bare `*` and
  other-source wildcard rejections, textual binding order across `SET`,
  `FROM`, and `WHERE`, generated-source compilation, a PostgreSQL test of a
  joined update and a `USING` delete, `mvn spotless:check`, and `mvn test`.
  Review evidence: an `UPDATE ... FROM` source, inner- or left-joined source,
  and comma-separated source, and a comma-separated `USING` list, each a table,
  a CTE of the write, or an aliased subquery, are in one `WHERE` level after
  the target, so a `WHERE` placeholder is typed and named from an added
  source's column, a subquery body and a CTE body type their own placeholders,
  an ambiguous unqualified column is reported across the target and the added
  sources, and a repeated exposed name, the target's included, is rejected. A
  join condition inside `FROM` types its placeholders over its own comma item
  up to its joined source, so naming the target or an earlier comma item is an
  unknown qualifier; `LATERAL`, an unaliased subquery, and the unsupported join
  kinds keep their diagnostics. A returned column of any source is typed from
  its column, nullable when left-joined; `RETURNING <target>.*`, matched
  ignoring case, returns the target's shared row on a joined and a
  single-table `INSERT`, `UPDATE`, and `DELETE`, and beside another item a
  query-specific result. A bare `*` on a joined write, another source's
  wildcard, an aliased or modified wildcard, an unknown qualifier (`old.*`),
  and the two remaining multi-table forms fail with the decided messages; the
  `update_from` and `delete_using` catalogue entries are removed. Placeholders
  bind in textual order across `SET`, a `FROM` subquery or join, and `WHERE`,
  indexed and named, with executable SQL unchanged. `mvn spotless:check` and
  `mvn clean test -Dsqlcj.test.requireDocker=true` passed (1461 tests, 0
  skipped), including
  `SqlcjCompilerIntegrationTest.shouldGenerateCompilableJavaForJoinedWrites`
  and `PostgresIntegrationTest.shouldExecuteGeneratedJoinedWritesAgainstPostgres`.
- **M24-W5 — Reference service nested queries and multi-row writes —
  Completed.** Proves the milestone acceptance. The reference service drops the
  casts its membership checks no longer need and adds a `NOT EXISTS` check, a
  CTE report, a subquery source, a multi-row insert, an `unnest` bulk insert,
  an `INSERT ... SELECT` from a table, a joined update, and a `USING` delete,
  each run against PostgreSQL through the generated repository; the
  unsupported catalogue adds a recursive and a data-modifying CTE. The
  history, the snapshot, and the dump still generate one byte-identical tree.
  The boundary is test fixtures, tests, and their `Verified by` documentation;
  production changes only close a gap in W1 to W4 that the fixture exposes.
  Acceptance evidence is `ReferenceServiceIntegrationTest` with Docker
  required on Java 17 and Java 21, `mvn spotless:check`, and `mvn test`.
  Review evidence: `ListVisibleProjects` and `ListOrganizationTasks` drop their
  `::bigint` casts and pass their existing assertions unchanged, while
  `ListTasksWithRecentComments` keeps its projection cast; the history, the
  hand-written snapshot, and the `pg_dump` snapshot still generate one
  byte-identical tree with an unchanged file set.
  `shouldExecuteTheGeneratedNestedQueriesAndMultiRowWrites` runs, in its own
  organization and without an audit write, a two-row insert sharing
  `:projectId` (2 rows), the `unnest` bulk insert with `ON CONFLICT DO NOTHING`
  (2, then 0), the `NOT EXISTS` check before and after a correlated author's
  comment, the CTE report at two priority bounds, the aliased subquery source
  with its `LIMIT` placeholder, the `INSERT ... SELECT` from `tasks` (2 rows),
  the `UPDATE ... FROM projects` returning `TasksRow` for each raised task, and
  the `DELETE ... USING tasks` (1 row). The `recursive_cte.sql` and
  `data_modifying_cte.sql` catalogue entries fail with their whole located W1
  diagnostic line and write nothing. No production source changed.
  `mvn spotless:check` and `mvn test -Dsqlcj.test.requireDocker=true` passed on
  Java 17 (1464 cli and 32 runtime tests, 0 failures, 0 skipped), and
  `ReferenceServiceIntegrationTest` with Docker required passed on Java 21 (20
  tests, 0 skipped).
  Refined by the architect on 2026-10-14 against `dbcb95b`: the draft queries
  and catalogue entries below, all sqlcj-owned, were run outside the
  repository through the analyzer compiled from that commit and against
  `postgres:16-alpine` with the reference migrations applied. Every draft
  query compiles with its placeholders typed from columns; the history, the
  hand-written snapshot, and the `pg_dump --schema-only` snapshot generate one
  byte-identical tree with the same file set, in which the two membership
  checks change only their executable SQL; every query runs as intended; and
  the two catalogue entries fail with the decided W1 messages. No gap in W1 to
  W4 was found, so no production change is planned. Decided:
  - The milestone's shapes move into the reference service as queries over
    the test's own organization, because the reference tests share one
    database: the former `insert_select` shape copies a project's open tasks
    into another project instead of archiving the audit log, whose archive
    another test asserts empty, and the joined update and `USING` delete are
    filtered to one organization or project.
  - The queries added are `ListTasksUncommentedBy` (`NOT EXISTS`),
    `CountOpenTasksByProject` (a CTE report joined to `projects`),
    `ListRecentTaskTitles` (an aliased subquery source with a `LIMIT`
    placeholder), `CreateTwoTasks` (two `VALUES` rows sharing `:projectId`),
    `AddMembers` (`INSERT ... SELECT :organizationId,
    unnest(:userIds::bigint[]) ON CONFLICT DO NOTHING`), `CopyOpenTasks`
    (`INSERT ... SELECT` from `tasks`), `RaiseProjectTaskPriority`
    (`UPDATE ... FROM projects` returning `t.*` as the shared task row), and
    `DeleteProjectComments` (`DELETE ... USING tasks`); the catalogue adds
    `recursive_cte.sql` and `data_modifying_cte.sql`.
  - They run in a test method of their own, beside the Milestone 23 shapes,
    and the boundary adds the reference service's lines in the query
    documentation's `Verified by` section.

#### Required

1. A subquery in `WHERE` or a join condition (`EXISTS`, `NOT EXISTS`,
   `IN (SELECT ...)`, or a scalar comparison) is analyzed in its own source
   scope, with the outer sources visible for correlation, and its placeholders
   are typed by the predicate rules.
2. A non-recursive CTE, and a subquery in `FROM`, whose `SELECT` is itself in
   the supported subset, is a source whose columns take that `SELECT`'s
   analyzed names, types, and nullability. This is one mechanism; it is also
   the future basis for views as sources.
3. An `INSERT` may write several `VALUES` rows, each value typed by its target
   column, or a `SELECT` whose items are typed against the target columns by
   position, so `INSERT ... SELECT :projectId, unnest(:userIds::bigint[])`
   compiles.
4. `UPDATE ... FROM` and `DELETE ... USING` add their sources to the scope of
   `SET`, `WHERE`, and `RETURNING`; a returned column of any of those sources
   is typed from its column, and the only full row a joined write returns is
   the target's, written `RETURNING <target>.*`. Amended on 2026-10-12 (see
   [Plan Changes](#plan-changes)).
5. Parameter order stays first textual occurrence across all scopes, and every
   occurrence is bound exactly once.

#### Out of scope

- recursive and data-modifying CTEs, set operations, `LATERAL`,
- views as sources,
- JDBC batch execution and `COPY`.

#### Acceptance

- The reference service's membership checks, CTE reports, bulk inserts, and
  joined updates compile without casts where a column gives the type, and run
  against PostgreSQL.
- Correlated subqueries resolve outer columns; an unknown or ambiguous column in
  any scope is rejected naming the scope's sources.
- Focused analyzer, parameter-order, generator, and PostgreSQL tests,
  `mvn spotless:check`, and `mvn test` pass.

### Milestone 25 — Result Shapes Settled

#### Goal

Settle the generated result API for joins and aggregates before `0.1.0` makes
it a contract.

#### Work items

Activated on 2026-10-16 at the owner's request. Reassessed against the
implementation at `60e2327`, its tests, the reference service fixtures, the
external sample, the query and configuration documentation, JSqlParser 5.4,
and PostgreSQL 16, all run outside the repository. Every shape below was run
with original, sqlcj-owned SQL over the reference service snapshot through the
CLI built from that commit, and its PostgreSQL meaning and result type were
checked against `postgres:16-alpine` (16.15) with the same snapshot; nothing
was fetched from sqlc. Every requirement is unimplemented, as assumed. Facts
that correct or shape the plan:

- Requirement 1: a wildcard expands into flat columns wherever it stands.
  `SELECT t.*, p.* FROM tasks t JOIN projects p ...` returns one record of
  seventeen components, `id1` to `createdAt2`; `SELECT *, now()::timestamptz
  AS at FROM tasks` and `DELETE FROM comments RETURNING *, id` flatten the row
  beside the other item; and `UPDATE tasks t ... FROM projects p ... RETURNING
  t.*, p.code` flattens the target's row. `SELECT t.* FROM tasks t JOIN
  projects p ...`, which reads exactly the `tasks` row, returns a
  query-specific record of its columns, because M16-W2 recognizes a full row
  only over a single source, while M24-W4 returns the target's shared row for
  `RETURNING <target>.*` on a joined write. The wildcard expansion is shared by
  the top-level `SELECT`, every CTE and subquery body, and the `SELECT` of an
  `INSERT`, where a wildcard defines derived columns or pairs with target
  columns instead of forming a result. A row record is generated only for a
  table some query returns whole, from that query's analyzed columns, and the
  rows of two groups are compared column by column, nullability included,
  while a left-joined wildcard marks every column nullable. Each repository's
  row mapper reads its row from position 1. A wildcard over a table with an
  unmapped column fails as `Column 'email' has unsupported type
  EMAIL_ADDRESS`, which keeps the reference service's `users.*` and
  `organizations.*` rejected.
- Requirement 2: a left-joined wildcard flattens into nullable columns, nothing
  reads row absence, and the runtime has no helper for it. PostgreSQL 16 reads
  every column of an unmatched left-joined row as `NULL`, a `NOT NULL` array
  included.
- Requirement 3: only an aliased, unqualified `COUNT(*)` is typed;
  `COUNT(t.id)`, `COUNT(DISTINCT t.id)`, and `MAX(c.created_at)` fail as
  `Cannot infer the type of <expression> in SELECT; state it with a cast ...`,
  and the query documentation lists `COUNT(column)`, `COUNT(DISTINCT column)`,
  and `COUNT(t.*)` as rejected. 5.4 reports each aggregate as a function with
  its name, a `DISTINCT` flag, an `ALL` flag, and its arguments: a column, a
  subscripted column in `MIN(labels[1])`, or a wildcard in `COUNT(t.*)`;
  `pg_catalog.max(x)` has a two-part name, and the `FILTER` and `OVER` forms
  are analytic expressions of their own. PostgreSQL 16 types `count` as
  `bigint`, `0` over no rows; `sum` of `smallint` and `integer` as `bigint`,
  of `bigint` and `numeric` as `numeric`, of `real` as `real`, and of
  `double precision` as `double precision`; `avg` of `smallint`, `integer`,
  `bigint`, and `numeric` as `numeric` and of `real` and `double precision` as
  `double precision`; and `min` and `max` as their argument's type, an enum,
  `char`, temporal, and array type included, except that `varchar` reads as
  `text`, which maps to the same Java type. `sum` and `avg` over no rows are
  `NULL`. It defines no `min` or `max` for `boolean`, `uuid`, `json`, `jsonb`,
  or `bytea`, and no `sum` for `text` or an array.
- The reference service has no list-with-count or latest-activity query yet:
  `SELECT p.*, COUNT(t.id) AS open_tasks ... GROUP BY p.id` and `SELECT t.id,
  MAX(c.created_at) AS last_comment_at ... GROUP BY t.id` fail today, and
  PostgreSQL 16 runs both, grouping by the primary key. None of its current
  queries changes shape under this milestone, because each of its wildcards is
  a lone single-source one or `RETURNING t.*`, and the history, the snapshot,
  and the dump keep generating one tree. `ListTasksWithRecentComments` keeps
  its cast, because its count is a scalar subquery in the projection, which
  requirement 3 does not name. The external sample uses neither a joined
  wildcard nor an aggregate other than `COUNT(*)`.

Decided by the architect on 2026-10-16, under the rule that pre-release
decisions are revisable by evidence:

- **Implementation order:** M25-W1 to M25-W4 as numbered. Embedding rows of
  sources that are not left-joined comes first, because it settles the result
  model, the row-record recording, and the mapping positions to which W2 adds
  absence; until W2, a left-joined wildcard keeps today's nullable flat
  columns, which are correct. Aggregates are independent of both, and W4
  proves the acceptance on the reference service last.
- **Embedded rows.** In the result of a top-level `SELECT` and of `RETURNING`,
  a wildcard of a table source — `<source>.*`, or each table source of a bare
  `*` — becomes one component in its written position, of the table's shared
  `<TableName>Row`, whose columns are the table's own in schema order with
  their declared nullability, so an embedded row and a whole-row result are one
  record. A derived source's wildcard stays flat columns, because a derived
  source has no row record, and a wildcard in a CTE or subquery body, in a
  predicate subquery, or in the `SELECT` of an `INSERT` keeps today's handling.
  The embedded row of a source that is not left-joined is non-null.
- **One table's full row.** A projection that is one wildcard covering exactly
  one table source that is not left-joined — `*` over a single source, or
  `<source>.*` beside any other sources — returns that table's shared row, as
  `RETURNING <target>.*` does on a joined write, instead of a query-specific
  record wrapping one embedded row. A lone wildcard of a left-joined source is a
  result of one component, because its row may be absent.
- **Component names.** An embedded component is named after its source's
  exposed name by the existing component rules: lower camel case, `_` after a
  keyword, and numeric suffixes in selected order for a repeated name, so
  `SELECT users.*, COUNT(*) AS users` generates `users1` and `users2`. No new
  naming rule is added.
- **Row records and mapping.** The package generates a row record for every
  table a query returns whole or embeds, once, in first-use order, and the
  comparison of two groups' rows covers embedded rows. A repository generates a
  row mapper field only for a row one of its queries returns whole; an embedded
  row is constructed inline in its result's mapper from the result's own
  positions, so the JDBC read order stays the selected-column order.
- **Absent rows.** A left-joined source's embedded row is a nullable component,
  `null` when every one of its columns reads SQL `NULL`. The generated mapper
  tests that through a new runtime method,
  `dev.sqlcj.runtime.SqlRow.isNull(resultSet, firstPosition, lastPosition)`,
  written fully qualified as `SqlArray` is, so the multi-column test stays out
  of generated code. The documentation states that a matched row whose every
  column is `NULL` reads as absent, which no table with a `NOT NULL` column
  can produce.
- **Aggregates.** An aliased, unqualified, case-insensitive `COUNT`, `MIN`,
  `MAX`, `SUM`, or `AVG` with one argument, with or without `DISTINCT` or
  `ALL`, is typed in every analyzed projection, derived bodies included.
  `COUNT` is a non-null `BIGINT` of any argument except a `*` written with
  `DISTINCT` or `ALL`, which PostgreSQL's grammar rejects and which keeps
  today's computed-projection diagnostic; a direct column argument
  resolves against the query's sources whether or not its type is mapped,
  `COUNT(<source>.*)` resolves its qualifier, and any other argument reaches
  the database as written. `MIN`, `MAX`, `SUM`, and `AVG` take one direct,
  unsubscripted column and are nullable: `MIN` and `MAX` take the column's
  type, enum and array included, except a scalar `BOOLEAN`, `UUID`, `BYTEA`,
  `JSON`, or `JSONB`; `SUM` of `SMALLINT` or `INTEGER` is `BIGINT`, of `BIGINT`
  or `DECIMAL` is `DECIMAL`, of `REAL` is `REAL`, and of `DOUBLE_PRECISION` is
  `DOUBLE_PRECISION`; `AVG` of `SMALLINT`, `INTEGER`, `BIGINT`, or `DECIMAL` is
  `DECIMAL` and of `REAL` or `DOUBLE_PRECISION` is `DOUBLE_PRECISION`. A column
  of an unmapped type keeps the unsupported-type diagnostic; a type outside
  these rules, which PostgreSQL 16 defines no such aggregate for, another
  argument, a qualified name, and the `FILTER` and `OVER` forms keep today's
  computed-projection diagnostic. A missing alias fails as `<expression>
  requires a result alias, such as <expression> AS total.`, which for
  `COUNT(*)` is today's message.
- **Catalogue.** No unsupported-catalogue entry is a shape of this milestone,
  so W4 adds reference queries and removes no entry.

- **M25-W1 — Table rows embed their shared row record — Completed.**
  Implements requirement 1 for every source that is not left-joined, with the
  embedded-row, full-row, component-name, and row-record decisions:
  `SELECT t.*, p.* FROM tasks t JOIN projects p ...` returns `TasksRow t` and
  `ProjectsRow p`, a wildcard beside a column or a count returns the row and
  the flat components in selected order, `SELECT t.* FROM tasks t JOIN
  projects p ...` returns `TasksRow`, and `RETURNING t.*, p.code` and
  `RETURNING *, id` embed the target's row. A left-joined source's wildcard
  keeps today's nullable flat columns until W2. The boundary is the analyzed
  result model, top-level read and `RETURNING` result analysis, row-record
  recording, naming, and result mapping in the generator, the projection,
  `RETURNING`, and generated-Java sections of the query documentation and the
  generated-name section of the configuration documentation, and the existing
  tests whose expectations the change revises. Acceptance evidence is analyzer
  tests of two joined wildcards, a bare `*` over joined sources, a wildcard
  beside a column and a count, a lone joined wildcard returning the shared
  row, one table embedded under two aliases, a derived and a left-joined
  source's wildcard staying flat, unchanged derived-source columns, and both
  `RETURNING` forms; generator tests of the result record, a row record and
  enum generated only for an embedded row, inline positional mapping and its
  imports, no mapper field for an embedded-only row, and a component-name
  collision; a compiler test of one row file shared by a group that embeds and
  a group that returns the table whole; generated-source compilation; a
  PostgreSQL test executing a joined read into the shared row types;
  `mvn spotless:check`; and `mvn test`.
  Review evidence: in a top-level `SELECT`, each `<source>.*` and each table
  source of a bare `*` that is not left-joined is one non-null component of
  the table's shared row record in its written position, named after the
  source's alias or written table name with the existing suffix rules
  (`users1`, `users2`), the same table under two aliases embedding twice;
  other items stay flat in selected order, and derived and left-joined
  wildcards keep their flat columns, nullable when left-joined. A lone
  wildcard of one such source, `*` over one source or `<source>.*` beside
  others, returns the shared row. `RETURNING <target>.*`, or `*` on a
  single-table write, beside another item embeds the target's row, and a
  clause that is exactly the wildcard still returns the shared row. The
  package generates a row record and the enums of its columns for every
  returned or embedded table, one file shared across groups; a repository has
  a row mapper field only for a row it returns whole; an embedded row is built
  inline from consecutive positions, so the read order is the selected order,
  and executable SQL and binding order are unchanged. A review probe through
  the CLI of an inner- and left-joined read with enum, array, and
  `TIMESTAMPTZ` columns, a lone joined wildcard, `INSERT ... RETURNING *, id`,
  and `UPDATE ... FROM ... RETURNING t.*, p.code` generated sources that
  compiled with `--release 17`. `mvn spotless:check` and
  `mvn test -Dsqlcj.test.requireDocker=true` passed (1478 tests, 0 skipped),
  including
  `SqlcjCompilerIntegrationTest.shouldGenerateOneSharedRowRecordForAnEmbeddedAndAWholeRowEntry`
  and
  `PostgresIntegrationTest.shouldExecuteGeneratedJoinedWildcardReadIntoSharedRowTypesAgainstPostgres`.
- **M25-W2 — Left-joined rows are null when absent — Completed.** Implements
  requirement 2 with the absent-row decision: a left-joined source's wildcard,
  a lone one included, embeds a nullable row that is `null` for an unmatched
  row. Rechecked at `f299ee5`: a left-joined table's wildcard still expands
  into flat columns the analyzer marks nullable, and a package
  compares two groups' row records column by column, nullability included, so
  the embedded row keeps the table's declared nullability and only the
  component is nullable; otherwise a group that left-joins a table and one
  that returns it whole would be rejected as differing definitions. A lone
  left-joined wildcard is a result of that one component, never the shared
  row itself. The boundary is the embedding of left-joined sources in the
  analyzer, their mapping in the generator, the new runtime method with its
  tests, and the projection and generated-Java sections of the query
  documentation. Acceptance evidence is analyzer and generator tests, runtime
  tests of an all-`NULL` and a partly `NULL` range, a compiler test of one row
  file shared by a group that left-joins the table and a group that returns
  it whole, generated-source compilation, a PostgreSQL test in which an
  unmatched left-joined row is `null` while a matched row with `NULL` columns
  is not, `mvn spotless:check`, and `mvn test`.
  Review evidence: in a top-level `SELECT`, `p.*` and each left-joined table
  source of a bare `*` is one nullable component of the table's shared row
  record in its written position, whose own columns keep their declared
  nullability; inner-joined and base sources stay non-null components, and
  derived wildcards stay flat columns. A lone left-joined wildcard returns a
  `<QueryName>Result` of that one nullable component and reports no row
  table. The generator guards the inline construction with
  `dev.sqlcj.runtime.SqlRow.isNull(resultSet, first, last)` over exactly the
  row's own positions, leaves the non-null construction unchanged, and
  generates the row record and its enums with no mapper field for a row no
  query returns whole; `SqlRow.isNull` reads only that range and stops at the
  first value. The query documentation states the absent-row rule and that it
  is exact for any table with a `NOT NULL` column. A review probe through the
  CLI of `SELECT *` and an `:optional` lone `p.*` over a left-joined table
  with enum, array, and `TIMESTAMPTZ` columns, beside an inner-joined `p.*,
  u.name`, generated the `Mood` enum and `ProfilesRow`, guarded ranges `3..7`
  and `1..5`, an unguarded inner-joined row, and sources that compiled with
  `--release 17`. `mvn spotless:check` and
  `mvn test -Dsqlcj.test.requireDocker=true` passed (1482 CLI and 35 runtime
  tests, 0 skipped), including
  `SqlcjCompilerIntegrationTest.shouldGenerateOneSharedRowRecordForALeftJoinedAndAWholeRowEntry`,
  `PostgresIntegrationTest.shouldExecuteGeneratedLeftJoinedWildcardAsAnAbsentRowAgainstPostgres`,
  and the three `SqlRowTest` cases.
- **M25-W3 — Common aggregates typed without a cast — Completed.** Implements
  requirement 3 with the aggregate decision. Rechecked at `4684828`: the top-level
  read, each CTE body, and each subquery source type their items through one
  shared projection rule, which types only an aliased, unqualified `COUNT(*)`
  without `DISTINCT`; an `INSERT`'s `SELECT`, a predicate subquery, and
  `RETURNING` do not use it and are unchanged. JSqlParser 5.4 reports the
  aggregate shapes as the reassessment states, `COUNT(DISTINCT (a, b))` as two
  arguments, and `COUNT(DISTINCT *)` as a `DISTINCT` function over `*`, which
  PostgreSQL rejects, so the aggregate decision keeps that form rejected. An
  aggregate's argument is typed before its alias is required, so an untyped
  aggregate without an alias reports the computed-projection or
  unsupported-type diagnostic. The boundary is projection
  analysis, the projection and unsupported sections of the query
  documentation, the quickstart's invalid-query example, whose
  `CountAuthorBios` names a count this item accepts, and the tests of today's
  aggregate rejections. Acceptance
  evidence is analyzer tests of each aggregate over each typed input, with
  `DISTINCT`, through a derived source, without an alias, and of the forms
  that keep their diagnostics; generated-source compilation; a PostgreSQL test
  executing `COUNT(column)`, `COUNT(DISTINCT column)`, `MIN`, `MAX`, `SUM`, and
  `AVG` over matching and empty groups into their Java types and `null`;
  `mvn spotless:check`; and `mvn test`.
  Review evidence: in the top-level `SELECT`, each CTE body, and each subquery
  source, an aliased, unqualified, case-insensitive `COUNT`, `MIN`, `MAX`,
  `SUM`, or `AVG` of one argument, `DISTINCT` and `ALL` included, is typed
  without a cast. `COUNT` is a non-null `BIGINT` of a column, an unmapped
  column, `<source>.*`, and an expression argument, a left-joined column
  included, while an unknown column or qualifier is still rejected and
  `COUNT(DISTINCT *)` and `COUNT(ALL *)` keep the computed-projection
  diagnostic. `MIN` and `MAX` copy the column's type, enum type, array, and
  blank-padded spelling and reject scalar `BOOLEAN`, `UUID`, `BYTEA`, `JSON`,
  and `JSONB`; `SUM` and `AVG` follow the decided PostgreSQL 16 tables for
  scalar columns only; all four are nullable, and an unmapped column keeps the
  unsupported-type diagnostic. A missing alias fails as `<written> requires a
  result alias, such as <written> AS total.` after the shape and argument
  checks, unchanged for `COUNT(*)`. A review probe of the analyzer showed a
  `SUM` of a left-joined column as a nullable `BIGINT`, an aggregate of an
  aggregate column through a CTE, `COUNT($1)` keeping the untyped-placeholder
  diagnostic, `COUNT($1::int)` binding before a predicate parameter, and the
  blank-padded predicate parameter beside `MIN(code)`. `mvn spotless:check`
  and `mvn test -Dsqlcj.test.requireDocker=true` passed (1532 tests, 0
  skipped), including
  `PostgresIntegrationTest.shouldExecuteGeneratedAggregatesAgainstPostgres`,
  which generates, compiles, and executes the uncast aggregates into `Long`,
  `LocalDateTime`, and `BigDecimal` components and reads `0` counts and `null`
  values over no matching row.
- **M25-W4 — Reference service result shapes — Completed.** Proves the
  milestone acceptance. The reference service adds a list-with-count query,
  `SELECT p.*, COUNT(t.id) AS open_tasks` over projects left-joined to their
  open tasks and grouped by `p.id`; a latest-activity query, `SELECT t.*,
  MAX(c.created_at) AS last_comment_at` over tasks left-joined to their
  comments and grouped by `t.id`; and a `SELECT t.*, p.*` read, inner-joined
  and left-joined, each run without casts against PostgreSQL through the
  generated repository, a project without tasks reading a `null` row. The
  history, the snapshot, and the dump still generate one byte-identical tree.
  The boundary is test fixtures, tests, and their `Verified by` documentation;
  production changes only close a gap in W1 to W3 that the fixture exposes.
  Acceptance evidence is `ReferenceServiceIntegrationTest` with Docker
  required on Java 17 and Java 21, `mvn spotless:check`, and `mvn test`.
  Refined by the architect on 2026-10-17 against `4159651`: the draft queries
  below, all sqlcj-owned, were appended to the reference queries and run
  outside the repository through the CLI compiled from that commit. Each
  compiles without a cast; the history and the hand-written snapshot generate
  one byte-identical tree with the unchanged file set, because `TasksRow`,
  `ProjectsRow`, and `TaskStatus` are already generated; and the sources
  compile with `--release 17`. No gap in W1 to W3 was found, so no production
  change is planned. Of the milestone acceptance, only the list-with-count and
  latest-activity item is unproven by W1 to W3, whose PostgreSQL tests use
  their own tables; W4 proves it and the joined-read item on the reference
  service. Decided:
  - The queries added are `ListProjectsWithOpenTaskCount` (`SELECT p.*,
    COUNT(t.id) AS open_tasks` over `projects p LEFT JOIN tasks t ON
    t.project_id = p.id AND t.status = 'open'`, by organization, grouped by
    `p.id`), returning `ProjectsRow p` and `Long openTasks`;
    `ListTasksWithLastComment` (`SELECT t.*, MAX(c.created_at) AS
    last_comment_at` over `tasks t LEFT JOIN comments c`, by project, grouped
    by `t.id`), returning `TasksRow t` and a nullable `OffsetDateTime
    lastCommentAt`; `ListTasksWithProject` (`SELECT t.*, p.*` over `tasks t
    JOIN projects p`, by organization), returning non-null `TasksRow t` and
    `ProjectsRow p`; and `ListProjectsWithTheirTasks` (`SELECT t.*, p.*` over
    `projects p LEFT JOIN tasks t`, by organization), whose `TasksRow t` is
    `null` for a project without tasks. The left-joined read keeps the
    `t.*, p.*` order but joins tasks onto projects, because a task's project
    is never absent.
  - They run in a test method of their own, in its own organization and
    without an audit write, and the boundary adds the reference service's
    line in the query documentation's `Verified by` section; the PostgreSQL
    documentation's line is unchanged, because no migration is added.

  Review evidence: the reference queries append the four decided queries
  after `DeleteProjectComments`, each without a cast, and the migration history
  generates `ListProjectsWithOpenTaskCountResult(ProjectsRow p, Long
  openTasks)`, `ListTasksWithLastCommentResult(TasksRow t, OffsetDateTime
  lastCommentAt)`, `ListTasksWithProjectResult(TasksRow t, ProjectsRow p)`, and
  `ListProjectsWithTheirTasksResult(TasksRow t, ProjectsRow p)`, whose task row
  is read through `SqlRow.isNull` over positions 1 to 12, in selected-column
  order and with the unchanged file set; no production file changed.
  `ReferenceServiceIntegrationTest.shouldExecuteTheGeneratedResultShapes`, in
  its own organization, projects, and user and without an audit write, reads
  open-task counts of `1` and `0`, the later comment's timestamp and `null` for
  a task without comments, two non-null embedded rows equal to the rows the
  writes returned, and a `null` task row for a project without tasks, against
  PostgreSQL 16. The query documentation's `Verified by` section names it.
  `mvn spotless:check` passed; `ReferenceServiceIntegrationTest` with Docker
  required passed on Java 17 and Java 21 (21 tests each), including the
  unchanged byte-identical history, snapshot, and dump test; and `mvn test
  -Dsqlcj.test.requireDocker=true` passed on Java 17 (1533 tests, 0 skipped).

#### Required

1. In a result that is not exactly one table's full row, each source's
   complete column set selected by a wildcard (`<source>.*`, or `*` over
   several sources, which is every source's `.*` in order) becomes one
   component of that table's shared `<TableName>Row` record, named after the
   source's exposed name (its alias, or its table name when unaliased), instead
   of that table's flattened columns. Other projection items stay flat
   components in their selected order, so
   `SELECT u.*, COUNT(t.id) AS tasks ... GROUP BY u.id` returns `UsersRow u`
   and `Long tasks`. This changes the type of already-accepted shapes, which is
   why it lands before publication.
2. An embedded row of a left-joined source is `null` when the joined row is
   absent, detected as every column of the embedded row reading `NULL`; the
   documentation states that this is exact for any table with a `NOT NULL`
   column.
3. Type these aggregates of a direct column without a cast, alias required:
   `COUNT(expr)` and `COUNT(DISTINCT column)` as a non-null `Long`; `MIN` and
   `MAX` as the column's type; and `SUM` and `AVG` as PostgreSQL's result type
   for the column's type. All but `COUNT` are nullable.

#### Out of scope

- nesting one-to-many results into lists, user-supplied result classes,
- inference for other functions and expressions, which keep the cast,
- configurable component names.

#### Acceptance

- `SELECT t.*, p.* FROM tasks t JOIN projects p ...` returns a result with
  `TasksRow t` and `ProjectsRow p`; a left-joined row is `null` for an
  unmatched row against PostgreSQL.
- The reference service's list-with-count and latest-activity queries compile
  without casts.
- Focused analyzer, generator, and PostgreSQL tests, generated-source
  compilation, `mvn spotless:check`, and `mvn test` pass.

### Milestone 26 — Project Identity

#### Goal

Give sqlcj its permanent name and its license before any new public coordinate
or document is created under the old name.

#### Entry conditions

- The owner has registered `sqlcj.org` (D-G). Waived by the owner on
  2026-10-17, who instructed the milestone to proceed as if the domain were
  registered: that day the Public Interest Registry's RDAP service returned no
  registration for `sqlcj.org` (HTTP 404), and the domain had no DNS.
  Registration stays a hard prerequisite of the namespace verification in
  Milestone 37.

#### Work items

Activated on 2026-10-17 at the owner's request. Reassessed against the working
tree at `0828606` and its tracked files, the reactor and sample POMs, the
scripts, the CI workflows, and the documentation. Every requirement is
unimplemented, as assumed. Facts that shape the plan:

- Requirement 1: every main and test source of the CLI and the runtime lives
  under a `dev/sqlcj` directory and declares a `dev.sqlcj` package, and the CLI
  writes several schema types fully qualified. The three reactor POMs and the
  external sample's POM name the `groupId`, and the CLI POM names
  `dev.sqlcj.Main` three times: the JAR manifest, the shaded JAR's manifest
  transformer, and the exec plugin. Generated code reaches the runtime only
  through the generator's imports and its fully qualified `SqlArray`, `SqlRow`,
  and `UntypedText` references, emitted in a fixed order, so generated text
  changes only by name. A command test starts `dev.sqlcj.Main` by name.
  `scripts/verify-release.sh` finds the staged JARs under the repository path
  `dev/sqlcj`, which a replacement of the dotted name misses. The root
  `sqlcj.yaml` generates into `dev.sqlcj.example`, and the README, the
  quickstart, the query documentation, and the PostgreSQL documentation name
  the package, the coordinate, or the main class. No resource, service file,
  or test fixture names the package; `version` reads the manifest through
  `Main`'s package, so it follows the move. `org` and `dev` have the same
  length, so no line rewraps, but the configured import order sorts
  `org.sqlcj` after `org.junit` and `org.postgresql` instead of first. Build
  output from earlier builds holds classes under `dev/sqlcj`.
- Requirement 2: the repository has no `LICENSE`, and the README says that
  there is no released license.
- Requirement 3: `sqlcj-dev` appears in one tracked file outside the roadmap,
  the private vulnerability reporting link of `.github/SECURITY.md`. The
  README's link to sqlc's own `sqlc-dev` organization is not a reference to
  sqlcj.

- **M26-W1 — The project is named `org.sqlcj` and licensed under Apache 2.0 —
  Completed.** Implements requirements 1 to 3 in one change, because each is
  a mechanical edit of the same files and none is useful alone: every package,
  directory, coordinate, main class, script path, configuration, and document
  that names `dev.sqlcj` names `org.sqlcj`, with no alias; the root `LICENSE`
  is the unmodified Apache License 2.0 text and the README states the license;
  and the security policy's link names `https://github.com/sqlcj/sqlcj`. The
  owner renames the GitHub organization; the work item changes only
  repository files. No behavior changes beyond the names. Acceptance evidence
  is a search of tracked files finding no `dev.sqlcj`, `dev/sqlcj`, or
  `sqlcj-dev` outside the roadmap; packaged JARs from a clean build holding no
  `dev/sqlcj` entry and the CLI JAR's manifest starting `org.sqlcj.Main`; the
  existing compile and PostgreSQL tests passing unchanged in count;
  `scripts/verify-release.sh` against PostgreSQL 16, compiling the sample
  against `org.sqlcj:sqlcj-runtime`; `mvn spotless:check`; and `mvn test`.

  Review evidence, rechecked by the architect on 2026-10-17 against
  `a105ad6`, which merged the change as `8cf61f5`, `00f38f0`, and `bb4ea34`:
  a search of tracked files finds no `dev.sqlcj`, `dev/sqlcj`, or `sqlcj-dev`
  outside the roadmap, and no tracked file under a `dev/sqlcj` directory
  (85 under `org/sqlcj`). The diff from `0828606` renames every source with
  its package line and adds or removes no test method. `LICENSE` is the Apache
  License 2.0 text served by `apache.org`, its terms unmodified and only the
  appendix's copyright placeholder filled in, and GitHub detects it as
  `Apache-2.0`; the README's License section states it; `.github/SECURITY.md`
  links `https://github.com/sqlcj/sqlcj`. The owner renamed the organization:
  `origin` is `github.com:sqlcj/sqlcj`, and GitHub redirects the old
  `sqlcj-dev/sqlcj` path. The JARs packaged from the change before it was
  committed hold no `dev/sqlcj` entry, and the CLI JAR's manifest names
  `Main-Class: org.sqlcj.Main`. The pull request's checks on `bb4ea34` passed:
  `Spotless check`, and `mvn -Dsqlcj.test.requireDocker=true test` followed by
  `scripts/verify-release.sh` against PostgreSQL 16, which compiles and runs
  the external sample against `org.sqlcj:sqlcj-runtime`, on Java 17 and on
  Java 21.

#### Required

1. Rename every Java package `dev.sqlcj` to `org.sqlcj` in the runtime, the CLI,
   and their tests, and the Maven `groupId` to `org.sqlcj`. Generated code
   references `org.sqlcj.runtime`, and the CLI starts `org.sqlcj.Main`. The
   documentation, the README, the external sample, the scripts, and every
   fixture or configuration that names `dev.sqlcj` follow. No `dev.sqlcj` name
   and no compatibility alias remain.
2. Add the Apache License 2.0 as the repository's `LICENSE` file (D-C) and
   state the license in the README.
3. Rename the GitHub organization `sqlcj-dev` to `sqlcj` (D-G). The owner
   renames it on GitHub; every repository reference names `sqlcj`, as in
   `https://github.com/sqlcj/sqlcj`.

#### Out of scope

- a website on `sqlcj.org`, registering the domain, its DNS records, and the
  Central namespace verification, which Milestone 37 needs,
- license headers in source files and the published POM's license metadata,
  which Milestone 37 adds,
- any change of behavior.

#### Acceptance

- No package, coordinate, or document outside the roadmap's own history names
  `dev.sqlcj`, and generated sources compile against `org.sqlcj:sqlcj-runtime`.
- No tracked, publishable file outside the roadmap's own history names
  `sqlcj-dev`; references name `sqlcj`, as in `https://github.com/sqlcj/sqlcj`.
- `scripts/verify-release.sh`, `mvn spotless:check`, and `mvn test` pass.

### Milestone 27 — Transactions in Plain Java and Spring

#### Goal

Make a multi-statement transaction correct by default without a framework, let
hand-written JDBC take part in it, and prove that generated repositories take
part in Spring-managed transactions without depending on Spring.

#### Work items

Activated on 2026-10-17 at the owner's request, after Milestone 26 was
completed. Reassessed against the working tree at `a105ad6`: the runtime and
its tests, `PostgresIntegrationTest`, the quickstart, the external sample,
`scripts/verify-release.sh`, the PostgreSQL and query documentation, and the
official Java 17, Spring Framework 7.0.9, and Maven Central sources named
below; nothing was fetched from sqlc. Every requirement is unimplemented, as
assumed. Facts that correct or shape the plan:

- Requirement 1: the runtime has no transaction API and no dependencies.
  `JdbcQueryExecutor` runs one connection per operation over a `DataSource`,
  or every operation over a caller-owned `Connection` that it never closes,
  commits, rolls back, or reconfigures; generated repositories take only a
  `QueryExecutor`, so the helper needs no generator change. Runtime failures
  are `QueryExecutionException`, whose message names a query, and its
  subclass `QueryCardinalityException`. Runtime tests run on H2 and observe
  JDBC calls through JDK proxies; `PostgresIntegrationTest` already proves a
  caller-owned commit and rollback of generated writes. Java 17's
  `Connection` Javadoc states that `setAutoCommit` called during a
  transaction with a changed mode commits that transaction, and that closing a
  connection with an active transaction is implementation-defined, so
  restoring auto-commit after a failed commit or rollback could commit the
  very work the helper failed to roll back. The runtime documentation calls
  `QueryExecutor` decoratable for logging and metrics; an executor the helper
  constructed itself would bypass such a decorator inside every transaction.
- Requirement 2: `docs/postgresql.md`'s "Connection Ownership and
  Transactions" section holds the connection contract and its `Verified by`
  list, is linked by anchor from the query documentation, and states that
  sqlcj provides no transaction callback. The quickstart's application and
  the external sample's each run one committed and one rolled-back
  transaction by hand, and neither rolls back when a statement fails; the
  README's getting-started line names that application-controlled commit and
  rollback. `scripts/verify-release.sh` runs the sample against PostgreSQL 16
  in CI on Java 17 and Java 21.
- Requirement 3: no Spring or JPA dependency exists in the build. Spring
  Framework 7.0.9 is the latest stable release (7.1 is at milestones) and
  Hibernate ORM 7.4.13.Final the latest stable 7.x release, licensed under the
  Apache License 2.0; Spring Boot 4.1.1 manages Framework 7.0.9 with Hibernate
  7.4.5.Final, all on Java 17. The 7.0.9 Javadoc confirms D-J's mechanism:
  `TransactionAwareDataSourceProxy` routes `getConnection` and `close` through
  `DataSourceUtils`, so code unaware of Spring works on the transaction's
  connection and behaves as the plain `DataSource` outside a transaction; the
  transaction manager must use the underlying `DataSource`, not the proxy; and
  `JpaTransactionManager` lets plain JDBC on the same `DataSource` join through
  the proxy when a vendor `JpaDialect` is configured, autodetecting the
  `DataSource` of a `LocalContainerEntityManagerFactoryBean`. Hibernate
  inserts an entity with an `IDENTITY` key at `persist`, to read the key, so
  the flush test needs a change Hibernate defers: an update of a managed
  entity or a `persist` with an assigned key.
- The milestone adds no query, so no reference service fixture changes;
  Milestone 36 runs the reference service through the helper and in a
  Spring-managed transaction.

Decided by the architect on 2026-10-17, under the rule that pre-release
decisions are revisable by evidence:

- **Names:** `org.sqlcj.runtime.JdbcTransactions`, constructed from a
  `DataSource`, with `inTransaction`, which returns the callback's value, and
  `useTransaction`, which returns none, after Jdbi's names. A failure to begin,
  commit, roll back, or release is a `TransactionException`, a runtime
  exception of its own, because `QueryExecutionException` names a query.
- **The callback receives the connection only (revises D-H).** It binds
  repositories with `new JdbcQueryExecutor(connection)`, the one way the
  runtime already binds an executor to a connection, so a decorated executor
  works inside a transaction, the callback stays a one-argument lambda, and no
  transaction type is added.
- **Checked exceptions:** a callback may throw one checked exception type of
  its own, which the helper rethrows unchanged, so hand-written JDBC that
  throws `SQLException` needs no wrapping and a callback that throws none
  forces no `catch`.
- **Auto-commit (refines requirement 1):** the helper disables auto-commit only
  when it is on, and restores it only after a successful commit or rollback;
  after a failed commit or rollback it closes the connection without touching
  auto-commit.
- **Documentation's one place** is the existing "Connection Ownership and
  Transactions" section of the PostgreSQL documentation, which keeps the
  anchor the query documentation links.
- **Spring tests** live in the CLI module's tests, beside the other tests that
  compile and run generated code, with Spring and Hibernate test-scoped
  there, programmatic `TransactionTemplate`s, and no application context or
  Spring Boot.
- **Implementation order:** M27-W1 to M27-W3 as numbered; W2 needs the helper,
  and W3 is independent of both.

- **M27-W1 — Transaction helper — Completed.** Implements requirement 1
  and the helper's and the caller-owned connection's part of requirement 2's
  documentation. The runtime gains `JdbcTransactions`, its two callback
  interfaces, and `TransactionException`; the "Connection Ownership and
  Transactions" section documents the helper, its commit, rollback, and
  failure rules, that a call inside another call is an independent
  transaction, and the caller-owned connection for an isolation level, a
  savepoint, or `SET LOCAL`. Generated code, the generator, and the existing
  executor do not change. Acceptance evidence is runtime tests over H2 for
  every commit, rollback, begin, commit-failure, rollback-failure, and release
  path, each asserting auto-commit and closing; `PostgresIntegrationTest`
  committing generated writes and a hand-written statement on the helper's
  connection and rolling them back on a runtime exception, an error, and a
  cardinality failure; `mvn spotless:check`; and `mvn test`.
  Review evidence: `JdbcTransactions` obtains one connection per call,
  disables auto-commit only when it is on, commits on a normal return, rolls
  back on any `Throwable` and rethrows the same instance, attaches a rollback
  failure as a suppressed `TransactionException`, restores auto-commit only
  after a successful commit or rollback, closes the connection on every path,
  and throws or attaches a restore or close failure as its pending state
  requires; `TransactionCallback`, `TransactionConsumer`, and
  `TransactionException` carry those rules in Javadoc. `JdbcTransactionsTest`
  (15 tests, H2, recording proxies) asserts the JDBC call order and closing on
  every commit, rollback, begin-failure, commit-failure, rollback-failure,
  restore-failure, and close-failure path, the already-disabled auto-commit
  connection, the independent nested call, and a callback with no `catch` or
  `throws`. `PostgresIntegrationTest` commits two generated writes and a
  hand-written `PreparedStatement` on the helper's connection and finds none
  after a runtime exception, an error, and a generated `:one` cardinality
  failure. "Connection Ownership and Transactions" replaces the no-callback
  sentence and documents the helper, its rules and messages, nested calls, and
  the caller-owned path. The runtime POM did not change. Observed at review:
  `mvn spotless:check` passed; `mvn -pl sqlcj-runtime -Dtest=JdbcTransactionsTest
  test` ran 15 tests with no failures; `mvn -Dsqlcj.test.requireDocker=true
  test` succeeded with 50 runtime tests and 1535 CLI tests, including 50
  `PostgresIntegrationTest` tests, and no failures.
- **M27-W2 — The quickstart and the external sample use the helper —
  Completed.** Implements the rest of requirement 2: both applications run
  their committed and rolled-back transactions through `JdbcTransactions`, with
  no hand-written commit or rollback, and the README's getting-started line
  follows. Acceptance evidence is `scripts/verify-release.sh` against
  PostgreSQL 16 and `mvn test`.
  Rechecked on 2026-10-17 against the working tree at `41fbf0e`: the helper's
  public API is as W1 recorded; both applications still open, configure,
  commit, and roll back a connection by hand, declare `throws SQLException`
  only for it, and roll back by an explicit call rather than on a failure;
  `scripts/verify-release.sh` greps only the sample's final success line, and
  no test or script runs the quickstart's code. Decided by the architect, under
  the rule that pre-release decisions are revisable by evidence:
  - The committed transaction uses `inTransaction` and returns the new
    author's id; the rolled-back one uses `useTransaction`, so both methods
    are shown.
  - The rolled-back transaction rolls back because a later generated
    statement fails, the `:one` `GetAuthor` read of a missing id, and the
    application catches the `QueryCardinalityException` the helper rethrows.
    D-H names the missing rollback on failure as the quickstart's gap, so the
    example shows that case instead of a deliberately thrown exception. The
    rolled-back callback returns nothing, so absence is read back by name
    through `SearchAuthors` instead of by id.
  - The applications' `main` methods drop `throws SQLException`, which the
    helper no longer requires; the quickstart's printed `rolled back` line
    and its explanation change to match; the PostgreSQL documentation's one
    sentence that says the quickstart runs the manual pattern now says it runs
    the helper. No other documentation, the runtime, the generator, the
    script, or the sample's queries change.
  Review evidence: the external sample and the quickstart's `App` construct
  one `JdbcTransactions` from their `DataSource`, commit two generated writes
  through `inTransaction`, which returns the new author's id, and roll two
  back through `useTransaction`, whose callback ends with the failing `:one`
  `GetAuthor(-1L)` read and whose `QueryCardinalityException` is caught
  outside the call; absence is read through `searchAuthors("Temporary
  Author")`, and each transaction binds its repository with
  `new JdbcQueryExecutor(connection)`. Neither application calls
  `setAutoCommit`, `commit`, `rollback`, or `getConnection`, and neither
  declares `throws SQLException`. The sample's prints and check messages are
  otherwise unchanged; the quickstart prints `rolled back: true`, and its
  explanation, the README's getting-started line, and the PostgreSQL
  documentation's one sentence describe the helper. Only those four files
  changed. Observed at review: `scripts/verify-release.sh` against PostgreSQL
  16 exited 0 with `committed: Compiler pioneer`, `rolled back: empty`,
  `count: 2`, `page: 2 Grace Hopper`, and the final success line; the
  quickstart's files, extracted verbatim and run against a fresh database with
  `V1` and `V2`, generated the two documented sources and the manifest,
  compiled, and printed output identical to the documented block;
  `mvn spotless:check` passed; `mvn -Dsqlcj.test.requireDocker=true test`
  succeeded with 50 runtime tests and 1535 CLI tests, including 50
  `PostgresIntegrationTest` tests, and no failures.
- **M27-W3 — Repositories in Spring-managed transactions — Completed.**
  Implements requirement 3 and the Spring part of requirement 2's
  documentation: test-scoped Spring Framework 7.0 and Hibernate ORM 7.4, and
  PostgreSQL tests in which generated writes over
  `new JdbcQueryExecutor(new TransactionAwareDataSourceProxy(dataSource))`
  commit and roll back with a `DataSourceTransactionManager` and with a
  `JpaTransactionManager` beside a JPA write, a repository over the plain
  `DataSource` does not take part, and a deferred JPA change is visible to a
  sqlcj read only after a flush. The section documents the bean definition,
  that the transaction manager keeps the plain `DataSource`, the flush rule,
  and that the helper is not used inside a framework's transaction. The
  runtime and generated code gain no dependency. Acceptance evidence is those
  tests with Docker required, the runtime POM unchanged, `mvn spotless:check`,
  and `mvn test`.
  Rechecked on 2026-10-17 against the working tree at `3b1ec3e`: the build
  still has no Spring or JPA dependency; the parent POM pins every version as
  a property and manages test-scoped libraries, Testcontainers and the
  PostgreSQL driver among them, which the CLI POM declares without a version;
  `PostgresIntegrationTest` and `ReferenceServiceIntegrationTest` each start
  their own `postgres:16-alpine` container, skip without Docker unless
  `sqlcj.test.requireDocker` is set, which CI sets on Java 17 and 21, and
  compile generated sources against `java.class.path` into a `@TempDir` loaded
  by a `URLClassLoader`; the "Connection Ownership and Transactions" section
  and its `Verified by` list name no framework. Maven Central lists Spring
  Framework 7.0.9 as the newest 7.0 release (7.1.0-M2 is a milestone) and
  Hibernate ORM 7.4.13.Final as the newest 7.x (8.0 is in beta), which
  depends on Jakarta Persistence 3.2.0 and baselines on Java 17; Spring Boot
  4.1.1 still manages Framework 7.0.9 with Hibernate 7.4.5.Final. The 7.0.9
  sources confirm that `new JpaTransactionManager(emf)` runs
  `afterPropertiesSet`, which takes the `DataSource` and the `JpaDialect` from
  a `LocalContainerEntityManagerFactoryBean`'s factory, and that
  `LocalContainerEntityManagerFactoryBean` needs no `persistence.xml` when
  its managed types are given. Decided by the architect, under the rule that
  pre-release decisions are revisable by evidence:
  - Versions are Spring Framework 7.0.9 and Hibernate ORM 7.4.13.Final, the
    newest stable releases of the lines the milestone names, as parent POM
    properties with test-scoped managed entries; the CLI POM declares only
    `org.springframework:spring-orm`, which pins `spring-jdbc` and
    `spring-tx` at its own version, and `org.hibernate.orm:hibernate-core`.
  - The tests are a new `SpringTransactionIntegrationTest` beside
    `PostgresIntegrationTest`, with its own container and helpers, as
    `ReferenceServiceIntegrationTest` already does, so the Spring and JPA
    imports stay out of the existing classes.
  - The entity factory is a `LocalContainerEntityManagerFactoryBean` over the
    plain `DataSource` with a `HibernateJpaVendorAdapter`, which supplies the
    `JpaDialect` JDBC participation requires, and one test entity named
    through `PersistenceManagedTypes`, built by `afterPropertiesSet` and
    destroyed after the class; the JPA writes go through
    `SharedEntityManagerCreator`'s shared `EntityManager`, the one
    `@PersistenceContext` injects. The entity's key is assigned, so Hibernate
    defers its insert until a flush.
  - The documentation is a Spring part of the existing "Connection Ownership
    and Transactions" section, its `Verified by` list, and the section's
    sentence naming the test-scoped dependencies.
  Review evidence: the parent POM pins `spring.version` 7.0.9 and
  `hibernate.version` 7.4.13.Final and manages test-scoped `spring-orm`,
  `spring-context`, and `hibernate-core`, which the CLI POM declares
  test-scoped without versions. `spring-context` departs from the decision
  above for a verified reason: `spring-orm` 7.0.9 depends only on
  `spring-beans`, `spring-core`, `spring-jdbc`, and `spring-tx`, while
  `LocalContainerEntityManagerFactoryBean` implements `spring-context`
  interfaces. `SpringTransactionIntegrationTest` starts its own
  `postgres:16-alpine` container under the same Docker gate as
  `PostgresIntegrationTest`, generates and compiles one query group into a
  `@TempDir`, binds the repository through `new JdbcQueryExecutor(new
  TransactionAwareDataSourceProxy(dataSource))`, and gives
  `DataSourceTransactionManager` the plain `DataSource` and
  `JpaTransactionManager` the factory built on it. Its six tests commit two
  generated writes that a plain-`DataSource` read does not see inside the
  transaction; roll them back, rethrowing the same exception instance; keep
  only the plain-`DataSource` write after a rollback; commit a `persist` and a
  generated write in one JPA transaction; roll back a flushed `persist` and a
  generated write; and find a pending `persist` through a proxied generated
  read only after `flush()`. The section documents the bean definition, the
  manager on the plain `DataSource`, the plain-`DataSource` repository
  outside the transaction, the flush rule, and that `JdbcTransactions` is not
  used inside a framework's transaction; its `Verified by` list names the six
  tests. The runtime POM and every `src/main` file are unchanged. Observed at
  review: `mvn spotless:check` passed; `mvn -Dsqlcj.test.requireDocker=true
  test` succeeded with 50 runtime tests and 1541 CLI tests, including the six
  Spring tests and 50 `PostgresIntegrationTest` tests, and no failures; the
  runtime-scope dependency trees of both modules contain no Spring, Hibernate,
  or Jakarta artifact.

#### Required

1. Add the runtime transaction helper D-H defines: constructed from a
   `DataSource`, it runs a callback that returns a value, or none, on one
   connection with auto-commit disabled, handing it that connection, over which
   it binds the repositories it needs and on which hand-written JDBC code takes
   part (D-L). It commits on a normal return; rolls back on any exception or
   error and rethrows it, with a failed rollback attached as suppressed;
   reports a failure to begin, commit, or roll back as a runtime exception
   carrying the `SQLException`; restores auto-commit after every successful
   commit or rollback; and closes the connection on every path.
2. Document transactions in one place: the helper, the caller-owned
   `Connection` path for an isolation level, a savepoint, or `SET LOCAL`, and
   Spring. The quickstart and the external sample use the helper instead of
   hand-written commit and rollback.
3. Document and verify with tests that a repository built over
   `new JdbcQueryExecutor(new TransactionAwareDataSourceProxy(dataSource))`
   takes part in a transaction of `DataSourceTransactionManager` and of
   `JpaTransactionManager` on the same `DataSource`, committing and rolling
   back with a JPA write; that a plain `DataSource` does not take part; and that
   a pending JPA change is visible to a sqlcj read only after a flush. Spring
   and the JPA provider are test-scoped only; the runtime and generated code
   gain no dependency.

#### Out of scope

- propagation, savepoints, isolation and read-only options, and retries,
- thread-bound transactions and transaction annotations,
- a Spring module, a Spring Boot starter, or a Spring-specific executor,
- Quarkus, Micronaut, and Jakarta EE verification (D-J).

#### Acceptance

- Generated writes made through the helper, and a hand-written statement on its
  connection, are committed on a normal return and rolled back on a runtime
  exception, an error, and a cardinality failure, against PostgreSQL; every
  successful commit or rollback restores auto-commit, and every path closes
  the connection.
- The Spring tests commit and roll back generated writes as documented, beside
  a JPA write in the same transaction.
- Runtime tests, `mvn spotless:check`, and `mvn test` pass.

### Milestone 28 — Set Operations and Recursive CTEs

#### Goal

Let a query combine the results of several `SELECT`s and walk a hierarchy, two
shapes the first-contact probe found with no way through.

#### Work items

Activated on 2026-10-17 at the owner's request, after Milestone 27 was
completed. Reassessed against the working tree at `04c98f7`: `QueryAnalyzer`
and its tests, `SqlcjCompilerIntegrationTest`, `PostgresIntegrationTest`, the
reference service and its test, and the query documentation; JSqlParser 5.4,
the version the POM pins, probed outside the repository; and the PostgreSQL 16
documentation of `SELECT`, `WITH` queries, and the type resolution of `UNION`
and `CASE`. Nothing was fetched from sqlc. Every requirement is unimplemented,
as assumed. Facts that correct or shape the plan:

- Rejections today: every place a `SELECT` is classified — the top level, a
  `WITH` body, a subquery source, a predicate subquery, and an `INSERT` row
  source — rejects a set operation with
  `UNION, INTERSECT, and EXCEPT are not supported; write one query per SELECT.`;
  a `WITH RECURSIVE` item is rejected before its body is read; and a column
  list on a `WITH` name or a subquery alias is rejected. Analyzer, compiler,
  and reference service tests assert each, and the query documentation lists
  them.
- Binding order needs no new mechanism: placeholder occurrences come from a
  textual walk of the parse tree, whatever the statement's shape, and each
  clause records the occurrences it types, so branches typed one by one bind
  in textual order. A placeholder repeated across branches is one parameter
  whose occurrences must agree in type, as today.
- `ORDER BY` is not analyzed today; a cast types a placeholder there. `LIMIT`
  and `OFFSET` placeholders are typed `INTEGER` from a `PlainSelect`.
- JSqlParser 5.4 parses every form the milestone names. A set operation is a
  `SetOperationList`: a flat list of branches and of `UnionOp`, `IntersectOp`,
  `ExceptOp`, or `MinusOp` operators, each with `isAll()`, with no precedence
  grouping, although PostgreSQL reads `A UNION B INTERSECT C` as
  `A UNION (B INTERSECT C)`. A parenthesized branch is a `ParenthesedSelect`
  that may hold its own `WITH`, `ORDER BY`, `LIMIT`, or set operation. A
  trailing `ORDER BY`, and a `LIMIT` or `OFFSET` after it, belong to the list,
  but without `ORDER BY` a trailing `LIMIT`, `OFFSET`, or `FOR UPDATE` is
  attached to the last branch when that branch is unparenthesized; PostgreSQL
  applies them to the whole result either way. A `WITH` before a top-level set
  operation belongs to the list. `WITH RECURSIVE` sets `isRecursive()` on the
  first item of the list only, although PostgreSQL's `RECURSIVE` covers the
  whole list and lets an item read one declared after it. A `WITH` column list
  is `getWithItemList()`, and `SEARCH` and `CYCLE` clauses are parsed into the
  item.
- PostgreSQL 16: set operations resolve column types pairwise by its `UNION`
  rules (same type; otherwise one type category, the candidate moving to a type
  the others convert to implicitly, stopping at the category's preferred
  type); an `ORDER BY` on a set operation's result may name only an output
  column or position; a recursive body is
  `non_recursive_term UNION [ALL | DISTINCT] recursive_term` with one
  self-reference, on the right-hand side; `SEARCH` and `CYCLE` add columns to
  the CTE's rows.
- The reference service's `union.sql` and `union_all.sql` select `text`
  columns of `projects` and `tasks`; `recursive_cte.sql` projects the literals
  `1` and `level + 1`, which sqlcj rejects as uncast computed projections
  however recursion is analyzed, so its catalogue entry gains casts. No
  reference service table refers to itself, so the hierarchy walk is proven on
  a table of `PostgresIntegrationTest`.

Decided by the architect on 2026-10-17, under the rule that pre-release
decisions are revisable by evidence:

- **Type resolution (refines requirement 1).** Per column, over every branch:
  one type, with its enum type, array, and blank-padding, resolves to itself;
  scalar numeric types to the widest of `smallint`, `integer`, `bigint`,
  `numeric`, `real`, `double precision`; scalar `date`, `timestamp`, and
  `timestamptz` to the widest of those; scalar string types to the first
  branch's; anything else is rejected. Each widening rule is kept only if
  PostgreSQL 16 reports the same result type in a test; a rule it contradicts
  becomes a rejection. An untyped literal or `NULL` keeps the cast every
  projection needs.
- **Result shape.** The first branch names the columns, so a later branch's
  cast or aggregate needs no alias. A set operation whose every branch returns
  one table's whole row returns that row record; any other returns flat
  columns, because an embedded row is a fact of one source's wildcard and a
  set operation's column has none.
- **`ORDER BY` after the last branch** is checked only where PostgreSQL
  restricts it: a column reference must name a result column, and every other
  item reaches PostgreSQL as written.
- **Nullability** follows the operators with `INTERSECT` binding first.
  `MINUS`, which PostgreSQL does not have, is rejected naming `EXCEPT`.
- **An `INSERT` row source** keeps rejecting a set operation, because
  requirement 1 names no such location; its diagnostic says to select from the
  set operation as a subquery.
- **Recursion (refines requirement 2).** `RECURSIVE` covers every item of its
  list. A recursive body's last operator is `UNION [ALL]` and its last branch
  alone may read the CTE; the branches before it give the CTE's columns, and
  the last branch's types must resolve to theirs, as PostgreSQL requires. A
  read of the CTE anywhere else, and a read of an item declared later in the
  list, is rejected rather than resolved as a table of that name. A column is
  nullable when either term's is; when the recursive term makes a column
  nullable that the first term does not, every column of the CTE is nullable,
  because the recursive term read the CTE before that was known. `SEARCH` and
  `CYCLE` are rejected, because they add columns sqlcj would not model.
- **Column lists** name a CTE's leading columns by position, as PostgreSQL
  allows; more names than columns are rejected.
- **Implementation order:** W1, W2, W3, W4. W2 needs W1; W4 needs W1 and W3.

- **M28-W1 — Top-level set operations — Completed.** Implements
  requirement 1 at the top level and requirement 3's `UNION` and `UNION ALL`
  entries: a top-level `UNION`, `INTERSECT`, or `EXCEPT`, with or without
  `ALL`, including parenthesized and nested branches, a `WITH` before it, and
  a trailing `ORDER BY`, `LIMIT`, and `OFFSET`, is analyzed by the decisions
  above. Every nested location keeps today's rejection. The query
  documentation gains a set-operation section. Acceptance evidence is analyzer
  tests of each operator's names, types, nullability, ordering check, binding
  order, row record, and rejections; generated-source compilation;
  `PostgresIntegrationTest` running each operator and confirming every
  widening rule's result type; the two reference service entries in its query
  catalogue, run against PostgreSQL; `mvn spotless:check`; and `mvn test`.
  Review evidence: a top-level `UNION`, `INTERSECT`, or `EXCEPT`, with or
  without `ALL`, is analyzed with leftmost-branch names, the widening and
  `INTERSECT`-first nullability rules, the shared row record of whole-row
  branches, and textual binding order; every `SetOperationList`, nested in a
  parenthesized branch or top-level, checks its own `ORDER BY` against its own
  result columns and types its own `LIMIT` and `OFFSET` as `INTEGER`
  `limit`/`offset` (`shouldResolveTheOwnClausesOfANestedSetOperationBranch`,
  `shouldRejectANestedSetOperationOrderingOutsideItsResultColumns`); a `WITH`
  body, subquery source, predicate subquery, and `INSERT` row source keep the
  set-operation rejection; the reference service's `union.sql` and
  `union_all.sql` moved into its query catalogue and run against PostgreSQL.
  Reviewed with `mvn -o spotless:check` (pass) and
  `mvn -o -Dsqlcj.test.requireDocker=true test` (1612 tests, 0 failures, 0
  errors, 0 skipped).
- **M28-W2 — Set operations in `WITH` bodies and subqueries — Completed.**
  Implements the rest of requirement 1: a set operation as a `WITH` body, a
  subquery source, and a predicate subquery, of a read or a write, whose
  branches resolve against the scope a nested `SELECT` has today; a derived
  source's columns are the columns W1's rules resolve, and the distinct-name
  rule applies. A predicate subquery models no result column, so each of its
  branches follows today's predicate-subquery rules, `MINUS` is rejected, its
  own `LIMIT` and `OFFSET` are typed `INTEGER`, and PostgreSQL checks its
  column counts, types, and `ORDER BY`. An `INSERT` row source gets its own
  diagnostic, which says to select from the set operation as a subquery, a
  rewrite that is analyzed. Acceptance evidence is analyzer tests of each
  location and of that rewrite, a PostgreSQL test of a set operation in a
  `WITH` body, in a subquery source, and in a predicate subquery, the
  documentation, `mvn spotless:check`, and `mvn test`.
  Review evidence: a set operation as a `WITH` body or a subquery source
  (`FROM`/`JOIN`, `UPDATE ... FROM`, `DELETE ... USING`) is analyzed by W1's
  rules, exposing leftmost names, the widened type, operator nullability, and
  all-nullable columns when left-joined, with an outer placeholder typed from
  the resolved column and textual binding order across branches and the outer
  query; a set operation in an `EXISTS`, `IN`, or scalar-comparison predicate,
  of a read or a `DELETE`, analyzes each branch by the predicate-subquery
  rules, correlated to the outer query, rejects `MINUS`, and types its own
  `LIMIT`/`OFFSET` `INTEGER`; a repeated named placeholder across branches stays
  one parameter; the column-count, `MINUS`, nested `ORDER BY`, distinct-name,
  `WITH RECURSIVE`, and column-list rejections hold; an `INSERT` set-operation
  row source, parenthesized or behind a `WITH`, throws the new subquery-rewrite
  diagnostic, and that rewrite is analyzed;
  `PostgresIntegrationTest.shouldExecuteGeneratedNestedSetOperationsAgainstPostgres`
  runs all three nested locations. Reviewed with `mvn -o spotless:check`
  (pass) and `mvn -o -Dsqlcj.test.requireDocker=true test` (1622 tests, 0
  failures, 0 errors, 0 skipped).
- **M28-W3 — Column lists on `WITH` names — Completed.** Implements the
  column list of requirement 2 for every `WITH` item, recursive or not; a
  subquery alias's list stays rejected. Acceptance evidence is analyzer tests of
  renaming, a shorter list, a longer list, and a renamed duplicate; a
  PostgreSQL test; the documentation; `mvn spotless:check`; and `mvn test`.
  Boundary refined by the architect on 2026-10-18, against the working tree at
  `563fe15` and JSqlParser 5.4's `WithItem.getWithItemList()`, whose items are
  plain `Column` expressions: the list renames the body's resolved columns by
  position after the body is analyzed, keeping each column's type, enum type,
  array, blank-padding, and nullability, and the columns past a shorter list
  keep the body's names; a list longer than the body's columns is rejected; the
  distinct-name rule applies to the renamed columns, so a list can resolve a
  repeated body name or create one. A position the list names takes its name
  from the list, so the body's projection need not alias an aggregate or a
  cast there, as PostgreSQL allows; every later position still requires one.
  A `WITH RECURSIVE` item keeps today's rejection until W4, which reuses the
  same renaming of the columns its first term gives.
  Review evidence: a column list on a `WITH` name of a read or a write renames
  the body's columns by position, keeping type and nullability, with an outer
  placeholder named and typed from the renamed column and binding order
  unchanged; a shorter list renames the leading columns only; an unaliased
  aggregate or cast at a listed position, in a plain body or the leftmost
  branch of a set-operation body, is named by the list, while one past the list
  keeps the alias rejection; a longer list is rejected with
  `The column list of <name> names <m> columns, but its SELECT returns only <n>.`;
  the distinct-name rule reads the renamed columns, resolving `u.id, p.id` and
  rejecting a list-created duplicate; `WITH RECURSIVE` with a list and a
  subquery alias's list keep their rejections;
  `PostgresIntegrationTest.shouldExecuteGeneratedWithColumnListsAgainstPostgres`
  runs a joined renamed clause and a shorter list. Reviewed with
  `mvn -o -q spotless:check` (pass) and
  `mvn -o -Dsqlcj.test.requireDocker=true test` (1631 tests, 0 failures, 0
  errors, 0 skipped).
- **M28-W4 — Recursive CTEs — Completed.** Implements the rest of
  requirement 2 and requirement 3's recursive entry by the recursion decision
  above, for reads and writes alike. Acceptance evidence is analyzer tests of
  the recursive form, its type and nullability rules, and each rejection; a
  PostgreSQL hierarchy walk over a self-referencing table; the reference
  service's recursive entry, with casts, in its query catalogue and run
  against PostgreSQL; the documentation; `mvn spotless:check`; and
  `mvn test`.
  Boundary refined by the architect on 2026-10-18, against the working tree at
  `3eaf01f` and JSqlParser 5.4, probed outside the repository; nothing was
  fetched from sqlc. JSqlParser parses `WITH RECURSIVE` before a `SELECT`,
  `INSERT`, `UPDATE`, and `DELETE` and inside a subquery source and a `WITH`
  body, reports `isRecursive()` on the first item only (and on a later item
  only for a `RECURSIVE` written before it, which PostgreSQL rejects), reports
  a recursive body as a `SetOperationList` of `UnionOp` operators, and reports
  `SEARCH` and `CYCLE` as the item's `getSearchClause()` and
  `getCycleClause()`. Every read `SELECT` without `FROM` is rejected today as
  `Only table sources are supported.`, so the reference service's entry, whose
  first term `SELECT 1 AS level` reads no table, needs more than casts.
  Decided, refining the recursion decision:
  - A `WITH` list is recursive when any of its items reports `isRecursive()`.
    In it, an item is recursive when its body, inside its parentheses, is a set
    operation whose last operator is `UNION [ALL]` and whose last branch names
    the item, unqualified, as its `FROM` source or a joined source; every other
    item is analyzed as today. Inside a recursive list, an item's own name
    outside its recursive term, and the name of every item declared after it,
    are rejected wherever a source names them, at any depth, rather than read
    as a table; a `WITH` list nested inside still shadows them, and a
    schema-qualified name still reads a table.
  - A recursive item's first term, the branches before the last, is analyzed
    by W1's rules and gives the CTE's columns, renamed by W3's column-list
    renaming before the recursive term reads them. A plain `SELECT` branch of
    the first term may omit `FROM`, as a series' seed row is written and as an
    `INSERT`'s `SELECT` already may; a `SELECT` without `FROM` anywhere else
    keeps its rejection.
  - Each column's type over both terms, by W1's resolution, must be the first
    term's, as PostgreSQL requires, or the query is rejected naming the cast to
    write in the first term. Nullability follows the recursion decision.
  - Where the recursive term may read the CTE (once, outside a subquery, not on
    the nullable side of an outer join) and an `ORDER BY`, `LIMIT`, or `OFFSET`
    of a recursive body stay PostgreSQL's checks.
  - `SEARCH` and `CYCLE` are rejected in every `WITH` list.
  Review evidence: in a `WITH RECURSIVE` list, before a read, `DELETE`,
  `UPDATE`, or inside a subquery source, an item whose body ends in
  `UNION [ALL]` with a last branch reading it as a `FROM` or `JOIN` source is
  analyzed as recursive: its first term, of one or several branches and with a
  `FROM`-less plain seed allowed, gives the columns, renamed by its column list
  (an unaliased cast named by the list); the recursive term reads them and
  types a placeholder from them; parameters bind in textual order across both
  terms, later clauses, and the outer query; a narrower recursive term is
  accepted, a wider one rejected naming the first-term cast; a column is
  nullable when either term's is, and every column is when the recursive term
  alone adds one; an item that does not read itself keeps W1's widening and
  the two formerly rejected rows are accepted; a nested `WITH` shadows the
  name and a schema-qualified name reads a table; the self-read in a first
  term, a plain body, a last-branch subquery, and an `INTERSECT`-ended body,
  the forward read, `SEARCH`, `CYCLE`, `MINUS`, the column count, and a
  `FROM`-less `SELECT` elsewhere are rejected;
  `PostgresIntegrationTest.shouldExecuteGeneratedRecursiveCtesAgainstPostgres`
  walks a self-referencing table, runs a `FROM`-less seed series, and confirms
  PostgreSQL rejects an `integer` first term with a `bigint` recursive term;
  `ReferenceServiceIntegrationTest.shouldExecuteTheGeneratedRecursiveQuery`
  runs `listPriorityLevels` from the query catalogue and gets 1 to 5. Reviewed
  with `mvn -o -q spotless:check` (pass) and
  `mvn -o -Dsqlcj.test.requireDocker=true test` (50 runtime and 1651 CLI tests,
  0 failures, 0 errors, 0 skipped).

#### Required

1. `UNION`, `INTERSECT`, and `EXCEPT`, each with or without `ALL`, are analyzed
   at top level, in a `WITH` body, and in a subquery. Each branch is analyzed as
   a `SELECT` of its own scope, with its placeholders typed by the clause rules
   and bound in textual order across branches; `INTERSECT` binds more tightly
   than `UNION` and `EXCEPT`, as in PostgreSQL. The result takes the first
   branch's column names. Each column's type follows PostgreSQL's resolution of
   the branches' types where sqlcj can state it exactly; any other mismatch is
   rejected naming the column and the cast to write. A column is nullable when
   any branch's is for `UNION`, when every branch's is for `INTERSECT`, and when
   the first branch's is for `EXCEPT`. `ORDER BY`, `LIMIT`, and `OFFSET` after
   the last branch apply to the whole result, and an `ORDER BY` column must name
   one of its columns. A set operation whose every branch returns one table's
   whole row returns that table's row record; any other returns columns of its
   own.
2. `WITH RECURSIVE` is analyzed: a recursive body is a set operation whose last
   operator is `UNION [ALL]` and whose last branch alone reads the CTE; the
   CTE's columns are those of the branches before it, and the last branch's
   types must resolve to them as requirement 1 states. A column list on a
   `WITH` name, which recursive CTEs commonly write, names that CTE's columns.
3. The reference service's `UNION`, `UNION ALL`, and recursive CTE entries move
   from the unsupported catalogue into the query catalogue and run against
   PostgreSQL.

#### Out of scope

- data-modifying CTEs and the `SEARCH` and `CYCLE` clauses,
- a column list on a subquery alias,
- type resolution that sqlcj cannot state exactly, which keeps the cast,
- a set operation as the row source of an `INSERT`,
- a joined table's row embedded in a set operation's result,
- a `WITH` item that reads one declared after it.

#### Acceptance

- Focused analyzer tests of each operator's names, types, nullability, ordering,
  and binding order; generated-source compilation; PostgreSQL tests of each
  operator and of a recursive hierarchy walk.
- The reference service's set operation and recursive CTE entries run against
  PostgreSQL from its query catalogue.
- `mvn spotless:check` and `mvn test` pass.

### Milestone 29 — Every Join Kind

#### Goal

Accept every join PostgreSQL has, so that existing SQL moves as written.

#### Work items

Activated on 2026-10-18 at the owner's request, after Milestone 28 was
completed. Reassessed against the working tree at `1d15c73`: `QueryAnalyzer`
and its tests, `SqlcjCompilerIntegrationTest`, `PostgresIntegrationTest`, the
reference service, its unsupported catalogue, and its test, and the query
documentation; JSqlParser 5.4, the version the POM pins, probed outside the
repository; and the PostgreSQL 16 documentation of table expressions and
`SELECT`. Nothing was fetched from sqlc. Every requirement is unimplemented,
as assumed. Facts that correct or shape the plan:

- Rejections today: `FULL`, `RIGHT`, `CROSS`, `NATURAL`, and `USING` joins and
  a comma-separated read `FROM` list are each rejected, naming the rewrite
  where one exists; a `LATERAL` subquery, as a read's `FROM` item, a joined
  source, or a write's `FROM` or `USING` source, is rejected as
  `LATERAL is not supported.` before its join kind; every other modifier as
  `Only unmodified INNER JOIN and LEFT JOIN clauses are supported.` Analyzer,
  compiler, and reference service tests, seven unsupported catalogue entries
  included, and the query documentation assert each.
- Join groups exist only in writes: `UPDATE ... FROM` and `DELETE ... USING`
  already accept comma-separated sources, each starting a group whose `ON`
  conditions see that group alone. A read's sources are one flat list: each
  condition sees every source before it and the enclosing levels of a
  predicate subquery, and its placeholders are typed over the first
  `index + 2` sources, which assumes that list.
- A source carries one left-joined flag, which already makes every column read
  from it nullable, a wildcard of it a row component that reads `null` when
  every column of the row is `NULL`, and a lone wildcard of it a result of its
  own rather than the shared row. `RIGHT` and `FULL` nullability needs no new
  generated shape, only the same flag on more sources.
- An unqualified column resolves against every source of its level and is
  ambiguous when two expose it, and a bare `*` expands every source in order,
  embedding each table's row; nothing models a column that belongs to a join
  rather than to one source.
- A derived source's body is analyzed with no enclosing level, so a `LATERAL`
  body needs the enclosing scope a predicate subquery already receives.
- JSqlParser 5.4 parses every form the milestone names into one flat `Join`
  list after the first `FROM` item, with no join tree. `RIGHT [OUTER]` and
  `FULL [OUTER]` set `isRight()` or `isFull()`; `CROSS JOIN` sets `isCross()`
  with no condition; a comma is `isSimple()`, which also reports
  `isInnerJoin()`, and a `JOIN` after a comma item joins that item; `USING`
  is `getUsingColumns()` with no `ON`; `NATURAL` sets `isNatural()` beside the
  side, except that `NATURAL INNER JOIN` loses `NATURAL` and reads as an
  `INNER JOIN` with no condition. A `LATERAL` subquery is a `LateralSubSelect`,
  a subclass of `ParenthesedSelect`, as the first `FROM` item, after a comma,
  after every join keyword, and in a write's `FROM` or `USING`; `LATERAL f(...)`
  is a `TableFunction`; a parenthesized join tree is a `ParenthesedFromItem`.
  `CROSS JOIN ... ON` and `NATURAL CROSS JOIN`, which PostgreSQL does not have,
  parse; a `USING (...) AS j` join alias and a join inside `DELETE ... USING`
  do not.
- PostgreSQL 16: `JOIN` binds more tightly than a comma, so a join condition
  sees its own join tree only, and `CROSS JOIN` is `INNER JOIN ... ON TRUE`.
  The joins of a tree associate left to right, so a `RIGHT JOIN` null-extends
  every source joined before it in its tree, and a `FULL JOIN` those and its
  own source. `JOIN ... USING` outputs one column per listed pair, in the list's
  order, then the left tree's remaining columns, then the right source's; the
  merged column takes the pair's common type, resolved as a `UNION` column's
  is, and reads the left column in an inner or left join, the right one in a
  right join, and `COALESCE` of both in a full join. The left tree must expose
  each listed name once, a column merged by an earlier `USING` counting once.
  `NATURAL` is `USING` over every common name, and with none is `ON TRUE`. An
  unqualified name reads the merged column, a qualified one the source's own,
  and `<source>.*` expands the source's whole row, its join columns included.
  A `LATERAL` item sees the `FROM` items before it and, inside a join tree, the
  sources on the left of its own join, except the left of a `RIGHT` or `FULL`
  join. A subquery in an `UPDATE`'s `FROM` or a `DELETE`'s `USING`, `LATERAL`
  or not, never sees the target.
- The reference service's `right_join.sql`, `full_join.sql`, `cross_join.sql`,
  `comma_join.sql`, and `lateral_join.sql` read meaningful columns and move as
  written. `using_join.sql` joins `tasks` and `comments` `USING (id)`, and
  `natural_join.sql` joins `tasks` and `projects` on their common `id` and
  `created_at`: both are valid SQL that matches unrelated rows, so each is
  rewritten to join columns that mean the same thing before it moves. No
  reference service task can lack its project, so the unmatched `FULL JOIN`
  rows on each side are proven on tables of `PostgresIntegrationTest`.

Decided by the architect on 2026-10-18, under the rule that pre-release
decisions are revisable by evidence:

- **Join groups (refines requirement 1).** A read's `FROM` takes the model a
  write's already has: the first item and each comma-separated item start a
  join group, and a `JOIN` of any kind joins the current group. A join
  condition resolves its columns and types its placeholders over its own
  group up to and including its joined source, then over the enclosing
  levels; `WHERE` and the projection see every group. Every `SELECT`, at any
  depth, and `UPDATE ... FROM` share the rule.
- **Nullability.** `LEFT` null-extends its joined source, `RIGHT` every
  earlier source of its group, and `FULL` both; a source once null-extended
  stays so. A write's target is never in a group, so it is never
  null-extended.
- **Conditions.** An inner, left, right, or full join requires exactly one
  `ON` condition, or from M29-W2 a `USING` list or `NATURAL`; `CROSS JOIN` and
  a comma take none, and `CROSS JOIN ... ON` is rejected. `NATURAL INNER JOIN`,
  which the parser reads as an `INNER JOIN` with no condition, keeps the
  condition rejection, because accepting it would analyze a cross join that
  PostgreSQL does not run. Every remaining modifier is rejected as
  `Only INNER, LEFT, RIGHT, FULL, and CROSS JOIN clauses are supported.`
- **Merged columns (refines requirement 2).** A merged column's type is the
  pair's type by Milestone 28's set-operation resolution; any other pair is
  rejected naming the column and both types, because a cast cannot be written
  in `USING`. It is nullable, in an inner or full join, only when both sides'
  columns are, because a matched pair holds equal values and a full join
  coalesces them, and as the left or the right column is in a left or right
  join. An unqualified reference to it, in a later condition, `WHERE`, a
  projection, or an aggregate, reads the merged column; a qualified reference
  reads its source's own column, null-extended with its source. A listed name
  missing from either side, or exposed more than once by the left tree, is
  rejected, as PostgreSQL rejects it. `NATURAL` merges the names common to the
  left tree's output and the joined source, and with none is a cross join.
- **A bare `*` (refines requirement 2)** expands each join group in order. A
  group any of whose joins merges a column expands to flat columns in
  PostgreSQL's order; a group that merges none keeps one embedded row per
  table source, as today. `<source>.*` embeds as today, nullable when its
  source is null-extended, and a derived source whose body writes such a `*`
  keeps the distinct-name rule.
- **`LATERAL` (refines requirement 3).** A `LATERAL` subquery as a read's
  `FROM` item, after a comma, as the joined source of any kind, or as a source
  of `UPDATE ... FROM` or `DELETE ... USING`, is a derived source whose body
  sees, as one enclosing level, the sources of the earlier groups and, unless
  it is joined by `RIGHT` or `FULL`, the earlier sources of its own group, and
  beyond them the enclosing levels of its query; never a write's target. Its
  placeholders and correlated references are typed and resolved as a
  predicate subquery's are. A `WITH` list inside the body keeps today's scope.
  An alias is required and a column list rejected, as for every subquery
  source. `LATERAL` function calls keep their rejection.
- **Writes.** The join kinds apply in `UPDATE ... FROM` exactly as in a read;
  `DELETE ... USING` takes comma-separated sources only, as JSqlParser reads
  it, and a `LATERAL` subquery among them from M29-W3.
- **Implementation order:** W1, W2, W3. W2 and W3 need W1.

- **M29-W1 — Outer, cross, and comma joins — Completed.** Implements
  requirement 1, and requirement 4's `right_join.sql`, `full_join.sql`,
  `cross_join.sql`, and `comma_join.sql` entries, by the join-group,
  nullability, and condition decisions, in every `SELECT` and in
  `UPDATE ... FROM`. `USING`, `NATURAL`, and `LATERAL` keep today's
  rejections. Acceptance evidence is analyzer tests of each kind's
  nullability, in a projection, an embedded and a lone wildcard, a derived
  source, and a returning `UPDATE ... FROM`; of condition scope across comma
  groups and enclosing levels; of binding order; and of each rejection;
  generated-source compilation; a PostgreSQL test of `RIGHT`, of `FULL` with an
  unmatched row on each side, of `CROSS`, and of a comma list with a later
  join; the four reference service entries in its query catalogue, run
  against PostgreSQL; the documentation; `mvn spotless:check`; and
  `mvn test`.
  Review evidence: the first `FROM` item and each comma item start a join
  group in every `SELECT` and in `UPDATE ... FROM`, through one shared
  resolution; a condition resolves and types its placeholders over its own
  group up to its joined source and then the enclosing levels, so
  `FROM users u, profiles p JOIN orders o ON o.user_id = u.id` is rejected as
  an unknown qualifier `u` while a cross-joined group and a correlated comma
  list inside `EXISTS` are accepted; `LEFT` null-extends its joined source,
  `RIGHT` every earlier source of its group, `FULL` both, and never the write
  target, in projections, embedded and lone wildcards, derived sources, and
  `RETURNING`; `CROSS JOIN ... ON`, a missing or doubled `ON`, and every other
  modifier are rejected after `LATERAL`, `NATURAL`, and `USING`; the four
  reference service entries moved verbatim into its query catalogue and run
  against PostgreSQL; `docs/queries.md` and the Nulls section of
  `docs/postgresql.md` state the null-extension rule. Reviewed with
  `mvn spotless:check` (pass) and
  `mvn -Dsqlcj.test.requireDocker=true test` (1667 tests, 0 failures, 0
  errors, 0 skipped).
- **M29-W2 — `USING` and `NATURAL` joins — Completed.** Implements
  requirement 2, and requirement 4's `using_join.sql` and `natural_join.sql`
  entries, rewritten as the reassessment states, by the merged-column and
  bare-`*` decisions, for every join kind and in `UPDATE ... FROM`. A bare `*`
  over a merging group lists, as PostgreSQL does, the merged columns of its
  latest join first, then those of each earlier join not merged again, then
  each source's remaining columns in order, and `INSERT ... SELECT *` counts
  the same columns. `NATURAL CROSS JOIN`, `CROSS JOIN ... USING`, and a
  `NATURAL` join with `ON` or `USING`, which JSqlParser 5.4 reads and
  PostgreSQL does not have, are rejected. `using_join.sql` becomes
  `projects JOIN memberships USING (organization_id)`, and `natural_join.sql`
  joins `tasks` `NATURAL` to a `projects` subquery exposing `id AS project_id`
  and `name`, because no two reference service tables share only names that
  mean one thing. Acceptance evidence
  is analyzer tests of a merged column's type, its nullability under each join
  kind, its unqualified and qualified resolution, a chained `USING`, a
  `NATURAL` join with and without common columns, the bare-`*` order and
  embedding, and each rejection; generated-source compilation; a PostgreSQL
  test of the merged order and type and of a `FULL JOIN ... USING` coalescing
  an unmatched row on each side; the two reference service entries in its
  query catalogue, run against PostgreSQL; the documentation;
  `mvn spotless:check`; and `mvn test`.
  Review evidence: `USING` and `NATURAL` joins of every kind merge their join
  columns in every `SELECT` and in `UPDATE ... FROM`, with the widened type,
  the per-kind nullability, null extension by a later `RIGHT` or `FULL` join,
  unqualified merged and qualified own-column reads, and each join-column and
  modifier rejection; a bare `*` over a merging group lists the merged columns
  first and then every member's remaining columns flat in group order,
  including a member an `ON`, `CROSS`, or non-merging `NATURAL` join adds after
  the latest merge, nullable when that join null-extends it, while a
  non-merging group keeps embedded rows and `source.*` embeds the full row;
  `using_join.sql` and `natural_join.sql` were rewritten into the reference
  service's query catalogue and run against PostgreSQL; `docs/queries.md` and
  `docs/postgresql.md` state the rules. Reviewed with `mvn spotless:check`
  (pass) and `mvn -Dsqlcj.test.requireDocker=true test` (1701 tests, 0
  failures, 0 errors, 0 skipped).
- **M29-W3 — `LATERAL` subqueries — Completed.** Implements requirement 3,
  and requirement 4's `lateral_join.sql` entry, by the `LATERAL` decision, in
  reads and writes. `LATERAL is not supported.` is retired: a `LATERAL`
  subquery takes the join kind's own classification and every subquery
  source's alias and column-list rejections. A reference to a source the body
  cannot see is reported as the unknown qualifier or missing column it is, as
  PostgreSQL rejects it, with no diagnostic of its own; a wildcard in the
  body's projection expands the body's own sources only, as every projection's
  does. `lateral_join.sql` moves verbatim. Acceptance evidence is analyzer
  tests of what a body sees — the earlier groups, its own group, neither left
  side of a `RIGHT` or `FULL` join, never a write's target — of a placeholder
  typed from an outer column, of binding order, of a left-joined body's
  nullability, and of a set-operation body; generated-source compilation; a
  PostgreSQL test of an inner and a left `LATERAL` join; the reference service
  entry in its query catalogue, run against PostgreSQL; the documentation;
  `mvn spotless:check`; and `mvn test`.
  Review evidence: a `LATERAL` subquery is a derived source as a read's first
  `FROM` item, after a comma, as the joined source of every kind, and in
  `UPDATE ... FROM` and `DELETE ... USING`; its body reads, as one enclosing
  level, the earlier groups and the earlier sources of its own group, a
  group's merged column included, only the earlier groups under `RIGHT` or
  `FULL`, never a write's target, and then its query's enclosing levels, so a
  first-item `LATERAL` inside `EXISTS` and a nested `LATERAL` read the outer
  query; correlated columns in its projection, conditions, `WHERE`, and
  aggregates resolve and its placeholders are typed through that scope and
  bind in textual order; `LEFT` and `FULL` make its columns nullable and a
  column read from a null-extended outer source stays nullable; a missing
  alias and a column list keep the subquery-source diagnostics, a
  non-`LATERAL` subquery source still sees no outer source, and `LATERAL`
  function calls keep their rejection; `lateral_join.sql` moved into the
  reference service's query catalogue and runs against PostgreSQL;
  `docs/queries.md` states the scope rule. Reviewed with `mvn spotless:check`
  (pass) and `mvn -Dsqlcj.test.requireDocker=true test` (1768 tests: 50
  runtime and 1718 CLI, 0 failures, 0 errors, 0 skipped).

#### Required

1. `RIGHT JOIN`, which null-extends every source joined before it in its
   `FROM` item; `FULL JOIN`, which null-extends those and its own source; and
   `CROSS JOIN` and a comma-separated `FROM` list, as joins with no condition,
   scoped as PostgreSQL scopes them: `JOIN` binds more tightly than a comma, so
   a join condition sees its own `FROM` item only. They apply in every
   `SELECT` and in `UPDATE ... FROM`.
2. `JOIN ... USING (...)` and `NATURAL JOIN`, of every join kind, whose join
   columns are merged as PostgreSQL defines: a merged column takes the pair's
   common type, an unqualified reference to it is not ambiguous, and it is
   nullable only when both sides' columns are in an inner or full join, and as
   the left or the right column is in a left or right join. A bare `*` over a
   `FROM` item that merges columns returns flat columns in PostgreSQL's order,
   the merged columns first, because no table's row is complete in it, while
   `<source>.*` still embeds that source's row record.
3. A `LATERAL` subquery in `FROM` or in any join sees the sources written
   before it, as a correlated subquery sees its outer query, except the left
   side of its own `RIGHT` or `FULL` join and a write's target, as in
   PostgreSQL, and is otherwise a derived source.
4. The reference service's join entries move from the unsupported catalogue
   into the query catalogue and run against PostgreSQL; the `USING` and
   `NATURAL` entries are first rewritten to join columns that mean the same
   thing.

#### Out of scope

- set-returning functions as sources, `LATERAL` function calls, `ROWS FROM`,
  and `WITH ORDINALITY`,
- join modifiers PostgreSQL does not have,
- a parenthesized join tree as a source, an alias on a join, and a `USING`
  join alias, which JSqlParser 5.4 does not read,
- a `WITH` list inside a `LATERAL` subquery seeing the sources before it.

#### Acceptance

- Focused analyzer tests of each join kind's scope and nullability, merged
  columns, and embedding; generated-source compilation; PostgreSQL tests of
  each join kind, an unmatched `FULL JOIN` row on each side included.
- The reference service's join entries run against PostgreSQL from its query
  catalogue.
- `mvn spotless:check` and `mvn test` pass.

### Milestone 30 — Everyday PostgreSQL Types

#### Goal

Map the column types an ordinary schema declares beyond today's set, each to an
exact Java type.

#### Work items

Activated on 2026-10-18 at the owner's request, after Milestone 29 was
completed. Reassessed against the working tree at `b6fa9bf`:
`ColumnTypeMapping`, `DefaultSchemaParser`, `QueryAnalyzer`,
`JavaCodeGenerator`, `DefaultTypeResolver`, the runtime's `UntypedText`,
`SqlArray`, and `JdbcQueryExecutor`, their tests, the reference service, and
`docs/postgresql.md` and `docs/queries.md`; JSqlParser 5.4 and pgjdbc 42.7.13,
the versions the POM pins, probed outside the repository against PostgreSQL
16.15 in `postgres:16-alpine`, the image the tests use; and the PostgreSQL 16
documentation of domains, network address types, and interval input and
output. Nothing was fetched from sqlc. Every requirement is unimplemented, as
assumed. Facts that correct or shape the plan:

- Today a column of `citext`, `inet`, `cidr`, `macaddr`, `macaddr8`,
  `interval`, `FLOAT`, `FLOAT(p)`, or a domain is recorded unmapped and fails
  only a query that uses it, and a cast to one of them is rejected as an
  unmapped cast type; `CREATE`, `ALTER`, and `DROP DOMAIN` are ignored. Schema,
  analyzer, and generator tests and both documents use `public.citext[]`,
  `interval`, and `FLOAT(p)` as their examples of unmapped types. The reference
  service declares `email_address` as a domain over `citext`, `users.handle
  citext`, `users.last_login_ip inet`, and `organizations.retention_period
  interval`; no query reads them, and its writes of `email` and `handle` cast
  `:email::text` and `:handle::text`.
- Binding: pgjdbc sends a `String` as `varchar`, and `citext = varchar`,
  `LIKE`, and `= ANY` of a `varchar[]` compare as `text`, case-sensitively. An
  untyped parameter, as `UntypedText` binds it with `Types.OTHER`, is resolved
  as `citext`, so `=`, `IN`, and `LIKE` match case-insensitively. A `varchar` is
  rejected where `inet` or `interval` is expected, and untyped text is
  accepted. `Connection.createArrayOf` builds a `citext`, `inet`, `cidr`,
  `macaddr`, or `interval` array from `String` elements, and a `citext[]`
  compares case-insensitively.
- Reading: the driver reports `citext`, the network types, and `interval` as
  `Types.OTHER` and rejects `getObject(position, String.class)` for each, as
  for JSON, while `getString` returns PostgreSQL's text. The same holds for
  their elements in an array, which `SqlArray.getList` reads with
  `getObject(2, String.class)` today.
- Interval text follows the session's `IntervalStyle`, which pgjdbc never sets.
  PostgreSQL's default, `postgres`, writes `1 year 2 mons -3 days
  +01:02:03.000004`, `-1 days -00:00:00.000001`, or `100000:00:00`, years and
  months together being one month count, and reads the same form. Under
  `sql_standard` a leading `-` applies to every field without a sign of its
  own, so text with an explicit sign on every field reads the same under every
  style. PostgreSQL compares `1 day` equal to `24 hours` and `1 mon` equal to
  `30 days`.
- A domain column is reported to the driver as its base type, so it binds,
  compares, and reads exactly as its base, a domain over `citext` included. An
  array of a domain reports its elements as the domain, which the driver
  classifies as `Types.DISTINCT` and reads through no typed `getObject`, so it
  cannot be read exactly. A domain's `NOT NULL` does not hold on a
  null-extended row. `ALTER TYPE ... RENAME TO` renames a domain and
  `DROP TYPE` drops one; `DROP DOMAIN` fails while a column uses the domain,
  and with `CASCADE` drops that column.
- JSqlParser 5.4 reads `CREATE DOMAIN` as `CreateDomain`, with its name and
  base `ColDataType`, and `ALTER DOMAIN` as `AlterDomain` for every action, but
  never `DROP DOMAIN`, and not the check `pg_dump` writes over a `citext`
  operator, `CHECK ((VALUE OPERATOR(public.~~) '%@%'::public.citext))`; the
  same statement's tokens before its `CONSTRAINT` read as a `CREATE DOMAIN`.
  `pg_dump` writes `public.citext`, `public.<domain>`, and
  `CREATE DOMAIN public.<domain> AS public.citext`. The parser drops an
  interval's fields, so `interval year to month` reads as `interval`, and
  keeps a float precision only in the type text, as `float (24)`.
- PostgreSQL 16 maps `float` to `double precision`, `float(1)` to `float(24)`
  to `real`, and `float(25)` to `float(53)` to `double precision`, and rejects
  any other precision. It defines `MIN` and `MAX` for `citext`, `inet`, and
  `interval`, and for `cidr` through `inet`, so they return `inet`, but none
  for `macaddr` and `macaddr8`; `SUM` and `AVG` of an `interval` are an
  `interval`. A set operation resolves `citext` with `text` as `text`.

Decided by the architect on 2026-10-18, under the rule that pre-release
decisions are revisable by evidence:

- **Text-form types (refines requirements 1 and 2).** `citext`, `inet`,
  `cidr`, `macaddr`, and `macaddr8` are bound as untyped text and read with
  `getString`, as JSON is; an array of one binds under its own element name
  and reads its elements with `getString`. `citext` resolves unqualified or
  `public`-qualified, as `pg_dump` writes it. A `LIKE` or `ILIKE` pattern
  placeholder takes a `citext` column, because a search is what the type is
  declared for.
- **Interval value (refines requirement 3).** The runtime gains
  `org.sqlcj.runtime.SqlInterval`, a record of `int months`, `int days`, and
  `long microseconds`, imported by generated code as a `java.time` type is,
  because the runtime has no driver dependency and `PGInterval` is the
  driver's. It is written as untyped text with an explicit sign on every field
  and read from the `postgres` style alone; text of another style fails naming
  `IntervalStyle`. `toPeriod()` fails on a time part; `toDuration()` fails on
  a month and counts a day as 24 hours, as `Duration.ofDays` does and as
  PostgreSQL compares intervals, because an interval between two timestamps
  carries whole days.
- **Domains (refines requirement 4).** A domain is resolved to its base's
  mapped type when it is declared, and a column when the column is declared,
  so a later rename or drop of the domain never changes a modeled column. A
  type name resolves to a modeled domain before an enum, and declaring an enum
  removes a modeled domain of its name, because the `DROP TYPE` that freed
  either name may be unseen. An unreadable `CREATE DOMAIN` is modeled from its
  tokens before its first top-level `COLLATE`, `DEFAULT`, `CONSTRAINT`, `NOT`,
  `NULL`, or `CHECK`, none of which can change its type, and as a domain over
  an unmapped type when those cannot be read either. `DROP DOMAIN`, which the
  parser never reads, is classified from its tokens, as D-F classifies
  `ALTER TABLE`. As with `DROP TYPE ... CASCADE`, the columns
  `DROP DOMAIN ... CASCADE` drops are not modeled as dropped. An array of a
  domain stays unmapped under requirement 6.
- **Reference service (adds requirement 7).** Its domain, `citext`, `inet`, and
  `interval` columns are the probe's evidence, its casts exist only because
  they were unmapped, and its `pg_dump` snapshot is the input that writes
  domain and `citext` DDL as `pg_dump` does.
- **Implementation order:** W1, W2, W3, W4, W5. W4 needs W1 for a domain over
  `citext`; W5 needs W1, W2, and W4.

- **M30-W1 — `citext` and network address types — Completed.** Implements
  requirements 1 and 2, and requirement 6 for these types, by the text-form
  decision. Each maps to `String` as a column, a placeholder a column of it
  types, a cast, and a one-dimensional array. `= ANY(:list)` over such a column
  binds an array of its type, and a `LIKE` or `ILIKE` pattern placeholder takes
  a `citext` column. `MIN` and `MAX` of `citext`, `inet`, and `cidr` are typed,
  `cidr`'s as `inet`, while `macaddr` and `macaddr8` keep the cast diagnostic;
  a set operation and a merged join column resolve each type only with
  itself. Acceptance evidence is schema tests of each spelling,
  `public.citext` and the arrays included; analyzer tests of comparison, `IN`,
  `LIKE`, `= ANY`, cast, aggregate, and set-operation typing; generator tests
  and generated-source compilation; a runtime test of text array elements; a
  PostgreSQL round trip of each type and its array, with a case-insensitive
  `citext` `=`, `LIKE`, and `= ANY`; the documentation; `mvn spotless:check`;
  and `mvn test`.
  Review evidence: `citext`, `inet`, `cidr`, `macaddr`, and `macaddr8` map to
  `String` and their one-dimensional arrays to `List<String>` in columns,
  `ALTER TABLE` columns, and casts, `citext` unqualified, `public`-qualified,
  and quoted, while `reporting.citext` and a two-dimensional array stay
  unmapped; a comparison, `IN`, and `= ANY` placeholder takes the column's
  type, a `LIKE` or `ILIKE` pattern takes a `citext` column, `MIN` and `MAX`
  of `cidr` are `inet`, of `macaddr` and `macaddr8` keep the cast diagnostic,
  and a set operation or merged join column resolves each type only with
  itself; generated code binds them through `UntypedText` or a `SqlArray` of
  the type's own name, reads them with `getString` or `SqlArray.getList`, and
  compiles; `SqlArray` reads a `String` element with `getString`; PostgreSQL
  16 round trips every type and its array with nulls, a case-insensitive
  `citext` `=`, `LIKE`, and `= ANY`, and an `inet` comparison;
  `docs/postgresql.md` and `docs/queries.md` state the rules. Reviewed with
  `mvn spotless:check` (pass) and `mvn -Dsqlcj.test.requireDocker=true test`
  (1842 tests: 51 runtime and 1791 CLI, 0 failures, 0 errors, 0 skipped).
- **M30-W2 — `INTERVAL` — Completed.** Implements requirement 3, and
  requirement 6 for `interval`, by the interval-value decision. `INTERVAL` with
  any fields or precision maps to `SqlInterval` as a column, a cast
  placeholder, and an array, bound as untyped text and as an `interval` array,
  and read through `SqlInterval`'s parser; `MIN`, `MAX`, `SUM`, and `AVG` of an
  interval column are intervals. The text written is
  `<±months> mons <±days> days <±microseconds> microseconds`, which every
  `IntervalStyle` reads alike over the whole range, while the `HH:MM:SS` form
  rejects the least time part. A `Duration` becomes microseconds alone, with
  no day, because a `Duration` day is exactly 24 hours, and a `Period` is read
  back with its years taken from the month count, as PostgreSQL writes it.
  `SqlInterval` converts from and to `Period` and `Duration`, failing on a
  component the target cannot represent and on overflow. The analyzer and
  documentation examples of an unmapped cast type move from `interval` to
  `money`. Acceptance evidence is runtime tests of every `postgres`-style form,
  of the text written, and of each conversion and its failures; schema,
  analyzer, and generator tests and generated-source compilation; a PostgreSQL
  round trip of an interval with months, days, and a time part, a negative one,
  an array, a write while the session's style is `sql_standard`, and
  `now() - :age::interval`; the documentation; `mvn spotless:check`; and
  `mvn test`.
  Review evidence: `INTERVAL` with any fields or precision, `interval(3)`,
  `INTERVAL YEAR TO MONTH`, and `INTERVAL DAY TO SECOND(3)` included, maps to
  `SqlInterval` in a column, an `ALTER TABLE` column, and a cast, and its
  one-dimensional array to `List<SqlInterval>`, while `interval[][]` stays
  unmapped; a comparison, `IN`, `BETWEEN`, and `= ANY` placeholder takes the
  column's type, a cast placeholder is typed inside `now() - :age::interval`,
  and `MIN`, `MAX`, `SUM`, and `AVG` of an interval column are intervals;
  generated code binds through `UntypedText` of `format()` or a `SqlArray` of
  `interval`, reads through `SqlInterval.parse`, imports `SqlInterval`, and
  compiles; `SqlInterval` reads every probed `postgres`-style form, the least
  and greatest time parts included, rejects other styles naming
  `IntervalStyle`, writes an explicit sign on every field, and converts from
  and to `Period` and `Duration` with their component and overflow failures;
  PostgreSQL 16 round trips a mixed and a negative value, an array with a null
  element, a null scalar, and a write under `sql_standard`, and filters on
  `now() - :age::interval`; the unmapped-cast examples use `money`;
  `docs/postgresql.md` and `docs/queries.md` state the rules. Reviewed with
  `mvn spotless:check` (pass) and `mvn -Dsqlcj.test.requireDocker=true test`
  (1907 tests: 89 runtime and 1818 CLI, 0 failures, 0 errors, 0 skipped).
- **M30-W3 — `FLOAT(p)` — Completed.** Implements requirement 5, and
  requirement 6 for `FLOAT(p)`. `FLOAT` and `FLOAT(25)` to `FLOAT(53)` map to
  `DOUBLE PRECISION` and `FLOAT(1)` to `FLOAT(24)` to `REAL`, in any case and
  spacing, as a column, an `ALTER TABLE` column, a cast and a cast
  placeholder, and a one-dimensional array, so they bind, read, and aggregate
  exactly as `REAL` and `DOUBLE PRECISION` do. Any other precision, which
  PostgreSQL rejects, stays unmapped and is recorded as `FLOAT(<p>)`, its
  precision kept, because `FLOAT` alone is mapped; every other type keeps
  its arguments removed from the recorded text. Acceptance evidence is
  schema tests of each boundary, `FLOAT(0)` and `FLOAT(54)`, and an array;
  analyzer tests of a cast, a cast placeholder, and an array cast; a
  PostgreSQL round trip of `FLOAT(24)` and `FLOAT`; the documentation;
  `mvn spotless:check`; and `mvn test`.
  Review evidence: `FLOAT`, `float`, `FLOAT(25)` to `FLOAT(53)`, and
  `float (25)` map to `DOUBLE PRECISION`, and `float(1)` to `FLOAT(24)` to
  `REAL`, in a column, an `ALTER TABLE ... ADD COLUMN` and `ALTER COLUMN ...
  TYPE` column, a `CAST`, a `::` cast, and a cast placeholder, their
  one-dimensional arrays included; `float(0)` and `float(54)` are recorded as
  `FLOAT(0)` and `FLOAT(54)`, `float(24)[][]` as `FLOAT[][]`, a schema holding
  one still loads, only a query reading it fails naming `FLOAT(54)`, and
  `:value::float(54)` fails naming its placeholder and `FLOAT(54)`; `REAL`,
  `FLOAT4`, `DOUBLE PRECISION`, `FLOAT8`, and every other spelling are
  unchanged; PostgreSQL 16 round trips a `FLOAT(24)` column as `Float` and a
  `FLOAT` column as `Double`, values and nulls; `docs/postgresql.md` states
  the rules. Known Low finding left open: JSqlParser 5.4 writes
  `float(2147483647)` as `float (MAX)`, which maps to `DOUBLE PRECISION`
  instead of staying unmapped, and rejects a precision beyond an `int` while
  parsing. Reviewed with `mvn spotless:check` (pass) and
  `mvn -Dsqlcj.test.requireDocker=true test` (1922 tests: 89 runtime and 1833
  CLI, 0 failures, 0 errors, 0 skipped).
- **M30-W4 — Domains — Completed.** Implements requirement 4, and
  requirement 6 for domains, by the domains decision. `CREATE DOMAIN` models a
  domain under its unqualified or `public`-qualified name over a mapped base, a
  domain or a `public`-qualified name included, and replaces a modeled domain
  of its name; an unreadable one, such as `pg_dump`'s with an operator check or
  with `NOT NULL DEFAULT`, is modeled from its tokens. `ALTER DOMAIN ...
  RENAME TO` and `ALTER TYPE ... RENAME TO` rename it, renaming an enum renames
  it in each domain over it, a rename onto the name of a modeled domain removes
  that domain, and every other `ALTER DOMAIN` action, an unreadable one
  included, is ignored; `DROP DOMAIN [IF EXISTS] ... [CASCADE | RESTRICT]`
  removes the modeled domains it names and ignores any other name, and one
  whose words state no such statement fails as an unsupported `DROP DOMAIN`.
  A column or cast of a domain has its base's type, Java type, binding, and
  reading, a domain over an array type included, and the column's own
  nullability; a domain over an unmapped type and an array of a domain are
  recorded with their declared type. PostgreSQL 16 defines no comparison and
  no `MIN` or `MAX` for a domain over an enum, so the documentation says so
  and its round trip writes and reads such a column only. Acceptance evidence
  is schema tests of each statement, a chain, both unreadable `pg_dump` forms,
  renames and drops in order, and enum name reuse; analyzer tests of a domain
  column and cast; generated-source compilation; a PostgreSQL round trip of a
  domain over `citext` compared case-insensitively by `=` and `= ANY`, over an
  integer with a check, over an enum, and over `text[]`, and of a null read
  through a left join from a column of a `NOT NULL` domain; the reference
  service's three schema inputs still generating one byte-identical tree; the
  documentation; `mvn spotless:check`; and `mvn test`.
  Review evidence: `CREATE DOMAIN` models a domain over `integer`, `citext`,
  `public.citext`, `text[]`, an enum, another domain, `CHAR`, and the unmapped
  `money`, under an unqualified, `public`-qualified, quoted, or non-ASCII
  unquoted name such as `café`, while another schema's domain is ignored; both
  unreadable `pg_dump` forms are modeled from their words, and a name-only
  fallback models a domain over no type; `ALTER DOMAIN` and `ALTER TYPE`
  renames, an enum rename reaching the domains over it, and enum and domain
  name reuse in both orders resolve the last declared type; `DROP DOMAIN` with
  `IF EXISTS`, several names, `CASCADE`, or `RESTRICT` removes the named
  modeled domains, `café` included, and words that state no such statement
  fail as `Unsupported schema statement: DROP DOMAIN at line <n>`; a built-in
  spelling resolves before a domain and a domain before an enum; a column or
  cast of a domain takes its base's type with the column's own nullability,
  and an array of a domain, a domain over an unmapped type, and another
  schema's domain stay unmapped with their diagnostics; PostgreSQL 16 round
  trips a domain over `citext` compared case-insensitively by `=` and
  `= ANY`, over a checked integer, over an enum, and over `text[]`, and a null
  read through a left join from a `NOT NULL` domain column; the reference
  service's three schema inputs generate one byte-identical tree;
  `docs/postgresql.md` and `docs/queries.md` state the rules. Reviewed with
  `mvn spotless:check` (pass) and `mvn -Dsqlcj.test.requireDocker=true test`
  (1968 tests: 89 runtime and 1879 CLI, 0 failures, 0 errors, 0 skipped).
- **M30-W5 — The reference service's types — Completed.** Implements
  requirement 7. The reference service's `CreateUser` and `ClaimHandle` bind
  `email` and `handle` without their `::text` casts, and its query catalogue
  gains `GetUserByEmail`, which finds a user by `email` alone, as its unique
  index does, `GetUserByHandle`, which finds a live user by organization and
  `handle`, both matched case-insensitively and reading the domain, `citext`,
  and `inet` columns, `RecordLogin`, which sets `last_login_ip`, and
  `SetRetentionPeriod` and `GetRetentionPeriod`, which set and read
  `retention_period`. Each new query lists its columns, so the generated files
  stay the same set; no production code, schema input, or unsupported
  catalogue entry changes. Acceptance evidence is the reference service test
  running them against PostgreSQL, a case-insensitive lookup through the
  domain and through `citext`, an `inet` value, and an interval with months,
  days, and a time part read back exactly, and a cleared interval read as
  `null`; its three schema inputs still generating one byte-identical tree;
  the documentation; `mvn spotless:check`; and `mvn test`.
  Review evidence: `CreateUser` and `ClaimHandle` write `email` and `handle`
  with no cast, and every existing reference service test that calls them
  passes unchanged; the catalogue gains `GetUserByEmail`, `GetUserByHandle`,
  `RecordLogin`, `SetRetentionPeriod`, and `GetRetentionPeriod`, each listing
  its columns, with parameters in SQL order, value before id;
  `ReferenceServiceIntegrationTest.shouldExecuteTheGeneratedReferenceServiceTypes`
  generates them from the migration history, compiles them, and runs them
  against PostgreSQL 16 in an organization of its own: the domain over
  `citext` matches an address in another case and reads back its stored
  spelling, an unused address reads no user, the `citext` handle matches in
  another case beside its user's email, an `inet` login address is written
  and read back, and an interval of 14 months, 3 days, and 3,723,000,004
  microseconds is read back equal after a `null` read, then cleared to
  `null`; the migration history, the hand-written snapshot, and the dump
  still generate one byte-identical tree of the unchanged file set; the
  unsupported `lower(:email)` entry is unchanged and still rejected;
  `docs/postgresql.md` and `docs/queries.md` name the test. Reviewed with
  `mvn spotless:check` (pass),
  `mvn -Dsqlcj.test.requireDocker=true -Dtest=ReferenceServiceIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`
  (15 tests, 0 failures, 0 errors, 0 skipped), and
  `mvn -Dsqlcj.test.requireDocker=true test` (1969 tests: 89 runtime and 1880
  CLI, 0 failures, 0 errors, 0 skipped).

#### Required

1. `citext` is a `String`, bound without a declared type, as an enum is, so
   that PostgreSQL compares a parameter as `citext`: a parameter bound as
   `varchar` resolves the comparison as `text`, case-sensitively. A `LIKE` or
   `ILIKE` pattern placeholder takes a `citext` column.
2. `inet`, `cidr`, `macaddr`, and `macaddr8` are a `String` in PostgreSQL's text
   form, bound without a declared type; `java.net.InetAddress` cannot hold a
   network mask.
3. `INTERVAL`, with any fields and precision, is an exact runtime value of
   months, days, and microseconds, PostgreSQL's own representation, written as
   text every interval style reads alike and read from PostgreSQL's default
   `postgres` style, with conversions to `java.time.Period` and
   `java.time.Duration` that fail when the value holds a component the target
   cannot represent: a `Period` cannot hold a time part, and a `Duration`
   cannot hold a month, while a day converts to 24 hours, as PostgreSQL
   compares intervals. A cast placeholder such as `:age::interval` is typed.
4. A domain over a mapped type, a domain over such a domain included, is that
   type, modeled from `CREATE DOMAIN`, `pg_dump`'s form included, `ALTER DOMAIN
   ... RENAME TO` and `ALTER TYPE ... RENAME TO`, and `DROP DOMAIN`; its column
   is nullable unless the column itself is `NOT NULL`, because PostgreSQL does
   not always enforce a domain's `NOT NULL`. A domain over an unmapped type
   stays unmapped.
5. `FLOAT` is `Double`, `FLOAT(1)` to `FLOAT(24)` is `Float`, and `FLOAT(25)` to
   `FLOAT(53)` is `Double`, as PostgreSQL maps them.
6. One-dimensional arrays of these types follow the array rules wherever the
   runtime can bind them exactly; any other stays unmapped. Arrays of `citext`,
   the network types, `INTERVAL`, and `FLOAT(p)` are mapped; an array of a
   domain is not, because the driver reads its elements as the domain.
7. The reference service's writes of its `citext` and domain columns need no
   cast, and its query catalogue reads and binds its domain, `citext`, `inet`,
   and `interval` columns against PostgreSQL.

#### Out of scope

- composites, ranges, multiranges, multidimensional arrays, `hstore`,
  geometric types, `MONEY`, `XML`, `TIMETZ`, and `tsvector`,
- type overrides and JSON library types,
- reading an interval in an `IntervalStyle` other than `postgres`,
- the columns `DROP DOMAIN ... CASCADE` drops, as for `DROP TYPE ... CASCADE`,
- a type of an extension installed in a schema other than `public`,
- set-operation and merged-column resolution between two different types this
  milestone maps, which keeps the cast.

#### Acceptance

- Schema, analyzer, generator, and runtime tests of each type; a PostgreSQL
  round trip of each, including a case-insensitive `citext` comparison and an
  interval with months, days, and a time part.
- The reference service's domain, `citext`, `inet`, and `interval` columns run
  against PostgreSQL from its query catalogue.
- `mvn spotless:check` and `mvn test` pass.

### Milestone 31 — Batch Writes

#### Goal

Execute one write statement for many rows in one round trip, as Java developers
expect from `JdbcTemplate.batchUpdate` and sqlc's `:batchexec`.

#### Work items

Activated on 2026-10-22 at the owner's request, after Milestone 30 was
completed. Reassessed against the working tree at `7b78b07`: `QueryType`,
`DefaultQueryParser`, `QueryAnalyzer`, `JavaNames`, `JavaCodeGenerator`, the
runtime's `QueryExecutor`, `JdbcQueryExecutor`, and `JdbcTransactions`, their
tests, `SpringTransactionIntegrationTest`, the reference service, and
`docs/queries.md` and `docs/postgresql.md`; pgjdbc 42.7.13, the version the
POM pins, probed outside the repository against PostgreSQL 16.15 in
`postgres:16-alpine`, the image the tests use, together with Spring Framework
7.0.9's `TransactionAwareDataSourceProxy`. Nothing was fetched from sqlc. Every
requirement is unimplemented, as assumed. Facts that correct or shape the
plan:

- `QueryType` already parses `:execresult`, `:batchexec`, `:batchmany`, and
  `:batchone`, but no test or document names them: the analyzer rejects each
  on every statement, a write without `RETURNING` with
  `Write queries without RETURNING must be declared as :exec`, and the
  generator's branch for them, which would emit a method throwing
  `UnsupportedOperationException`, is unreachable. The generator would also
  give a batch query a `<QueryName>Result` record, because it treats every
  annotation other than `:exec` as one with a result.
- `QueryExecutor` has four abstract operations and one hand-written
  implementation outside the runtime, a test double in
  `JavaCodeGeneratorNamingTest`; `JdbcQueryExecutor` binds `UntypedText`,
  `SqlArray`, and every other argument per statement and, over a
  `DataSource`, leaves the connection's auto-commit as it finds it.
- Counts: without `reWriteBatchedInserts` the driver reports each row's exact
  count, `1` per inserted row, the count of an update or delete, and `0` for
  a row that matched nothing or an `ON CONFLICT DO NOTHING` conflict. With it,
  inserts are rewritten into multi-row statements and every row of a
  rewritten group reports `Statement.SUCCESS_NO_INFO` (`-2`), while a row left
  over executes alone and reports its count: 3 rows report `-2, -2, 1`, 21
  rows twenty `-2` and one `1`, and 2, 130, 1,000, or 10,000 rows only `-2`.
  Updates and deletes are never rewritten. An empty batch reports an empty
  array.
- A batch with `RETURNING` reports `0` for every row and gives no access to
  the returned rows, so a returning batch cannot be expressed through
  `executeBatch`.
- Failure: a failing row raises `BatchUpdateException`, a `SQLException` with
  the row's SQL state, here `23505`. Inside a transaction every count is
  `EXECUTE_FAILED`, the transaction is aborted, and a rollback leaves no row.
  In auto-commit mode the outcome depends on the driver's internal flushing:
  a duplicate key at row 2,000 of 3,000 left 1,785 rows committed, and at row
  25,000 of 30,000 left 24,990, while the same batches with
  `reWriteBatchedInserts` left none. Only an explicit transaction makes a batch
  all or nothing.
- Inside a Spring-managed transaction, a connection a
  `TransactionAwareDataSourceProxy` hands out is not in auto-commit mode, and
  `commit()` on it commits Spring's transaction: a row written before it
  survived the Spring rollback. Outside a Spring transaction the proxy hands
  out an auto-commit connection. A batch that always committed its
  `DataSource` connection would therefore break Milestone 27's Spring
  participation.
- Binding per row works as a single execution binds: rows that bind `null`
  and values, untyped text and its `null`, and an array and a `null` array in
  one batch all insert correctly, with and without rewriting, and a prepared
  statement runs a second batch. 10,000 rows ran as one batch in 137 ms, or
  57 ms rewritten, so no chunking is needed for the acceptance.
- A generated method of the planned shape, a nested record per query and
  `batch.stream().map(params -> java.util.Arrays.asList(...)).toList()` passed
  as `List<? extends List<?>>`, compiles with `--release 17` without a
  warning, for no parameter, for repeated parameters, and with an
  `UntypedText` argument; a `null` list or element fails with
  `NullPointerException` before the executor is called.

Decided by the architect on 2026-10-22, under the rule that pre-release
decisions are revisable by evidence:

- **Annotation (refines requirement 1).** The annotation is `:batchexec`,
  sqlc's name for the same command, which the parser already reads. It
  accepts exactly the writes `:exec` accepts, so a multi-row, `SELECT`-sourced,
  upsert, joined, or `USING` write batches too, and its rejections say what to
  write: a write without `RETURNING` must be `:exec` or `:batchexec`, and a
  `:batchexec` write with `RETURNING` is told that a batch returns no rows.
  `:execresult`, `:batchmany`, and `:batchone` stay as they are.
- **Generated method (refines requirement 1).** A `:batchexec` query generates
  a nested `public record <QueryName>Params`, whose components are named as the
  single-call method's parameters would be, apart from the names a record
  cannot declare, and a method `int[] <method>(List<<QueryName>Params> batch)`
  that builds every row's argument list before it calls the executor, so a
  `null` list or element fails with `NullPointerException` before anything
  executes. It generates no result record. `int[]` is what JDBC's
  `executeBatch` and Spring's `batchUpdate` return.
- **Runtime contract (refines requirement 2).** `QueryExecutor` gains the
  abstract `int[] executeBatch(String repository, String query, String sql,
  List<? extends List<?>> batch)`, abstract so that a decorator that does not
  implement it fails to compile rather than at run time. An empty batch
  returns an empty array without obtaining a connection.
- **Transaction rule (amends requirement 2).** Over a `DataSource`,
  `JdbcQueryExecutor` runs a batch as its own transaction only when the
  connection it obtains is in auto-commit mode, restoring auto-commit only
  after a successful commit or rollback and attaching a failed rollback as
  suppressed, as `JdbcTransactions` does. A connection that is not in
  auto-commit mode already belongs to a transaction its owner ends, such as
  Spring's, so the batch takes part in it and never commits or rolls it back.
  Over a caller-owned connection nothing changes: the driver decides what a
  failed batch in auto-commit mode leaves, and the documentation says to run a
  batch there inside a transaction.
- **Counts (refines requirement 3).** The method returns the driver's array
  unchanged, `SUCCESS_NO_INFO` included; the documentation states the rewrite
  pattern above.
- **Reference service (adds requirement 4).** Repeated writes a Java developer
  expects to batch are one of the reasons not to publish today, and Milestones
  27 to 33 each add their part to the reference service, so it gains a batched
  audit-log insert and a batched optimistic task move.
- **Implementation order:** W1, W2, W3. W2 needs W1's operation; W3 needs W2.

- **M31-W1 — Runtime batch execution — Completed.** Implements
  requirement 2 by the runtime-contract and transaction decisions.
  `QueryExecutor.executeBatch` executes one statement once per argument list
  as one JDBC batch and returns the driver's counts in batch order;
  `JdbcQueryExecutor` binds every list exactly as a single execution binds
  its arguments, runs the batch on one connection, translates any
  `SQLException`, a `BatchUpdateException` included, into the usual
  `QueryExecutionException` naming the repository and query, and closes the
  statement and an obtained connection on every path. Over a `DataSource`
  whose connection is in auto-commit mode the batch commits on success and
  rolls back on failure; over one already in a transaction, and over a
  caller-owned connection, it never commits, rolls back, or changes
  auto-commit. An empty batch obtains no connection. Acceptance evidence is
  runtime tests of counts in order for inserts, updates, and deletes, of
  `UntypedText` and `SqlArray` rows, of an empty batch, of a failing row
  leaving no row of a `DataSource` batch with auto-commit restored and the
  connection closed, of a `DataSource` connection already in a transaction
  and a caller-owned one left uncommitted and unchanged, and of failure
  translation; the Javadoc and `docs/postgresql.md`; `mvn spotless:check`;
  and `mvn test`.
  Review evidence: `QueryExecutor` gains the abstract `int[]
  executeBatch(String, String, String, List<? extends List<?>>)`, documented
  with batch order, unchanged driver counts including `SUCCESS_NO_INFO`, the
  empty batch, and `NullPointerException` for a `null` batch;
  `JdbcQueryExecutor` returns `new int[0]` for an empty batch before obtaining
  a connection, prepares the statement once, binds and adds each list, and
  returns `executeBatch`'s counts unchanged; over an auto-commit `DataSource`
  connection it disables auto-commit, commits, and restores it, rolls back on
  failure, restores auto-commit only after a successful commit or rollback,
  and suppresses later rollback, restore, and close failures; an unchecked
  failure there is rethrown as the same instance after the rollback, restore,
  and close; a `DataSource` connection already in a transaction runs the
  batch only, and a caller-owned connection is never committed, rolled back,
  reconfigured, or closed. Runtime tests prove counts `1, 1`, `1, 0, 1`, and
  `1, 0, 1` for an insert, update, and delete, `UntypedText` and `SqlArray`
  row binding, an empty batch touching no connection or statement, a failing
  row leaving no row with `getConnection`, `setAutoCommit(false)`,
  `rollback`, `setAutoCommit(true)`, `close` and a `BatchUpdateException`
  cause, the same sequence for a `null` row rethrown as
  `NullPointerException`, a connection already in a transaction seeing only
  `getConnection` and `close`, an untouched caller-owned connection, and
  failure translation on both paths. Reviewed with `mvn spotless:check`
  (pass) and `mvn -Dsqlcj.test.requireDocker=true test` (1981 tests: 101
  runtime and 1880 CLI, 0 failures, 0 errors, 0 skipped).
- **M31-W2 — `:batchexec` queries — Completed.** Implements requirements 1
  and 3 by the annotation, generated-method, and counts decisions. The
  analyzer accepts `:batchexec` wherever it accepts `:exec`, names both
  annotations in the diagnostic of a write without `RETURNING`, and rejects a
  `:batchexec` write with `RETURNING` with a diagnostic that a batch returns
  no rows; a `:batchexec` `SELECT` or set operation keeps its existing
  rejection. The generator emits the `Params` record, whose components avoid
  `Object`'s member names as well as the names a method parameter avoids, and
  the `int[]` batch method; records the record's name against enum names as
  it records result types; imports `java.util.List`; and emits no result
  record or row mapper. `docs/queries.md` documents the annotation;
  `docs/postgresql.md`, whose batch section W1 wrote with the counts and the
  caller-owned auto-commit caveat, links it and lists its verification.
  Acceptance evidence is analyzer tests of a single-row, multi-row, and
  `SELECT`-sourced insert, an upsert, an update with and without `FROM`, and
  a delete with and without `USING` accepted as `:batchexec`, and of both
  rejections; generator tests of the record, the method, a query without
  parameters, a repeated parameter, enum, `JSON`, interval, and array
  arguments, a component a record cannot declare, and the enum collision;
  generated-source compilation; PostgreSQL tests that insert, update, and
  delete 5,000 rows in one batch each with the driver's exact counts, that
  report `-2, -2, 1` for a three-row insert over `reWriteBatchedInserts`, in
  which a duplicate key at row 2,000 of 3,000 leaves no row of a `DataSource`
  batch and a `null` element executes nothing, and in which a batch inside a
  Spring-managed transaction rolls back with it; `mvn spotless:check`; and
  `mvn test`.
  Review evidence: the analyzer accepts `:batchexec` on every write shape
  `:exec` accepts, analyzing to the `:exec` model apart from its type, names
  both annotations in the diagnostic of a write without `RETURNING`, and
  rejects a returning `:batchexec` write because a batch returns no rows. The
  generator emits a nested `<QueryName>Params` record in logical parameter
  order, a one-line record without components for a query without
  parameters, components avoiding `Object`'s member names (`hashCode1`), the
  record name checked against enum names, `java.util.List` imported, no result
  record or row mapper, and `int[] <method>(List<<QueryName>Params> batch)`
  passing `batch.stream().map(params -> java.util.Arrays.asList(...)).toList()`
  to `executeBatch` with arguments in binding order wrapped as a single
  execution wraps them; a query that binds no parameter adds
  `.map(java.util.Objects::requireNonNull)`, so a `null` element of every
  batch raises `NullPointerException` before the executor runs, which a
  generator test proves by loading the compiled repository over a recording
  executor that sees no batch. PostgreSQL 16 tests prove 5,000-row insert,
  update, and delete counts, `-2, -2, 1` over `reWriteBatchedInserts`, a
  duplicate key at row 2,000 of 3,000 and a `null` element each leaving no
  row, and a Spring rollback. Reviewed with `mvn spotless:check` (pass) and
  `mvn -o -Dsqlcj.test.requireDocker=true test` (2004 tests: 101 runtime and
  1903 CLI, 0 failures, 0 errors, 0 skipped; `JavaCodeGeneratorTest` 157,
  `QueryAnalyzerTest` 844, `PostgresIntegrationTest` 65,
  `SpringTransactionIntegrationTest` 7).
- **M31-W3 — The reference service's batch writes — Completed.** Implements
  requirement 4. The query catalogue gains `RecordAuditEntries`, a
  `:batchexec` form of `RecordAudit`'s insert into the partitioned
  `audit_log`, and `MoveTasks`, a `:batchexec` form of `MoveTask`'s optimistic
  update, each written after its single-call form. No production code
  changes, and the generated file set stays the same, because each `Params`
  record is nested in the generated repository. Acceptance evidence is a
  reference service test running both against PostgreSQL inside its own
  organization, project, and request ids: the audit entries read back through
  `ListAudit2026` with their `JSONB`, `uuid`, and `null` actor, and a task
  batch whose second row repeats the version its first row already advanced,
  reporting `1, 0, 1`; the existing test's `audit_log_2026` assertion narrowed
  to its own entry, because the tests share one database in no fixed order;
  the three schema inputs still generating one byte-identical tree of the same
  files; the `docs/queries.md` reference service entry; `mvn spotless:check`;
  and `mvn test`.
  Review evidence: the query catalogue gains `MoveTasks` directly after
  `MoveTask` and `RecordAuditEntries` directly after `RecordAudit`, each with
  its single-call form's SQL unchanged, and no file under `src/main` changes.
  `shouldExecuteTheGeneratedBatchWrites` generates from the migration history,
  compiles, and, inside its own organization, user, project, and two open
  tasks, proves `recordAuditEntries` reporting `1, 1` and its two 2026 entries
  read back through `ListAudit2026`, filtered to its own request ids, in row
  order with their actions, the user's id and a `null` actor, each `JSONB`
  detail, and each `uuid` request id, and `moveTasks` reporting `1, 0, 1` with
  both tasks then listed as `doing` at version 2 and none as `blocked`. The
  existing audit assertion is narrowed to its own request id and still
  requires exactly one entry; `shouldGenerateIdenticalRepositoriesFromHistorySnapshotAndDump`
  passes with `GENERATED_FILES` unchanged, and `docs/queries.md` lists the new
  test. Reviewed with `mvn spotless:check` (pass),
  `mvn -o -Dsqlcj.test.requireDocker=true test` (2005 tests: 101 runtime and
  1904 CLI, 0 failures, 0 errors, 0 skipped; `ReferenceServiceIntegrationTest`
  16), and `ReferenceServiceIntegrationTest` twice more in random method order
  (16 tests, 0 failures each).

#### Required

1. The `:batchexec` annotation, on an `INSERT`, `UPDATE`, or `DELETE` without
   `RETURNING` that `:exec` accepts, generates a method taking a `List` of a
   nested `<QueryName>Params` record, whose components are the query's
   parameters in logical order, and executes its rows as one JDBC batch. A
   `:batchexec` write with `RETURNING` is rejected with a diagnostic that says
   a batch returns no rows.
2. The `QueryExecutor` contract gains one batch operation. `JdbcQueryExecutor`
   binds each row as a single execution does and runs the batch on one
   connection. Over a `DataSource` whose connection is in auto-commit mode the
   batch is one transaction, all or nothing; a connection the `DataSource`
   hands out inside a transaction, as a `TransactionAwareDataSourceProxy` does
   inside a Spring-managed one, takes part in that transaction, and the batch
   never commits or rolls it back. Over a caller-owned connection it never
   commits, rolls back, or changes auto-commit. An empty list executes
   nothing.
3. The method reports the affected-row counts the driver reports and never
   invents one; the documentation states what the PostgreSQL driver reports
   when it rewrites batched inserts, and that a failed batch on a caller-owned
   connection in auto-commit mode may leave rows written.
4. The reference service's query catalogue gains batch writes that run
   against PostgreSQL.

#### Out of scope

- batches that return rows, `:batchone`, `:batchmany`, `COPY`, `:copyfrom`,
  generated keys, and streaming,
- splitting a batch into chunks, continuing past a failing row, and `long`
  counts,
- a generated parameter record for any other annotation,
- making a failed batch on a caller-owned connection in auto-commit mode all
  or nothing.

#### Acceptance

- Analyzer, generator, and runtime tests; generated-source compilation; a
  PostgreSQL test that inserts, updates, and deletes thousands of rows in one
  batch each, one in which a failing row leaves no row of a `DataSource` batch
  written, and one in which a batch inside a Spring-managed transaction rolls
  back with it.
- The reference service's batch writes run against PostgreSQL from its query
  catalogue.
- `mvn spotless:check` and `mvn test` pass.

### Milestone 32 — Criteria Queries

#### Goal

Make the criteria idiom of D-M first-class: at most one cast per parameter,
documented with its planning behavior, and verified on the reference service.

#### Work items

Activated on 2026-10-24 at the owner's request, after Milestone 31 was
completed. Reassessed against the working tree at `3daafd7`:
`SqlParameterCompiler`, `SqlParameters`, `PlaceholderOccurrence`,
`QueryAnalyzer`'s placeholder resolution and its null-test, `LIKE`, and
projection rules, `QueryModel`, `JavaCodeGenerator`'s argument wrapping,
`JdbcQueryExecutor`'s binding, their tests, `PostgresIntegrationTest`, the
reference service, and `docs/queries.md` and `docs/postgresql.md`; pgjdbc
42.7.13, the version the POM pins, probed outside the repository against
PostgreSQL 16.15 in `postgres:16-alpine`, the image the tests use. Nothing was
fetched from sqlc. Every requirement is unimplemented, as assumed. Facts that
correct or shape the plan:

- Every occurrence of a named placeholder, and of a repeated `$N`, already
  compiles to a `?` of its own, and the analyzer types each occurrence on its
  own: by the clause that analyzes it, by the cast it is the direct operand
  of, or not at all, in which case the first such occurrence is rejected as
  `Cannot infer the type of ...` even when another occurrence of the same
  parameter is typed. One parameter per index is then kept, and conflicting
  types are rejected. So `(:q::text IS NULL OR name ILIKE '%' || :q || '%')`
  is rejected at its second occurrence, while
  `(:name::text IS NULL OR name = :name)` compiles because a clause types its
  second occurrence.
- PostgreSQL therefore never receives a repeated parameter from sqlcj; it
  types each `?` on its own. Its own rule for a repeated `$1` would not help
  either: the first occurrence that types it fixes the type, so
  `$1 IS NULL OR project_id = $1` fails with
  `could not determine data type of parameter $1`.
- pgjdbc sends a non-null argument with the type of its Java class, such as
  `varchar` for a `String` and `int8` for a `Long`, but a `null` argument
  bound through `setObject`, a `null` array, and every `UntypedText` argument,
  which carries an enum, `JSON`, `JSONB`, `citext`, network, or `interval`
  value, without a type. PostgreSQL types such a value from its context in a
  concatenation, a comparison, `lower`, `coalesce`, `@>`, a `CASE`, and
  `ORDER BY`, but rejects it as the operand of `IS NULL` and `IS NOT NULL`
  (`could not determine data type of parameter $1`) and as the argument of an
  overloaded function such as `date_trunc('day', ?)`
  (`function date_trunc(unknown, unknown) is not unique`), where a non-null
  `String`, `Long`, or `OffsetDateTime` succeeds. An occurrence typed only by
  another occurrence and sent as a bare `?` would therefore fail only for a
  `null` argument, the argument a criteria query exists for, in contexts
  sqlcj cannot recognize without PostgreSQL's function catalogue.
- Written with its type, as `?::varchar`, `?::text`, an enum's `?::<name>`,
  or `?::int8[]`, every such occurrence ran with a `null` argument and with a
  value, `IS NULL` and `date_trunc` included.
- Planning: pgjdbc prepares a statement on the server at its fifth execution
  on one connection (`prepareThreshold`, default 5), counting executions of
  the same SQL across `PreparedStatement` objects, so a pooled connection
  reaches it and a connection per operation never does; `prepareThreshold=0`
  kept every execution unnamed. PostgreSQL plans the first five executions of
  a server-prepared statement with their values, as custom plans, and then
  uses a generic plan when it estimates that plan no costlier. The generic
  plan of `($1::bigint IS NULL OR project_id = $2)` cannot drop a switched-off
  criterion, so `EXPLAIN (GENERIC_PLAN)` shows a sequential scan where the
  custom plan uses the `project_id` index. Over 500,000 rows PostgreSQL kept
  custom plans for every execution, with and without criteria, because the
  generic plan costs more; over 50 rows it switched to the generic plan after
  five custom plans, and that plan still scanned sequentially once the table
  had grown to 500,000 rows. `plan_cache_mode = force_custom_plan`, set with
  pgjdbc's `options=-c plan_cache_mode=force_custom_plan` connection property
  or with `SET`, kept every execution on a custom plan.
- The reference service has no criteria search; `ListTasksPage` already sorts
  with `CASE` over a cast placeholder and pages with `LIMIT` and `OFFSET`.
  `docs/queries.md` documents the optional filter and the computed pattern,
  each with a cast, and states that an uncast computed `LIKE` pattern is
  rejected.

Decided by the architect on 2026-10-24, under the rule that pre-release
decisions are revisable by evidence:

- **Typing from another occurrence (amends requirement 1).** An occurrence
  that neither a clause nor a cast types takes the parameter of the first
  occurrence of the same logical parameter, in textual order, that one does,
  wherever both stand, and sqlcj writes that parameter's PostgreSQL type at
  it, `?::<type>` in place of `?`, so PostgreSQL receives the statement the
  user would have written with that cast. Clause- and cast-typed occurrences
  stay `?`, so every query accepted today generates the same SQL. The
  requirement's diagnostic for an occurrence PostgreSQL could not type is
  dropped: with the type written there is none, and the probe shows that
  sqlcj could not recognize one exactly. `(:email IS NULL OR email = :email)`
  therefore compiles as well. A parameter that no occurrence types keeps the
  existing diagnostic at its first occurrence, and a projected placeholder
  stays rejected, because a projection states a result column with a cast and
  an alias. The rule is by logical parameter, so a repeated `$N` follows it as
  a repeated name does. The written type is the name the runtime already gives
  an array element for that type, `bpchar` for a blank-padded type included,
  or `bytea`, `json`, or `jsonb`; an enum's declared name; and `[]` after the
  element of an array. D-M's rule that the executable SQL differs from the
  written SQL only at its placeholders still holds.
- **Planning documentation (refines requirement 2).** The documentation states
  the planning facts above as PostgreSQL 16 and pgjdbc behavior, not as a
  sqlcj guarantee, and names the remedies: `plan_cache_mode =
  force_custom_plan` for the application's connections, role, or database, or
  `prepareThreshold=0`. sqlcj sets no driver or session option.
- **Implementation order:** W1, W2, W3. W2 needs W1's typing; W3 documents
  W1's rule with W2's search as its example.

- **M32-W1 — One typed occurrence types the others — Completed.** Implements
  requirement 1 by the typing decision. An occurrence that no clause or cast
  types takes the parameter of its parameter's first typed occurrence and is
  written `?::<type>` in the analyzed executable SQL; a parameter no
  occurrence types keeps its diagnostic. Acceptance evidence is analyzer tests
  of the computed search pattern, of an uncast null test before and after the
  occurrence that types it, of a repeated `$N` named after its typing column,
  of enum and list parameters, of a parameter typed by a clause and by a cast,
  and of an occurrence in a nested query typed from the outer one, each with
  its executable SQL; of the unchanged SQL of a query whose occurrences are all
  typed; and of the kept diagnostic for a parameter no occurrence types, of a
  projected placeholder, and of conflicting types; a PostgreSQL test running
  generated queries with such occurrences with a `null` argument and with a
  value; `docs/queries.md`; `mvn spotless:check`; and `mvn test`.
  Review evidence: `QueryAnalyzer.resolveOccurrences` types clause- and
  cast-typed occurrences first, keeps the first typed parameter per logical
  index, fills every other occurrence with that parameter, and rejects the
  first occurrence of a parameter no occurrence types; `toExecutableSql`
  writes `::<type>` only after the `?` offsets `SqlParameters.offsets`
  reports for those occurrences, so a fully typed query keeps its compiled
  SQL. Analyzer tests assert parameters, binding indexes, and executable SQL
  for `'%' || ?::text || '%'`, `?::varchar IS NULL` before and after its
  typing occurrence, a repeated `$1` named `email`, `?::stage_setting`,
  `?::int8[]`, a cast- and a clause-typed parameter together, a nested
  `EXISTS` occurrence, and an unchanged fully typed query; the untyped and
  projected rejections are asserted and the existing conflicting-type tests
  still pass. `SqlParameterCompilerTest` checks offsets past a `'$1'`
  literal. `shouldExecuteGeneratedCriteriaFiltersTypedByAnotherOccurrenceAgainstPostgres`
  runs the search pattern, the uncast null test, and an `= ANY` list
  criterion with `null` and with a value. `docs/queries.md` states the rule,
  the written type names, and the qualified rejections, which CLI probes of
  `ORDER BY`, a parenthesized `LIMIT`, a subscripted `SET`, a `COALESCE`
  value, and a subscripted comparison confirmed. `mvn spotless:check` passed
  and `mvn -Dsqlcj.test.requireDocker=true test` passed, with 101 runtime
  and 1917 CLI tests and no failures.
- **M32-W2 — The reference service's criteria search — Completed.**
  Implements requirement 3. The query catalogue gains a criteria search over a
  project's tasks with several optional criteria, at most one cast per
  parameter, sorted and paged, and its matching count query. Acceptance
  evidence is a reference service test running both against PostgreSQL with no
  criterion, with each alone, and with several combined; the three schema
  inputs still generating one byte-identical tree; the `docs/queries.md`
  reference service entry; `mvn spotless:check`; and `mvn test`.
  Decided by the architect on 2026-10-24, rechecked against `40de580`: the
  search is `SearchTasks :many`, a `SELECT *` of `tasks` scoped to a required
  project and guarded by one criterion of each kind requirement 2 names — a
  title search (`ILIKE` over a computed pattern, the one cast), a status list
  (`= ANY`), an assignee equality, and an upper priority bound, chosen over a
  `created_at` range because the catalogue's `CreateTask` cannot set that
  column, so a test's bound would depend on insertion timing; those three
  carry no cast, their column typing them. It sorts as `ListTasksPage` does,
  by `CASE` over a `text` sort key cast once, with a `null` key keeping `id`
  order, and pages with uncast `LIMIT` and `OFFSET`. The count is
  `CountSearchedTasks :one`, an aliased `COUNT(*)` over the same `WHERE` text,
  unsorted and unpaged, so both methods take the criteria in one order. The
  search reads the shared `TasksRow` and the count a nested result record, so
  the generated file set is unchanged. `ListTasksPage` keeps its cast page.
  Review evidence: the catalogue gains `SearchTasks` and `CountSearchedTasks`
  after an unchanged `ListTasksPage`, with one cast on `search` and `sort` and
  none elsewhere; the CLI generated, outside the repository, the same nine
  files, `searchTasks(Long projectId, String search, List<TaskStatus>
  statuses, Long assigneeId, Short maxPriority, String sort, Integer pageSize,
  Integer pageOffset)` returning `List<TasksRow>` and `countSearchedTasks`
  taking the first five in that order and returning
  `CountSearchedTasksResult(Long total)`, with `?::text`,
  `?::task_status[]`, `?::int8`, and `?::int2` written at the uncast
  occurrences and `LIMIT ? OFFSET ?` left bare.
  `shouldExecuteTheGeneratedCriteriaSearch` runs, in its own organization and
  project, no criterion in `id` order, each of the four criteria alone, two
  combined sets, both sort keys, and one page, asserting the count equals each
  unpaged search and stays 4 while the search is paged; the diff only adds
  lines, so no existing test changed, and
  `shouldGenerateIdenticalRepositoriesFromHistorySnapshotAndDump` passes
  unchanged. `docs/queries.md` lists the test. `mvn spotless:check` passed and
  `mvn -Dsqlcj.test.requireDocker=true test` passed, with 101 runtime and 1918
  CLI tests and no failures.
- **M32-W3 — Criteria query documentation — Completed.** Implements
  requirement 2 by the planning decision. `docs/queries.md` gains a section on
  criteria queries: optional equality, list, range, and search criteria,
  dynamic sorting, paging with a matching count query, and how PostgreSQL plans
  such a query once pgjdbc prepares it on the server, with its remedies.
  Acceptance evidence is the section, its examples matching the queries W1's
  and W2's tests run, its verification list, `mvn spotless:check`, and
  `mvn test`.
  Decided by the architect on 2026-10-24, rechecked against `5c74867`: the
  section is a top-level `Criteria Queries` placed after `Parameters` and
  before `Generated Java`, because it composes the predicate, ordering,
  paging, and one-cast-per-parameter rules stated above it and links to them
  rather than restating them. Its worked example is the reference service's
  `SearchTasks` and `CountSearchedTasks` as the catalogue writes them, with
  the `tasks` column types they depend on and their generated signatures; its
  range criterion is the upper priority bound, the range W2 chose and its test
  runs, and a two-ended range is described in prose as one guarded bound per
  end, without an untested example. The single-criterion forms it names are
  those W1's PostgreSQL test runs. The planning subsection states the
  milestone's planning facts as PostgreSQL 16 and pgjdbc 42.7.13 behavior that
  no sqlcj test asserts, with `plan_cache_mode = force_custom_plan` and
  `prepareThreshold=0` as the remedies. `Verified by` gains a `Criteria
  queries` group naming the tests that run the section's examples. The
  existing `Predicates`, `Ordering`, and `Pagination` examples, the README,
  `docs/quickstart.md`, and `docs/postgresql.md` are unchanged.
  Review evidence: `docs/queries.md` is the only changed file, gaining a
  top-level `Criteria Queries` between `Parameters` and `Generated Java` and a
  `Criteria queries` verification group between the `Parameters` and
  `Generated Java` groups, 136 added lines and no deletion. The section states
  the static-SQL rule, the equality, list, range, and search guard forms, the
  two-ended range in prose alone, the once-cast sort key, the `LIMIT`/`OFFSET`
  page, and the count that repeats the `WHERE` text, linking to `Ordering`,
  `Pagination`, and `Parameters` instead of restating them. Its
  worked example is byte-identical to `queries.sql` lines 106-126, which `diff`
  confirmed, and the guard forms it names are the ones W1's tests run. The
  `tasks` types, the signatures `searchTasks(Long projectId, String search,
  List<TaskStatus> statuses, Long assigneeId, Short maxPriority, String sort,
  Integer pageSize, Integer pageOffset)` returning `List<TasksRow>` and
  `countSearchedTasks` taking the first five and returning
  `CountSearchedTasksResult(Long total)`, and the executed `?::text`,
  `?::task_status[]`, `?::int8`, `?::int2`, and bare `LIMIT ? OFFSET ?` were
  reconfirmed by running the CLI on the reference service fixtures into a
  directory outside the repository. `Query planning` carries the PostgreSQL 16
  and pgjdbc 42.7.13 attribution, the `prepareThreshold` default of 5 counted
  across `PreparedStatement` objects, the five custom plans before a generic
  one, the generic plan of `(?::int8 IS NULL OR assignee_id = ?)` and
  `EXPLAIN (GENERIC_PLAN)`, the 500,000-row and 50-row observations, and both
  remedies, and states that sqlcj sets no driver or session option; the
  verification group states that no sqlcj test asserts a query plan. Every
  cited test name exists. `mvn spotless:check` passed and
  `mvn -Dsqlcj.test.requireDocker=true test` passed, with 1918 CLI tests and no
  failure in either module.
  Accepted on review: the worked example matches `queries.sql` lines 106-126
  byte for byte; a CLI run of the reference service fixtures outside the
  repository generated the stated signatures, `CountSearchedTasksResult(Long
  total)`, and the stated `?::<type>` occurrences with a bare
  `LIMIT ? OFFSET ?`; the four analyzer tests assert exactly the executable
  SQL the verification group quotes; the PostgreSQL test runs the search,
  equality, and `= ANY` list criteria with `null` and with a value; every
  cited test and link anchor exists; no test reads `docs/queries.md`.
  `mvn -Dsqlcj.test.requireDocker=true test`, whose `validate` phase runs
  `spotless:check`, passed with 101 runtime and 1918 CLI tests and no failure,
  against PostgreSQL 16.15.

#### Required

1. A parameter that one occurrence types, by a clause or by a cast, needs no
   cast at its other occurrences: sqlcj writes that type at each of them in the
   executable SQL, so `(:q::text IS NULL OR name ILIKE '%' || :q || '%')` and
   `(:email IS NULL OR email = :email)` compile as written and run with a
   `null` argument. A parameter that no occurrence types keeps its diagnostic.
2. The query documentation presents criteria queries: optional equality, list,
   range, and search criteria, dynamic sorting, paging with a matching count
   query, and how PostgreSQL plans such a query once the driver prepares it on
   the server, with the `plan_cache_mode` setting that keeps it planned with its
   values.
3. The reference service gains a criteria search over tasks with several
   optional criteria, run against PostgreSQL with no criterion, with each alone,
   and with several combined.

#### Out of scope

- generated parameter objects, optional-clause markers, and runtime-built SQL,
- typing a placeholder that no occurrence types from the expression around it,
  and a projected placeholder typed by another occurrence,
- any driver or session setting applied by sqlcj.

#### Acceptance

- Analyzer tests of occurrences typed by another occurrence, with their
  executable SQL, and of parameters kept rejected; a PostgreSQL test running
  such occurrences with a `null` argument and with a value; the reference
  criteria search against PostgreSQL.
- `mvn spotless:check` and `mvn test` pass.
