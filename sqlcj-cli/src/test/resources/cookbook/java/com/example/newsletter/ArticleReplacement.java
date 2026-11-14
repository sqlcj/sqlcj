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
