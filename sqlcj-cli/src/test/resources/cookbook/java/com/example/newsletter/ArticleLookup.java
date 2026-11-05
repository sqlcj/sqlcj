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
