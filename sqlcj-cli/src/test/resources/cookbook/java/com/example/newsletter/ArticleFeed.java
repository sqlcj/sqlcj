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
