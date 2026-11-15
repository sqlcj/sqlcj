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
        NewsletterRepository.Page page = NewsletterRepository.Page.builder()
            .size(pageSize)
            .offset((pageNumber - 1) * pageSize)
            .build();

        return newsletter.listArticlePage(page);
    }
}
