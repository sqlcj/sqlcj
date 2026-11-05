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
