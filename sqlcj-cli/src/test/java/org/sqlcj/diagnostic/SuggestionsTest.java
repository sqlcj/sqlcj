package org.sqlcj.diagnostic;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SuggestionsTest {

    @Test
    void shouldSuggestCandidateThatDiffersOnlyInCase() {
        assertEquals(
            Optional.of(":many"),
            Suggestions.closest(":MANY", List.of(":one", ":many"))
        );
    }

    @Test
    void shouldSuggestCandidateOneEditAway() {
        assertEquals(
            Optional.of("authors"),
            Suggestions.closest("author", List.of("books", "authors"))
        );
    }

    @Test
    void shouldSuggestCandidateTwoEditsAway() {
        assertEquals(
            Optional.of("title"),
            Suggestions.closest("titel", List.of("title"))
        );
    }

    @Test
    void shouldSuggestNoCandidateThreeEditsAway() {
        assertEquals(
            Optional.empty(),
            Suggestions.closest("autor", List.of("authored"))
        );
    }

    @Test
    void shouldSuggestNoCandidateAtLeastAsFarAsTheWrittenNameIsLong() {
        assertEquals(
            Optional.empty(),
            Suggestions.closest("ox", List.of("ab"))
        );
    }

    @Test
    void shouldSuggestTheSmallestDistanceWhateverTheCandidateOrder() {
        assertEquals(
            Optional.of("author"),
            Suggestions.closest("autor", List.of("authors", "author"))
        );

        assertEquals(
            Optional.of("author"),
            Suggestions.closest("autor", List.of("author", "authors"))
        );
    }

    @Test
    void shouldSuggestTheFirstCandidateOfATie() {
        assertEquals(
            Optional.of("mane"),
            Suggestions.closest("nmae", List.of("mane", "name"))
        );

        assertEquals(
            Optional.of("name"),
            Suggestions.closest("nmae", List.of("name", "mane"))
        );
    }

    @Test
    void shouldSuggestNothingWithoutCandidates() {
        assertEquals(
            Optional.empty(),
            Suggestions.closest("authors", List.of())
        );
    }
}
