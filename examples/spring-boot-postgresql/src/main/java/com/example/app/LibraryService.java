package com.example.app;

import com.example.app.db.AuthorRepository;
import com.example.app.db.AuthorsRow;
import com.example.app.db.BookRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one author and that author's book through the two generated
 * repositories inside one Spring-managed transaction.
 *
 * <p>Both repositories arrive by constructor injection. The application
 * declares no sqlcj bean of its own: the starter registers every generated
 * repository of the application's packages as a bean built from the one
 * executor it auto-configures over the application's {@code DataSource}, so a
 * generated repository is injected like any other bean.
 *
 * <p>That executor runs its statements on a transaction-aware view of the
 * {@code DataSource}, so both repositories take part in the transaction Spring
 * Boot's transaction manager opens for a {@code @Transactional} method. The
 * first method commits both writes on a normal return; the second makes the
 * same two writes and then throws a {@link RuntimeException}, which rolls both
 * of them back.
 *
 * <p>The class and its methods are public and non-final so that the container
 * can proxy them.
 */
@Service
public class LibraryService {

    private final AuthorRepository authors;

    private final BookRepository books;

    public LibraryService(AuthorRepository authors, BookRepository books) {
        this.authors = authors;
        this.books = books;
    }

    /**
     * Writes one author and one book of that author, and returns the new
     * author's row. The transaction commits on this normal return, so both
     * rows must be readable afterwards.
     */
    @Transactional
    public AuthorsRow createAuthorWithBook(String name, String bio, String title) {
        AuthorsRow author = authors.createAuthor(name, bio);

        books.createBook(author.id(), title);

        return author;
    }

    /**
     * Makes the same two writes and then fails, so the transaction is rolled
     * back and neither row must be readable afterwards. The exception reaches
     * the caller unchanged.
     */
    @Transactional
    public void createAuthorWithBookAndFail(String name, String bio, String title) {
        AuthorsRow author = authors.createAuthor(name, bio);

        books.createBook(author.id(), title);

        throw new RuntimeException("the sample rolls this transaction back");
    }
}
