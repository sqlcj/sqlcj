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
