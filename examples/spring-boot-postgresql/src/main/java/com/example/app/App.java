package com.example.app;

import com.example.app.db.AuthorRepository;
import com.example.app.db.AuthorsRow;
import com.example.app.db.BookRepository;
import com.example.app.db.BooksRow;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;
import java.util.Objects;

/**
 * Runs the sqlcj workflow against PostgreSQL inside a Spring Boot application
 * that configures nothing of sqlcj's at all.
 *
 * <p>The application depends on the sqlcj Spring Boot starter and the
 * PostgreSQL driver, and its only configuration is the data source Spring Boot
 * reads from {@code application.properties}. There is no {@code @Bean} for an
 * executor or for a repository and no {@code @Import}: the starter
 * auto-configures one executor over the application's {@code DataSource} and
 * registers the two generated repositories of {@code com.example.app.db},
 * which sits under this {@code @SpringBootApplication} package, as beans built
 * from it. {@link LibraryService} therefore receives
 * {@link AuthorRepository} and {@link BookRepository} by constructor
 * injection, and this class takes all three from the context.
 *
 * <p>Three steps are checked, in order:
 *
 * <ul>
 *   <li>a write made through a repository bean outside any transaction is
 *       stored;</li>
 *   <li>the {@code @Transactional} method that returns normally stores both
 *       the author and the book it writes, so the counts of both repositories
 *       grow and the author's book is readable;</li>
 *   <li>the {@code @Transactional} method that throws lets its
 *       {@link RuntimeException} reach the caller and leaves both counts and
 *       the list of authors exactly as they were, so Spring rolled both
 *       generated writes back.</li>
 * </ul>
 *
 * <p>The counts come from the {@code :one} {@code CountAuthors} and
 * {@code CountBooks} queries, whose single non-null column makes each return
 * the {@code Long} count itself rather than a record, while the queries that
 * return a complete row return the generated {@link AuthorsRow} and
 * {@link BooksRow} records of the two tables.
 *
 * <p>Every step is checked, so the process exits non-zero as soon as one
 * generated operation returns an unexpected result or the application fails to
 * start.
 *
 * <p>The verification harness applies {@code sql/migrations} to empty
 * {@code authors} and {@code books} tables before this application runs.
 */
@SpringBootApplication
public class App {

    public static void main(String[] args) {
        try (ConfigurableApplicationContext context = SpringApplication.run(App.class, args)) {
            AuthorRepository authors = context.getBean(AuthorRepository.class);
            BookRepository books = context.getBean(BookRepository.class);
            LibraryService library = context.getBean(LibraryService.class);

            AuthorsRow outside = authors.createAuthor("Ada Lovelace", "First programmer");

            check(outside != null, "CreateAuthor returned no row outside a transaction");
            check(outside.id() != null, "CreateAuthor returned no database-generated id");
            checkEquals("Ada Lovelace", outside.name(), "CreateAuthor name");
            checkEquals("First programmer", outside.bio(), "CreateAuthor bio");
            checkEquals(1L, authors.countAuthors(), "CountAuthors after the untransacted write");
            checkEquals(0L, books.countBooks(), "CountBooks after the untransacted write");

            System.out.println("stored without a transaction: " + outside.id() + " " + outside.name());

            String committedTitle = "The Education of a Computer";

            AuthorsRow committed = library.createAuthorWithBook(
                "Grace Hopper",
                "Compiler pioneer",
                committedTitle
            );

            check(committed != null, "the committed transaction returned no author");
            check(committed.id() != null, "the committed author has no database-generated id");
            checkEquals("Grace Hopper", committed.name(), "committed name");
            checkEquals(2L, authors.countAuthors(), "CountAuthors after the committed transaction");
            checkEquals(1L, books.countBooks(), "CountBooks after the committed transaction");

            List<BooksRow> committedBooks = books.listAuthorBooks(committed.id());

            checkEquals(1, committedBooks.size(), "ListAuthorBooks row count for the committed author");
            checkEquals(committed.id(), committedBooks.get(0).authorId(), "committed book author id");
            checkEquals(committedTitle, committedBooks.get(0).title(), "committed book title");

            System.out.println("committed: " + committed.name() + " / " + committedBooks.get(0).title());

            Long authorsBeforeFailure = authors.countAuthors();
            Long booksBeforeFailure = books.countBooks();

            String rolledBackMessage = null;

            try {
                library.createAuthorWithBookAndFail("Temporary Author", "never stored", "Never Stored");
            } catch (RuntimeException e) {
                rolledBackMessage = e.getMessage();
            }

            check(
                rolledBackMessage != null,
                "the failing transaction must let its exception reach the caller"
            );

            checkEquals(
                authorsBeforeFailure,
                authors.countAuthors(),
                "CountAuthors after the rolled back transaction"
            );
            checkEquals(
                booksBeforeFailure,
                books.countBooks(),
                "CountBooks after the rolled back transaction"
            );

            List<AuthorsRow> remaining = authors.listAuthors();

            checkEquals(2, remaining.size(), "ListAuthors row count after the rolled back transaction");
            checkEquals(outside.id(), remaining.get(0).id(), "first remaining author id");
            checkEquals(committed.id(), remaining.get(1).id(), "second remaining author id");

            System.out.println(
                "rolled back: " + rolledBackMessage + ", " + remaining.size()
                    + " authors and " + books.countBooks() + " books remain"
            );

            System.out.println("sqlcj spring boot sample verification passed");
        }
    }

    private static void checkEquals(Object expected, Object actual, String description) {
        check(
            Objects.equals(expected, actual),
            description + ": expected " + expected + " but was " + actual
        );
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
