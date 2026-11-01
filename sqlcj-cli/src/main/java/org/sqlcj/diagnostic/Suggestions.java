package org.sqlcj.diagnostic;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The one rule by which a diagnostic answers a name the compiler does not know
 * with the name the user most likely meant.
 *
 * <p>The query parser, the schema parser, and semantic analysis each name
 * candidates of their own and none of them owns the others' names, so the rule
 * itself lives here.
 */
public final class Suggestions {

    /** The largest edit distance a candidate may stand at. */
    private static final int MAXIMUM_DISTANCE = 2;

    private Suggestions() {
    }

    /**
     * The candidate closest to {@code written}: the one at the smallest
     * Levenshtein distance — the number of single-character insertions,
     * deletions, and substitutions, without transpositions — comparing both
     * names lower-cased.
     *
     * <p>A candidate is close enough when that distance is at most {@code 2}
     * and smaller than the length of the written name, so a short name is not
     * answered with an unrelated one. A tie goes to the first candidate given,
     * which makes the suggestion deterministic, and the candidate is returned
     * exactly as given. No candidate is close enough for an empty result.
     */
    public static Optional<String> closest(String written, List<String> candidates) {
        String lowered = written.toLowerCase(Locale.ROOT);

        String closest = null;
        int smallest = Integer.MAX_VALUE;

        for (String candidate : candidates) {
            int distance = distance(lowered, candidate.toLowerCase(Locale.ROOT));

            if (distance <= MAXIMUM_DISTANCE && distance < written.length() && distance < smallest) {
                closest = candidate;
                smallest = distance;
            }
        }

        return Optional.ofNullable(closest);
    }

    /**
     * The Levenshtein distance between two names, computed over one row of the
     * distance matrix at a time, because only the previous row is needed.
     */
    private static int distance(String written, String candidate) {
        int[] previous = new int[candidate.length() + 1];
        int[] current = new int[candidate.length() + 1];

        for (int j = 0; j <= candidate.length(); j++) {
            previous[j] = j;
        }

        for (int i = 1; i <= written.length(); i++) {
            current[0] = i;

            for (int j = 1; j <= candidate.length(); j++) {
                int substitution = written.charAt(i - 1) == candidate.charAt(j - 1)
                    ? previous[j - 1]
                    : previous[j - 1] + 1;

                current[j] = Math.min(
                    substitution,
                    Math.min(previous[j] + 1, current[j - 1] + 1)
                );
            }

            int[] swapped = previous;

            previous = current;
            current = swapped;
        }

        return previous[candidate.length()];
    }
}
