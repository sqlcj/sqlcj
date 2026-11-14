package org.sqlcj.generator;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The names one generated file writes and the single-type imports they need, so
 * that the file reads as the Java a developer writes: every type it names
 * outside {@code java.lang} is imported and written by its simple name.
 *
 * <p>Each file is rendered twice against a scope of its own. The first pass,
 * over a {@link #collecting} scope, finds the generated row records and enums
 * the file names, which are the only types that can take a simple name from
 * another type the file writes. The second pass, over the scope
 * {@link #resolved()} returns, writes every name in its final form and collects
 * the imports the file ends up needing, so that a line is measured as it is
 * written. The scope is passed to every rendering method, so no pass can read a
 * name another pass resolved.
 */
final class NameScope {

    /**
     * The package Java resolves a simple name in without an import, and which
     * a type of the file's own package therefore shadows.
     */
    private static final String JAVA_LANG_PACKAGE = "java.lang";

    /** The prefix of the imports the second import group holds. */
    private static final String JDK_PREFIX = "java.";

    private static final String ARRAY_SUFFIX = "[]";

    /** The packages the generated row records and enums of the run belong to. */
    private final Set<String> generatedPackages;

    /** The simple names of the types the file declares. */
    private final Set<String> declaredNames;

    /**
     * The simple names of the generated types the file's own package holds,
     * which shadow a {@code java.lang} type of the same name.
     */
    private final Set<String> packageTypeNames;

    /** The simple names this pass writes a non-declared type qualified for. */
    private final Set<String> shadowedNames;

    /** The simple names of the generated types this pass named. */
    private final Set<String> generatedNames = new LinkedHashSet<>();

    /** The types this pass wrote by their simple name, sorted by Java name. */
    private final Set<String> imports = new TreeSet<>();

    private NameScope(
        Set<String> generatedPackages,
        Set<String> declaredNames,
        Set<String> packageTypeNames,
        Set<String> shadowedNames
    ) {
        this.generatedPackages = generatedPackages;
        this.declaredNames = declaredNames;
        this.packageTypeNames = packageTypeNames;
        this.shadowedNames = shadowedNames;
    }

    /**
     * The scope of the first pass over one file.
     *
     * @param generatedPackages the packages the generated row records and
     *                          enums of the run belong to
     * @param declaredNames     the simple names of the types the file declares,
     *                          a repository's nested records included
     * @param packageTypeNames  the simple names of the generated types the
     *                          file's own package holds
     */
    static NameScope collecting(
        Set<String> generatedPackages,
        Set<String> declaredNames,
        Set<String> packageTypeNames
    ) {
        return new NameScope(
            Set.copyOf(generatedPackages),
            Set.copyOf(declaredNames),
            Set.copyOf(packageTypeNames),
            Set.copyOf(declaredNames)
        );
    }

    /**
     * The scope of the second pass over the same file, where a simple name is
     * taken by a type the file declares or by a generated row record or enum
     * the first pass found the file names.
     */
    NameScope resolved() {
        Set<String> shadowed = new LinkedHashSet<>(declaredNames);

        shadowed.addAll(generatedNames);

        return new NameScope(generatedPackages, declaredNames, packageTypeNames, shadowed);
    }

    /**
     * Writes one type by the name this file names it with: its simple name,
     * imported from the package it belongs to, and its fully qualified name
     * where a type the file declares or a generated row record or enum the file
     * names already holds that simple name.
     *
     * <p>A generated row record or enum always keeps its simple name, so that a
     * type the application reads is never written in full. A {@code java.lang}
     * type is never imported, because Java resolves its simple name without
     * one, and is written in full where a generated type of the file's own
     * package shadows it as well. A primitive array type belongs to no package
     * and needs no import.
     */
    String type(String qualifiedName) {
        if (qualifiedName.endsWith(ARRAY_SUFFIX)) {
            return qualifiedName;
        }

        String simpleName = simpleName(qualifiedName);
        String packageName = packageName(qualifiedName);

        if (generatedPackages.contains(packageName)) {
            generatedNames.add(simpleName);
            imports.add(qualifiedName);

            return simpleName;
        }

        if (shadowedNames.contains(simpleName)) {
            return qualifiedName;
        }

        if (packageName.equals(JAVA_LANG_PACKAGE)) {
            return packageTypeNames.contains(simpleName)
                ? qualifiedName
                : simpleName;
        }

        imports.add(qualifiedName);

        return simpleName;
    }

    /** Writes one annotation by the name this file names its type with. */
    String annotation(String qualifiedName) {
        return "@" + type(qualifiedName);
    }

    /**
     * Writes one type carrying one type annotation, which sits directly before
     * the simple-name segment the annotation qualifies, as in
     * {@code @Nullable BigDecimal} and {@code java.math.@Nullable BigDecimal}.
     * An array type carries it before its brackets, as in
     * {@code byte @Nullable []}, which states that the array itself is
     * annotated rather than its elements.
     */
    String annotated(String qualifiedName, String annotation) {
        if (qualifiedName.endsWith(ARRAY_SUFFIX)) {
            return qualifiedName.substring(0, qualifiedName.length() - ARRAY_SUFFIX.length())
                + " "
                + annotation
                + " "
                + ARRAY_SUFFIX;
        }

        String name = type(qualifiedName);
        int lastDot = name.lastIndexOf('.');

        return lastDot < 0
            ? annotation + " " + name
            : name.substring(0, lastDot + 1) + annotation + " " + name.substring(lastDot + 1);
    }

    /**
     * The import block of the file, which holds one single-type import per type
     * the file writes by its simple name, in two groups separated by one blank
     * line: every import outside {@code java.} first and then the {@code java.}
     * imports, each sorted by qualified name. An empty group is omitted, and a
     * file that names no imported type writes no block at all.
     */
    String imports() {
        return Stream.of(
            importGroup(name -> !name.startsWith(JDK_PREFIX)),
            importGroup(name -> name.startsWith(JDK_PREFIX))
        )
            .filter(group -> !group.isEmpty())
            .collect(Collectors.joining("\n\n"));
    }

    private String importGroup(Predicate<String> group) {
        return imports.stream()
            .filter(group)
            .map(name -> "import " + name + ";")
            .collect(Collectors.joining("\n"));
    }

    private static String simpleName(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');

        return lastDot < 0
            ? qualifiedName
            : qualifiedName.substring(lastDot + 1);
    }

    private static String packageName(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');

        return lastDot < 0
            ? ""
            : qualifiedName.substring(0, lastDot);
    }
}
