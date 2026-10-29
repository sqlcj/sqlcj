# PostgreSQL Support

sqlcj compiles PostgreSQL schema snapshots and queries into Java that runs on
JDBC. This document states the database contract of configuration version `"1"`
and lists the SQL types and `CREATE TABLE` constructs that a schema source may
use.

## Engine Contract

Configuration version `"1"` means PostgreSQL schema and query input and
blocking JDBC execution. There is no engine option and no dialect option in
`sqlcj.yaml`; the full file format is documented in
[Configuration](configuration.md), and the accepted query shapes in
[Queries](queries.md).

The application owns the database connection: sqlcj's runtime
`org.sqlcj.runtime.JdbcQueryExecutor` executes each generated query as a JDBC
`PreparedStatement`. sqlcj does not ship a JDBC driver, so the application also
supplies the PostgreSQL driver.

## Connection Ownership and Transactions

`JdbcQueryExecutor` has two construction paths. Both share the same positional
parameter binding, row mapping, cardinality enforcement, affected-row counting,
and exception translation, and both accept the same generated repositories
without regeneration:

| Construction | Connection ownership |
| --- | --- |
| `new JdbcQueryExecutor(javax.sql.DataSource)` | The executor obtains one connection per operation and closes it before the operation returns, so each operation runs on that connection's own transaction state, typically one auto-committed statement. |
| `new JdbcQueryExecutor(java.sql.Connection)` | The executor runs every operation on the supplied connection and never closes, commits, or rolls it back, never changes its auto-commit setting, and never otherwise configures it. |

The caller-owned connection path is how several generated operations take part
in one application-controlled transaction: the application disables auto-commit,
constructs another repository instance over an executor bound to that
connection, runs generated reads and writes through it, and then calls `commit`
or `rollback` itself. The runtime's transaction helper runs that pattern for the
application, and an application that needs the connection in its own hands runs
it directly. The [Quickstart](quickstart.md) runs the helper end to end.

`org.sqlcj.runtime.JdbcTransactions` is constructed from a `DataSource` and runs
one unit of work on one connection inside one transaction. `inTransaction`
returns the callback's value, and `useTransaction` takes a callback that returns
none:

```java
JdbcTransactions transactions = new JdbcTransactions(dataSource);

Long id = transactions.inTransaction(connection -> {
    AuthorRepository authors = new AuthorRepository(new JdbcQueryExecutor(connection));

    AuthorsRow created = authors.createAuthor("Ada Lovelace", "First programmer");

    try (
        PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO author_audit (author_id, action) VALUES (?, ?)"
        )
    ) {
        statement.setLong(1, created.id());
        statement.setString(2, "created");
        statement.executeUpdate();
    }

    return created.id();
});
```

The callback receives the transaction's connection, binds the generated
repositories it needs over `new JdbcQueryExecutor(connection)`, and may run
hand-written JDBC on the same connection, so every operation takes part in the
same transaction. It never commits, rolls back, closes, or reconfigures the
connection itself. A callback that throws a checked exception has it rethrown as
declared, and a callback that throws none needs neither a `catch` nor a `throws`
clause.

Each call manages the transaction by these rules:

- It obtains one connection from the `DataSource` and disables auto-commit on
  it, unless that connection already has auto-commit disabled, which it then
  leaves untouched.
- A normal return commits the transaction.
- Any exception or error the callback throws rolls the transaction back and is
  rethrown as the same instance, unchanged, including a
  `QueryCardinalityException` a generated operation raised.
- A failure to begin or to commit raises
  `org.sqlcj.runtime.TransactionException` with the `SQLException` as its cause.
  On a begin failure the callback does not run.
- A failure to roll back is attached to the callback's failure as a suppressed
  `TransactionException`.
- Auto-commit is restored to `true` only when the call disabled it, and only
  after a successful commit or rollback, because changing auto-commit during an
  open transaction commits that transaction.
- The connection is closed on every path, even when restoring auto-commit
  failed. A failure to restore auto-commit or to close raises
  `TransactionException` when no other failure is pending, and is attached to
  the pending failure as a suppressed `TransactionException` otherwise.

A `TransactionException` reports what the helper itself could not do, so its
message names no query:

```text
Failed to begin a transaction
Failed to commit the transaction
Failed to roll back the transaction
Failed to release the transaction's connection
```

Each call uses its own connection, so a call made inside another call's callback
is an independent transaction: it commits or rolls back on its own, and the
outcome of the surrounding call does not change it.

The helper covers one transaction over one connection. Anything else a
transaction may need, such as a `setTransactionIsolation` level, a savepoint, or
a `SET LOCAL` statement, is applied by the application on a connection it
obtains and owns itself, with repositories bound over
`new JdbcQueryExecutor(connection)` and with `commit` or `rollback` called by
the application, as the table above describes. sqlcj has no API of its own for
any of them.

In both paths the `PreparedStatement` and any `ResultSet` opened for an
operation are closed before that operation returns, on success, on a cardinality
failure, and on any other failure.

Every generated method passes the generated repository's class name and its
query name to the executor, so every runtime failure names the query the
application called.

Every `SQLException` raised while acquiring a connection, preparing a statement,
binding parameters, executing, reading results, or closing a DataSource-acquired
connection is translated into `org.sqlcj.runtime.QueryExecutionException` with
the message `Failed to execute query '<query>' in <repository>` and the
`SQLException` as its cause:

```text
Failed to execute query 'GetAuthor' in AuthorRepository
```

A row count an annotation does not allow raises
`org.sqlcj.runtime.QueryCardinalityException`, a subclass of
`QueryExecutionException` that carries only a message:

```text
Query 'GetAuthor' in AuthorRepository returned no row; expected exactly one
Query 'GetAuthor' in AuthorRepository returned more than one row; expected exactly one
Query 'FindAuthor' in AuthorRepository returned more than one row; expected at most one
```

A cardinality check runs after the statement has executed, so a returning write
that fails it has already changed the database.

A failed operation on a caller-owned connection leaves the connection open, so
the application decides whether to continue or roll back.

`QueryExecutor.executeBatch`, which the method of a
[`:batchexec`](queries.md#batch-writes) query calls, executes one statement
once per argument list, in the order of the lists, as one JDBC batch on one
connection. Each list is bound exactly as the arguments of a single execution
are bound, and the counts the driver reported are returned in that order,
unchanged. An empty batch executes nothing, obtains no connection, and returns
an empty array. Its transaction handling follows the connection it runs on:

- On a connection obtained from a `DataSource` in auto-commit mode the batch is
  one transaction, all or nothing: auto-commit is disabled for the batch, a
  successful batch is committed, a failing one is rolled back, and auto-commit
  is restored only after a successful commit or rollback.
- A connection the `DataSource` hands out already in a transaction, as a
  `TransactionAwareDataSourceProxy` does inside a Spring-managed transaction,
  takes part in that transaction: the batch is never committed, rolled back, or
  reconfigured, and that transaction decides its outcome.
- On a caller-owned connection the batch never commits, rolls back, or changes
  auto-commit, as no other operation does either. With the PostgreSQL driver a
  failed batch on a caller-owned connection in auto-commit mode is not atomic
  and may leave the rows written before the failing one committed, so a batch
  on a caller-owned connection is run with auto-commit disabled or through
  `JdbcTransactions`.

Every `SQLException` a batch raises, a `java.sql.BatchUpdateException`
included, is translated into the same `QueryExecutionException` as every other
operation's, and a failure to roll back, to restore auto-commit, or to close
the obtained connection that follows it is attached to that failure as a
suppressed `QueryExecutionException`. An unchecked failure of a batch on a
connection obtained from a `DataSource` is rethrown unchanged, as the same
instance, after the batch is rolled back, when auto-commit was disabled for it,
and the connection is closed, with a rollback, restore, or close failure
attached to it as a suppressed `QueryExecutionException`.

The counts a batch returns are the driver's own. The PostgreSQL driver reports
the exact affected-row count of each statement of the batch, `0` for a
statement that affected no row included. With `reWriteBatchedInserts=true` it
rewrites a group of batched inserts into one multi-row insert and then reports
`java.sql.Statement.SUCCESS_NO_INFO`, which is `-2`, for every row of that
group, while a row left outside the rewritten group reports its real count: a
three-row insert batch reports `-2`, `-2`, `1`. Batched updates and deletes are
never rewritten and report exact counts.

The runtime is blocking and synchronous. An executor built on a caller-owned
connection inherits that connection's confinement to a single thread at a time.

A framework that manages transactions itself owns the connection, so a
generated repository takes part in such a transaction by executing on the
connection the framework bound to it. In Spring that connection is reached
through `org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy`,
which wraps the application's `DataSource`, so the repository is built over an
executor on that proxy:

```java
@Bean
AuthorRepository authorRepository(DataSource dataSource) {
    return new AuthorRepository(
        new JdbcQueryExecutor(new TransactionAwareDataSourceProxy(dataSource))
    );
}
```

The proxy wraps only the `DataSource` handed to `JdbcQueryExecutor`. The
transaction manager keeps the plain `DataSource`: a
`DataSourceTransactionManager` is built on it, and with JPA the
`EntityManagerFactory` is built on that same `DataSource` and the
`JpaTransactionManager` on that factory. Inside a transaction of either
manager, the operations of a repository bound to the proxy run on the
transaction's connection, so its writes are committed or rolled back with that
transaction, a JPA write in the same transaction included.

Two consequences follow from the connection the proxy returns:

- A repository built over the plain `DataSource` obtains a connection of its
  own, so it runs outside the surrounding transaction: its write is committed
  at once and survives a rollback of that transaction.
- A pending JPA change is visible to a sqlcj read in the same transaction only
  after `EntityManager.flush()`, because that read reaches the database and not
  the persistence context. A `persist` of an entity with an assigned key, for
  instance, is written at the flush, after which the same read returns the row.

`JdbcTransactions` is not used inside a framework's transaction: it obtains a
connection of its own, so its work would be a second, independent transaction.
Inside a Spring-managed transaction the transaction belongs to Spring, and the
repository takes part through the proxy alone.

Spring and a JPA provider are used only by sqlcj's own tests, which verify the
behavior above; neither the runtime nor the generated code depends on either.

Behavior is verified against PostgreSQL 16. The pipeline is executed end to end
against a `postgres:16-alpine` container: the schema snapshot is run as
PostgreSQL DDL, the generated Java is compiled, and the generated repository is
executed through the JDBC runtime. Those tests are skipped when Docker is
unavailable.

H2 is used only inside sqlcj's own tests as a convenience database and is not a
supported target. The PostgreSQL driver, the container library, H2, Spring
Framework, and Hibernate ORM are all test-scoped dependencies of the build.

## Schema Snapshot Input

`sql[].schema` is an explicit snapshot of the tables a query may use. It is one
file, a list of files, or a directory of `.sql` migration files; see
[`sql[].schema`](configuration.md#sqlschema) for the accepted forms and the
order the files are read in.

- One rule decides every statement: only the `CREATE TABLE`, `DROP TABLE`, and
  `ALTER TABLE` forms listed in [Ordered Table DDL](#ordered-table-ddl), the
  `CREATE TYPE ... AS ENUM` and `ALTER TYPE ... ADD VALUE`, `RENAME TO`, and
  `RENAME VALUE` forms listed in [Enum Types](#enum-types), and the
  `CREATE DOMAIN`, `ALTER DOMAIN ... RENAME TO`, and `DROP DOMAIN` forms listed
  in [Domain Types](#domain-types) update the schema
  model, and every other statement a schema file records is accepted and
  leaves it unchanged. Only the few
  statements listed in [Ignored Statements](#ignored-statements) are rejected
  instead, because sqlcj cannot tell what they would do to the model.
- The statements of the schema files of one entry are applied in order to one
  schema model, so each of them sees the tables and columns the statements and
  files before it left.
- A file may contain several statements, and a table keeps the position of the
  statement that created it.
- SQL identifier delimiters are removed for the parsed model, so the table
  `"user data"` is modeled as `user data` and the column `"user id"` is modeled
  as `user id`.
- sqlcj models one namespace, so an unqualified and a `public`-qualified name
  are the same name: `users`, `public.users`, and `"public"."users"` are one
  table, and `mood`, `public.mood`, and `"public"."mood"` are one type. A schema
  qualifier is compared without its SQL identifier delimiters and
  case-insensitively, as every other name is.
- A table or a type qualified with any other schema is invisible: sqlcj never
  models it, every statement whose own table or type stands there is ignored
  before anything it names is resolved, and a query that names such a table is
  rejected as
  `<schema>.<t> belongs to schema <schema>, which sqlcj does not model; sqlcj
  models only the public schema.`, whether or not the snapshot creates it.
  `search_path` is not followed, so an
  unqualified name always belongs to the one namespace sqlcj models, whatever a
  `SET search_path` statement in the snapshot says.

A `pg_dump --schema-only` file is such a snapshot and loads unchanged: it is
configured as one schema file, and nothing in it has to be edited, removed, or
reordered first. This is the schema input of a service that does not keep its
schema as SQL migrations, for example one with Java-based migrations or a
JPA-generated schema; see
[Migration Tool Schemas](#migration-tool-schemas).

A dump states much more than the tables it creates, and sqlcj ignores all of it
rather than rejecting it:

- the `\restrict` and `\unrestrict` meta-command lines recent releases write,
  each with a fresh key, and every other psql meta-command line,
- the `SET` preamble and the `SELECT pg_catalog.set_config(...)` call, including
  `SET search_path`,
- the identity form it writes for every identity column,
  `ALTER TABLE ... ALTER COLUMN ... ADD GENERATED ... AS IDENTITY (SEQUENCE NAME ...)`,
  which sqlcj's parser cannot read but which cannot change a column either, and
  its sequences and `SET DEFAULT nextval(...)` statements,
- its constraint and index statements, its `OWNER TO` statements, and its
  grants, policies, comments, extensions, composite types, functions,
  triggers, views, and materialized views, and
- the tables, types, and statements of every schema other than `public`.

A dump's `CREATE DOMAIN` statements are modeled rather than ignored, including
the forms sqlcj's parser cannot read: a domain with a `CONSTRAINT ... CHECK`
over an operator and one with a `COLLATE` and a `NOT NULL DEFAULT`; see
[Domain Types](#domain-types).

A dump writes a partition as a plain `CREATE TABLE` followed by
`ALTER TABLE ONLY ... ATTACH PARTITION`, so the partition is modeled with its
own columns and then linked to its partitioned table, and it qualifies every
table, type, and column type with `public.`, which is the one namespace sqlcj
models. A dump of a database built by a migration history that changes the
schema only through statements sqlcj reads therefore generates the same Java as
that history. A schema change the history makes indirectly, such as DDL inside
a `DO` block or a function body, or a column dropped by
`DROP TYPE ... CASCADE`, stands in the database and in its dump but not in the
history sqlcj reads; see [Ignored Statements](#ignored-statements).

## Migration Tool Schemas

sqlcj reads the SQL a migration tool applies and never runs that tool, connects
to a database, or parses a tool's own change format.

### Flyway

Configure the migration directory. sqlcj reads a directory in Flyway's own
version order, which [`sql[].schema`](configuration.md#sqlschema) states.

### Liquibase Formatted SQL

A formatted SQL changelog is read as the SQL it spells, because Liquibase's
directives are written as SQL comments:

- `--liquibase formatted sql`, `--changeset`, `--comment`, `--rollback`, and a
  `/* liquibase rollback */` block are comments, so no rollback is ever applied.
- Every other directive is a comment too, so every changeset of a listed file is
  applied.
- A root changelog's `--include` and `--includeAll` directives are comments as
  well, so they include nothing: list the included files themselves, or a
  directory of them.
- A listed directory is read non-recursively, in the order
  [`sql[].schema`](configuration.md#sqlschema) states. That is `includeAll`'s own
  order within one directory only when no file name has Flyway's `V<version>__`
  or `U<version>__` form, and `includeAll` also descends into subdirectories, so
  a changelog in a subdirectory has to be listed itself.
- A statement ends only at `;`. A statement written without its `;`, and one
  ended by an `endDelimiter` such as `/`, merges into the statement that follows
  it.
- A dollar-quoted body stays one statement, so a `$body$ ... $body$` function
  body holding `;` is read as the single statement it is.

### Liquibase XML, YAML, or JSON

sqlcj does not read a changelog written as XML, YAML, or JSON. Let Liquibase
write the SQL of that changelog instead, with its offline `update-sql` command,
and configure the written file as the schema:

```bash
docker run --rm \
  -v "$PWD/src/main/resources/db/changelog":/liquibase/changelog:ro \
  -w /liquibase/changelog \
  -e LIQUIBASE_COMMAND_CHANGELOG_FILE=changelog.xml \
  -e 'LIQUIBASE_COMMAND_URL=offline:postgresql?outputLiquibaseSql=all&changeLogFile=/tmp/databasechangelog.csv' \
  liquibase/liquibase:5.0.4 update-sql > sql/liquibase-schema.sql
```

- The mounted directory holds the root changelog and the files it includes.
- The `offline:` URL needs no database.
- The SQL goes to standard output and Liquibase's banner to standard error, so
  the redirection writes the SQL alone.
- Liquibase records the changesets it has written in a changelog CSV, and a
  later run that reads the same CSV writes only the changesets newer than the
  ones it records. Keeping that CSV inside the discarded container makes every
  run write them all.
- `outputLiquibaseSql=all` adds the `databasechangelog` table and its `INSERT`
  statements, which generate nothing unless a query names that table.
- Configure the output as one schema file, and run the command again whenever
  the changelog changes.

### MyBatis Migrations

Configure the `scripts` directory, whose undo sections are never applied; see
[`sql[].schema`](configuration.md#sqlschema) for the undo-marker rule itself.

- The scripts are read in file-name order, which is MyBatis's own id order for
  its default 14-digit timestamp ids, and for any set of ids of one width.
- `bootstrap.sql` and `onabort.sql`, which MyBatis leaves out of the migration
  history, are read as ordinary `.sql` files of the directory, after the
  numbered scripts. The `bootstrap.sql` that `migrate init` writes holds only
  comments.
- The changelog script that `migrate init` writes loads unchanged and models
  nothing. Its `${changelog}` table is invisible, as a table of another schema
  is: a query that names it is rejected as an unknown table, and nothing else
  the schema models changes; see [Ignored Statements](#ignored-statements).
- A `${...}` variable is never substituted. Any other table statement that
  names one is rejected, naming the table or the token sqlcj's parser could not
  read, and a statement of any other kind, such as an `INSERT` or a
  `CREATE INDEX`, stays ignored like any unmodeled statement.

### Other Tools

For Java-based migrations, a tool of any other kind, or a hand-managed database,
snapshot a database the tool has migrated and configure that file:

```bash
pg_dump --schema-only -U <user> -d <database> > schema.sql
```

See [Schema Snapshot Input](#schema-snapshot-input) for what such a file states
and what sqlcj reads of it.

## Supported Column Types

Type spellings are matched case-insensitively, after parenthesized type
arguments are removed and remaining whitespace is collapsed to single spaces.
A declared length, precision, or scale is therefore accepted and ignored:
`VARCHAR(255)`, `DECIMAL(10, 2)`, and `timestamp(3) with time zone` are all
accepted and map exactly like their unparameterized spellings. `FLOAT(p)` is
the one exception, because PostgreSQL lets its precision select the type: a
precision of `1` through `24` is `REAL` and one of `25` through `53` is
`DOUBLE PRECISION`.

| Accepted SQL spellings | Java type | Notes |
| --- | --- | --- |
| `INTEGER`, `INT`, `INT4`, `SERIAL`, `SERIAL4` | `Integer` | A serial column is always modeled non-null. |
| `BIGINT`, `INT8`, `BIGSERIAL`, `SERIAL8` | `Long` | A serial column is always modeled non-null. |
| `SMALLINT`, `INT2`, `SMALLSERIAL`, `SERIAL2` | `Short` | A serial column is always modeled non-null. |
| `BOOLEAN`, `BOOL` | `Boolean` | |
| `VARCHAR`, `CHARACTER VARYING` | `String` | |
| `CHAR`, `CHARACTER` | `String` | PostgreSQL blank-pads a stored value to the declared length, so `ab` written into a `CHAR(3)` column reads back as `ab `. |
| `TEXT` | `String` | |
| `DATE` | `java.time.LocalDate` | |
| `TIME`, `TIME WITHOUT TIME ZONE` | `java.time.LocalTime` | |
| `TIMESTAMP`, `TIMESTAMP WITHOUT TIME ZONE` | `java.time.LocalDateTime` | |
| `TIMESTAMP WITH TIME ZONE`, `TIMESTAMPTZ` | `java.time.OffsetDateTime` | PostgreSQL normalizes the stored value to the session time zone, so a value read back equals the written value by instant rather than by offset. |
| `DECIMAL`, `NUMERIC` | `java.math.BigDecimal` | |
| `REAL`, `FLOAT4`, `FLOAT(1)` through `FLOAT(24)` | `Float` | |
| `DOUBLE PRECISION`, `FLOAT8`, `FLOAT`, `FLOAT(25)` through `FLOAT(53)` | `Double` | A `FLOAT` without a precision is `DOUBLE PRECISION`, as PostgreSQL defines it. Any other precision, such as `FLOAT(0)` and `FLOAT(54)`, is rejected by PostgreSQL and has no mapping; it is recorded with its own precision, as in `FLOAT(54)`, because `FLOAT` alone is mapped. |
| `UUID` | `java.util.UUID` | |
| `BYTEA` | `byte[]` | A record compares an array component by reference, so two row records holding equal bytes are not `equals`. |
| `JSON` | `String` | The JSON text itself. PostgreSQL stores it as written, so it reads back exactly as written. sqlcj never parses, validates, or normalizes it. |
| `JSONB` | `String` | The JSON text itself. PostgreSQL stores a decomposed value, so the text reads back as PostgreSQL renders it rather than as written, and `=` compares by value. sqlcj never parses, validates, or normalizes it. |
| `CITEXT` | `String` | The text itself. PostgreSQL compares, matches, and orders a `citext` ignoring case, so an equality, a `LIKE`, an `ILIKE`, and an `= ANY(citext[])` over such a column are case-insensitive. The type comes from the `citext` extension, which belongs to the schema it was installed in, so the name is resolved in the one namespace sqlcj models: `public.citext` and `"public"."citext"` are the type `citext`, and a name qualified with any other schema never resolves. |
| `INET`, `CIDR` | `String` | PostgreSQL's own text of the value, its netmask included, as in `192.168.0.10/24` and `10.0.0.0/8`. `java.net.InetAddress` holds no netmask, so the text is the exact value. |
| `MACADDR`, `MACADDR8` | `String` | PostgreSQL's own text of the address, as in `08:00:2b:01:02:03`. |
| `INTERVAL`, with any fields and any precision, such as `INTERVAL YEAR TO MONTH` and `INTERVAL(3)` | `org.sqlcj.runtime.SqlInterval` | A record of `int months`, `int days`, and `long microseconds`, which is PostgreSQL's own representation: none of the three converts into another without a calendar, because a month is 28 to 31 days and a day is 23, 24, or 25 hours across a daylight-saving change. The fields and the precision of the spelling are ignored, as PostgreSQL ignores them for the stored value. `SqlInterval.of(java.time.Period)` and `SqlInterval.of(java.time.Duration)` build one, and `toPeriod()` and `toDuration()` convert back, each throwing `java.time.DateTimeException` when the value holds a component the target cannot hold: a `Period` holds no time part and a `Duration` holds no month. `toDuration()` counts a day as 24 hours, as `Duration.ofDays` does and as PostgreSQL compares two intervals. |
| The name of an enum type the schema declares | The generated Java enum of that type | Matched without SQL identifier delimiters and case-insensitively, as PostgreSQL resolves an unquoted type name. A `public`-qualified name resolves as its unqualified name, so `public.mood` and `"public"."mood"` are the type `mood`, and a name qualified with any other schema never resolves. See [Enum Types](#enum-types). |
| The name of a domain the schema declares | The Java type of the type the domain is declared over | A domain is a name for its base type, so a column or a cast of it has that type's Java type, binding, and reading, a domain over a domain and a domain over an array type included. Matched without SQL identifier delimiters and case-insensitively, and a `public`-qualified name resolves as its unqualified name, as an enum type's name does. A built-in spelling resolves before a domain and a domain before an enum type. A domain over a type that is not listed above, and an array of a domain, such as `email_address[]`, have no mapping. See [Domain Types](#domain-types). |
| A one-dimensional array of any spelling above except `BYTEA`, `JSON`, and `JSONB`, written `type[]` or `type[n]` | `java.util.List<T>` of the element's Java type | The declared size is ignored, as PostgreSQL ignores it. See [Array Types](#array-types). |

Any spelling that is not listed above has no Java mapping. Such a column is
recorded with its declared type instead of
failing the schema, and fails only a query that uses it; see
[Unsupported Types and DDL](#unsupported-types-and-ddl).

The generated repository imports `java.time.LocalDate`, `java.time.LocalTime`,
`java.time.LocalDateTime`, `java.time.OffsetDateTime`, `java.math.BigDecimal`,
`java.util.UUID`, and `org.sqlcj.runtime.SqlInterval` as needed; the remaining
types need no import, and `java.util.List` is already imported by every
repository. A row record imports
`java.util.List` when one of its components is an array. A generated
enum belongs to the generated package, so it is used by its simple name and
needs no import either. Each result column is read at its one-based position in
the selected-column list, with `resultSet.getObject(position, JavaType.class)`,
or with `resultSet.getString(position)` for a `JSON`, `JSONB`, `CITEXT`,
`INET`, `CIDR`, `MACADDR`, or `MACADDR8` column, which the driver reports as a
type of its own rather than as a character type. An
enum column reads its label the same way and resolves it with
`<EnumType>.fromLabel(...)`, an `INTERVAL` column reads PostgreSQL's own text
the same way and parses it with
`org.sqlcj.runtime.SqlInterval.parse(resultSet.getString(position))`, and an
array column is read with `org.sqlcj.runtime.SqlArray.getList(...)`.

`SqlInterval.parse` reads the text PostgreSQL writes under its default
`IntervalStyle`, `postgres`, such as `1 year 2 mons -3 days +01:02:03.000004`,
and reads `null` as `null`, so a SQL `NULL` column is a `null` component. Text
of any other `IntervalStyle`, which the session can select, is rejected with
`IllegalArgumentException` naming the text and `IntervalStyle`, because such
text cannot be told apart from the `postgres` form: a leading `-` of
`sql_standard` applies to every unsigned field after it. sqlcj never changes the
session setting.

A `JSON`, `JSONB`, `CITEXT`, `INET`, `CIDR`, `MACADDR`, or `MACADDR8` argument
is passed to the executor as
`new org.sqlcj.runtime.UntypedText(value)`, written out in full so that the
generated imports are unchanged, and `JdbcQueryExecutor` binds that text with
`java.sql.Types.OTHER`. PostgreSQL then types the text from the context of its
placeholder, which is what a column or comparison of one of those types needs:
text bound as `varchar` is rejected for an `inet` and resolves a `citext`
comparison as a case-sensitive `text` one. An enum argument is passed the same way,
around the label of its constant, because a label bound as `varchar` is
rejected where an enum is expected. An array argument is wrapped in
`org.sqlcj.runtime.SqlArray` the same way; see [Array Types](#array-types).

An `INTERVAL` argument is passed to the executor as
`new org.sqlcj.runtime.UntypedText(value == null ? null : value.format())`.
`format()` writes the three counts with an explicit sign on every field, as in
`+14 mons -3 days +3723000004 microseconds`, so that PostgreSQL reads the text
alike under every `IntervalStyle` and the least microsecond count the record
holds is spellable, which the `HH:MM:SS` form cannot be.

## Enum Types

`CREATE TYPE <name> AS ENUM (...)` adds an enum type to the schema, and a
column whose declared type names it is modeled as a column of that type. Every
query that reads or binds such a column uses the one Java enum the
package generates for the type:

```sql
CREATE TYPE stage_setting AS ENUM ('indoor', 'outdoor');

ALTER TYPE stage_setting ADD VALUE 'covered' BEFORE 'outdoor';
```

```java
// Code generated by sqlcj. DO NOT EDIT.

package com.example.app.db;

/**
 * Generated by sqlcj.
 *
 * Enum: stage_setting
 */
public enum StageSetting {

    INDOOR("indoor"),
    COVERED("covered"),
    OUTDOOR("outdoor");

    private final String label;

    StageSetting(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static StageSetting fromLabel(String label) {
        if (label == null) {
            return null;
        }

        for (StageSetting value : values()) {
            if (value.label.equals(label)) {
                return value;
            }
        }

        throw new IllegalArgumentException("Unknown label for enum type stage_setting: " + label);
    }
}
```

- The constants keep their exact PostgreSQL labels and follow them in
  PostgreSQL's own sort order, which is the declared order with each added
  label in the position its `ALTER TYPE ... ADD VALUE` places it.
- A constant is named by the naming rules in
  [Generated Java Names](configuration.md#generated-java-names).
- `fromLabel` reads back a label: `null` for a SQL `NULL`, and a failure for a
  label the generated enum does not hold, which means the database declares one
  the schema source does not.
- Only an enum type a query actually uses generates a file, including an enum
  type only an array element names.
- A one-dimensional array of an enum type, such as `stage_setting[]`, is a
  `List` of that generated enum; see [Array Types](#array-types).

These statements update the enum types the statements and files before them
left:

| Statement | Handling |
| --- | --- |
| `CREATE TYPE ... AS ENUM (...)` | Adds the type with its declared labels. |
| `ALTER TYPE ... ADD VALUE '<label>'` | Appends the label after the last one. |
| `ALTER TYPE ... ADD VALUE '<label>' BEFORE '<neighbour>'` | Inserts the label directly before the neighbour. |
| `ALTER TYPE ... ADD VALUE '<label>' AFTER '<neighbour>'` | Inserts the label directly after the neighbour. |
| `ALTER TYPE ... ADD VALUE IF NOT EXISTS '<label>'` | Does nothing when the type already has the label. As PostgreSQL does, the existing label decides before the neighbour, so a neighbour the type does not have is not resolved at all. |
| `ALTER TYPE ... RENAME TO <name>` | Renames the type in its position, keeping its labels, and every column of that type, scalar and array alike, follows it. |
| `ALTER TYPE ... RENAME VALUE '<label>' TO '<new label>'` | Renames the label in its position, so the modeled labels keep PostgreSQL's sort order. |
| A `CREATE TYPE ... AS ENUM (...)` or an `ALTER TYPE ... RENAME TO` whose name a modeled enum already has | Replaces that enum in its position with the newly declared or renamed one, because the `DROP TYPE ... CASCADE` that freed the name is never seen. This is how the migration that replaces an enum loads, in either of its usual shapes: renaming the old type away before declaring the new one under its name, or declaring the new type beside it and renaming the new type onto its name once the columns are retyped. A column that still names a replaced enum keeps that name, so it is modeled as a column of the replacing enum with its labels, which is the same unseen `DROP TYPE ... CASCADE` case: PostgreSQL would have removed the column. |
| `ALTER TYPE ... OWNER TO`, `RENAME ATTRIBUTE`, `ADD ATTRIBUTE`, `DROP ATTRIBUTE`, and `ALTER ATTRIBUTE` | Ignored. Ownership cannot change a modeled enum and the attribute actions belong to a composite type, so the stated type is not resolved at all. |
| `ALTER TYPE ... SET SCHEMA <schema>` | Rejected for a modeled enum as `Unsupported ALTER TYPE action: SET SCHEMA <schema> at line <n>`, because the columns of that type would keep it in a namespace sqlcj does not model. |
| `ALTER TYPE ... SET SCHEMA public` | Does nothing for a modeled enum: the type already stands in the one namespace sqlcj models. |
| `ALTER TYPE ... RENAME TO` or `SET SCHEMA` of a type sqlcj does not model | Ignored, however the type is qualified, because such a type is none of its enum types. |
| A `CREATE TYPE` or an `ALTER TYPE` whose own type is qualified with a schema other than `public` | Ignored before anything it states is resolved, so it never touches the modeled enum of the same unqualified name and neither a repeated label nor a missing label or neighbour fails. |
| `CREATE TYPE` of a composite, range, or shell type | Ignored. A column of such a type is recorded with its declared type, as [Unsupported Types and DDL](#unsupported-types-and-ddl) describes. |
| `DROP TYPE` | Ignored. sqlcj's parser cannot read the statement, and an unreadable statement that does not open as table or type DDL is ignored, so an enum the schema models stays modeled with its labels and the columns of that type stay modeled; see [Ignored Statements](#ignored-statements). |

An `ALTER TYPE` written in a form sqlcj's parser cannot read, such as a
qualified `RENAME TO` target, several actions joined by commas, or
`SET (...)`, is rejected as unreadable type DDL, unless its own type is
qualified with a schema other than `public`, which is ignored; see
[Ignored Statements](#ignored-statements).

Type names are matched case-insensitively, after their SQL identifier
delimiters are removed, and labels are matched exactly. Because sqlcj models one
namespace, a type name resolves by its unqualified name, so
`CREATE TYPE public.stage_setting` and `CREATE TYPE "public"."stage_setting"`
declare the type `stage_setting` and replace a modeled `stage_setting` rather
than adding a second enum, and `ADD VALUE`, `RENAME VALUE`, and `RENAME TO`
written through `public.stage_setting` act on that one type and name it
unqualified in their diagnostics. A statement that repeats a label, or that
refers to a type or a label that is not modeled, is rejected as PostgreSQL
rejects it:

```text
sqlcj: Invalid schema source /home/dev/project/sql/migrations/V2__stages.sql: Type not found in schema: stage_setting
sqlcj: Invalid schema source /home/dev/project/sql/migrations/V2__stages.sql: Label already exists in type stage_setting: indoor
sqlcj: Invalid schema source /home/dev/project/sql/migrations/V2__stages.sql: Label not found in type stage_setting: covered
```

A placeholder index repeated across two enum types, or across an enum and any
other type, has no single Java type and is rejected by query analysis, which
names each enum by its PostgreSQL type:

```text
sqlcj: Invalid query 'UpdateStage' in /home/dev/project/sql/queries.sql at line 5: Placeholder $1 has conflicting types: stage_setting from 'setting' and String from 'handle'
```

Two entries of one package that use one enum type must define its labels alike,
and a generated enum must not collide with another generated type of the
package; both are documented in
[Generated Java Names](configuration.md#generated-java-names).

## Domain Types

`CREATE DOMAIN <name> AS <type>` adds a domain to the schema, and a column or
a cast whose declared type names it is the type the domain is declared over: it
carries that type's mapped type, Java type, binding, and reading. A domain is
never generated as a Java type of its own:

```sql
CREATE EXTENSION IF NOT EXISTS citext;

CREATE DOMAIN email_address AS citext;

CREATE TABLE users (
    id    bigserial PRIMARY KEY,
    email email_address NOT NULL
);
```

A query that reads or binds `email` therefore takes and returns a `String`,
exactly as it does for the `citext` the domain is declared over, and the
argument is bound the same way.

- A domain over a domain is the type at the end of the chain, and a domain over
  a one-dimensional array type, such as `text[]`, is an array column and a
  `List` of the element's Java type.
- A column's nullability comes from its own declaration alone, so a domain's
  own `NOT NULL` never makes a column of it non-null: PostgreSQL reports such a
  column as nullable, and a left join reads it as `null` for an unmatched row.
- A domain over a type sqlcj does not map, and an array of a domain, such as
  `email_address[]`, have no Java type. Such a column is recorded with its
  declared type and fails only a query that uses it; see
  [Unsupported Types and DDL](#unsupported-types-and-ddl).
- A built-in spelling resolves before a domain and a domain before an enum
  type, as PostgreSQL resolves a declared name, so a domain never falls through
  to an enum type of the same name.
- A column of a domain over an enum type is a column of that enum type, so it
  binds and reads the one Java enum the package generates for the enum type
  itself. PostgreSQL 16 defines no `=`, no `= ANY`, and no `MIN` or `MAX` for
  such a domain, though it writes, reads, and orders one, so a query may
  insert, assign, read, and `ORDER BY` such a column but not compare it.
- A domain over `citext` is compared, matched, and ordered ignoring case, as
  the `citext` itself is, through an equality, a `LIKE`, an `ILIKE`, and an
  `= ANY(citext[])` alike.

These statements update the domains the statements and files before them left:

| Statement | Handling |
| --- | --- |
| `CREATE DOMAIN ... AS <type>` | Adds the domain over the type it is declared over, resolved against the enum types and the domains the statements before it left. A `COLLATE`, a `DEFAULT`, and a constraint are accepted and not modeled. |
| A `CREATE DOMAIN` sqlcj's parser cannot read, such as the `CONSTRAINT ... CHECK ((VALUE OPERATOR(public.~~) ...))` and `COLLATE ... NOT NULL DEFAULT` forms `pg_dump` writes | Modeled from the statement's own words: the words before its first `COLLATE`, `DEFAULT`, `CONSTRAINT`, `NOT`, `NULL`, or `CHECK` outside parentheses state the name and the base type alone and are read again as a `CREATE DOMAIN` of their own. Words whose base type does not read either model the domain over no type, so a column of it is recorded with its declared type; words that state no name at all are rejected with the parser's own reason. |
| A `CREATE DOMAIN` whose name a modeled domain already has | Replaces that domain in its position, because the `DROP DOMAIN` that freed the name may be one sqlcj never sees. |
| `ALTER DOMAIN ... RENAME TO <name>` and `ALTER TYPE ... RENAME TO <name>` of a modeled domain | Renames the domain in its position, keeping the type it is declared over, so a column declared afterwards resolves through the new name. A column already resolved keeps the type its own statement gave it. |
| A rename whose new name a modeled domain has, and a `CREATE TYPE ... AS ENUM` of a modeled domain's name | Removes that domain, because PostgreSQL holds one type of a name at a time. |
| `ALTER TYPE ... RENAME TO` of an enum type | Renames the enum type in every domain over it, so a column of such a domain still reads the one modeled enum. |
| Every other `ALTER DOMAIN` action, readable or not: `SET DEFAULT`, `DROP DEFAULT`, `SET NOT NULL`, `DROP NOT NULL`, `ADD`, `DROP`, `RENAME`, and `VALIDATE CONSTRAINT`, `OWNER TO`, and `SET SCHEMA` | Ignored. None of them can change the type a column of the domain has, and a domain's own `NOT NULL` never decides a column's nullability. |
| `DROP DOMAIN [IF EXISTS] <name>[, <name> ...]`, with or without `CASCADE` or `RESTRICT` | Removes every modeled domain it names and skips every other name. sqlcj's parser reads no `DROP DOMAIN` at all, so the statement is classified by its own words, each name an identifier optionally qualified with a schema. A column already resolved keeps the type its own statement gave it, and a column declared afterwards is recorded with its declared type. |
| A `DROP DOMAIN` whose words state no such statement | Rejected as `Unsupported schema statement: DROP DOMAIN at line <n>`, because sqlcj cannot tell what it would drop. |
| A `CREATE DOMAIN`, `ALTER DOMAIN`, or `DROP DOMAIN` name qualified with a schema other than `public` | Ignored before anything it states is resolved, as every other statement of another schema is; a `DROP DOMAIN` skips such a name among its names. |

Domain names are matched case-insensitively, after their SQL identifier
delimiters are removed, and resolve in the one namespace sqlcj models, so
`CREATE DOMAIN public.email_address` and
`CREATE DOMAIN "public"."email_address"` declare the domain `email_address`.

## Array Types

A column declared as a one-dimensional array, written `type[]` or `type[n]`, is
modeled as an array of its element type and generates `java.util.List<T>` of
that element's Java type, for method parameters and result components alike:

```sql
CREATE TABLE stages (
    id       BIGINT PRIMARY KEY,
    tags     varchar(20)[],
    past     stage_setting[]
);
```

generates `List<String> tags` and `List<StageSetting> past`.

- The element type is any spelling in
  [Supported Column Types](#supported-column-types) except `BYTEA`, `JSON`, and
  `JSONB`, or the name of an enum type the schema declares. `INTERVAL[]` is a
  `List<org.sqlcj.runtime.SqlInterval>`, while `INTERVAL[][]` has no mapping and
  is recorded as `INTERVAL[][]`.
- The declared size of a dimension is ignored, as PostgreSQL ignores it, so
  `numeric(10, 2)[3]` is the same type as `numeric(10, 2)[]`.
- An array column is typed the same way through `CREATE TABLE`,
  `ALTER TABLE ... ADD COLUMN`, and `ALTER TABLE ... ALTER COLUMN ... TYPE`, and
  a rename or a nullability change keeps it.
- An array is a type of its own: a placeholder index used once as an array and
  once as a value of its element type is rejected, like any other conflicting
  index.
- A `LIKE`/`ILIKE` pattern placeholder still requires a scalar `VARCHAR`,
  `TEXT`, or `CITEXT` column, so an array column is rejected there.
- A reference written with a subscript or a slice, such as `tags[1]`,
  `tags[1:2]`, or `s.tags[1]`, reads one element or one range rather than the
  column, so it is not a direct column anywhere: it types no result column and
  no placeholder, and a cast states the type of either. See
  [Queries](queries.md#projections).
- The `= ANY` list predicate is the one analyzed array operator: a placeholder
  written as `<column> = ANY($1)` binds a list of the compared column's type,
  which must be a non-array column of an element type above or of a declared
  enum type. See [Queries](queries.md#predicates) for the shape and its
  rejections. No other array operator or function, such as `&&`, is analyzed.

An array argument is passed to the executor as
`new org.sqlcj.runtime.SqlArray("<element type>", <parameter>)`, written out in
full so that the generated imports are unchanged, where `<element type>` is
PostgreSQL's own name of the element type: `int4`, `int8`, `int2`, `bool`,
`varchar`, `bpchar`, `text`, `date`, `time`, `timestamp`, `timestamptz`,
`numeric`, `float4`, `float8`, `uuid`, `citext`, `inet`, `cidr`, `macaddr`,
`macaddr8`, `interval`, or the declared name of an enum type. A
`CHAR` or `CHARACTER` element is named `bpchar`, PostgreSQL's own name of the
blank-padded character type, because PostgreSQL compares a `bpchar` array only
with another one; `VARCHAR` and `CHARACTER VARYING` elements are named
`varchar`. Both read their elements back as `String`, blank padded to the
declared length for `CHAR`, as a scalar of that type is read. An array of an
enum carries the labels of its constants instead, as
`org.sqlcj.runtime.SqlArray.of("<enum type>", <parameter>, <EnumType>::label)`,
and an array of `INTERVAL` carries the text of its elements, as
`org.sqlcj.runtime.SqlArray.of("interval", <parameter>,
org.sqlcj.runtime.SqlInterval::format)`.

`JdbcQueryExecutor` builds the value with `Connection.createArrayOf` on the
connection the operation runs on and binds it with
`PreparedStatement.setArray`. An array column is read with
`org.sqlcj.runtime.SqlArray.getList(resultSet, position, <Type>.class)`, which
reads each element as a scalar of that type is read, a `String` element with
`getString`, for the same reason a scalar of one of those types is; an array of
an enum is read
as `org.sqlcj.runtime.SqlArray.getList(resultSet, position, String.class,
<EnumType>::fromLabel)`, and an array of `INTERVAL` as
`org.sqlcj.runtime.SqlArray.getList(resultSet, position, String.class,
org.sqlcj.runtime.SqlInterval::parse)`. All of them go through the JDBC
`java.sql.Array` API alone, so neither the runtime nor the generated code
depends on a driver.

Nulls are carried in both directions:

- a `null` list argument is bound with
  `PreparedStatement.setNull(position, java.sql.Types.ARRAY)`, so the column
  receives a SQL `NULL` rather than an empty array,
- a `null` element is bound as a `NULL` element of the array,
- a SQL `NULL` column reads back as a `null` list, an empty array as an empty
  list, and a `NULL` element as a `null` element, so a `NULL` element of an
  `INTERVAL[]` is a `null` `SqlInterval`.

A declared multidimensional array, such as `integer[][]`, and an array of
`BYTEA`, `JSON`, `JSONB`, or an unmapped element type have no Java mapping and
are recorded with their declared type; see
[Unsupported Types and DDL](#unsupported-types-and-ddl).

## Supported `CREATE TABLE` Constructs

| Construct | Handling |
| --- | --- |
| `NOT NULL` | Accepted and modeled. It is the only source of column nullability. |
| Column-level `PRIMARY KEY` | Accepted and recorded as a primary-key constraint on that column. |
| Table-level `PRIMARY KEY (...)`, named or unnamed | Accepted and recorded as a primary-key constraint. |
| Column-level `UNIQUE` | Accepted and recorded as a unique constraint on that column. |
| Table-level `UNIQUE (...)`, named or unnamed | Accepted and recorded as a unique constraint. |
| `DEFAULT` with a literal or a function, such as `DEFAULT 1`, `DEFAULT 'new'`, `DEFAULT now()` | Accepted and ignored. |
| Column-level `REFERENCES t (c)`, including referential actions such as `ON DELETE CASCADE` | Accepted and ignored. |
| Column-level `CHECK (...)` | Accepted and ignored. |
| Table-level `FOREIGN KEY (...) REFERENCES ...`, named or unnamed | Accepted and ignored. |
| Table-level `CHECK (...)`, named or unnamed | Accepted and ignored. |
| Table-level `EXCLUDE USING ... (...)`, named or unnamed | Accepted and ignored. |
| `LIKE t`, with any `INCLUDING` and `EXCLUDING` options | Accepted. Copies the columns, types, and nullability of the modeled table `t` at the position the clause stands in. |
| `PARTITION OF t`, with `FOR VALUES FROM ... TO ...`, `FOR VALUES IN (...)`, `FOR VALUES WITH (MODULUS ...)`, or `DEFAULT` | Accepted. Copies the columns, types, and nullability of the modeled table `t` and keeps following its column changes; see [Ordered Table DDL](#ordered-table-ddl). |
| An empty element list, `()` | Accepted and modeled as a table without columns. |
| `AS SELECT ...` | Rejected as `Unsupported CREATE TABLE clause: AS SELECT at line <n>`. |
| `OF <type>` | Rejected as `Unsupported CREATE TABLE clause: OF <type> at line <n>`. |
| `INHERITS (p)` | Rejected as `Unsupported CREATE TABLE clause: INHERITS at line <n>`. |
| A table statement sqlcj's parser cannot read, such as `ALTER FOREIGN TABLE` | Rejected; see [Ignored Statements](#ignored-statements). |
| Any other table-constraint kind | Rejected. |
| Unparsable SQL | Rejected. |

Constraints never affect generated Java. A recorded primary-key or unique
constraint is kept in the parsed schema model, but no code in the compilation
pipeline reads it, so it changes no generated type, method, parameter, or
result component. "Ignored" means the construct is accepted as valid schema
input and is not carried into the model at all.

A `CREATE TABLE` element list may state column definitions, table constraints,
and `LIKE` clauses in any order, and the table's columns are modeled in that
written order, as PostgreSQL creates them, each `LIKE` contributing its source's
columns at its own position. A partition starts with its parent's columns,
before every element of its own list. A column name that is repeated, by two
column definitions, by a `LIKE` and a column definition, or by two `LIKE`
clauses, fails as `Column already exists in table <t>: <c>`.

A `LIKE`'s `INCLUDING` and `EXCLUDING` options cannot change a copied column's
type or nullability and are ignored, and neither `LIKE` nor `PARTITION OF`
copies its source's recorded constraints: a copy records only the constraints
its own statement states. A `LIKE` copy is independent of its source afterwards,
as in PostgreSQL. A `LIKE` or `PARTITION OF` source sqlcj does not model fails
as `Table not found in schema: <t>`.

Because sqlcj models one namespace, a `LIKE` or `PARTITION OF` source and an
`ATTACH` or `DETACH PARTITION` partition resolve by their unqualified name, so
`public.t` is `t`. Such a name qualified with any other schema names a table
sqlcj leaves unmodeled rather than a modeled `t`: a `LIKE` or `PARTITION OF`
source fails as `Table not found in schema: <schema>.<t>`, because the created
table's columns would otherwise be unknown, and an `ATTACH` or
`DETACH PARTITION` partition is ignored, like any other partition sqlcj does not
model.

A `CREATE TABLE` with neither an element list, `()`, nor `PARTITION OF`, such as
`CREATE TABLE c LIKE t` without the parentheses PostgreSQL requires, states no
columns at all and is rejected as
`Unsupported schema statement: CREATE TABLE at line <n>`. A form sqlcj's parser
cannot read, such as the column options `PARTITION OF` and `OF <type>` allow
(`id WITH OPTIONS NOT NULL`) or `AS SELECT ... WITH NO DATA`, is rejected as
unreadable table DDL with the parser's reason; see
[Ignored Statements](#ignored-statements).

## Ordered Table DDL

A schema of `CREATE TABLE` statements alone is modeled exactly as it was before
ordered DDL existed. Beyond it, these statements update the schema the
statements and files before them left; the enum-type statements are listed in
[Enum Types](#enum-types):

| Statement | Handling |
| --- | --- |
| `CREATE TABLE` | Adds the table after the tables already modeled. |
| `CREATE TABLE IF NOT EXISTS` | Does nothing when the table already exists. |
| `DROP TABLE`, with one or several names | Removes each named table and, recursively, every partition of it, as PostgreSQL does. A name qualified with a schema other than `public` names a table sqlcj leaves unmodeled and is skipped without failing, because the statement may name a modeled table beside it. |
| `DROP TABLE IF EXISTS` | Does nothing for a name that is not modeled. |
| `ALTER TABLE ... ADD COLUMN` | Appends the column, typed exactly as a `CREATE TABLE` column of the same declaration, and records its column-level `PRIMARY KEY` or `UNIQUE`. |
| `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` | Does nothing when the column already exists. |
| `ALTER TABLE ... DROP COLUMN` | Removes the column and every recorded constraint that lists it. |
| `ALTER TABLE ... DROP COLUMN IF EXISTS` | Does nothing when the column is not modeled. |
| `ALTER TABLE ... RENAME COLUMN` | Renames the column in its position and in the constraints that list it. |
| `ALTER TABLE ... RENAME TO` | Renames the table in its position, keeping its columns and constraints, and keeps every partition of it linked to the new name. |
| `ALTER TABLE ... ATTACH PARTITION` | Links a modeled table to the altered one, so it follows its column changes from then on. A partition sqlcj does not model is ignored. |
| `ALTER TABLE ... DETACH PARTITION` | Ends the link of a modeled partition. A partition sqlcj does not model is ignored. |
| `ALTER TABLE ... ALTER COLUMN ... TYPE` | Maps or records the new type as a `CREATE TABLE` column of that type is mapped or recorded, keeping the column's position and its nullability, which a type change does not state. |
| `ALTER TABLE ... ALTER COLUMN ... SET NOT NULL` | Models the column non-null. |
| `ALTER TABLE ... ALTER COLUMN ... DROP NOT NULL` | Models the column nullable. |
| `ALTER TABLE IF EXISTS ...` | Does nothing when the table is not modeled. It covers only the table, so a missing column of a modeled table still fails. |
| `ALTER TABLE ... SET SCHEMA <schema>`, as the statement's sole action, which is the only form PostgreSQL accepts | Removes the table, keeping the order of the remaining ones, because it moves out of the one namespace sqlcj models. `SET SCHEMA public` does nothing: the table already stands there. A table with modeled partitions is rejected as `Unsupported ALTER TABLE action: SET SCHEMA <schema> at line <n>`, because its partitions would stay in the modeled namespace following a parent sqlcj no longer models. |
| A `CREATE TABLE` or an `ALTER TABLE` whose own table is qualified with a schema other than `public` | Ignored before anything it names is resolved, so neither its rejected clauses, its rejected actions, nor its `LIKE` and `PARTITION OF` sources are resolved and a modeled table of the same unqualified name is left alone. |
| Any other `ALTER TABLE` action outside the ignored actions listed in [Ignored Statements](#ignored-statements) | Rejected as `Unsupported ALTER TABLE action: <action> at line <n>`, quoting the action's own SQL. |
| `DROP` of any object other than a table, such as `DROP VIEW` or `DROP INDEX` | Ignored; see [Ignored Statements](#ignored-statements). A `DROP VIEW` or `DROP MATERIALIZED VIEW` changes no modeled table either, and only removes the names it states from the recorded views. |

One `ALTER TABLE` may state several actions; they are applied in the written
order. Table and column names are matched case-insensitively, as query analysis
looks them up, after their SQL identifier delimiters are removed.

PostgreSQL applies every column change of a partitioned table to its partitions,
so a partition created by `PARTITION OF`, or attached by `ATTACH PARTITION` when
sqlcj models its table, stays linked to its parent: `ADD COLUMN`,
`DROP COLUMN`, `RENAME COLUMN`, `ALTER COLUMN ... TYPE`, `SET NOT NULL`, and
`DROP NOT NULL` on the parent are applied to its partitions as well,
recursively, so a nested partition follows too. A partition keeps its own name
when its parent is renamed, `DROP TABLE` of the parent removes it, and
`DETACH PARTITION` ends the link, after which the parent's later column changes
no longer reach it. A `LIKE` copy is never linked to its source.

Outside the `IF [NOT] EXISTS` forms above, a statement that refers to a table or
a column that is not modeled is rejected, and so is a statement that would give
two tables, or two columns of one table, the same name, as PostgreSQL rejects it:

```text
sqlcj: Invalid schema source /home/dev/project/sql/migrations/V2__orders.sql: Table not found in schema: payments
sqlcj: Invalid schema source /home/dev/project/sql/migrations/V2__orders.sql: Column not found in table orders: total
sqlcj: Invalid schema source /home/dev/project/sql/migrations/V2__orders.sql: Table already exists in schema: orders
sqlcj: Invalid schema source /home/dev/project/sql/migrations/V2__orders.sql: Column already exists in table orders: total
```

## Ignored Statements

A schema file records the whole history of a database, not only its tables, so
one rule decides every statement it states: a statement sqlcj models is applied,
and every other statement is accepted and leaves the schema model exactly as the
statements and files before it left it. There is no list of accepted kinds to
keep up to date.

The modeled statements are the `CREATE TABLE`, `DROP TABLE`, and `ALTER TABLE`
forms of [Ordered Table DDL](#ordered-table-ddl), the
`CREATE TYPE ... AS ENUM` and `ALTER TYPE` forms of [Enum Types](#enum-types),
and the `CREATE DOMAIN`, `ALTER DOMAIN ... RENAME TO`, and `DROP DOMAIN` forms
of [Domain Types](#domain-types).
Everything else a migration history holds is ignored, including

- `CREATE INDEX`, `CREATE UNIQUE INDEX`, `ALTER INDEX`, and `DROP INDEX`,
- `COMMENT ON` of any target, including `TABLE`, `COLUMN`, `VIEW`, `TYPE`,
  `INDEX`, `FUNCTION`, `SCHEMA`, `EXTENSION`, and `CONSTRAINT`,
- `CREATE EXTENSION`, `CREATE SEQUENCE`, `ALTER SEQUENCE`, and `DROP SEQUENCE`,
- `GRANT`, `REVOKE`, and `ALTER DEFAULT PRIVILEGES`,
- `CREATE FUNCTION`, `CREATE PROCEDURE`, `DROP FUNCTION`, `CREATE TRIGGER`, and
  `DROP TRIGGER`, with a body written any way: the untagged `$$ ... $$`, a
  tagged `$body$ ... $body$`, a single-quoted `AS 'SELECT 1'`, or `RETURN 1`,
- `DO` blocks, with an untagged or a tagged body and with or without a stated
  `LANGUAGE`,
- `INSERT`, `UPDATE`, `DELETE`, and `TRUNCATE`, a migration's data statements,
- `CREATE VIEW`, `DROP VIEW`, and the `CREATE`, `REFRESH`, and `DROP` forms of
  `MATERIALIZED VIEW`, which change no schema model, because a view's columns
  stand in its own query; the views and materialized views of the modeled
  namespace are recorded by their name and their kind nonetheless, from the
  words of `CREATE [OR REPLACE] [RECURSIVE] VIEW`,
  `CREATE MATERIALIZED VIEW [IF NOT EXISTS]`, and
  `DROP [MATERIALIZED] VIEW [IF EXISTS]`, so that a query that names one is
  rejected as
  `<v> is a view, which sqlcj does not model; write its query in a WITH clause
  instead.`, and a materialized view as
  `<v> is a materialized view, which sqlcj does not model; write its query in a
  WITH clause instead.`; a view of another schema and a temporary view are
  recorded nowhere, and a `DROP` of a name no view is recorded of does nothing,
- `CREATE SCHEMA` and `DROP SCHEMA`,
- `SET`, including `SET search_path TO a, b`, `RESET`, `ANALYZE`, `VACUUM`,
  `LOCK TABLE`, `BEGIN`, `COMMIT`, `COPY`, and a plain `SELECT`, such as the
  `SELECT pg_catalog.set_config(...)` a `pg_dump` snapshot opens with,
- `CREATE POLICY`, `DROP POLICY`, `CREATE ROLE`, `CREATE USER`, and
  `ALTER ROLE`,
- every `ALTER DOMAIN` action other than `RENAME TO` of a modeled domain,
- `CREATE RULE` and `CREATE EVENT TRIGGER`,
- a `CREATE TYPE` of a composite, range, or shell type, and `DROP TYPE`,
- a `CREATE TABLE`, `ALTER TABLE`, `CREATE TYPE`, or `ALTER TYPE` whose own
  table or type is qualified with a schema other than `public`, readable or not,
- a `CREATE TABLE [IF NOT EXISTS] ${name} (` and an
  `ALTER TABLE [IF EXISTS] [ONLY] ${name}` whose every top-level action adds a
  constraint, which are the two statements of the MyBatis Migrations changelog
  script; see [MyBatis Migrations](#mybatis-migrations), and
- a statement sqlcj's parser cannot read, or reports only as opaque text, and
  that does not open as table or type DDL, such as `ALTER FUNCTION`,
  `ALTER SCHEMA`, `CREATE AGGREGATE`, or `CREATE CAST`.

An ignored statement is not resolved against the schema at all, so it may name a
table or a column the snapshot does not model: a view over an unmodeled table
and a `TRUNCATE` of one are both accepted.

These statements are rejected rather than ignored, because sqlcj cannot tell
what they would do to the schema model:

| Statement | Diagnostic |
| --- | --- |
| An `ALTER TABLE` action outside [Ordered Table DDL](#ordered-table-ddl) and the ignored actions below | `Unsupported ALTER TABLE action: <action> at line <n>`, quoting the action's own SQL |
| A `CREATE TABLE ... AS SELECT`, `OF <type>`, or `INHERITS`, whose columns sqlcj cannot determine | `Unsupported CREATE TABLE clause: <clause> at line <n>`, naming `AS SELECT`, `OF <type>`, or `INHERITS` |
| An `ALTER TYPE ... SET SCHEMA <other schema>` of a modeled enum type | `Unsupported ALTER TYPE action: SET SCHEMA <schema> at line <n>` |
| A statement sqlcj's parser cannot read that opens as table or type DDL, outside the `ALTER TABLE` actions below and the names of another schema below | The parser's own reason with the line and column of the unexpected token, as in `Encountered unexpected token: "DATA" at line 18, column 41` |
| A statement sqlcj's parser reports only as opaque text, or as several statements, and that opens as table or type DDL | `Unsupported schema statement: ALTER FOREIGN TABLE at line <n>`, quoting the opening words |
| A `DROP DOMAIN` whose words state no such statement, and a `CREATE DOMAIN` sqlcj's parser cannot read whose words state no name | `Unsupported schema statement: DROP DOMAIN at line <n>` and the parser's own reason; see [Domain Types](#domain-types) |

A statement opens as table DDL when its first words are `CREATE`, `ALTER`, or
`DROP`, then any of `GLOBAL`, `LOCAL`, `TEMP`, `TEMPORARY`, `UNLOGGED`, or
`FOREIGN`, then `TABLE`, and as type DDL when they are `CREATE` or `ALTER`, then
`TYPE`. An unreadable statement whose first words are `CREATE DOMAIN` or
`DROP DOMAIN` is classified before those openings, because both change the
domains the schema models; see [Domain Types](#domain-types). The words are
matched case-insensitively and the diagnostic quotes them
in upper case, single-spaced, so both
`ALTER FOREIGN TABLE ft ADD COLUMN x integer` and its lower-case spelling are
rejected as `Unsupported schema statement: ALTER FOREIGN TABLE at line <n>`.

An unreadable statement that opens as `CREATE` or `ALTER TABLE` or as `CREATE`
or `ALTER TYPE` is ignored instead when the object it names stands in a schema
sqlcj does not model, decided from its words alone: the qualifier before the
dot of the name that follows the opening words, an optional `IF [NOT] EXISTS`,
and an optional `ONLY`. An unreadable
`ALTER TABLE reporting.users ALTER COLUMN x SET DATA TYPE int` is therefore
ignored, while the same statement about `users` or `public.users` stays rejected
with the parser's reason. An unreadable `DROP TABLE` keeps its handling, because
another of its names may be modeled.

Every `ALTER TABLE` action that cannot change a column's existence, name, type,
or nullability is ignored like any other unmodeled statement, except that the
statement still resolves its table:

| Action | Notes |
| --- | --- |
| `ADD [CONSTRAINT name] PRIMARY KEY (...)` | Named or unnamed. |
| `ADD [CONSTRAINT name] UNIQUE (...)` | Named or unnamed. |
| `ADD [CONSTRAINT name] FOREIGN KEY (...) REFERENCES ...` | Named or unnamed. |
| `ADD CONSTRAINT name CHECK (...)` | |
| `DROP CONSTRAINT [IF EXISTS] name` | |
| `RENAME CONSTRAINT` | |
| `ALTER [COLUMN] c SET DEFAULT ...` and `DROP DEFAULT` | A `DEFAULT` is accepted and ignored in a `CREATE TABLE` as well. |
| `ALTER [COLUMN] c SET STATISTICS`, `SET STORAGE`, and `SET COMPRESSION` | Column storage, not the column's type. |
| `ALTER [COLUMN] c DROP EXPRESSION` | Only the readable spelling; `DROP EXPRESSION IF EXISTS` is unreadable. |
| `ALTER [COLUMN] c ADD GENERATED ... AS IDENTITY`, `SET GENERATED ...`, a sequence option such as `SET INCREMENT BY 2`, `RESTART`, and `DROP IDENTITY [IF EXISTS]` | PostgreSQL requires the column to be `NOT NULL` already, so an identity never changes nullability. |
| `ENABLE`, `DISABLE`, `FORCE`, and `NO FORCE ROW LEVEL SECURITY` | |
| `ATTACH PARTITION` and `DETACH PARTITION` of a table sqlcj does not model | A modeled partition is linked or unlinked instead, as [Ordered Table DDL](#ordered-table-ddl) states. |
| `OWNER TO` | |
| `ENABLE TRIGGER`, `DISABLE TRIGGER`, `ENABLE REPLICA TRIGGER`, and `ENABLE ALWAYS TRIGGER`, and the same four for `RULE` | |
| `VALIDATE CONSTRAINT` | |
| `REPLICA IDENTITY ...` | |
| `CLUSTER ON` and `SET WITHOUT CLUSTER` | |
| `SET WITHOUT OIDS` | |
| `SET (...)` and `RESET (...)` | Storage parameters. |
| `SET TABLESPACE`, `SET LOGGED`, `SET UNLOGGED`, and `SET ACCESS METHOD` | |
| `INHERIT` and `NO INHERIT` | A table's columns are modeled from its own statements; inherited columns are not modeled. |
| `OF type` and `NOT OF` | |

An ignored action resolves its table but not its column, so
`ALTER TABLE users ALTER COLUMN nickname SET STATISTICS 100` is accepted for a
column that is not modeled, while `ALTER TABLE payments OWNER TO app` fails as
`Table not found in schema: payments`, exactly as any other `ALTER TABLE` of a
missing table does, and `ALTER TABLE IF EXISTS payments OWNER TO app` does
nothing.

A constraint an `ALTER TABLE` adds is not recorded, so it is not carried into
the schema model the way a `CREATE TABLE` constraint is. An ignored action may
stand alone or beside modeled actions of one `ALTER TABLE`, which are applied in
the written order, so
`ALTER TABLE users ADD COLUMN age INTEGER, ALTER COLUMN name SET DEFAULT 'new', OWNER TO app`
appends `age` and records nothing else.

sqlcj's parser has no form of its own for the table-level actions listed from
`OWNER TO` down in the table above. It reports such an action as the text that
runs to the end of the statement, so sqlcj splits that text at the commas
outside parentheses, with its own SQL lexer, and every action in it must be one
of those. `SET SCHEMA` is not among them: written as the statement's sole
action, the only form PostgreSQL accepts, it is applied as
[Ordered Table DDL](#ordered-table-ddl) states, and written beside another
action, as in `ALTER TABLE users ADD COLUMN x int, SET SCHEMA archive` or
`ALTER TABLE users SET SCHEMA archive, OWNER TO app`, it is rejected as
`Unsupported ALTER TABLE action: SET SCHEMA archive at line <n>`. A modeled
action written after an ignored action is rejected rather than skipped, so
`ALTER TABLE users OWNER TO app, ADD COLUMN x int` is rejected as
`Unsupported ALTER TABLE action: ADD COLUMN x int at line <n>`. Written the
other way round, `ALTER TABLE users ADD COLUMN x int, OWNER TO app` appends `x`.

An `ALTER TABLE` sqlcj's parser cannot read is classified by its words instead,
and ignored when every one of its top-level actions — the parts separated by
the commas that stand outside parentheses — begins with one of

- `ADD CONSTRAINT <name>`,
- `ADD CHECK`, `ADD UNIQUE`, or `ADD EXCLUDE`,
- `ADD PRIMARY KEY` or `ADD FOREIGN KEY`, or
- `ALTER [COLUMN] <name> ADD GENERATED`.

PostgreSQL 16 writes `ALTER TABLE ONLY public.t ALTER COLUMN id ADD GENERATED
... AS IDENTITY (SEQUENCE NAME ...)` for every identity column of a
`pg_dump --schema-only` snapshot, and sqlcj's parser reads neither that form nor
an `EXCLUDE`, `DEFERRABLE INITIALLY DEFERRED`, `UNIQUE USING INDEX`,
`PRIMARY KEY USING INDEX`, or `NOT VALID` constraint, nor an unnamed
`ADD CHECK`. None of them can change a modeled column's existence, name, type,
or nullability, which comes from `NOT NULL` alone. Such a statement is ignored
without its table being resolved, like every other ignored statement, so it also
loads for a table the snapshot does not model. Every other unreadable
`ALTER TABLE`, including `ALTER COLUMN ... SET DATA TYPE` and an ignored action
written beside it, stays rejected with the parser's reason, unless its table
stands in a schema sqlcj does not model.

sqlcj splits a schema file at the statement separators its own SQL lexer
reports and parses each statement alone, so a statement the parser cannot read
fails only itself:

- A separator inside a quoted string, a quoted identifier, a comment, or the
  untagged `$$ ... $$` literal is not a boundary. A tagged dollar-quoted body
  ends at the next occurrence of its own opening delimiter, as PostgreSQL ends
  it, whether that delimiter stands alone or is glued to the body as in
  `$fn$BEGIN ... END$fn$`. A body that is never closed fails the source as
  `Unterminated dollar-quoted string at line <n>, column <c>`.
- A psql meta-command line, a line whose first non-blank character is a
  backslash, such as the `\restrict` and `\unrestrict` lines recent `pg_dump`
  releases write, is skipped. Every line and column a diagnostic reports is a
  position in the file as it was written.
- An escape string that quotes a single quote with a backslash, such as
  `E'it\'s'`, is mis-lexed by sqlcj's SQL lexer and fails the whole file.
  Writing the doubled `E'it''s'`, or the ordinary `'it''s'`, avoids it.

sqlcj reads only the statements a schema file states, so a schema change a
statement makes indirectly is never seen:

- DDL inside a function or procedure body, or inside a `DO` block, belongs to
  that body, so a table or a type the body creates, alters, or drops is not
  modeled.
- `DROP TYPE ... CASCADE` drops every column of the dropped type, and sqlcj
  never models those columns as dropped; `DROP TYPE` itself is ignored, so the
  enum stays modeled with its labels; see [Enum Types](#enum-types).
  `DROP DOMAIN ... CASCADE` drops every column of the dropped domain the same
  way, and sqlcj removes the domain alone; see
  [Domain Types](#domain-types).

## Nulls

Every generated method parameter and every generated result component uses a
reference type, so each of them can represent SQL `NULL`. A result component may
be `null` exactly when its column is modeled nullable by the schema snapshot or
is read from a null-extended source — one a `LEFT`, `RIGHT`, or `FULL` join
null-extends — whose columns are all nullable because an unmatched row supplies
no value for them.

The column a `JOIN ... USING (...)` or a `NATURAL JOIN` merges is nullable by
the value PostgreSQL exposes for it, read from both sides before that join
null-extends either of them: an inner join reads the pair's equal values and a
full join coalesces them, so their merged column is nullable only when both
sides are; a left join reads the left column and a right join the right column,
so the merged column is nullable exactly when that side is. No join
null-extends the columns it merges itself, while a later `RIGHT` or `FULL` join
of the same group null-extends them, as it does every other source written
before it.

Row absence is a different thing from a null component, and the two are never
mixed:

- absence is expressed only by the query's annotation: `:optional` returns
  `Optional.empty()`, and `:one` fails with
  `org.sqlcj.runtime.QueryCardinalityException`,
- `Optional` is used only as the return type of an `:optional` query. No record
  component and no method parameter is wrapped in `Optional`, and no custom
  nullable wrapper is used.

Null handling does not depend on the column type:

- the generated argument list is built with `java.util.Arrays.asList`, which
  accepts null elements,
- `JdbcQueryExecutor` binds each argument positionally with
  `PreparedStatement.setObject`, so a null argument is bound as SQL `NULL`. A
  `JSON`, `JSONB`, `CITEXT`, `INET`, `CIDR`, `MACADDR`, or `MACADDR8` argument
  is bound the same way through its
  `org.sqlcj.runtime.UntypedText` wrapper, so a null value becomes a SQL `NULL`
  without a declared type. A null enum argument is wrapped the same way, so it
  is bound as SQL `NULL` instead of as the label of a constant. A null array
  argument is bound as a SQL `NULL` array through its
  `org.sqlcj.runtime.SqlArray` wrapper, and a null element stays null inside the
  bound array,
- each result column is read with `ResultSet.getObject(position, Class)`, or
  with `ResultSet.getString(position)` for `JSON`, `JSONB`, `CITEXT`, `INET`,
  `CIDR`, `MACADDR`, `MACADDR8`, and an enum, so a
  SQL `NULL` is read back as `null`, which `fromLabel` keeps `null` for an
  enum. An array column is read with `org.sqlcj.runtime.SqlArray.getList`, which
  reads a SQL `NULL` as a null list and a `NULL` element as a null element.

Nullability itself is parsed from `NOT NULL` only:

- a column without `NOT NULL` is modeled nullable, so a column declared bare
  `PRIMARY KEY` is modeled nullable,
- a serial column, declared `SMALLSERIAL`, `SERIAL`, `BIGSERIAL`, `SERIAL2`,
  `SERIAL4`, or `SERIAL8`, is always modeled non-null, because PostgreSQL
  defines those spellings as an integer type with a sequence default and
  `NOT NULL`,
- nullability never changes a generated Java type. It is carried into the
  analyzed model, but the generated type is resolved from the column type alone.

Null binding and null reading are executed against PostgreSQL for the nullable
columns of the integration schema snapshots, which cover `SMALLINT`, `VARCHAR`,
`TEXT`, `BOOLEAN`, `DATE`, `TIMESTAMP`, `DECIMAL`, `UUID`,
`TIMESTAMP WITH TIME ZONE`, `REAL`, `DOUBLE PRECISION`, `FLOAT(24)`, `FLOAT`,
`BYTEA`, `TIME`, `JSON`, `JSONB`, `CITEXT`, `INET`, `CIDR`, `MACADDR`,
`MACADDR8`, an enum type, and an array of every mapped element type.

## Unsupported Types and DDL

The following type families have no Java mapping, because only the spellings
listed in [Supported Column Types](#supported-column-types) are mapped:

- multidimensional arrays, such as `integer[][]`, and arrays of `BYTEA`,
  `JSON`, `JSONB`, or an unmapped element type,
- a domain over an unmapped type, and an array of a domain,
- range types,
- composite types,
- spatial types.

These spellings of otherwise mapped families are unmapped as well:

- a `FLOAT(p)` whose precision PostgreSQL rejects, such as `FLOAT(0)` and
  `FLOAT(54)`,
- `TIME WITH TIME ZONE` and `TIMETZ`.

A column of such a type does not fail the schema. It is recorded with its
declared type, written as the canonical spelling of that type — upper case, with
parenthesized type arguments removed — followed by `[]` for each declared array
dimension, so `xml` is recorded as `XML`, `jsonb[]` as `JSONB[]`,
and `integer[][]` as `INTEGER[][]`. A `FLOAT` precision PostgreSQL rejects is
the one type argument kept, so `float(54)` is recorded as `FLOAT(54)` and
`float(0)[]` as `FLOAT(0)[]`, because `FLOAT` alone names a mapped type; the
precision of a mapped `FLOAT` is removed like any other type argument, so
`float(24)[][]` is recorded as `FLOAT[][]`.

A recorded column fails only the analysis of a query that

- reads it, as a `SELECT` item or a `RETURNING` item,
- binds it, meaning a placeholder takes its type in a comparison, an `IN` list,
  a range bound, a `LIKE`/`ILIKE` pattern, an `INSERT` column, or an `UPDATE`
  assignment, or
- expands it, through `SELECT *`, `SELECT qualifier.*`, `RETURNING *`, or
  `RETURNING <target>.*`.

A query that reads or binds the column names it and its recorded type,
`Column 'tags' has unsupported type JSONB[]`. A wildcard that expands it adds
what the query may write instead, because the query never wrote the column
itself: `Column 'tags' has unsupported type JSONB[]; a wildcard selects it, so
list the columns the query needs instead.`

Every other query over the same table compiles, including one that references
the column without using its type, such as `WHERE tags IS NULL`.

### Failure Behavior

An unsupported statement, a schema sqlcj cannot parse, or a query that uses a
column of an unmapped type stops compilation. `sqlcj generate` prints a single
message on standard error
and exits with status `1`. Because every configured source is analyzed and
generated before the run writes its first file, such a failure writes no
generated file, and output
written by an earlier successful run is left unchanged. The writing step itself
is sequential rather than atomic; see
[Generated Output and Failures](configuration.md#generated-output-and-failures).

A schema message names the schema source and the offending statement or action
in SQL terms with the line it begins on, the table or column the statement
refers to, or the syntax error with the line and column it was found at. A query
message names the query, its source, its header line, and the offending column
and recorded type:

```text
sqlcj: Invalid schema source /home/dev/project/schema.sql: Unsupported schema statement: ALTER FOREIGN TABLE at line 12
sqlcj: Invalid schema source /home/dev/project/schema.sql: Unsupported ALTER TABLE action: SET SCHEMA archive at line 18
sqlcj: Invalid schema source /home/dev/project/schema.sql: Unsupported CREATE TABLE clause: AS SELECT at line 24
sqlcj: Invalid schema source /home/dev/project/schema.sql: Table not found in schema: reporting.events
sqlcj: Invalid schema source /home/dev/project/schema.sql: Encountered unexpected token: ";" at line 4, column 1
sqlcj: Invalid query 'ListTags' in /home/dev/project/queries.sql at line 5: Column 'tags' has unsupported type JSONB[]
```

## Verified by

Schema snapshot input:

- `ReferenceServiceIntegrationTest.shouldGenerateIdenticalRepositoriesFromHistorySnapshotAndDump`
  applies the reference service's eleven migrations to PostgreSQL 16 with `psql`,
  runs `pg_dump --schema-only` against the migrated database, and generates one
  byte-identical, compiling output tree, manifest included, from the migration
  history, the hand-written snapshot, and that dump.
- `ReferenceServiceIntegrationTest.shouldExecuteTheGeneratedReferenceServiceQueries`
  executes the generated repository against the migrated database, including the
  replaced enum, the renamed enum label, an optimistic update, the partition
  that follows the column its partitioned table gained, and the `LIKE` copy that
  does not.
- `ReferenceServiceIntegrationTest.shouldRejectTheUnsupportedSetDataTypeMigration`
  and
  `ReferenceServiceIntegrationTest.shouldRejectAQueryOnASecondSchemaTable`
  cover the located `SET DATA TYPE` diagnostic and the other-schema diagnostic
  of a query that names a table of the reporting schema, which belongs to a
  schema sqlcj does not model, each writing no file.

Migration tool schemas:

- `SqlcjCompilerIntegrationTest.shouldGenerateOneTreeFromALiquibaseFormattedSqlDirectoryAndItsOfflineUpdateSql`
  generates one byte-identical, compiling tree from a five-changeset Liquibase
  formatted SQL directory, whose `--rollback` lines and `liquibase rollback`
  block would change the schema if they were applied, and from the offline
  `update-sql` output of the equivalent XML changelog, whose
  `databasechangelog` table and `INSERT` statements generate nothing.
- `SqlcjCompilerIntegrationTest.shouldGenerateTheSameRepositoryFromAMyBatisScriptsDirectoryAndASnapshot`
  generates a byte-identical, compiling repository from a MyBatis Migrations
  scripts directory holding the changelog script, two timestamp-named scripts
  with undo sections, and a comment-only `bootstrap.sql`, and from a snapshot of
  the schema those scripts state.
- `DefaultSchemaParserTest.shouldEndASchemaSourceAtTheMyBatisUndoMarker`,
  `DefaultSchemaParserTest.shouldEndASchemaSourceAtEachLineMyBatisReadsAsItsUndoMarker`,
  `DefaultSchemaParserTest.shouldApplyTheStatementsAfterALineThatIsNotTheUndoMarker`,
  `DefaultSchemaParserTest.shouldEndACarriageReturnSchemaSourceAtTheUndoMarker`,
  `DefaultSchemaParserTest.shouldNotRejectAnUnreadableStatementAfterTheUndoMarker`,
  and
  `DefaultSchemaParserTest.shouldRejectAnUnreadableStatementBeforeTheUndoMarkerAtItsOwnLine`
  cover the undo section a `DROP TABLE` stands in, each marker and non-marker
  line MyBatis's rule decides, a `\r\n` script, the unreadable statement after
  the marker that is not reported, and the one before it that still is.
- `DefaultSchemaParserTest.shouldApplyNothingOfTheMyBatisChangelogScript` leaves
  the schema model unchanged for the whole changelog script, and
  `DefaultSchemaParserTest.shouldRejectEveryOtherStatementThatNamesAVariable`
  covers the rejected `ADD COLUMN`, the mixed action list, `${schema}.audit`,
  `audit_${suffix}`, and `DROP TABLE ${changelog}`.

Type table:

- `DefaultSchemaParserTest.shouldKeepParsingDeliveredColumnTypes`,
  `DefaultSchemaParserTest.shouldParseAddedColumnTypes`, and
  `DefaultSchemaParserTest.shouldParsePostgresTypeSpellings` cover the accepted
  spellings, their case-insensitive forms, and the ignored type arguments.
- `DefaultSchemaParserTest.shouldParsePostgresTypeSpellings`,
  `DefaultSchemaParserTest.shouldModelOneDimensionalArrayColumns`, and
  `DefaultSchemaParserTest.shouldRecordUnsupportedColumnType` cover the
  `FLOAT` spellings of each precision boundary, their arrays through every
  statement that types a column, and the rejected `float(0)` and `float(54)`
  recorded with their own precision beside a `float(24)[][]` recorded without
  it; `QueryAnalyzerTest.shouldTypeAFloatCast` and
  `QueryAnalyzerTest.shouldRejectUnsupportedCastType` cover the cast and the
  cast placeholder of each mapped precision, scalar and array, and the
  `FLOAT(54)` placeholder diagnostic; and
  `PostgresIntegrationTest.shouldRoundTripFloatingPointBinaryAndTimeValues`
  executes a round trip of a `FLOAT(24)` and a `FLOAT` column, with values and
  nulls, against PostgreSQL 16.
- `PostgresIntegrationTest.shouldExecuteGeneratedOneQueryAgainstPostgres`,
  `PostgresIntegrationTest.shouldExecuteGeneratedWriteAgainstPostgres`,
  `PostgresIntegrationTest.shouldRoundTripSerialUuidAndTimestampWithTimeZoneValues`,
  `PostgresIntegrationTest.shouldRoundTripPostgresTypeSpellingValues`,
  `PostgresIntegrationTest.shouldRoundTripFloatingPointBinaryAndTimeValues`, and
  `PostgresIntegrationTest.shouldRoundTripJsonValues`
  execute the Java mappings, including the blank-padded `CHAR` values, the JSON
  text as written and as PostgreSQL renders it, and a `JSONB` equality
  predicate, against PostgreSQL 16.
- `DefaultSchemaParserTest.shouldModelPublicQualifiedCitextColumnTypes` covers
  the `public`-qualified and quoted `citext` spellings, their arrays, and the
  names that stay unmapped, and
  `PostgresIntegrationTest.shouldRoundTripCitextAndNetworkValues` executes a
  round trip of `citext` and of every network address type, scalar and array,
  with nulls, a case-insensitive equality, `LIKE`, and `= ANY`, and an `inet`
  ordering comparison, against PostgreSQL 16.
- `JavaCodeGeneratorTest.shouldWrapCitextAndNetworkArgumentsAndReadThemAsText`
  covers the generated `UntypedText` argument, the `SqlArray` argument of a
  `citext` array, the `getString` and `getList` reads, and compilation of the
  generated source.
- `SqlIntervalTest` covers every `postgres`-style text PostgreSQL 16 writes,
  the rejection of `sql_standard`, `iso_8601`, and `postgres_verbose` text, the
  text `format()` writes, and each `Period` and `Duration` conversion with its
  unrepresentable-component and overflow failures;
  `DefaultSchemaParserTest.shouldParsePostgresTypeSpellings`,
  `DefaultSchemaParserTest.shouldModelOneDimensionalArrayColumns`, and
  `DefaultSchemaParserTest.shouldRecordUnsupportedColumnType` cover the
  `INTERVAL` fields and precision spellings, the array through every statement
  that types a column, and `interval[][]` staying unmapped;
  `JavaCodeGeneratorTest.shouldWrapIntervalArgumentsAndReadThemThroughSqlInterval`
  covers the generated binding, the `SqlInterval` import, the parsing reads, and
  compilation of the generated source; and
  `PostgresIntegrationTest.shouldRoundTripIntervalValues` executes a round trip
  of a value holding months, days, and a time part, a negative value, an array
  holding a null element, a null scalar, a write under session
  `IntervalStyle sql_standard`, and a `now() - :age::interval` filter, against
  PostgreSQL 16.
- `JavaCodeGeneratorTest.shouldWrapJsonArgumentsAndReadJsonColumnsAsText` and
  `JavaCodeGeneratorTest.shouldGenerateCompilableJavaSourceForJsonTypes` cover
  the generated `UntypedText` argument at every binding position of a JSON
  placeholder, the `getString` read, the unchanged binding and reading of the
  other `String` types, and compilation of the generated source.
- `JdbcQueryExecutorTest.shouldBindUntypedTextWithoutADeclaredSqlType` and
  `JdbcQueryExecutorTest.shouldBindNullUntypedTextWithoutADeclaredSqlType`
  cover the runtime binding of a null and a non-null `UntypedText` beside an
  ordinary argument.
- `DefaultSchemaParserTest.shouldModelColumnsOfADeclaredEnumType` covers the
  enum column of a `CREATE TABLE`, an added column, and a changed column type,
  and `JavaCodeGeneratorTest.shouldBindEnumLabelsAndReadEnumColumnsByLabel`
  covers the generated enum parameter, its `UntypedText` argument at every
  binding position, and the `fromLabel` read.
- `DefaultSchemaParserTest.shouldRecordUnsupportedColumnType` and
  `DefaultSchemaParserTest.shouldParseNullabilityOfUnsupportedColumnTypes` cover
  the recorded type of an unmapped spelling and of an array, beside the mapped
  columns of the same table.
- `QueryAnalyzerTest.shouldRejectQueryThatUsesAnUnsupportedTypeColumn` covers
  each reading and binding query,
  `QueryAnalyzerTest.shouldRejectWildcardThatExpandsAnUnsupportedTypeColumn`
  covers each expanding wildcard and its own message, and
  `QueryAnalyzerTest.shouldAnalyzeQueryBesideAnUnsupportedTypeColumn` and
  `QueryAnalyzerTest.shouldAnalyzeIsNullOnAnUnsupportedTypeColumn` cover the
  queries over the same table that still compile.
- `SqlcjCompilerIntegrationTest.shouldReportTheQueryThatReadsAnUnsupportedTypeColumn`
  covers the query diagnostic and that no file is written, and
  `SqlcjCompilerIntegrationTest.shouldGenerateCompilableRepositoryBesideUnsupportedTypeColumns`
  compiles a repository generated beside a `JSONB[]` and an `XML` column.

Array types:

- `DefaultSchemaParserTest.shouldModelOneDimensionalArrayColumns` covers every
  element spelling through `CREATE TABLE`, `ADD COLUMN`, and
  `ALTER COLUMN ... TYPE`, including the ignored declared size, and
  `DefaultSchemaParserTest.shouldCarryTheArrayShapeOfARenamedAndRetypedColumn`
  covers the rename, the nullability change, and the two retypes;
  `DefaultSchemaParserTest.shouldModelTheBlankPaddedSpellingOfCharacterArrayColumns`
  covers the `CHAR` and `CHARACTER` spellings that carry `bpchar` and the
  varying spellings that do not, through the same statements.
- `DefaultSchemaParserTest.shouldRecordUnsupportedColumnType` covers the
  multidimensional arrays and the `BYTEA`, `JSON`, `JSONB`, and `XML` arrays
  that stay recorded.
- `QueryAnalyzerTest.shouldCarryTheArrayShapeOfSelectedColumnsAndTheirParameters`,
  `QueryAnalyzerTest.shouldExpandTheArrayColumnsOfAWildcard`, and
  `QueryAnalyzerTest.shouldCarryTheArrayShapeOfAReturningWrite` cover the
  analyzed positions, and
  `QueryAnalyzerTest.shouldRejectARepeatedIndexAcrossAnArrayAndItsElement` and
  `QueryAnalyzerTest.shouldRejectLikeOnAnArrayColumn` cover the rejections.
  `QueryAnalyzerTest.shouldCarryTheBlankPaddedSpellingOfAnArrayParameter` covers
  the spelling a character array parameter carries.
  `QueryAnalyzerTest.shouldResolveListParameterOfAnyFromItsComparedColumn` and
  `QueryAnalyzerTest.shouldRejectAListParameterOfAColumnWithoutAnArrayBinding`
  cover the list a `= ANY` placeholder binds and the columns it rejects, and
  `PostgresIntegrationTest.shouldExecuteGeneratedListPredicateAgainstPostgres`
  executes it.
- `JavaCodeGeneratorTest.shouldBindAndReadArrayColumnsPerElementType` covers the
  generated `List` type, the `SqlArray` argument, and the `getList` read of
  every element type;
  `JavaCodeGeneratorTest.shouldBindEnumArrayLabelsAndReadEnumArrayColumnsByLabel`
  covers the enum array at every binding position of its index and the enum file
  an array element alone generates; and
  `JavaCodeGeneratorTest.shouldImportListForAnArrayRowComponent` covers the row
  record's imports. The enum array test and the row-component test compile the
  generated source. `JavaCodeGeneratorTest.shouldBindBlankPaddedCharacterArraysAsBpchar`
  covers the `bpchar` argument of a `CHAR` array and the `varchar` argument of a
  `VARCHAR` array.
- `SqlArrayTest.shouldReadStringArrayElementsAsText` covers the `getString`
  read of a `String` element, over element rows that reject a read as a Java
  class the way a driver rejects one of a type it maps to no class.
- `JdbcQueryExecutorTest.shouldBindSqlArrayAsAServerArray` and
  `JdbcQueryExecutorTest.shouldBindNullSqlArrayElementsAsANullArray` cover the
  recorded `createArrayOf` name and elements, the `setArray` position, and the
  `setNull` of a null list, and `SqlArrayTest` covers the converted elements and
  the null, empty, and element-null lists a read returns.
- `PostgresIntegrationTest.shouldRoundTripArrayValues` writes and reads a
  non-empty list holding a `null` element, an empty list, and a `null` list for
  every mapped element type and an enum type against PostgreSQL 16, comparing
  the `TIMESTAMPTZ` elements by instant and the `CHAR(3)` elements with the
  blank-padded values PostgreSQL stores, and finds the row again through a
  placeholder equality on the `CHAR(3)` array column.

Enum types:

- `DefaultSchemaParserTest.shouldAddEnumTypeWithItsDeclaredLabels`,
  `DefaultSchemaParserTest.shouldAddEnumLabelInItsStatedPosition`,
  `DefaultSchemaParserTest.shouldIgnoreAddValueIfNotExistsForAnExistingLabel`,
  and `DefaultSchemaParserTest.shouldApplyAnAddedLabelToAnEarlierEnumColumn`
  cover the applied forms and the label order they leave, and
  `DefaultSchemaParserTest.shouldReportTheEnumStatementItCannotApply` covers
  each diagnostic.
- `DefaultSchemaParserTest.shouldKeepTheEnumTypeOfARenamedAndRetypedColumn`
  covers the enum type a rename and a nullability change carry along, and
  `DefaultSchemaParserTest.shouldModelEnumArraysAndRecordUndeclaredTypesAsUnsupported`
  covers the modeled enum array beside the multidimensional enum array and the
  undeclared type that stay recorded.
- `DefaultSchemaParserTest.shouldRejectUnsupportedStatementsInSqlTerms` covers
  the rejected `ALTER TYPE` actions,
  `DefaultSchemaParserTest.shouldIgnoreEveryStatementItDoesNotModel` and
  `DefaultSchemaParserTest.shouldRecordAColumnOfAnIgnoredCompositeType` cover
  the ignored composite, range, and shell `CREATE TYPE` and the column a
  composite type leaves recorded, and
  `DefaultSchemaParserTest.shouldIgnoreDropTypeAndKeepTheModeledEnum` covers the
  ignored `DROP TYPE` and the enum and enum column that survive it.
- `DefaultSchemaParserTest.shouldModelAPublicQualifiedTypeNameAsItsUnqualifiedName`
  covers the unqualified, `public`-qualified, quoted, and upper-case qualifier
  forms of a `CREATE TYPE` that replaces a modeled enum and of `ADD VALUE`,
  `RENAME VALUE`, and `RENAME TO`;
  `DefaultSchemaParserTest.shouldIgnoreATypeStatementOfAnotherSchema` covers
  every `CREATE TYPE` and `ALTER TYPE` of another schema that is ignored,
  including the repeated label and the missing neighbour that are never
  resolved; and
  `DefaultSchemaParserTest.shouldIgnoreSetSchemaPublicOfAModeledEnum` covers
  `SET SCHEMA public` of a modeled enum.
- `DefaultSchemaParserTest.shouldModelPublicQualifiedEnumColumnTypes` covers the
  `public`-qualified enum column and enum array, quoted and unquoted, through
  `CREATE TABLE`, `ADD COLUMN`, and `ALTER COLUMN ... TYPE`, beside the
  multidimensional and other-schema types that stay recorded, and
  `QueryAnalyzerTest.shouldResolveAPublicQualifiedEnumCastType` covers the
  `public`-qualified enum array cast and the rejected cast type of another
  schema.
- `QueryAnalyzerTest.shouldCarryTheEnumTypeOfASelectedColumnAndItsParameter`,
  `QueryAnalyzerTest.shouldCarryTheEnumTypeOfAReturningColumnAndItsParameter`,
  `QueryAnalyzerTest.shouldAcceptARepeatedIndexOfOneEnumType`, and
  `QueryAnalyzerTest.shouldRejectARepeatedIndexAcrossConflictingEnumTypes`
  cover the analyzed enum type and the repeated-index rejection.
- `JavaCodeGeneratorTest.shouldGenerateCompilableEnumWithLabelLookups` compiles
  the generated enum and executes `label`, `fromLabel`, its null, and its
  unknown label;
  `JavaCodeGeneratorTest.shouldGenerateOnlyTheEnumTypesTheQueriesUse`,
  `JavaCodeGeneratorTest.shouldNameTheGeneratedEnumAndItsConstants`, and the
  four generator rejection tests cover the naming rules and the collisions.
- `SqlcjCompilerIntegrationTest.shouldGenerateOneSharedEnumForTwoEntries`,
  `SqlcjCompilerIntegrationTest.shouldDeleteTheStaleEnumOfARemovedEnumQuery`,
  and
  `SqlcjCompilerIntegrationTest.shouldReportTwoEntriesThatDefineOneEnumTypeDifferently`
  cover the shared file, the manifest, the stale deletion, and the cross-entry
  diagnostic that writes nothing.
- `PostgresIntegrationTest.shouldRoundTripEnumValues` runs the ordered DDL
  against PostgreSQL 16, compares the generated constants with
  `pg_enum` in `enumsortorder`, and round trips a null and a non-null value as
  an `INSERT` value, an equality predicate, and a result component.

Domain types:

- `DefaultSchemaParserTest.shouldModelDomainsOverTheTypesTheyAreDeclaredOver`
  covers a domain over an integer with a check, over `citext`, over
  `public.citext`, over `text[]`, over an enum type, over another domain, over
  the blank-padded `CHAR`, and over the unmapped `money`, under an unqualified,
  a `public`-qualified, and a quoted name, beside the domain of another schema
  that is ignored, and
  `DefaultSchemaParserTest.shouldReplaceAModeledDomainByCreateDomainInItsPosition`
  covers the replaced domain.
- `DefaultSchemaParserTest.shouldModelColumnsOfADeclaredDomain` covers the
  domain column through `CREATE TABLE`, `ADD COLUMN`, and
  `ALTER COLUMN ... TYPE`, including the array of a domain, the domain over an
  unmapped type, and the other-schema name that stay recorded;
  `DefaultSchemaParserTest.shouldModelAColumnOfADomainOverAnEnumType` covers
  the enum column a domain over an enum type leaves; and
  `DefaultSchemaParserTest.shouldKeepTheOwnNullabilityOfAColumnOfANotNullDomain`
  covers the nullability of a column of a `NOT NULL` domain with and without
  its own `NOT NULL`.
- `DefaultSchemaParserTest.shouldModelTheUnreadableCreateDomainFormsOfADump`
  covers both `pg_dump` forms sqlcj's parser cannot read,
  `DefaultSchemaParserTest.shouldModelAnUnreadableCreateDomainOverAnUnreadableBaseType`
  covers the name-only fallback, and
  `DefaultSchemaParserTest.shouldIgnoreAnUnreadableCreateDomainOfAnotherSchema`
  covers the unreadable domain of another schema.
- `DefaultSchemaParserTest.shouldRenameAModeledDomainThroughAlterDomainAndAlterType`
  covers both renames and the column declared after them,
  `DefaultSchemaParserTest.shouldRemoveTheModeledDomainARenameTakesTheNameOf`
  covers the domain a rename removes,
  `DefaultSchemaParserTest.shouldRenameARenamedEnumTypeInTheDomainsOverIt`
  covers the enum rename that reaches the domains over it, and
  `DefaultSchemaParserTest.shouldResolveTheLastDeclaredTypeOfOneName` covers
  the enum and domain name reuse in both orders.
- `DefaultSchemaParserTest.shouldDropTheModeledDomainsADropDomainNames` and
  `DefaultSchemaParserTest.shouldDropAModeledDomainNamedAnyAcceptedWay` cover
  `DROP DOMAIN` with `IF EXISTS`, several names, `CASCADE`, and `RESTRICT`, the
  skipped names, the unchanged column, and the column declared afterwards;
  `DefaultSchemaParserTest.shouldRejectADropDomainItsWordsDoNotState` covers
  the diagnostic of a `DROP DOMAIN` its words cannot classify; and
  `DefaultSchemaParserTest.shouldIgnoreTheDomainStatementsThatCannotChangeAModeledDomain`
  covers every ignored `ALTER DOMAIN` action, readable and unreadable, and the
  renames of a domain sqlcj does not model.
- `QueryAnalyzerTest.shouldTypeAPlaceholderOfADomainColumn` covers the
  comparison, `IN`, `LIKE`, and `= ANY` placeholders a column of a domain over
  `citext` types, `QueryAnalyzerTest.shouldTypeACastToADomain` covers the cast
  to a domain over `citext` and over an integer,
  `QueryAnalyzerTest.shouldRejectACastToADomainItDoesNotMap` covers the
  rejected array, unmapped-base, and other-schema cast types, and
  `QueryAnalyzerTest.shouldRejectQueryThatReadsAColumnOfADomainOverAnUnmappedType`
  covers the recorded column a query reads.
- `PostgresIntegrationTest.shouldRoundTripDomainValues` runs the domain DDL,
  the `pg_dump` operator-check form included, against PostgreSQL 16 and
  executes the generated code: a domain over `citext` compared
  case-insensitively by `=` and by `= ANY`, a domain over an integer with a
  check, a domain over an enum type written and read back as the generated
  enum, a domain over `text[]` holding a null element, and a `null` read
  through a left join from a column of a `NOT NULL` domain.
- `ReferenceServiceIntegrationTest.shouldGenerateIdenticalRepositoriesFromHistorySnapshotAndDump`
  generates one byte-identical tree from the migration history, the
  hand-written snapshot, and the dump of the reference service, whose `email`
  column is a column of a domain over `citext`.
- `ReferenceServiceIntegrationTest.shouldExecuteTheGeneratedReferenceServiceTypes`
  executes the reference service's uncast writes of its `email_address` domain
  and its `citext` handle, the lookups that match either of them in any case
  while reading back the stored spelling, its `inet` login address, and its
  retention `interval`, which holds months, days, and a time part and reads
  back as `null` once it is cleared, against PostgreSQL 16 through the
  generated repository.

`CREATE TABLE` table:

- `DefaultSchemaParserTest.shouldParseTableConstraints`,
  `DefaultSchemaParserTest.shouldParseTableWithoutConstraints`,
  `DefaultSchemaParserTest.shouldCanonicalizeQuotedTableAndColumnNames`,
  `DefaultSchemaParserTest.shouldParseColumnSpecificationsThatDoNotAffectTypes`,
  `DefaultSchemaParserTest.shouldIgnoreForeignKeyAndCheckTableConstraints`,
  `DefaultSchemaParserTest.shouldIgnoreExcludeTableConstraints`, and
  `DefaultSchemaParserTest.shouldParseNamedTableConstraintsAlongsideIgnoredOnes`
  cover the modeled and ignored constructs, including a named and an unnamed
  `EXCLUDE` beside a recorded `PRIMARY KEY`.
- `DefaultSchemaParserTest.shouldRejectUnsupportedSchemaStatement` and
  `DefaultSchemaParserTest.shouldThrowSchemaParseExceptionForInvalidSql` cover
  the rejected input.
- `DefaultSchemaParserTest.shouldCopyTheColumnsOfALikeSource` covers a `LIKE`
  alone with its options and a `public`-qualified source,
  `DefaultSchemaParserTest.shouldModelTheColumnsOfAnElementListInWrittenOrder`
  covers a `LIKE` written before and after a column definition and two `LIKE`
  clauses, and
  `DefaultSchemaParserTest.shouldRecordOnlyTheOwnConstraintsOfALikeTable`
  covers the source constraints that are not copied beside the copy's own.
- `DefaultSchemaParserTest.shouldCopyTheColumnsOfAPartitionParent` covers the
  range, list, hash, and `DEFAULT` bounds and the `public`-qualified parent,
  `DefaultSchemaParserTest.shouldRecordTheTableConstraintsOfAPartition` covers
  the constraint list a `PARTITION OF` may state, and
  `DefaultSchemaParserTest.shouldModelAnEmptyElementListAsATableWithoutColumns`
  covers `()`.
- `DefaultSchemaParserTest.shouldReportARepeatedColumnNameOfALikeSource`,
  `DefaultSchemaParserTest.shouldReportTheMissingSourceOfACreatedTable`,
  `DefaultSchemaParserTest.shouldRejectTheCreateTableClausesWhoseColumnsItCannotDetermine`,
  and `DefaultSchemaParserTest.shouldRejectASourceOrPartitionOfAnotherSchema`
  cover the repeated column name, the missing source, each rejected clause at
  the line of its own statement, and the `LIKE` and `PARTITION OF` source
  qualified with another schema; and
  `DefaultSchemaParserTest.shouldIgnoreAPartitionOfAnotherSchema` covers the
  `ATTACH` and `DETACH PARTITION` partition qualified that way.
- `SqlcjCompilerIntegrationTest.shouldGenerateCompilableRepositoriesOverCopiedTableColumns`
  generates and compiles `SELECT *` row records and a repository over a `LIKE`
  table and a `PARTITION OF` table.
- `PostgresIntegrationTest.shouldExecuteGeneratedQueryForSnapshotWithIgnoredTableConstraints`
  proves that such a snapshot is valid PostgreSQL DDL and compiles and executes.

Ordered table DDL:

- `DefaultSchemaParserTest.shouldComposeTheSchemaOfSeveralParsedSources`,
  `DefaultSchemaParserTest.shouldAppendAddedColumnsTypedLikeCreateTableColumns`,
  `DefaultSchemaParserTest.shouldRecordTheColumnConstraintsOfAnAddedColumn`,
  `DefaultSchemaParserTest.shouldDropColumnAndTheConstraintsThatListIt`,
  `DefaultSchemaParserTest.shouldRenameColumnInItsPositionAndInItsConstraints`,
  `DefaultSchemaParserTest.shouldRenameTableInItsPosition`,
  `DefaultSchemaParserTest.shouldChangeColumnTypeInItsPositionKeepingItsNullability`,
  `DefaultSchemaParserTest.shouldChangeARecordedColumnTypeBackToAMappedType`,
  `DefaultSchemaParserTest.shouldSetAndDropColumnNullability`,
  `DefaultSchemaParserTest.shouldApplyTheActionsOfOneAlterTableInOrder`,
  `DefaultSchemaParserTest.shouldDropEveryTableOfOneDropStatement`, and
  `DefaultSchemaParserTest.shouldMatchTableAndColumnNamesCaseInsensitively`
  cover the applied forms.
- `DefaultSchemaParserTest.shouldIgnoreCreateTableIfNotExistsForAnExistingTable`,
  `DefaultSchemaParserTest.shouldIgnoreAddColumnIfNotExistsForAnExistingColumn`,
  `DefaultSchemaParserTest.shouldIgnoreDropTableIfExistsForAMissingTable`,
  `DefaultSchemaParserTest.shouldIgnoreAlterTableIfExistsForAMissingTable`,
  `DefaultSchemaParserTest.shouldIgnoreDropColumnIfExistsForAMissingColumn`, and
  `DefaultSchemaParserTest.shouldReportTheMissingColumnOfAnAlterTableIfExists`
  cover the `IF [NOT] EXISTS` variants.
- `DefaultSchemaParserTest.shouldApplyEveryColumnActionOfAParentToItsPartition`
  and `DefaultSchemaParserTest.shouldApplyAColumnActionToANestedPartition`
  cover each propagated column action and a nested partition;
  `DefaultSchemaParserTest.shouldKeepThePartitionLinkThroughARenameOfTheParent`
  and `DefaultSchemaParserTest.shouldDropThePartitionsOfADroppedParent` cover
  the parent's rename and `DROP TABLE`, and
  `DefaultSchemaParserTest.shouldDropAParentNamedBeforeItsPartition` covers the
  one `DROP TABLE` that names a parent before one of its partitions;
  `DefaultSchemaParserTest.shouldStopPropagationAfterDetachPartition` and
  `DefaultSchemaParserTest.shouldStartPropagationAfterAttachPartition` cover the
  ended and the started link; and
  `DefaultSchemaParserTest.shouldNotFollowTheSourceOfALikeCopy` covers the
  `LIKE` copy that stays independent of its source.
- `DefaultSchemaParserTest.shouldReportTheMissingTableOfAStatement`,
  `DefaultSchemaParserTest.shouldReportTheMissingColumnOfAnAlterTableAction`,
  `DefaultSchemaParserTest.shouldReportAStatementThatRepeatsATableName`,
  `DefaultSchemaParserTest.shouldReportAStatementThatRepeatsAColumnName`, and
  `DefaultSchemaParserTest.shouldReportTheLineOfARejectedAlterTableAction`
  cover the rejected statements and their messages.
- `DefaultSchemaParserTest.shouldModelAPublicQualifiedTableNameAsItsUnqualifiedName`
  covers the unqualified, `public`-qualified, quoted, and upper-case qualifier
  forms of one `CREATE TABLE`, `ALTER TABLE`, and `DROP TABLE`;
  `DefaultSchemaParserTest.shouldIgnoreAStatementAboutATableOfAnotherSchema`
  covers every ignored statement of another schema, including the clauses,
  actions, and sources it never resolves; and
  `DefaultSchemaParserTest.shouldSkipTheDroppedNamesOfAnotherSchema` covers the
  `DROP TABLE` that carries on past such a name.
- `DefaultSchemaParserTest.shouldIgnoreSetSchemaPublicOfAModeledTable`,
  `DefaultSchemaParserTest.shouldRemoveATableMovedToAnotherSchema`, and
  `DefaultSchemaParserTest.shouldRejectSetSchemaOfATableWithModeledPartitions`
  cover the `SET SCHEMA` no-op, the removed table, and the rejected action and
  its line for a table with modeled partitions, and
  `DefaultSchemaParserTest.shouldRejectUnsupportedStatementsInSqlTerms` covers
  `SET SCHEMA` written beside another action.
- `SqlcjCompilerIntegrationTest.shouldGenerateTheSameRepositoryFromAlteringMigrationsAndASnapshot`
  generates one compilable repository from migrations that rename, add, drop, and
  alter columns, byte-identical to the one generated from the equivalent
  snapshot, and
  `SqlcjCompilerIntegrationTest.shouldReportTheMigrationFileThatAltersAMissingTable`
  covers the file-naming diagnostic of a missing reference and that no file is
  written.

Ignored statements:

- `DefaultSchemaParserTest.shouldIgnoreEveryStatementItDoesNotModel` covers one
  spelling per statement kind JSqlParser parses, including the lower-case
  `ALTER INDEX` form, a dollar-quoted `CREATE OR REPLACE FUNCTION` and a
  dollar-quoted `CREATE PROCEDURE`, the views, schemas, session, privilege,
  role, policy, non-rename `ALTER DOMAIN`, and non-enum `CREATE TYPE`
  statements, and the opaque
  `ALTER FUNCTION`, `ALTER SCHEMA`, `CREATE AGGREGATE`, and `CREATE CAST`; and
  `DefaultSchemaParserTest.shouldIgnoreConstraintAlterTableActions` covers the
  named and unnamed constraint actions.
- `DefaultSchemaParserTest.shouldIgnoreTheAlterTableActionsThatCannotChangeAColumn`
  covers one spelling per ignored `ALTER TABLE` action, including the lower-case
  and `ALTER TABLE ONLY public.users` spellings, the unqualified and
  `public`-qualified `ATTACH` and `DETACH PARTITION` of a partition sqlcj does
  not model, and two actions classified by their words in one statement.
- `DefaultSchemaParserTest.shouldNotResolveAnIgnoredStatementAgainstTheSchema`
  covers that an ignored statement, including a view over an unmodeled table and
  a `TRUNCATE` of one, may name an unmodeled table or column;
  `DefaultSchemaParserTest.shouldReportTheMissingTableOfAnIgnoredConstraintAction`
  and
  `DefaultSchemaParserTest.shouldReportTheMissingTableOfAnIgnoredAction`
  cover that an ignored `ALTER TABLE` action still resolves its table and that
  `IF EXISTS` skips it; and
  `DefaultSchemaParserTest.shouldIgnoreAnAlterColumnActionOnAMissingColumn`
  covers that it does not resolve its column.
- `DefaultSchemaParserTest.shouldApplyAModeledActionBesideAnIgnoredConstraintAction`,
  `DefaultSchemaParserTest.shouldApplyAnAddedColumnBesideIgnoredActions`, and
  `DefaultSchemaParserTest.shouldApplyAnAddedColumnAfterARowLevelSecurityAction`
  cover an ignored action beside a modeled one, in both written orders.
- `DefaultSchemaParserTest.shouldIgnoreEveryUnreadableStatementThatIsNotTableOrTypeDdl`
  covers one spelling per statement kind sqlcj's parser cannot read, including
  the untagged, tagged, and `LANGUAGE` forms of `DO`, `DROP TYPE`, every
  `COMMENT ON` target the parser does not read, `SET search_path TO a, b`,
  `DROP POLICY`, `VACUUM`, `LOCK TABLE`, `BEGIN`, `COPY ... TO`,
  `CREATE RULE`, `CREATE EVENT TRIGGER`, and the single-quoted, `RETURN`, and
  tagged function and procedure bodies.
- `DefaultSchemaParserTest.shouldIgnoreTheUnreadableAlterTableActionsThatCannotChangeAColumn`
  and
  `DefaultSchemaParserTest.shouldIgnoreThePgDumpIdentityAlterTableAction` cover
  every ignored unreadable action, alone and two in one statement, on a table
  the schema does not model, including the multi-line `pg_dump` identity form.
- `DefaultSchemaParserTest.shouldIgnoreAnUnreadableStatementQualifiedWithAnotherSchema`
  covers the ignored unreadable `CREATE` and `ALTER TABLE` and `CREATE` and
  `ALTER TYPE` of another schema, including the `ONLY` and `IF [NOT] EXISTS`
  spellings, and
  `DefaultSchemaParserTest.shouldRejectAnUnreadableStatementOfTheModeledSchema`
  covers the same statements still rejected unqualified and
  `public`-qualified.
- `DefaultSchemaParserTest.shouldRejectUnsupportedStatementsInSqlTerms` covers
  the exact message and line of each statement and action rejected in SQL terms,
  including `SET SCHEMA` after a modeled action, a modeled action written after
  an ignored one, an unrecognized `ALTER COLUMN` action, and the upper- and
  lower-case `ALTER FOREIGN TABLE`, and
  `DefaultSchemaParserTest.shouldRejectUnsupportedSchemaStatement`,
  `DefaultSchemaParserTest.shouldReportTheLineOfARejectedStatementAfterCommentsAndAFunctionBody`,
  and
  `DefaultSchemaParserTest.shouldReportTheLineOfARejectedStatementAfterUnreadableOnesAndATaggedBody`
  cover the reported line after comments that contain a statement separator,
  after a dollar-quoted body, and after a blanked meta-command line and
  unreadable statements.
- `DefaultSchemaParserTest.shouldRejectAnUnreadableAlterColumnActionAtItsFileLineAndColumn`,
  `DefaultSchemaParserTest.shouldRejectAnUnreadableAlterTableActionBesideAnIgnoredOne`,
  and
  `DefaultSchemaParserTest.shouldRejectAnUnreadableCreateTypeAtItsFileLineAndColumn`
  cover the parser's reason at the file line and column of `SET DATA TYPE`, of
  `SET DATA TYPE` beside an ignored action in a statement that begins in the
  middle of a line, and of an unreadable `CREATE TYPE`.
- `DefaultSchemaParserTest.shouldApplyTheStatementsAroundUnreadableOnesAndNotModelDdlInsideABody`
  covers the statements applied before and after unreadable ones and the DDL of
  an untagged, a tagged, and a glued `$fn$BEGIN ... END$fn$` body that is not
  modeled;
  `DefaultSchemaParserTest.shouldApplyTheStatementsAfterAFunctionBodyThatIsNotDollarQuoted`
  covers the statements after a single-quoted body.
- `DefaultSchemaParserTest.shouldSkipPsqlMetaCommandLines` covers a schema source
  with `\restrict` and `\unrestrict` lines, and
  `DefaultSchemaParserTest.shouldRejectAnUnterminatedDollarQuotedBody` covers the
  located failure of a tagged body that is never closed.
- `DefaultSchemaParserTest.shouldLoadTheMigrationFilesInOrder` covers a migration
  history that states `COMMENT ON TYPE` beside the targets the parser reads,
  loaded unchanged.
- `SqlcjCompilerIntegrationTest.shouldReportTheMigrationFileAndLineOfAnUnsupportedStatement`
  covers the migration file, the SQL-term message, and the line of a rejected
  statement that follows an ignored index and an ignored view, and that no file
  is written.

Nulls:

- `PostgresIntegrationTest.shouldBindAndReadNullValuesThroughGeneratedCode`
  and `PostgresIntegrationTest.shouldRoundTripFloatingPointBinaryAndTimeValues`
  bind null arguments and read null results through generated code against
  PostgreSQL.
- `PostgresIntegrationTest.shouldEnforceResultCardinalitiesAgainstPostgres`
  proves that row absence is reported by `:optional` and `:one` rather than by a
  null result.
- `PostgresIntegrationTest.shouldExecuteGeneratedMergedJoinColumnsAgainstPostgres`
  proves that a `FULL JOIN ... USING` reads the merged column of an unmatched
  row on each side as the value the present side holds, and
  `QueryAnalyzerTest.shouldResolveMergedJoinColumnNullabilityOfEachJoinKind`
  and `QueryAnalyzerTest.shouldNullExtendMergedColumnsOfALaterOuterJoin` cover
  the modeled nullability of a merged column.
- `DefaultSchemaParserTest.shouldParseColumns`,
  `DefaultSchemaParserTest.shouldParseNullabilityOfAddedColumnTypes`,
  `DefaultSchemaParserTest.shouldParseNullabilityOfIntegerAliasColumns`, and
  `DefaultSchemaParserTest.shouldParseSerialColumnAsNotNullable` cover parsed
  nullability.

Connection ownership and transactions:

- `JdbcQueryExecutorTest` covers both construction paths for `queryOne`,
  `queryOptional`, `queryMany`, and `execute`, including
  `shouldCloseAcquiredConnectionForEachDataSourceOperation`,
  `shouldCloseAcquiredConnectionWhenDataSourceOperationFails`,
  `shouldCloseAcquiredConnectionWhenCardinalityFails`,
  `shouldLeaveCallerOwnedConnectionOpenAndItsTransactionStateUnchanged`,
  `shouldCloseStatementsAndResultSetsOfCallerOwnedConnection`,
  `shouldCloseStatementWhenExecutionFailsOnCallerOwnedConnection`,
  `shouldCloseStatementWhenCardinalityFailsOnCallerOwnedConnection`, and
  `shouldWrapSqlExceptionForCallerOwnedConnection`.
- `JdbcQueryExecutorTest` covers `executeBatch` on both construction paths: the
  counts of each row in batch order in
  `shouldReturnPerRowCountsForABatchInsert`,
  `shouldReturnPerRowCountsForABatchUpdate`, and
  `shouldReturnPerRowCountsForABatchDelete`, the last two including a row that
  affected none; the argument binding of each row in
  `shouldBindUntypedTextOfEachBatchRow` and
  `shouldBindSqlArrayOfEachBatchRow`; the batch that executes nothing in
  `shouldExecuteNothingForAnEmptyBatch` and `shouldRejectANullBatch`; the
  transaction handling of each connection in
  `shouldRollBackADataSourceBatchWhoseRowFails`, which also asserts the
  restored auto-commit, the closed connection and statement, and the
  `BatchUpdateException` cause,
  `shouldReleaseADataSourceConnectionWhenABatchFailsWithAnUncheckedException`,
  which asserts the same rollback, restore, and close for an unchecked failure
  rethrown untranslated,
  `shouldLeaveADataSourceConnectionAlreadyInATransactionUncommitted`, and
  `shouldLeaveCallerOwnedConnectionUnchangedForABatch`; and the failure
  translation of both paths in `shouldWrapSqlExceptionForExecuteBatch`.
- `PostgresIntegrationTest.shouldExecuteGeneratedBatchWritesOfThousandsOfRowsAgainstPostgres`
  runs a 5,000-row insert, update, and delete batch of a generated
  `:batchexec` method against PostgreSQL 16 and asserts the exact count of each
  row in batch order, `0` included for the one updated row that matched none;
  `PostgresIntegrationTest.shouldReturnTheDriverCountsOfRewrittenBatchedInsertsAgainstPostgres`
  asserts the `-2`, `-2`, `1` a three-row insert reports over a data source
  with `reWriteBatchedInserts=true`;
  `PostgresIntegrationTest.shouldLeaveNoRowWhenAGeneratedBatchRowFailsAgainstPostgres`
  asserts the `QueryExecutionException`, its `BatchUpdateException` cause with
  SQL state `23505`, and the empty table a duplicate key at row 2,000 of 3,000
  leaves;
  `PostgresIntegrationTest.shouldExecuteNothingWhenAGeneratedBatchHoldsANullRowAgainstPostgres`
  asserts the `NullPointerException` a `null` element raises before the
  executor runs, and the empty table; and
  `SpringTransactionIntegrationTest.shouldRollBackAGeneratedBatchWithTheDataSourceTransactionManager`
  rolls a batch back with the `DataSourceTransactionManager` transaction it ran
  in.
- `PostgresIntegrationTest.shouldCommitGeneratedOperationsOnCallerOwnedConnection`
  and
  `PostgresIntegrationTest.shouldRollBackGeneratedOperationsOnCallerOwnedConnection`
  run a generated affected-row write, a generated returning write, and a
  generated read on one caller-owned connection with auto-commit disabled, and
  prove the application's own `commit` and `rollback`.
- `JdbcTransactionsTest` covers the helper over H2: the committed value of
  `inTransaction` and of `useTransaction` in
  `shouldReturnTheCallbackValueAndCommit` and
  `shouldCommitACallbackThatReturnsNoValue`; the rolled-back and unchanged
  rethrow of a runtime exception, an error, a checked `SQLException` from
  hand-written JDBC, and a `QueryCardinalityException` in
  `shouldRollBackAndRethrowARuntimeException`,
  `shouldRollBackAndRethrowAnError`,
  `shouldRollBackAndRethrowACheckedSqlException`, and
  `shouldRollBackAndRethrowACardinalityFailure`; and the failure of every JDBC
  call the helper makes in `shouldFailWhenTheConnectionCannotBeObtained`,
  `shouldFailWhenAutoCommitCannotBeDisabled`, `shouldFailWhenTheCommitFails`,
  `shouldAttachARollbackFailureToTheCallbackFailure`,
  `shouldFailWhenAutoCommitCannotBeRestored`, and
  `shouldReportAFailureToCloseTheConnection`. Those tests assert, through a
  recording proxy, the `setAutoCommit`, `commit`, `rollback`, and `close` calls
  the helper made on the connection it obtained, so the auto-commit handling
  and the closing of every path are covered.
  `shouldNotTouchAutoCommitOfAConnectionThatAlreadyHasItDisabled` covers the
  connection the helper leaves configured as it was,
  `shouldRunANestedCallAsAnIndependentTransaction` covers the nested call that
  commits although the outer call rolls back, and
  `shouldRequireNoCatchOrThrowsForACallbackThatThrowsNothing` compiles a
  callback that throws nothing with neither a `catch` nor a `throws` clause.
- `PostgresIntegrationTest.shouldCommitGeneratedAndHandWrittenWritesThroughTheTransactionHelper`
  commits two generated writes and a hand-written `PreparedStatement` insert
  made on the connection the helper handed the callback, and
  `PostgresIntegrationTest.shouldRollBackGeneratedAndHandWrittenWritesWhenTheTransactionFails`
  finds none of them after a runtime exception, an error, and the cardinality
  failure of a generated `:one` query; both assert that the callback's
  connection is closed afterwards.
- `SpringTransactionIntegrationTest` covers the Spring-managed transaction over
  PostgreSQL 16, with a repository bound to a
  `TransactionAwareDataSourceProxy` and each transaction manager given the
  plain `DataSource` or the entity factory built on it:
  `shouldCommitGeneratedWritesWithTheDataSourceTransactionManager` and
  `shouldRollBackGeneratedWritesWithTheDataSourceTransactionManager` commit and
  roll back two generated writes of a `DataSourceTransactionManager`
  transaction, the first also finding neither write through a plain
  `DataSource` repository while the transaction is open;
  `shouldLeaveAPlainDataSourceRepositoryOutsideTheTransaction` keeps the write
  made through a plain `DataSource` repository after that transaction rolls
  back and removes the write made through the proxy;
  `shouldCommitAJpaWriteAndAGeneratedWriteInOneTransaction` and
  `shouldRollBackAJpaWriteAndAGeneratedWriteInOneTransaction` commit and roll
  back a `persist` of a JPA entity beside a generated write in one
  `JpaTransactionManager` transaction; and
  `shouldSeeAPendingJpaWriteOnlyAfterAFlush` covers the deferred `persist` a
  generated read finds only after `EntityManager.flush()`.
