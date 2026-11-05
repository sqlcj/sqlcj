-- Cookbook fixture: the named SQL of the newsletter publisher's recipes.

-- name: ListArticlePage :many
SELECT *
FROM articles
ORDER BY published_at DESC, id DESC
LIMIT :pageSize OFFSET :pageOffset;

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

-- name: UpsertSubscriber :one
INSERT INTO subscribers (email, name)
VALUES (:email, :name)
ON CONFLICT (email) DO UPDATE
    SET name = EXCLUDED.name
RETURNING *;

-- name: RecordArticleViews :batchexec
INSERT INTO article_views (article_id, viewed_at)
VALUES (:articleId, :viewedAt);

-- name: ListArticlesByIds :many
SELECT *
FROM articles
WHERE id = ANY (:ids)
ORDER BY id;

-- name: CreateArticle :one
INSERT INTO articles (slug, title, status, published_at)
VALUES (:slug, :title, :status, :publishedAt)
RETURNING *;

-- name: SetArticleStatus :exec
UPDATE articles
SET status = :status
WHERE id = :id;
