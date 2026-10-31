#!/usr/bin/env bash
#
# Verifies that clean external Maven projects can consume staged sqlcj
# artifacts.
#
# The script stages the reactor into an isolated local Maven repository, runs
# the packaged CLI from that repository, and then copies examples/maven-postgresql
# outside the repository to generate, compile, and execute it against a running
# PostgreSQL 16 server. The sqlcj source tree, its reactor classes, and its
# target directories are never placed on the sample's classpath.
#
# Generation is verified repeatedly over the copied sample. The CLI first
# generates it with '--config' from an empty sibling directory, which must stay
# empty, and then generates it again to prove that the sources and the output
# manifest are byte-identical. Two scratch configurations outside the sample
# then generate the query groups 'Author' and 'Library' and next only 'Writer',
# proving that a renamed or removed entry leaves no stale repository and a
# manifest listing the surviving files alone. Both entries share the one
# top-level 'AuthorsRow' record and the one 'BookFormat' enum the package
# generates. The final generation uses the sample's own 'sqlcj.yaml' from the
# sample directory and must restore the first generated output byte for byte
# before the sample is compiled and run. The sample's migrations declare an
# enum type and add an enum, an array, and a 'JSONB' column to 'books' and an
# 'updated_at' column to 'authors', so the generated package holds the shared
# 'AuthorsRow' and 'BooksRow' records, the 'BookFormat' enum, and one
# repository whose methods cover every named query, including the named
# parameter filter, the 'now()' rename, the grouped book count, the upsert, and
# the id-list read.
#
# The second external project, examples/spring-boot-postgresql, consumes the
# staged Spring Boot starter. It is verified once per supported Spring Boot
# version, each time from a fresh copy outside the repository: the staged CLI
# generates its two marked repositories, the resolved classpath is checked to
# hold the staged starter and runtime and that version's 'spring-boot' jar but
# neither the source tree nor the command line tool, the schema is reset, and
# 'spring-boot:run' runs the application in a forked JVM, whose goal fails on a
# non-zero exit. The application configures nothing of sqlcj's: the starter
# registers both generated repositories as beans, so the run proves a write
# outside a transaction, a committed '@Transactional' write across both
# repositories, and a '@Transactional' rollback that undoes both of them.
#
# Requirements: JDK 17 or later, Maven, psql, and a reachable PostgreSQL
# server.
#
# Connection settings, defaulting to the values used by docs/quickstart.md:
#
#   SQLCJ_SAMPLE_JDBC_URL      jdbc:postgresql://localhost:5432/quickstart
#   SQLCJ_SAMPLE_DB_USER       quickstart
#   SQLCJ_SAMPLE_DB_PASSWORD   quickstart

set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "${script_dir}/.." && pwd)

jdbc_url=${SQLCJ_SAMPLE_JDBC_URL:-jdbc:postgresql://localhost:5432/quickstart}
db_user=${SQLCJ_SAMPLE_DB_USER:-quickstart}
db_password=${SQLCJ_SAMPLE_DB_PASSWORD:-quickstart}

workspace=$(mktemp -d "${TMPDIR:-/tmp}/sqlcj-verify-release.XXXXXXXXXX")
local_repo="${workspace}/repository"
sample_dir="${workspace}/sample"

trap 'rm -rf "${workspace}"' EXIT

log() {
    printf '\n=== %s\n' "$1"
}

fail() {
    printf 'verify-release: %s\n' "$1" >&2
    exit 1
}

sample_mvn() {
    mvn --batch-mode --no-transfer-progress \
        -f "${sample_dir}/pom.xml" \
        -Dmaven.repo.local="${local_repo}" \
        -Dsqlcj.version="${version}" \
        "$@"
}

# Runs Maven over the Spring Boot sample copy of the version under
# verification, pinning both the staged sqlcj artifacts and that Spring Boot
# version.
spring_sample_mvn() {
    mvn --batch-mode --no-transfer-progress \
        -f "${spring_sample_dir}/pom.xml" \
        -Dmaven.repo.local="${local_repo}" \
        -Dsqlcj.version="${version}" \
        -Dspring-boot.version="${spring_boot_version}" \
        "$@"
}

command -v psql >/dev/null 2>&1 || fail "psql is required to apply the sample migrations"

log "Staging the reactor into ${local_repo}"

mvn --batch-mode --no-transfer-progress \
    -f "${repo_root}/pom.xml" \
    -Dmaven.repo.local="${local_repo}" \
    -Dmaven.test.skip=true \
    install

version=$(
    mvn --batch-mode --no-transfer-progress --quiet \
        -f "${repo_root}/pom.xml" \
        -Dmaven.repo.local="${local_repo}" \
        -Dexpression=project.version \
        -DforceStdout \
        help:evaluate | tail -n 1 | tr -d '[:space:]'
)

[ -n "${version}" ] || fail "could not determine the reactor version"

log "Verifying the staged artifacts for version ${version}"

cli_jar="${local_repo}/org/sqlcj/sqlcj-cli/${version}/sqlcj-cli-${version}.jar"
runtime_jar="${local_repo}/org/sqlcj/sqlcj-runtime/${version}/sqlcj-runtime-${version}.jar"
starter_jar="${local_repo}/org/sqlcj/sqlcj-spring-boot-starter/${version}/sqlcj-spring-boot-starter-${version}.jar"

[ -f "${cli_jar}" ] || fail "the staged CLI is missing: ${cli_jar}"
[ -f "${runtime_jar}" ] || fail "the staged runtime is missing: ${runtime_jar}"
[ -f "${starter_jar}" ] || fail "the staged Spring Boot starter is missing: ${starter_jar}"

reported_version=$(java -jar "${cli_jar}" version | tr -d '[:space:]')

[ "${reported_version}" = "${version}" ] \
    || fail "the packaged CLI reports '${reported_version}' instead of '${version}'"

log "Verifying the handled configuration failure"

failure_dir="${workspace}/handled-failure"
mkdir -p "${failure_dir}"

set +e
failure_output=$(cd "${failure_dir}" && java -jar "${cli_jar}" generate 2>&1)
failure_status=$?
set -e

[ "${failure_status}" -eq 1 ] \
    || fail "a missing configuration file exited with ${failure_status} instead of 1"

printf '%s\n' "${failure_output}" | grep -q '^sqlcj: ' \
    || fail "the handled failure did not print an 'sqlcj: ' diagnostic: ${failure_output}"

if printf '%s\n' "${failure_output}" | grep -qE 'Exception|^[[:space:]]+at .*\('; then
    fail "the handled failure printed a stack trace: ${failure_output}"
fi

log "Copying the external sample to ${sample_dir}"

cp -R "${repo_root}/examples/maven-postgresql" "${sample_dir}"
rm -rf "${sample_dir}/target"

generated_root="${sample_dir}/target/generated-sources/sqlcj"
generated_package="${generated_root}/com/example/app/db"
manifest_file="${generated_root}/sqlcj-manifest.txt"

elsewhere_dir="${workspace}/elsewhere"
first_generation="${workspace}/first-generation"

mkdir -p "${elsewhere_dir}"

log "Generating with --config from another working directory"

(cd "${elsewhere_dir}" && java -jar "${cli_jar}" generate --config ../sample/sqlcj.yaml)

[ -f "${generated_package}/AuthorRepository.java" ] \
    || fail "generating with --config did not write ${generated_package}/AuthorRepository.java"

[ -f "${manifest_file}" ] \
    || fail "generating with --config did not write ${manifest_file}"

elsewhere_entries=$(ls -A "${elsewhere_dir}")

[ -z "${elsewhere_entries}" ] \
    || fail "generating with --config wrote into the working directory: ${elsewhere_entries}"

cp -R "${generated_root}" "${first_generation}"

log "Regenerating to compare the generated bytes"

(cd "${elsewhere_dir}" && java -jar "${cli_jar}" generate --config ../sample/sqlcj.yaml)

diff -r "${first_generation}" "${generated_root}" \
    || fail "regenerating the sample did not reproduce the previous output byte for byte"

log "Verifying stale repository cleanup"

regeneration_dir="${workspace}/regeneration"
mkdir -p "${regeneration_dir}"

cat > "${regeneration_dir}/author-and-library.yaml" <<'YAML'
version: "1"
sql:
  - name: Author
    schema: ../sample/sql/migrations
    queries: ../sample/sql/queries.sql
  - name: Library
    schema: ../sample/sql/migrations
    queries: ../sample/sql/queries.sql
java:
  package: com.example.app.db
  out: ../sample/target/generated-sources/sqlcj
YAML

cat > "${regeneration_dir}/writer.yaml" <<'YAML'
version: "1"
sql:
  - name: Writer
    schema: ../sample/sql/migrations
    queries: ../sample/sql/queries.sql
java:
  package: com.example.app.db
  out: ../sample/target/generated-sources/sqlcj
YAML

(cd "${elsewhere_dir}" && java -jar "${cli_jar}" generate \
    --config ../regeneration/author-and-library.yaml)

for generated in AuthorRepository LibraryRepository AuthorsRow BooksRow BookFormat; do
    [ -f "${generated_package}/${generated}.java" ] \
        || fail "the renamed-entry setup is missing ${generated_package}/${generated}.java"
done

shared_row_count=$(find "${generated_root}" -type f -name 'AuthorsRow.java' | wc -l)

[ "${shared_row_count}" -eq 1 ] \
    || fail "expected the two entries to share 1 row record but found ${shared_row_count}"

shared_enum_count=$(find "${generated_root}" -type f -name 'BookFormat.java' | wc -l)

[ "${shared_enum_count}" -eq 1 ] \
    || fail "expected the two entries to share 1 enum but found ${shared_enum_count}"

(cd "${elsewhere_dir}" && java -jar "${cli_jar}" generate \
    --config ../regeneration/writer.yaml)

for repository in AuthorRepository LibraryRepository; do
    if [ -e "${generated_package}/${repository}.java" ]; then
        fail "regenerating left the stale ${generated_package}/${repository}.java behind"
    fi
done

stale_count=$(find "${generated_root}" -type f -name '*.java' | wc -l)

[ "${stale_count}" -eq 4 ] \
    || fail "expected 4 generated sources after the rename but found ${stale_count}"

for generated in WriterRepository AuthorsRow BooksRow BookFormat; do
    [ -f "${generated_package}/${generated}.java" ] \
        || fail "the surviving generated sources are missing ${generated_package}/${generated}.java"
done

expected_manifest="${workspace}/expected-manifest.txt"
printf 'com/example/app/db/AuthorsRow.java\ncom/example/app/db/BookFormat.java\ncom/example/app/db/BooksRow.java\ncom/example/app/db/WriterRepository.java\n' \
    > "${expected_manifest}"

cmp "${expected_manifest}" "${manifest_file}" \
    || fail "the manifest does not list the surviving files alone: $(cat "${manifest_file}")"

log "Generating sources with the packaged CLI"

(cd "${sample_dir}" && java -jar "${cli_jar}" generate)

for generated in AuthorRepository AuthorsRow BooksRow BookFormat; do
    [ -f "${generated_package}/${generated}.java" ] \
        || fail "the expected generated source is missing: ${generated_package}/${generated}.java"
done

generated_count=$(find "${generated_root}" -type f -name '*.java' | wc -l)

[ "${generated_count}" -eq 4 ] \
    || fail "expected 4 generated sources but found ${generated_count}"

for method in createAuthor getAuthor findAuthor listAuthors updateAuthorBio deleteAuthor \
    searchAuthors countAuthors listAuthorPage createBook listAuthorBooks \
    createCatalogedBook listBooksByFormat filterAuthors renameAuthor \
    countBooksByAuthor upsertAuthor listAuthorsByIds; do
    grep -q " ${method}(" "${generated_package}/AuthorRepository.java" \
        || fail "the generated repository is missing the ${method} method"
done

grep -q "LocalDateTime createdAt" "${generated_package}/AuthorsRow.java" \
    || fail "the generated row is missing the column the second migration adds"

grep -q "LocalDateTime updatedAt" "${generated_package}/AuthorsRow.java" \
    || fail "the generated row is missing the column the fourth migration adds"

diff -r "${first_generation}" "${generated_root}" \
    || fail "generating from the sample directory did not restore its first generated output"

find "${generated_root}" -type f -name '*.java' | sort

log "Verifying the sample dependency classpath"

classpath_file="${workspace}/sample-classpath.txt"

sample_mvn org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
    "-Dmdep.outputFile=${classpath_file}"

grep -q "sqlcj-runtime-${version}.jar" "${classpath_file}" \
    || fail "the sample classpath does not use the staged runtime artifact"

if grep -q "${repo_root}" "${classpath_file}"; then
    fail "the sample classpath references the sqlcj source tree: $(cat "${classpath_file}")"
fi

tr ':' '\n' < "${classpath_file}"

log "Applying the sample migrations to ${jdbc_url}"

# psql accepts the JDBC URL as a connection URI once the 'jdbc:' prefix is
# removed, so the sample and the migration reset share one setting.
PGPASSWORD="${db_password}" psql \
    --set=ON_ERROR_STOP=1 \
    --quiet \
    --username "${db_user}" \
    --command 'DROP TABLE IF EXISTS books, authors; DROP TYPE IF EXISTS book_format;' \
    --file "${sample_dir}/sql/migrations/V1__create_authors_and_books.sql" \
    --file "${sample_dir}/sql/migrations/V2__add_author_created_at_and_book_index.sql" \
    --file "${sample_dir}/sql/migrations/V3__add_book_format_tags_and_details.sql" \
    --file "${sample_dir}/sql/migrations/V4__add_author_updated_at.sql" \
    "${jdbc_url#jdbc:}"

log "Compiling the sample against the staged runtime"

sample_mvn compile

log "Running the sample against PostgreSQL"

sample_output="${workspace}/sample-output.txt"

export SQLCJ_SAMPLE_JDBC_URL="${jdbc_url}"
export SQLCJ_SAMPLE_DB_USER="${db_user}"
export SQLCJ_SAMPLE_DB_PASSWORD="${db_password}"

sample_mvn exec:java | tee "${sample_output}"

grep -q 'sqlcj sample verification passed' "${sample_output}" \
    || fail "the sample did not report a successful run"

for spring_boot_version in 4.0.9 4.1.2; do
    log "Copying the external Spring Boot sample for Spring Boot ${spring_boot_version}"

    spring_sample_dir="${workspace}/spring-boot-sample-${spring_boot_version}"

    cp -R "${repo_root}/examples/spring-boot-postgresql" "${spring_sample_dir}"
    rm -rf "${spring_sample_dir}/target"

    spring_generated_root="${spring_sample_dir}/target/generated-sources/sqlcj"
    spring_generated_package="${spring_generated_root}/com/example/app/db"

    log "Generating the Spring Boot sample with the packaged CLI"

    (cd "${spring_sample_dir}" && java -jar "${cli_jar}" generate)

    for generated in AuthorRepository BookRepository AuthorsRow BooksRow; do
        [ -f "${spring_generated_package}/${generated}.java" ] \
            || fail "the Spring Boot sample is missing ${spring_generated_package}/${generated}.java"
    done

    spring_generated_count=$(find "${spring_generated_root}" -type f -name '*.java' | wc -l)

    [ "${spring_generated_count}" -eq 4 ] \
        || fail "expected 4 generated Spring Boot sample sources but found ${spring_generated_count}"

    find "${spring_generated_root}" -type f -name '*.java' | sort

    # The marker is printed rather than matched quietly, so the run records
    # that both repositories carry the annotation the starter scans for.
    for repository in AuthorRepository BookRepository; do
        grep -H '@org.sqlcj.runtime.GeneratedRepository' "${spring_generated_package}/${repository}.java" \
            || fail "the generated ${repository} does not carry the marker the starter scans for"
    done

    log "Verifying the Spring Boot sample dependency classpath"

    spring_classpath_file="${workspace}/spring-boot-sample-classpath-${spring_boot_version}.txt"

    spring_sample_mvn org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
        "-Dmdep.outputFile=${spring_classpath_file}"

    grep -q "sqlcj-spring-boot-starter-${version}.jar" "${spring_classpath_file}" \
        || fail "the Spring Boot sample classpath does not use the staged starter artifact"

    grep -q "sqlcj-runtime-${version}.jar" "${spring_classpath_file}" \
        || fail "the Spring Boot sample classpath does not use the staged runtime artifact"

    grep -q "/spring-boot-${spring_boot_version}.jar" "${spring_classpath_file}" \
        || fail "the Spring Boot sample classpath does not run on Spring Boot ${spring_boot_version}"

    if grep -q "${repo_root}" "${spring_classpath_file}"; then
        fail "the Spring Boot sample classpath references the sqlcj source tree: $(cat "${spring_classpath_file}")"
    fi

    # The starter's dependency on the command line tool is test scope, so it
    # must not reach a consumer of the published starter.
    if grep -q 'sqlcj-cli-' "${spring_classpath_file}"; then
        fail "the Spring Boot sample classpath reaches the command line tool: $(cat "${spring_classpath_file}")"
    fi

    tr ':' '\n' < "${spring_classpath_file}"

    log "Resetting the Spring Boot sample schema in ${jdbc_url}"

    PGPASSWORD="${db_password}" psql \
        --set=ON_ERROR_STOP=1 \
        --quiet \
        --username "${db_user}" \
        --command 'DROP TABLE IF EXISTS books, authors; DROP TYPE IF EXISTS book_format;' \
        --file "${spring_sample_dir}/sql/migrations/V1__create_authors_and_books.sql" \
        "${jdbc_url#jdbc:}"

    log "Compiling the Spring Boot sample against the staged starter"

    spring_sample_mvn compile

    log "Running the Spring Boot sample on Spring Boot ${spring_boot_version}"

    spring_sample_output="${workspace}/spring-boot-sample-output-${spring_boot_version}.txt"

    spring_sample_mvn spring-boot:run | tee "${spring_sample_output}"

    grep -q '^rolled back: ' "${spring_sample_output}" \
        || fail "the Spring Boot sample did not report a rolled back transaction"

    grep -q 'sqlcj spring boot sample verification passed' "${spring_sample_output}" \
        || fail "the Spring Boot sample did not report a successful run"
done

log "Release verification passed for version ${version}"
