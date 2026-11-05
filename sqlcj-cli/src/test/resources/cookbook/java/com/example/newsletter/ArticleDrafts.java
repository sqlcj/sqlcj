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
