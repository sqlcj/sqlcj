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
