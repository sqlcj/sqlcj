package org.sqlcj.compiler;

import org.sqlcj.analysis.QueryAnalyzer;
import org.sqlcj.analysis.QueryGroupModel;
import org.sqlcj.analysis.QueryModel;
import org.sqlcj.config.Config;
import org.sqlcj.config.ConfigurationException;
import org.sqlcj.generator.CodeGenerator;
import org.sqlcj.generator.GeneratedFile;
import org.sqlcj.generator.JavaCodeGenerator;
import org.sqlcj.io.GeneratedFileWriter;
import org.sqlcj.io.GeneratedOutputManifest;
import org.sqlcj.parser.Query;
import org.sqlcj.parser.QueryRejection;
import org.sqlcj.schema.Schema;
import org.sqlcj.schema.parser.DefaultSchemaParser;
import org.sqlcj.schema.parser.SchemaParseException;
import org.sqlcj.schema.parser.SchemaParser;
import org.sqlcj.sql.ParsedSql;
import org.sqlcj.sql.SqlParseException;
import org.sqlcj.sql.SqlParser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

public final class SqlcjCompiler {

    /**
     * The end of every internal-error diagnostic, which reports a failure sqlcj
     * itself did not foresee and therefore describes no input.
     */
    private static final String INTERNAL_ERROR_SUFFIX = "; please report this sqlcj defect";

    private final SourceLoader sourceLoader = new DefaultSourceLoader();
    private final GeneratedFileWriter generatedFileWriter = new GeneratedFileWriter();
    private final GeneratedOutputManifest generatedOutputManifest = new GeneratedOutputManifest();

    private final SchemaParser schemaParser;

    /** Parses and analyzes one query of one group against that group's schema. */
    private final BiFunction<Query, Schema, QueryModel> queryAnalysis;

    /** The generator of one configured package, by that package's name. */
    private final Function<String, CodeGenerator> codeGenerators;

    public SqlcjCompiler() {
        this(
            new DefaultSchemaParser(),
            defaultQueryAnalysis(),
            JavaCodeGenerator::new
        );
    }

    SqlcjCompiler(
        SchemaParser schemaParser,
        BiFunction<Query, Schema, QueryModel> queryAnalysis,
        Function<String, CodeGenerator> codeGenerators
    ) {
        this.schemaParser = schemaParser;
        this.queryAnalysis = queryAnalysis;
        this.codeGenerators = codeGenerators;
    }

    /** Parses one query's SQL source and analyzes it against its schema. */
    private static BiFunction<Query, Schema, QueryModel> defaultQueryAnalysis() {
        SqlParser sqlParser = new SqlParser();
        QueryAnalyzer queryAnalyzer = new QueryAnalyzer();

        return (query, schema) -> {
            ParsedSql parsedSql = sqlParser.parse(query.sql());

            return queryAnalyzer.analyze(query, parsedSql, schema);
        };
    }

    /**
     * Compiles every configured entry before any generated file is written, so
     * a failure in a later source cannot leave a mixture of previously
     * generated and newly generated output.
     *
     * <p>The manifest of the previous run is read before the first write, and
     * the files it lists that this run did not produce are deleted before the
     * new manifest replaces it, so a failed write or cleanup keeps the previous
     * manifest for the next run.
     */
    public void compile(Config config) {
        List<Source> sources = sourceLoader.load(config);

        String packageName = config.java().packageName();

        CodeGenerator codeGenerator = codeGenerators.apply(packageName);

        List<GeneratedFile> files = generate(sources, codeGenerator, packageName);

        Path outputDirectory = Path.of(config.java().out());

        List<String> previousEntries = readManifest(outputDirectory);

        for (GeneratedFile file : files) {
            write(file, outputDirectory);
        }

        List<Path> generatedPaths = files.stream()
            .map(GeneratedFile::path)
            .toList();

        deleteStale(outputDirectory, previousEntries, generatedPaths);

        writeManifest(outputDirectory, generatedPaths);
    }

    /**
     * Generates one repository per configured query group, followed by the row
     * records and the enum types those groups share.
     *
     * <p>Every entry is analyzed before any is generated, so that one run
     * reports every rejected query of every entry, in configuration order and
     * then header-line order, and generates nothing when any query was
     * rejected. A schema failure ends the run where it stands, because the
     * queries of its own entry cannot be analyzed without its schema; the
     * rejections of the entries analyzed before it are reported first.
     */
    private List<GeneratedFile> generate(
        List<Source> sources,
        CodeGenerator codeGenerator,
        String packageName
    ) {
        List<String> diagnostics = new ArrayList<>();
        List<QueryGroupModel> groups = new ArrayList<>(sources.size());

        for (Source source : sources) {
            Schema schema;

            try {
                schema = parseSchema(source);
            } catch (CompilationException e) {
                if (diagnostics.isEmpty()) {
                    throw e;
                }

                diagnostics.addAll(e.diagnostics());

                throw new CompilationException(diagnostics);
            }

            groups.add(analyze(source, schema, diagnostics));
        }

        if (!diagnostics.isEmpty()) {
            throw new CompilationException(diagnostics);
        }

        List<GeneratedFile> files = new ArrayList<>();
        Map<String, GeneratedRepository> generatedRepositories = new HashMap<>();

        for (int index = 0; index < sources.size(); index++) {
            Source source = sources.get(index);

            GeneratedFile file = generateRepository(source, groups.get(index), codeGenerator);

            checkGeneratedPath(source, file, generatedRepositories);

            files.add(file);
        }

        files.addAll(generateShared(codeGenerator, packageName));

        return files;
    }

    /**
     * Generates the row records and the enum types the groups of one package
     * share, which belong to the package rather than to one group, so their own
     * diagnostics name the package and not a group.
     */
    private List<GeneratedFile> generateShared(CodeGenerator codeGenerator, String packageName) {
        try {
            List<GeneratedFile> shared = new ArrayList<>(codeGenerator.generateRows());

            shared.addAll(codeGenerator.generateEnums());

            return shared;
        } catch (RuntimeException e) {
            if (isDiagnostic(e)) {
                throw e;
            }

            throw internalError(
                "Internal error while generating the shared row records and enums of package '%s'"
                    .formatted(packageName),
                e
            );
        }
    }

    /**
     * Applies the schema files of one entry in order to that entry's schema, so
     * a table keeps the position of the file that declares it and a later file
     * alters what the earlier files left.
     */
    private Schema parseSchema(Source source) {
        Schema schema = new Schema(List.of());

        for (SchemaFile schemaFile : source.schemaFiles()) {
            schema = parseSchemaFile(schema, schemaFile);
        }

        return schema;
    }

    private Schema parseSchemaFile(Schema schema, SchemaFile schemaFile) {
        try {
            return schemaParser.parse(schema, schemaFile.sql());
        } catch (RuntimeException e) {
            if (!isDiagnostic(e)) {
                throw internalError(
                    "Internal error while parsing schema source %s".formatted(schemaFile.path()),
                    e
                );
            }

            int line = e instanceof SchemaParseException failure
                ? failure.line()
                : 0;

            if (line == 0) {
                throw new CompilationException(
                    "Invalid schema source %s: %s".formatted(schemaFile.path(), reason(e)),
                    e
                );
            }

            throw new CompilationException(
                located(schemaFile.path(), line, "Invalid schema source: " + reason(e)),
                e
            );
        }
    }

    /**
     * Analyzes every query of one group against that group's schema, collecting
     * the diagnostic of every query the parser or the analyzer rejected in
     * header-line order, so the rejections of a header and of an analyzed query
     * interleave as the source states them.
     *
     * <p>The returned group holds the queries that compiled, and is generated
     * only when no query of any entry was rejected.
     */
    private QueryGroupModel analyze(Source source, Schema schema, List<String> diagnostics) {
        List<QueryModel> queries = new ArrayList<>(source.queries().size());
        List<Rejection> rejections = new ArrayList<>();

        for (QueryRejection rejection : source.rejections()) {
            rejections.add(
                new Rejection(
                    rejection.line(),
                    located(
                        source.queriesPath(),
                        rejection.line(),
                        "Invalid query source: " + rejection.reason()
                    )
                )
            );
        }

        for (Query query : source.queries()) {
            try {
                queries.add(queryAnalysis.apply(query, schema));
            } catch (RuntimeException e) {
                rejections.add(new Rejection(query.line(), analysisRejection(source, query, e)));
            }
        }

        rejections.sort(Comparator.comparingInt(Rejection::line));

        for (Rejection rejection : rejections) {
            diagnostics.add(rejection.diagnostic());
        }

        return new QueryGroupModel(source.name(), List.copyOf(queries), schema.enums());
    }

    /**
     * The diagnostic of one rejected query, located at its header line, which
     * reports a parse or analysis failure in SQL terms and a failure sqlcj did
     * not foresee as that query's internal error.
     */
    private String analysisRejection(Source source, Query query, RuntimeException e) {
        if (!isDiagnostic(e)) {
            return located(
                source.queriesPath(),
                query.line(),
                "Internal error while analyzing query '%s'%s"
                    .formatted(query.name(), INTERNAL_ERROR_SUFFIX)
            );
        }

        return located(
            source.queriesPath(),
            query.line(),
            "Invalid query '%s': %s".formatted(query.name(), reason(e))
        );
    }

    /**
     * One diagnostic of a source line, which begins with the absolute path of
     * the source and the line, the form IDE terminals and CI log viewers turn
     * into a link to the file.
     */
    private String located(Path path, int line, String diagnostic) {
        return "%s:%d: %s".formatted(path.toAbsolutePath(), line, diagnostic);
    }

    /**
     * Generates one group, reporting a generation failure such as a repeated
     * repository method, or a row record that conflicts with the row of an
     * earlier group, with the group and its query source.
     */
    private GeneratedFile generateRepository(
        Source source,
        QueryGroupModel group,
        CodeGenerator codeGenerator
    ) {
        try {
            return codeGenerator.generate(group);
        } catch (RuntimeException e) {
            if (!isDiagnostic(e)) {
                throw internalError(
                    "Internal error while generating query group '%s' in %s"
                        .formatted(group.name(), source.queriesPath()),
                    e
                );
            }

            throw new CompilationException(
                "Invalid query group '%s' in %s: %s"
                    .formatted(group.name(), source.queriesPath(), reason(e)),
                e
            );
        }
    }

    /**
     * Reports whether a failure of one compilation step is a diagnostic of a
     * compiled input, which sqlcj states in SQL terms.
     *
     * <p>The schema parser, the SQL parser, the configuration, and the compiler
     * itself have exceptions of their own, and the schema parser, the analyzer,
     * and the generator state a diagnostic by convention as an unsupported
     * operation or an illegal argument that carries a message. Every other
     * exception, including a subclass such as {@link NumberFormatException} and
     * an exception of that convention's types without a message, is a failure
     * sqlcj did not foresee.
     */
    private boolean isDiagnostic(RuntimeException e) {
        if (
            e instanceof SqlParseException
                || e instanceof SchemaParseException
                || e instanceof ConfigurationException
                || e instanceof CompilationException
        ) {
            return true;
        }

        boolean convention = e.getClass() == UnsupportedOperationException.class
            || e.getClass() == IllegalArgumentException.class;

        return convention && e.getMessage() != null && !e.getMessage().isBlank();
    }

    /**
     * Reports an unexpected failure of one compilation step as one line that
     * names the step's own input and asks for a report, and carries neither the
     * message nor the class of the failure, which describes no compiled input.
     * The failure itself stays as the cause.
     */
    private CompilationException internalError(String step, RuntimeException e) {
        return new CompilationException(step + INTERNAL_ERROR_SUFFIX, e);
    }

    /** Uses the first message line so a diagnostic stays focused. */
    private String reason(RuntimeException e) {
        String message = e.getMessage();

        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }

        return message.lines()
            .findFirst()
            .orElse(message)
            .trim();
    }

    /**
     * Rejects a generated path that repeats, or differs only by case from, an
     * already generated path before any file is written.
     */
    private void checkGeneratedPath(
        Source source,
        GeneratedFile file,
        Map<String, GeneratedRepository> generatedRepositories
    ) {
        String portabilityKey = file.path()
            .toString()
            .toLowerCase(Locale.ROOT);

        GeneratedRepository generated = generatedRepositories.get(portabilityKey);

        if (generated == null) {
            generatedRepositories.put(
                portabilityKey,
                new GeneratedRepository(source.name(), file.path())
            );

            return;
        }

        if (generated.path().equals(file.path())) {
            throw new CompilationException(
                "Duplicate generated file for repositories '%s' and '%s': %s"
                    .formatted(generated.groupName(), source.name(), file.path())
            );
        }

        throw new CompilationException(
            "Generated file paths for repositories '%s' and '%s' differ only by case: %s and %s"
                .formatted(
                    generated.groupName(),
                    source.name(),
                    generated.path(),
                    file.path()
                )
        );
    }

    private void write(GeneratedFile file, Path outputDirectory) {
        try {
            generatedFileWriter.write(file, outputDirectory);
        } catch (IOException e) {
            throw new CompilationException(e.getMessage(), e);
        }
    }

    private List<String> readManifest(Path outputDirectory) {
        try {
            return generatedOutputManifest.read(outputDirectory);
        } catch (IOException e) {
            throw new CompilationException(e.getMessage(), e);
        }
    }

    private void deleteStale(
        Path outputDirectory,
        List<String> previousEntries,
        List<Path> generatedPaths
    ) {
        try {
            generatedOutputManifest.deleteStale(outputDirectory, previousEntries, generatedPaths);
        } catch (IOException e) {
            throw new CompilationException(e.getMessage(), e);
        }
    }

    private void writeManifest(Path outputDirectory, List<Path> generatedPaths) {
        try {
            generatedOutputManifest.write(outputDirectory, generatedPaths);
        } catch (IOException e) {
            throw new CompilationException(e.getMessage(), e);
        }
    }

    private record GeneratedRepository(String groupName, Path path) {
    }

    /** One rejected query of one entry: its header line and its diagnostic. */
    private record Rejection(int line, String diagnostic) {
    }
}
