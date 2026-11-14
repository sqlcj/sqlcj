# Cookbook

This document holds one recipe for each everyday task of an application that
reads and writes PostgreSQL through a generated sqlcj repository: paging a feed
by offset and by key, searching by criteria a caller may leave out, upserting,
writing a batch, reading the rows of a list of ids, committing two writes as
one transaction in plain Java and in Spring, reporting a duplicate key instead
of raising it, and retrying a transaction the database refused to run
concurrently.

Every block below is a copy rather than an illustration. Each SQL block and
each recipe's Java is copied from the cookbook fixture of this repository,
which `CookbookIntegrationTest` generates, compiles, and runs against
PostgreSQL 16; each generated record and each generated method declaration is
copied from the tree sqlcj generates from that fixture, a declaration written
with a `;` in place of its Javadoc and body. [Verified by](#verified-by) names
the test of each recipe and what it asserts.

Related documents:

- [Quickstart](quickstart.md) builds a project from an empty directory: the
  artifacts, `sqlcj.yaml`, the migrations, generation, and the runtime
  dependency every recipe here assumes.
- [Queries](queries.md) owns the rules the recipes follow: the annotations, the
  accepted statement shapes, parameter order and binding, and the generated
  API.
- [PostgreSQL Support](postgresql.md) owns the column types, the null contract,
  and the connection and transaction contract the recipes execute on.

## The Fixture

Every recipe reads and writes one small newsletter schema: its articles, its
subscribers, and the recorded views of its articles.

```sql
-- Cookbook fixture: a small newsletter publisher, its subscribers, and the
-- views of its articles. The cookbook's recipes read and write these tables.

CREATE TYPE article_status AS ENUM ('draft', 'published', 'archived');

CREATE TABLE articles
(
    id           bigint GENERATED ALWAYS AS IDENTITY NOT NULL PRIMARY KEY,
    slug         text                                NOT NULL UNIQUE,
    title        text                                NOT NULL,
    status       article_status                      NOT NULL,
    published_at timestamptz                         NOT NULL
);

CREATE TABLE subscribers
(
    id    bigint GENERATED ALWAYS AS IDENTITY NOT NULL PRIMARY KEY,
    email text                                NOT NULL UNIQUE,
    name  text                                NOT NULL
);

CREATE TABLE article_views
(
    article_id bigint      NOT NULL REFERENCES articles (id),
    viewed_at  timestamptz NOT NULL
);
```

The query group is named `Newsletter` and the generated package is
`com.example.newsletter`, so the group's queries become the methods of one
`com.example.newsletter.repositories.NewsletterRepository`; the complete
`articles` and `subscribers` rows its queries return become the records
`ArticlesRow` and `SubscribersRow` of `com.example.newsletter.rows`; and the
`article_status` enum type becomes `ArticleStatus` of
`com.example.newsletter.enums`, whose constants `DRAFT`, `PUBLISHED`, and
`ARCHIVED` carry the labels `draft`, `published`, and `archived`. Every recipe
below is written in `com.example.newsletter` itself and imports what it uses
from those three generated subpackages.

The recipes read the components of the two row records, which generate as:

```java
@org.jspecify.annotations.NullMarked
public record ArticlesRow(
    Long id,
    String slug,
    String title,
    ArticleStatus status,
    OffsetDateTime publishedAt
) {
}
```

```java
@org.jspecify.annotations.NullMarked
public record SubscribersRow(
    Long id,
    String email,
    String name
) {
}
```

## Offset Pagination

A page addressed by a page number: the query orders the whole feed and skips
the pages before the one asked for, so the recipe turns a page number into the
offset its query skips. `LIMIT` and `OFFSET` each bind one `Integer` parameter
named after its own placeholder, as [Pagination](queries.md#pagination) states.

The order must be total for consecutive pages to partition the feed.
`published_at` alone repeats among the fixture's articles, so `id` breaks its
ties and no article is returned twice or skipped.

```sql
-- name: ListArticlePage :many
SELECT *
FROM articles
ORDER BY published_at DESC, id DESC
LIMIT :pageSize OFFSET :pageOffset;
```

```java
public List<ArticlesRow> listArticlePage(Integer pageSize, Integer pageOffset);
```

```java
package com.example.newsletter;

import com.example.newsletter.repositories.NewsletterRepository;
import com.example.newsletter.rows.ArticlesRow;

import java.util.List;

/**
 * Offset pagination: one page of the newsletter's articles, newest first,
 * addressed by a page number.
 */
public final class ArticlePages {

    /**
     * The articles of one page, where {@code pageNumber} counts from 1. The
     * page number becomes the offset the query skips.
     */
    public List<ArticlesRow> page(NewsletterRepository newsletter, int pageNumber, int pageSize) {
        return newsletter.listArticlePage(pageSize, (pageNumber - 1) * pageSize);
    }
}
```

## Keyset Pagination

The same feed continued from the key of the last row of the page before rather
than from a row count: the first page needs no key, and each later page
compares the row `(published_at, id)` against that key, so the database never
walks over the rows it has already returned. The cost of an offset page grows
with its offset, while the cost of a keyset page does not.

Both casts in the row comparison are required. A row comparison is not a
comparison between a direct column and a placeholder, so neither placeholder is
typed by the column written beside it, and each parameter states its own type:
`:publishedAt::timestamptz` and `:id::bigint`. Without them the query is
rejected with `Cannot infer the type of :publishedAt`.

```sql
-- name: ListLatestArticles :many
SELECT *
FROM articles
ORDER BY published_at DESC, id DESC
LIMIT :pageSize;

-- name: ListArticlesBefore :many
SELECT *
FROM articles
WHERE (published_at, id) < (:publishedAt::timestamptz, :id::bigint)
ORDER BY published_at DESC, id DESC
LIMIT :pageSize;
```

```java
public List<ArticlesRow> listLatestArticles(Integer pageSize);

public List<ArticlesRow> listArticlesBefore(
    OffsetDateTime publishedAt,
    Long id,
    Integer pageSize
);
```

```java
package com.example.newsletter;

import com.example.newsletter.repositories.NewsletterRepository;
import com.example.newsletter.rows.ArticlesRow;

import java.util.List;

/**
 * Keyset pagination: the same order as the offset page, continued from the key
 * of the last row of the page before rather than from a row count, so the
 * database never walks over the rows it has already returned.
 */
public final class ArticleFeed {

    /** The newest page, which needs no key. */
    public List<ArticlesRow> firstPage(NewsletterRepository newsletter, int pageSize) {
        return newsletter.listLatestArticles(pageSize);
    }

    /**
     * The page that follows {@code page}, read from the key of its last row. An
     * empty page is the end of the feed, so nothing follows it.
     */
    public List<ArticlesRow> nextPage(NewsletterRepository newsletter, List<ArticlesRow> page, int pageSize) {
        if (page.isEmpty()) {
            return List.of();
        }

        ArticlesRow last = page.get(page.size() - 1);

        return newsletter.listArticlesBefore(last.publishedAt(), last.id(), pageSize);
    }
}
```

## Criteria Search

One statement serves every combination of three criteria the caller may leave
out, because a null criterion switches itself off: `:search::text IS NULL`
holds when no search was given, so its `OR` matches every row, and the status
list and the published-since bound are written the same way. The query stays
one static SQL text whichever criteria a call supplies, so sqlcj assembles no
SQL at run time. The rules, including the one cast such a search needs and what
a reused query plan means for it, are in
[Criteria Queries](queries.md#criteria-queries).

The page and the number of matches are two queries that repeat the same
criteria: the `:many` read returns one page, and the `:one` count returns how
many articles the criteria match unpaged, which the recipe returns beside the
page.

```sql
-- name: SearchArticles :many
SELECT *
FROM articles
WHERE (:search::text IS NULL OR title ILIKE '%' || :search || '%')
  AND (:statuses IS NULL OR status = ANY (:statuses))
  AND (:publishedSince IS NULL OR published_at >= :publishedSince)
ORDER BY published_at DESC, id DESC
LIMIT :pageSize OFFSET :pageOffset;

-- name: CountSearchedArticles :one
SELECT COUNT(*) AS total
FROM articles
WHERE (:search::text IS NULL OR title ILIKE '%' || :search || '%')
  AND (:statuses IS NULL OR status = ANY (:statuses))
  AND (:publishedSince IS NULL OR published_at >= :publishedSince);
```

```java
public List<ArticlesRow> searchArticles(
    @org.jspecify.annotations.Nullable String search,
    @org.jspecify.annotations.Nullable List<@org.jspecify.annotations.Nullable ArticleStatus> statuses,
    @org.jspecify.annotations.Nullable OffsetDateTime publishedSince,
    Integer pageSize,
    Integer pageOffset
);

public Long countSearchedArticles(
    @org.jspecify.annotations.Nullable String search,
    @org.jspecify.annotations.Nullable List<@org.jspecify.annotations.Nullable ArticleStatus> statuses,
    @org.jspecify.annotations.Nullable OffsetDateTime publishedSince
);
```

```java
package com.example.newsletter;

import com.example.newsletter.enums.ArticleStatus;
import com.example.newsletter.repositories.NewsletterRepository;
import com.example.newsletter.rows.ArticlesRow;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A criteria search: one page of the articles that match the criteria the
 * caller supplies, beside the number of articles the same criteria match
 * unpaged. A null criterion is switched off, so one statement serves every
 * combination of them.
 */
public final class ArticleSearch {

    /** One page of matches and the number of matches the whole search has. */
    public record Result(List<ArticlesRow> articles, long total) {
    }

    /**
     * The matches of one page, where {@code pageNumber} counts from 1, beside
     * the total the count query reads with the same criteria.
     */
    public Result search(
        NewsletterRepository newsletter,
        String titleContains,
        List<ArticleStatus> statuses,
        OffsetDateTime publishedSince,
        int pageNumber,
        int pageSize
    ) {
        List<ArticlesRow> articles = newsletter.searchArticles(
            titleContains,
            statuses,
            publishedSince,
            pageSize,
            (pageNumber - 1) * pageSize
        );

        return new Result(articles, newsletter.countSearchedArticles(titleContains, statuses, publishedSince));
    }
}
```

## Upsert

One sign-up form that both stores a new subscriber and renames the subscriber
an address already names, in one statement: `ON CONFLICT (email) DO UPDATE`
assigns the proposed name from `EXCLUDED`, and `RETURNING *` returns the stored
row either way, so the caller needs neither a read before the write nor a
second write. The conflict target is a column list of the inserted table; the
accepted targets, actions, and assignments are in
[Upsert with `ON CONFLICT`](queries.md#upsert-with-on-conflict).

```sql
-- name: UpsertSubscriber :one
INSERT INTO subscribers (email, name)
VALUES (:email, :name)
ON CONFLICT (email) DO UPDATE
    SET name = EXCLUDED.name
RETURNING *;
```

```java
public SubscribersRow upsertSubscriber(String email, String name);
```

```java
package com.example.newsletter;

import com.example.newsletter.repositories.NewsletterRepository;
import com.example.newsletter.rows.SubscribersRow;

/**
 * An upsert: one sign-up form that both creates a subscriber and renames the
 * one an address already names, in one statement.
 */
public final class SubscriberSignup {

    /**
     * Signs {@code email} up, or renames the subscriber it already names, and
     * returns the stored subscriber either way.
     */
    public SubscribersRow subscribe(NewsletterRepository newsletter, String email, String name) {
        return newsletter.upsertSubscriber(email, name);
    }
}
```

## Batch Insert

One view row per article id, written as a single JDBC batch instead of one
statement execution per row. A `:batchexec` write generates the nested record
of its parameters, takes a list of that record, executes its statement once per
element in the order of the list, and returns the affected-row count of each
element in that order. An empty list executes nothing and returns an empty
array; see [Batch writes](queries.md#batch-writes).

```sql
-- name: RecordArticleViews :batchexec
INSERT INTO article_views (article_id, viewed_at)
VALUES (:articleId, :viewedAt);
```

```java
public record RecordArticleViewsParams(
    Long articleId,
    OffsetDateTime viewedAt
) {
}

public int[] recordArticleViews(List<RecordArticleViewsParams> batch);
```

```java
package com.example.newsletter;

import com.example.newsletter.repositories.NewsletterRepository;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A batch insert: one view row per article id, written as one JDBC batch
 * instead of one statement execution per row.
 */
public final class ArticleViewLog {

    /**
     * Records one view of each article id, in the order of the list, and
     * returns the affected row count of each. An empty list executes nothing
     * and returns an empty array.
     */
    public int[] recordViews(NewsletterRepository newsletter, List<Long> articleIds, OffsetDateTime viewedAt) {
        List<NewsletterRepository.RecordArticleViewsParams> batch = articleIds.stream()
            .map(articleId -> new NewsletterRepository.RecordArticleViewsParams(articleId, viewedAt))
            .toList();

        return newsletter.recordArticleViews(batch);
    }
}
```

## Reading a List of Ids

The articles of many ids read by one statement: `id = ANY (:ids)` takes one
list parameter, bound as one PostgreSQL array, rather than one placeholder per
id, so the number of ids changes no SQL text and prepares no second statement,
where an `IN` list of placeholders would need one statement per list length.
`= ANY` between a column and one list placeholder is the shape
[Predicates](queries.md#predicates) accepts for this.

An id no article has contributes no row, so the result may be shorter than the
list of ids, and the query orders by `id`, so the rows it does return have one
defined order.

```sql
-- name: ListArticlesByIds :many
SELECT *
FROM articles
WHERE id = ANY (:ids)
ORDER BY id;
```

```java
public List<ArticlesRow> listArticlesByIds(List<@org.jspecify.annotations.Nullable Long> ids);
```

```java
package com.example.newsletter;

import com.example.newsletter.repositories.NewsletterRepository;
import com.example.newsletter.rows.ArticlesRow;

import java.util.List;

/**
 * An id list: the articles of many ids read by one statement, whose list is
 * bound as one PostgreSQL array rather than as one placeholder per id.
 */
public final class ArticleLookup {

    /**
     * The articles the given ids name, in id order. An id no article has
     * contributes no row, so the result may be shorter than the list.
     */
    public List<ArticlesRow> byIds(NewsletterRepository newsletter, List<Long> ids) {
        return newsletter.listArticlesByIds(ids);
    }
}
```

## A Transaction in Plain Java

Replacing an article is two writes — archive the article being replaced, store
its published replacement — which must either both commit or neither happen.
`JdbcTransactions.inTransaction` opens one transaction, hands the unit of work
a `JdbcTransaction` carrying the application's executor bound to that
transaction's connection, commits the transaction when the work returns, and
rolls it back when the work fails. The unit of work binds its own repository
over `transaction.executor()`, so both writes take part in that transaction
through the executor the application uses everywhere else; a repository built
over the `DataSource` would take a connection of its own and commit by itself.
Connection ownership and the exception each refused statement raises are in
[Connection Ownership and Transactions](postgresql.md#connection-ownership-and-transactions).

A replacement whose slug another article already holds raises
`UniqueViolationException`, which rolls the archiving back too and reaches the
caller unchanged.

```sql
-- name: CreateArticle :one
INSERT INTO articles (slug, title, status, published_at)
VALUES (:slug, :title, :status, :publishedAt)
RETURNING *;

-- name: SetArticleStatus :exec
UPDATE articles
SET status = :status
WHERE id = :id;
```

```java
public ArticlesRow createArticle(
    String slug,
    String title,
    ArticleStatus status,
    OffsetDateTime publishedAt
);

public int setArticleStatus(ArticleStatus status, Long id);
```

```java
package com.example.newsletter;

import com.example.newsletter.enums.ArticleStatus;
import com.example.newsletter.repositories.NewsletterRepository;
import com.example.newsletter.rows.ArticlesRow;
import org.sqlcj.runtime.JdbcTransactions;

import java.time.OffsetDateTime;

/**
 * A transaction in plain Java: replacing an article archives the old one and
 * publishes its replacement, and the two writes either both happen or neither
 * does.
 *
 * <p>The unit of work runs in the one transaction {@link JdbcTransactions}
 * opens, and it binds its own repository over that transaction's executor, so
 * both writes take part in the same transaction. A normal return commits it;
 * any failure, such as the
 * {@link org.sqlcj.runtime.UniqueViolationException} of a replacement whose
 * slug is already taken, rolls the archiving back too and reaches the caller
 * unchanged.
 */
public final class ArticleReplacement {

    /**
     * Archives the article {@code articleId} names and returns the published
     * replacement it stored in the same transaction.
     */
    public ArticlesRow replace(
        JdbcTransactions transactions,
        long articleId,
        String slug,
        String title,
        OffsetDateTime publishedAt
    ) {
        return transactions.inTransaction(transaction -> {
            NewsletterRepository newsletter = new NewsletterRepository(transaction.executor());

            newsletter.setArticleStatus(ArticleStatus.ARCHIVED, articleId);

            return newsletter.createArticle(slug, title, ArticleStatus.PUBLISHED, publishedAt);
        });
    }
}
```

## A Transaction in Spring

The same two writes, as one `@Transactional` method over an injected generated
repository. The repository joins the transaction Spring opened because the
`QueryExecutor` it is built over runs on a `TransactionAwareDataSourceProxy` of
the application's `DataSource`, which the sqlcj Spring Boot starter configures,
so each generated write reaches the connection of the transaction it finds
itself in. The transaction commits when the method returns and is rolled back
by a failure such as the `UniqueViolationException` of a taken slug; see
[Transactions](spring.md#transactions).

The class and the method are public and non-final so that the container can
proxy them. The runtime's `JdbcTransactions` helper is not used here, because
it would open a second transaction of its own.

```sql
-- name: CreateArticle :one
INSERT INTO articles (slug, title, status, published_at)
VALUES (:slug, :title, :status, :publishedAt)
RETURNING *;

-- name: SetArticleStatus :exec
UPDATE articles
SET status = :status
WHERE id = :id;
```

```java
public ArticlesRow createArticle(
    String slug,
    String title,
    ArticleStatus status,
    OffsetDateTime publishedAt
);

public int setArticleStatus(ArticleStatus status, Long id);
```

```java
package com.example.newsletter;

import com.example.newsletter.enums.ArticleStatus;
import com.example.newsletter.repositories.NewsletterRepository;
import com.example.newsletter.rows.ArticlesRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * The same replacement as {@link ArticleReplacement}, in Spring: the two
 * writes of one {@code @Transactional} method, made through an injected
 * generated repository.
 *
 * <p>The repository takes no part by itself; it does so because the
 * {@code QueryExecutor} it is built over runs on a transaction-aware view of
 * the {@code DataSource}, such as Spring's
 * {@code TransactionAwareDataSourceProxy}, which the sqlcj Spring Boot starter
 * configures. The transaction then commits on a normal return and is rolled
 * back by any failure, such as the
 * {@link org.sqlcj.runtime.UniqueViolationException} of a replacement whose
 * slug is already taken.
 *
 * <p>The class and the method are public and non-final so that the container
 * can proxy them.
 */
@Service
public class ArticleReplacementService {

    private final NewsletterRepository newsletter;

    public ArticleReplacementService(NewsletterRepository newsletter) {
        this.newsletter = newsletter;
    }

    /**
     * Archives the article {@code articleId} names and returns the published
     * replacement it stored in the same transaction.
     */
    @Transactional
    public ArticlesRow replace(long articleId, String slug, String title, OffsetDateTime publishedAt) {
        newsletter.setArticleStatus(ArticleStatus.ARCHIVED, articleId);

        return newsletter.createArticle(slug, title, ArticleStatus.PUBLISHED, publishedAt);
    }
}
```

## A Duplicate Key

A slug the newsletter already uses is an answer a drafting form expects rather
than a failure, so this recipe catches the unique violation of the `slug`
column and returns an empty `Optional` instead of letting it reach its caller.

The write must not run inside a transaction that is meant to continue:
PostgreSQL aborts a transaction whose statement failed, so every later
statement of that transaction fails too and only a rollback ends it. Either run
the write outside a transaction, as this recipe does and as its test does, or
let the violation roll its transaction back, as
[A Transaction in Plain Java](#a-transaction-in-plain-java) does.

```sql
-- name: CreateArticle :one
INSERT INTO articles (slug, title, status, published_at)
VALUES (:slug, :title, :status, :publishedAt)
RETURNING *;
```

```java
public ArticlesRow createArticle(
    String slug,
    String title,
    ArticleStatus status,
    OffsetDateTime publishedAt
);
```

```java
package com.example.newsletter;

import com.example.newsletter.enums.ArticleStatus;
import com.example.newsletter.repositories.NewsletterRepository;
import com.example.newsletter.rows.ArticlesRow;
import org.sqlcj.runtime.UniqueViolationException;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * A duplicate key reported instead of thrown: drafting an article whose slug
 * the newsletter already uses is an answer the form expects, not a failure, so
 * the recipe catches the unique violation of the {@code slug} column and
 * returns nothing.
 *
 * <p>The write must not run inside a transaction that should continue:
 * PostgreSQL aborts a transaction whose statement failed, so every later
 * statement of it fails too. Either run the write outside a transaction, as
 * this recipe does, or let the violation roll its transaction back.
 */
public final class ArticleDrafts {

    /**
     * Stores a draft of {@code slug} and returns it, or returns an empty
     * {@link Optional} when an article already uses that slug.
     */
    public Optional<ArticlesRow> createDraft(
        NewsletterRepository newsletter,
        String slug,
        String title,
        OffsetDateTime publishedAt
    ) {
        try {
            return Optional.of(
                newsletter.createArticle(slug, title, ArticleStatus.DRAFT, publishedAt)
            );
        } catch (UniqueViolationException slugTaken) {
            return Optional.empty();
        }
    }
}
```

## Retrying a Transaction Conflict

A transaction the database could not run concurrently with another one is no
failure of the application's logic: the conflicting transaction is itself gone,
so running the unit of work again from its beginning usually succeeds. This
recipe wraps `JdbcTransactions.inTransaction` in a bounded loop that retries
`TransactionConflictException` alone, which is the one type a serialization
failure and a deadlock have, wherever PostgreSQL reports it, in a statement or
at the `COMMIT`. Every other failure reaches the caller at once, and so does
the conflict of the last allowed attempt.

Because the work is run again from its beginning, it must be safe to repeat: it
reads what it needs inside the transaction it is given and keeps no state of an
attempt that was rolled back. The `DataSource` the transactions are opened on
must also open `SERIALIZABLE` transactions, by its own configuration or by the
server's `default_transaction_isolation`, for PostgreSQL to report the
conflicts this loop retries.

The unit of work the test runs is one such repeatable one: inside its
transaction it reads the article with `listArticlesByIds` and archives it with
`setArticleStatus`, and a rival transaction that commits a new title of the
same row between the two makes that write raise the serialization failure the
loop retries.

```java
package com.example.newsletter;

import org.sqlcj.runtime.JdbcTransactions;
import org.sqlcj.runtime.TransactionCallback;
import org.sqlcj.runtime.TransactionConflictException;

/**
 * A bounded retry of a transaction the database could not run concurrently
 * with another one: the unit of work is run again from its beginning, because
 * the conflicting transaction itself is gone.
 *
 * <p>Only {@link TransactionConflictException} is retried, which is the one
 * type a serialization failure and a deadlock have, wherever PostgreSQL
 * reports it, in a statement or at the {@code COMMIT}. Every other failure
 * reaches the caller at once, since running the work again would fail the same
 * way.
 *
 * <p>The unit of work is run up to {@code maxAttempts} times, so it must be
 * safe to repeat: it reads what it needs inside the transaction it is given
 * and keeps no state of an attempt that was rolled back.
 *
 * <p>The {@code DataSource} the transactions are opened on must open
 * {@code SERIALIZABLE} transactions, by its own configuration or by the
 * server's {@code default_transaction_isolation}, for the database to report
 * the conflicts this loop retries.
 */
public final class RetryingTransactions {

    /**
     * Runs {@code work} in a transaction and returns its value, running it
     * again after a conflict until it succeeds or {@code maxAttempts} attempts
     * have been made, after which the conflict of the last attempt reaches the
     * caller.
     */
    public <T, X extends Exception> T inTransaction(
        JdbcTransactions transactions,
        int maxAttempts,
        TransactionCallback<T, X> work
    ) throws X {
        int attempt = 1;

        while (true) {
            try {
                return transactions.inTransaction(work);
            } catch (TransactionConflictException conflict) {
                if (attempt >= maxAttempts) {
                    throw conflict;
                }

                attempt++;
            }
        }
    }
}
```

## An Enum Label a Migration Added

A generated enum holds the labels its enum type declared when the code was
generated. A build older than the migration that added a label therefore cannot
represent that label, and a read that meets it raises
`UnmappableValueException` naming the repository, the query, the enum type, and
the label itself, rather than guessing a constant for a value the running code
does not know.

The order of a release avoids it: the build that knows the new label is
deployed before the migration that adds it, so no running build reads a label
it was not generated with. The generated enum is described in
[Enum Types](postgresql.md#enum-types), and the exception and the two values it
carries in
[Connection Ownership and Transactions](postgresql.md#connection-ownership-and-transactions).

## Verified by

`CookbookIntegrationTest` applies the fixture's migration to a PostgreSQL 16
container, generates the `Newsletter` group from the fixture's migration
directory and query file with no rejected query, compiles the generated tree
together with the ten recipes, and runs each recipe against a database seeded
with five articles. The class is skipped when Docker is unavailable, and
`-Dsqlcj.test.requireDocker=true` turns an unavailable Docker into a failure
instead of a skip.

- `CookbookIntegrationTest.shouldPageArticlesByOffset` reads three consecutive
  pages of size 2 and asserts that they return the five articles in the feed's
  order, that two consecutive pages share no article, and that the last page
  returns the one article that remains.
- `CookbookIntegrationTest.shouldPageArticlesByKeyset` follows the feed from
  its first page until a page comes back empty, and asserts that the pages
  together visit every article exactly once in the same order the offset pages
  have, the two articles of one `published_at` included, and that nothing
  follows an empty page.
- `CookbookIntegrationTest.shouldSearchArticlesByCriteria` asserts that null
  criteria match every article, that the search matches the titles that contain
  it ignoring case, that the status list and the published-since bound each
  narrow the result, and that the count of a page of 2 counts the unpaged
  matches.
- `CookbookIntegrationTest.shouldUpsertASubscriber` asserts that a new address
  stores a subscriber, and that signing the same address up again keeps that
  subscriber's id, replaces its name, and leaves one row.
- `CookbookIntegrationTest.shouldRecordArticleViewsInOneBatch` asserts the two
  affected-row counts of a batch of two ids, the two views it stored and their
  order, and that an empty list returns an empty array and stores nothing.
- `CookbookIntegrationTest.shouldListArticlesByIds` asserts that three ids, one
  of which no article has, return the two articles that exist, in id order.
- `CookbookIntegrationTest.shouldReplaceAnArticleInOneTransaction` and
  `CookbookIntegrationTest.shouldReplaceAnArticleInASpringTransaction` assert,
  for the plain and the Spring recipe alike, that a replacement whose slug is
  taken raises `UniqueViolationException` of SQL state `23505` and leaves the
  archiving undone with no replacement stored, and that a free slug commits
  both writes: the returned row is the published replacement, the article it
  replaces is archived, and the newsletter holds six articles. The Spring test
  runs the service in a context whose executor is built over a
  `TransactionAwareDataSourceProxy`, as the starter builds it.
- `CookbookIntegrationTest.shouldReportATakenSlugAsAnEmptyDraft` asserts that a
  free slug stores a draft of that title and status, that the taken slug is
  reported as `Optional.empty()` instead of raised, and that the second call
  stored nothing.
- `CookbookIntegrationTest.shouldRetryAConflictingTransactionUpToItsBound`
  asserts, over a data source that opens `SERIALIZABLE` transactions, that one
  rival commit with a bound of 3 runs the work exactly twice and archives the
  article under the title the rival committed, and that a rival on every
  attempt with a bound of 2 raises `TransactionConflictException` of SQL state
  `40001` after exactly 2 attempts, leaving the article published.
