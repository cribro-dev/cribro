/*
 * Copyright (C) 2026 cribro contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.cribrodev;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The report prints each live mutant under its file and line, with the line's source text and the
 * tests PIT recorded as reaching it, and counts every other mutant as killed.
 */
class PitestSurvivorsTest {

  /** What a report with live mutants ends with, before the paths of the full report. */
  private static final String LIVE_FOOTER =
      "\n\n"
          + "For each live mutant, decide which case it is:\n"
          + "  - the tests miss behavior the change defines: add or strengthen a test,"
          + " next to the tests named for its line\n"
          + "  - no caller can observe the difference, so the mutant is equivalent:"
          + " say so in the pull request\n"
          + "  - you cannot tell: report it as unresolved";

  @TempDir Path repository;

  /**
   * Returns the mutants.tsv line for a mutant in method {@code m} of the given class of package
   * {@code com.x}, covered by the given tests of class {@code com.x.T}.
   */
  private static String mutation(
      String status, String className, String file, int line, String description, String... tests) {
    StringBuilder out =
        new StringBuilder(
            status + "\tcom.x." + className + "\tm\t" + file + "\t" + line + "\t" + description
                + " for com/x/" + className + "::m");
    for (String test : tests) {
      out.append("\tcom.x.").append(test).append("(com.x.T)");
    }
    return out.toString();
  }

  /** Writes the mutants to mutants.tsv and returns the path. */
  private Path mutantsFile(String... mutations) throws IOException {
    Path file = repository.resolve("mutants.tsv");
    Files.writeString(file, String.join("\n", mutations) + "\n");
    return file;
  }

  /**
   * Reads a run whose sources are com/x/A.java and com/x/B.java, each with five lines, and whose
   * changed lines with bytecode are the lines of its mutants.
   */
  private PitestSurvivors run(String... mutations) throws IOException {
    return read(Map.of(), Map.of(), mutations);
  }

  /**
   * Reads a run whose sources are com/x/A.java and com/x/B.java, each with five lines, under the
   * root {@code src}, with the changed-lines file naming the given path for each key.
   */
  private PitestSurvivors run(Map<String, String> paths, String... mutations) throws IOException {
    return read(paths, Map.of(), mutations);
  }

  /**
   * Reads a run whose sources are com/x/A.java and com/x/B.java, each with five lines, and which
   * recorded the given changed lines with bytecode, or wrote no such record where null.
   */
  private PitestSurvivors runRecording(
      Map<String, Set<Integer>> withBytecode, String... mutations) throws IOException {
    return read(Map.of(), withBytecode, mutations);
  }

  /**
   * Reads a run whose sources are com/x/A.java and com/x/B.java, each with five lines, under the
   * root {@code src}.
   */
  private PitestSurvivors read(
      Map<String, String> paths, Map<String, Set<Integer>> withBytecode, String... mutations)
      throws IOException {
    Path sources = repository.resolve("src");
    Files.createDirectories(sources.resolve("com/x"));
    Files.writeString(sources.resolve("com/x/A.java"), "a1\n  a2\na3\na4\na5\n");
    Files.writeString(sources.resolve("com/x/B.java"), "b1\nb2\nb3\nb4\nb5\n");
    return PitestSurvivors.read(
        "against origin/master (abc), all tests",
        mutantsFile(mutations).toFile(),
        repository.toFile(),
        List.of(new File("absent"), sources.toFile()),
        paths,
        withBytecode);
  }

  /**
   * Reads a run that wrote no mutants.tsv, as PIT does where it generated no mutant on the changed
   * lines, and which recorded the given changed lines with bytecode.
   */
  private PitestSurvivors runWithoutMutantsFile(Map<String, Set<Integer>> withBytecode)
      throws IOException {
    return PitestSurvivors.read(
        "against origin/master (abc), all tests",
        repository.resolve("absent/mutants.tsv").toFile(),
        repository.toFile(),
        List.of(repository.toFile()),
        Map.of(),
        withBytecode);
  }

  /** Writes a file of the repository. */
  private void write(String path, String text) throws IOException {
    Path file = repository.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
  }

  @Nested
  class TheQuotedFile {

    /** A Kotlin file may lie in a directory that does not follow its package. */
    @Test
    void aPathTheChangedLinesFileNamesIsQuotedRatherThanTheFileUnderARoot() throws IOException {
      write("gen/A.java", "g1\ng2\n");
      assertEquals(
          "Mutation testing of changed lines against origin/master (abc), all tests\n"
              + "  1 mutant on 1 of 1 changed line: 0 killed, 1 survived, 0 not reached by any test\n"
              + "  ordered by live mutants per file, then by line\n"
              + "\n"
              + "  gen/A.java\n"
              + "    2: g2\n"
              + "        survived: removed call, in m\n"
              + "\n"
              + "Live mutants on 1 line in 1 file."
              + LIVE_FOOTER,
          run(
                  Map.of("com/x/A.java", "gen/A.java"),
                  mutation("SURVIVED", "A", "A.java", 2, "removed call"))
              .render(DiffCoverageReport.Limits.NONE, List.of()));
    }

    @Test
    void aPathTheChangedLinesFileNamesThatNoLongerExistsFallsBackToTheRoots() throws IOException {
      assertEquals(
          "Mutation testing of changed lines against origin/master (abc), all tests\n"
              + "  1 mutant on 1 of 1 changed line: 0 killed, 1 survived, 0 not reached by any test\n"
              + "  ordered by live mutants per file, then by line\n"
              + "\n"
              + "  src/com/x/A.java\n"
              + "    2: a2\n"
              + "        survived: removed call, in m\n"
              + "\n"
              + "Live mutants on 1 line in 1 file."
              + LIVE_FOOTER,
          run(
                  Map.of("com/x/A.java", "gone/A.java"),
                  mutation("SURVIVED", "A", "A.java", 2, "removed call"))
              .render(DiffCoverageReport.Limits.NONE, List.of()));
    }

    @Test
    void aMutantInTheDefaultPackageIsFoundByItsFileNameUnderARoot() throws IOException {
      write("src/D.java", "d1\n");
      assertEquals(
          "Mutation testing of changed lines against origin/master (abc), all tests\n"
              + "  1 mutant on 1 of 1 changed line: 0 killed, 1 survived, 0 not reached by any test\n"
              + "  ordered by live mutants per file, then by line\n"
              + "\n"
              + "  src/D.java\n"
              + "    1: d1\n"
              + "        survived: removed call, in m\n"
              + "\n"
              + "Live mutants on 1 line in 1 file."
              + LIVE_FOOTER,
          run("SURVIVED\tD\tm\tD.java\t1\tremoved call for D::m")
              .render(DiffCoverageReport.Limits.NONE, List.of()));
    }
  }

  @Test
  void liveMutantsAreGroupedByFileAndLineWithTheTestsThatReachThem() throws IOException {
    assertEquals(
        "Mutation testing of changed lines against origin/master (abc), all tests\n"
            + "  4 mutants on 3 of 3 changed lines: 1 killed, 2 survived, 1 not reached by any test\n"
            + "  ordered by live mutants per file, then by line\n"
            + "\n"
            + "  src/com/x/A.java\n"
            + "    2: a2\n"
            + "        survived: negated conditional, in m\n"
            + "        survived: removed call, in m\n"
            + "        reached by 1 test:\n"
            + "          T.t1\n"
            + "    4: a4\n"
            + "        no test reaches it: replaced return value with null, in m\n"
            + "\n"
            + "Live mutants on 2 lines in 1 file." + LIVE_FOOTER,
        run(
                mutation("NO_COVERAGE", "A", "A.java", 4, "replaced return value with null"),
                mutation("SURVIVED", "A", "A.java", 2, "negated conditional", "T.t1"),
                mutation("KILLED", "A", "A.java", 3, "removed call", "T.t2"),
                mutation("SURVIVED", "A", "A.java", 2, "removed call", "T.t1"))
            .render(DiffCoverageReport.Limits.NONE, List.of()));
  }

  @Test
  void aTimeoutCountsAsKilledAndANonViableMutantIsCountedApart() throws IOException {
    assertEquals(
        "Mutation testing of changed lines against origin/master (abc), all tests\n"
            + "  3 mutants on 3 of 3 changed lines: 2 killed (1 timed out), 0 survived, 0 not reached by any test,"
            + " 1 NON_VIABLE",
        run(
                mutation("KILLED", "A", "A.java", 1, "removed call"),
                mutation("TIMED_OUT", "A", "A.java", 2, "negated conditional"),
                mutation("NON_VIABLE", "A", "A.java", 3, "negated conditional"))
            .render(DiffCoverageReport.Limits.NONE, List.of()));
  }

  @Test
  void theKilledCountNamesTheTimeoutsAndTheMemoryErrorsAmongIt() throws IOException {
    assertEquals(
        "Mutation testing of changed lines against origin/master (abc), all tests\n"
            + "  4 mutants on 4 of 4 changed lines: 4 killed (2 timed out, 1 out of memory), 0 survived,"
            + " 0 not reached by any test",
        run(
                mutation("MEMORY_ERROR", "A", "A.java", 1, "removed call"),
                mutation("TIMED_OUT", "A", "A.java", 2, "negated conditional"),
                mutation("KILLED", "A", "A.java", 3, "negated conditional"),
                mutation("TIMED_OUT", "A", "A.java", 4, "negated conditional"))
            .render(DiffCoverageReport.Limits.NONE, List.of()));
  }

  @Nested
  class TheChangedLineCount {

    @Test
    void countsTheLinesWithAMutantAmongTheChangedLinesWithBytecode() throws IOException {
      assertEquals(
          "Mutation testing of changed lines against origin/master (abc), all tests\n"
              + "  3 mutants on 2 of 4 changed lines: 3 killed, 0 survived,"
              + " 0 not reached by any test\n"
              + "\n"
              + "PIT generates no mutant on a line that calls a logging library.",
          runRecording(
                  Map.of("com/x/A.java", Set.of(1, 2, 3), "com/x/B.java", Set.of(1)),
                  mutation("KILLED", "A", "A.java", 1, "removed call"),
                  mutation("KILLED", "A", "A.java", 1, "negated conditional"),
                  mutation("KILLED", "B", "B.java", 1, "removed call"))
              .render(DiffCoverageReport.Limits.NONE, List.of()));
    }

    @Test
    void aMutatedLineTheRunDidNotRecordStillCountsAsALineWithBytecode() throws IOException {
      assertEquals(
          "Mutation testing of changed lines against origin/master (abc), all tests\n"
              + "  1 mutant on 1 of 2 changed lines: 1 killed, 0 survived,"
              + " 0 not reached by any test\n"
              + "\n"
              + "PIT generates no mutant on a line that calls a logging library.",
          runRecording(
                  Map.of("com/x/A.java", Set.of(1)),
                  mutation("KILLED", "A", "A.java", 2, "removed call"))
              .render(DiffCoverageReport.Limits.NONE, List.of()));
    }

    @Test
    void withoutTheRecordedLinesOnlyTheLinesWithAMutantAreCounted() throws IOException {
      assertEquals(
          "Mutation testing of changed lines against origin/master (abc), all tests\n"
              + "  3 mutants on 2 changed lines: 3 killed, 0 survived, 0 not reached by any test",
          runRecording(
                  null,
                  mutation("KILLED", "A", "A.java", 1, "removed call"),
                  mutation("KILLED", "A", "A.java", 1, "negated conditional"),
                  mutation("KILLED", "A", "A.java", 2, "removed call"))
              .render(DiffCoverageReport.Limits.NONE, List.of()));
    }

    @Test
    void withoutTheRecordedLinesASurvivorStillGetsTheNoteOnUnmutatedLines() throws IOException {
      String report =
          runRecording(null, mutation("SURVIVED", "A", "A.java", 1, "removed call", "T.p"))
              .render(DiffCoverageReport.Limits.NONE, List.of());
      assertTrue(
          report.endsWith("PIT generates no mutant on a line that calls a logging library."),
          report);
    }

    @Test
    void aRunWithNoMutantCountsTheChangedLinesWithBytecode() throws IOException {
      assertEquals(
          "Mutation testing of changed lines against origin/master (abc), all tests\n"
              + "  no mutant: PIT generated none on the 2 changed lines\n"
              + "\n"
              + "PIT generates no mutant on a line that calls a logging library.",
          runWithoutMutantsFile(Map.of("com/x/A.java", Set.of(1, 2)))
              .render(DiffCoverageReport.Limits.NONE, List.of()));
    }

    @Test
    void aRunWhoseChangedLinesHaveNoBytecodeSaysSo() throws IOException {
      assertEquals(
          "Mutation testing of changed lines against origin/master (abc), all tests\n"
              + "  no mutant: no changed line has bytecode PIT may mutate",
          runWithoutMutantsFile(Map.of()).render(DiffCoverageReport.Limits.NONE, List.of()));
    }
  }

  @Test
  void aRunInWhichNoTestReachedAnyLineSaysWhyThatCanHappen() throws IOException {
    assertEquals(
        "Mutation testing of changed lines against origin/master (abc), all tests\n"
            + "  1 mutant on 1 of 1 changed line: 0 killed, 0 survived, 1 not reached by any test\n"
            + "  ordered by live mutants per file, then by line\n"
            + "\n"
            + "No test reached any of the changed lines. Either no test of this module exercises this"
            + " code, or the tests that do failed under PIT, which leaves them out silently;"
            + " pit.log beside the report names each test that failed.\n"
            + "\n"
            + "  src/com/x/A.java\n"
            + "    1: a1\n"
            + "        no test reaches it: removed call, in m\n"
            + "\n"
            + "Live mutants on 1 line in 1 file."
            + LIVE_FOOTER,
        run(mutation("NO_COVERAGE", "A", "A.java", 1, "removed call"))
            .render(DiffCoverageReport.Limits.NONE, List.of()));
  }

  @Test
  void theFileWithMoreLiveMutantsComesFirst() throws IOException {
    assertEquals(
        "Mutation testing of changed lines against origin/master (abc), all tests\n"
            + "  3 mutants on 3 of 3 changed lines: 0 killed, 3 survived, 0 not reached by any test\n"
            + "  ordered by live mutants per file, then by line\n"
            + "\n"
            + "  src/com/x/B.java\n"
            + "    1: b1\n"
            + "        survived: removed call, in m\n"
            + "    2: b2\n"
            + "        survived: removed call, in m\n"
            + "\n"
            + "  src/com/x/A.java\n"
            + "    1: a1\n"
            + "        survived: removed call, in m\n"
            + "\n"
            + "Live mutants on 3 lines in 2 files." + LIVE_FOOTER,
        run(
                mutation("SURVIVED", "A", "A.java", 1, "removed call"),
                mutation("SURVIVED", "B", "B.java", 1, "removed call"),
                mutation("SURVIVED", "B", "B.java", 2, "removed call"))
            .render(DiffCoverageReport.Limits.NONE, List.of()));
  }

  @Test
  void theTotalLineLimitEndsTheReportAfterTheLinesItAllows() throws IOException {
    assertEquals(
        "Mutation testing of changed lines against origin/master (abc), all tests\n"
            + "  3 mutants on 3 of 3 changed lines: 0 killed, 3 survived, 0 not reached by any test\n"
            + "  ordered by live mutants per file, then by line\n"
            + "\n"
            + "  src/com/x/B.java\n"
            + "    1: b1\n"
            + "        survived: removed call, in m\n"
            + "    1 more line\n"
            + "\n"
            + "  1 more file\n"
            + "\n"
            + "Live mutants on 3 lines in 2 files." + LIVE_FOOTER,
        run(
                mutation("SURVIVED", "A", "A.java", 1, "removed call"),
                mutation("SURVIVED", "B", "B.java", 1, "removed call"),
                mutation("SURVIVED", "B", "B.java", 2, "removed call"))
            .render(new DiffCoverageReport.Limits(10, 10, 1), List.of()));
  }

  @Test
  void theReportFileHoldsWhatTheLimitsCut() throws IOException {
    PitestSurvivors report =
        run(
            mutation("SURVIVED", "A", "A.java", 1, "removed call"),
            mutation("SURVIVED", "B", "B.java", 1, "removed call"));
    Path file = repository.resolve("build/survivors.txt");

    report.writeAndRender(repository.toFile(), file.toFile(), 1, 1, 1, false, List.of());

    assertEquals(
        report.render(DiffCoverageReport.Limits.NONE, List.of()) + "\n", Files.readString(file));
  }

  @Test
  void showAllPrintsTheWholeReport() throws IOException {
    PitestSurvivors report =
        run(
            mutation("SURVIVED", "A", "A.java", 1, "removed call"),
            mutation("SURVIVED", "B", "B.java", 1, "removed call"));

    assertEquals(
        report.render(DiffCoverageReport.Limits.NONE, List.of("build/survivors.txt")),
        report.writeAndRender(
            repository.toFile(),
            repository.resolve("build/survivors.txt").toFile(),
            1,
            1,
            1,
            true,
            List.of()));
  }

  @Test
  void theTestsNamedFirstAreTheOnesReachingTheMostLiveMutants() throws IOException {
    assertEquals(
        "Mutation testing of changed lines against origin/master (abc), all tests\n"
            + "  2 mutants on 2 of 2 changed lines: 0 killed, 2 survived, 0 not reached by any test\n"
            + "  ordered by live mutants per file, then by line\n"
            + "\n"
            + "  src/com/x/A.java\n"
            + "    1: a1\n"
            + "        survived: removed call, in m\n"
            + "        reached by 5 tests, among them:\n"
            + "          T.d\n"
            + "          T.e\n"
            + "          T.a\n"
            + "\n"
            + "  src/com/x/B.java\n"
            + "    1: b1\n"
            + "        survived: removed call, in m\n"
            + "        reached by 2 tests:\n"
            + "          T.d\n"
            + "          T.e\n"
            + "\n"
            + "Live mutants on 2 lines in 2 files." + LIVE_FOOTER,
        run(
                mutation("SURVIVED", "A", "A.java", 1, "removed call", "T.a", "T.b", "T.c", "T.d", "T.e"),
                mutation("SURVIVED", "B", "B.java", 1, "removed call", "T.d", "T.e"))
            .render(DiffCoverageReport.Limits.NONE, List.of()));
  }

  /**
   * T.p reaches two live mutants through five invocations, and U.q reaches three live mutants, so
   * U.q is named first only where each invocation of T.p does not count as a test of its own.
   */
  @Test
  void theInvocationsOfOneTestShareARowAndCountAsOneTest() throws IOException {
    assertEquals(
        "Mutation testing of changed lines against origin/master (abc), all tests\n"
            + "  3 mutants on 2 of 2 changed lines: 0 killed, 3 survived, 0 not reached by any test\n"
            + "  ordered by live mutants per file, then by line\n"
            + "\n"
            + "  src/com/x/A.java\n"
            + "    1: a1\n"
            + "        survived: removed call, in m\n"
            + "        survived: negated conditional, in m\n"
            + "        reached by 2 tests:\n"
            + "          U.q\n"
            + "          T.p[1-3, 10, 33]\n"
            + "\n"
            + "  src/com/x/B.java\n"
            + "    1: b1\n"
            + "        survived: removed call, in m\n"
            + "        reached by 1 test:\n"
            + "          U.q\n"
            + "\n"
            + "Live mutants on 2 lines in 2 files." + LIVE_FOOTER,
        run(
                mutation(
                    "SURVIVED", "A", "A.java", 1, "removed call",
                    "T.p[33]", "T.p[2]", "T.p[10]", "T.p[1]", "U.q"),
                mutation("SURVIVED", "A", "A.java", 1, "negated conditional", "T.p[3]", "U.q"),
                mutation("SURVIVED", "B", "B.java", 1, "removed call", "U.q"))
            .render(DiffCoverageReport.Limits.NONE, List.of()));
  }

  /** A range joins the invocations of one container and stops where the container changes. */
  @Test
  void consecutiveNestedInvocationsShareARange() throws IOException {
    String report =
        run(
                mutation(
                    "SURVIVED", "A", "A.java", 1, "removed call",
                    dynamic(2, 5), dynamic(2, 6), dynamic(2, 7), dynamic(3, 1)))
            .render(DiffCoverageReport.Limits.NONE, List.of());
    assertTrue(report.contains("\n          FooTest.cases[2/5-7, 3/1]\n"), report);
  }

  /**
   * A test factory that returns a dynamic test beside a dynamic container gives one test an
   * invocation at each of two levels.
   */
  @Test
  void invocationsAtTwoLevelsAreListedApart() throws IOException {
    String report =
        run(
                mutation(
                    "SURVIVED", "A", "A.java", 1, "removed call",
                    dynamic(2, 1),
                    "com.x.FooTest.[engine:junit-jupiter]/[class:com.x.FooTest]"
                        + "/[test-factory:cases()]/[dynamic-test:#2]"))
            .render(DiffCoverageReport.Limits.NONE, List.of());
    assertTrue(report.contains("\n          FooTest.cases[2, 2/1]\n"), report);
  }

  private static String dynamic(int container, int test) {
    return "com.x.FooTest.[engine:junit-jupiter]/[class:com.x.FooTest]/[test-factory:cases()]"
        + "/[dynamic-container:#" + container + "]/[dynamic-test:#" + test + "]";
  }

  @Test
  void theLimitsCutLinesAndFilesAndTheReportNamesTheFullOne() throws IOException {
    assertEquals(
        "Mutation testing of changed lines against origin/master (abc), all tests\n"
            + "  3 mutants on 3 of 3 changed lines: 0 killed, 3 survived, 0 not reached by any test\n"
            + "  ordered by live mutants per file, then by line\n"
            + "\n"
            + "  src/com/x/A.java\n"
            + "    1: a1\n"
            + "        survived: removed call, in m\n"
            + "    1 more line\n"
            + "\n"
            + "  1 more file\n"
            + "\n"
            + "Live mutants on 3 lines in 2 files." + LIVE_FOOTER + "\n"
            + "\n"
            + "Full report:\n"
            + "  build/survivors.txt",
        run(
                mutation("SURVIVED", "A", "A.java", 1, "removed call"),
                mutation("SURVIVED", "A", "A.java", 5, "removed call"),
                mutation("SURVIVED", "B", "B.java", 1, "removed call"))
            .render(new DiffCoverageReport.Limits(1, 1, 50), List.of("build/survivors.txt")));
  }

  @Test
  void aRunThatWroteNoReportSaysNoMutantWasAnalyzed() throws IOException {
    Path build = repository.resolve("build");
    Files.createDirectories(build);
    Files.writeString(build.resolve("pit.log"), "");
    PitestSurvivors report =
        PitestSurvivors.read(
            "against origin/master (abc), all tests",
            build.resolve("mutants.tsv").toFile(),
            repository.toFile(),
            List.of(repository.toFile()),
            Map.of(),
            null);

    assertEquals(
        "Mutation testing of changed lines against origin/master (abc), all tests\n"
            + "  no mutant: PIT generated none on the changed lines\n"
            + "\n"
            + "PIT generates no mutant on a line that calls a logging library.\n"
            + "\n"
            + "Full report:\n"
            + "  build/survivors.txt\n"
            + "  build/pit.log",
        report.writeAndRender(
            repository.toFile(),
            build.resolve("survivors.txt").toFile(),
            10,
            10,
            50,
            false,
            List.of("build/index.html", "build/pit.log")));
  }

  /** The report quotes no text for a line it cannot decode, and still reports the mutant. */
  @Test
  void aSourceFileThatIsNotUtf8LeavesTheLineUnquoted() throws IOException {
    Path sources = repository.resolve("src");
    Files.createDirectories(sources.resolve("com/x"));
    Files.write(sources.resolve("com/x/A.java"), new byte[] {(byte) 0xff, '\n'});
    PitestSurvivors report =
        PitestSurvivors.read(
            "against origin/master (abc), all tests",
            mutantsFile(mutation("SURVIVED", "A", "A.java", 1, "removed call")).toFile(),
            repository.toFile(),
            List.of(sources.toFile()),
            Map.of(),
            Map.of());

    assertEquals(
        "Mutation testing of changed lines against origin/master (abc), all tests\n"
            + "  1 mutant on 1 of 1 changed line: 0 killed, 1 survived, 0 not reached by any test\n"
            + "  ordered by live mutants per file, then by line\n"
            + "\n"
            + "  src/com/x/A.java\n"
            + "    1: \n"
            + "        survived: removed call, in m\n"
            + "\n"
            + "Live mutants on 1 line in 1 file."
            + LIVE_FOOTER,
        report.render(DiffCoverageReport.Limits.NONE, List.of()));
  }

  @Test
  void aLambdaIsNamedByTheMethodThatHoldsIt() {
    assertEquals("a lambda in check", PitestSurvivors.readableMethod("lambda$check$0"));
  }

  @Test
  void aConstructorIsNamedAsSuch() {
    assertEquals("a constructor", PitestSurvivors.readableMethod("<init>"));
  }

  @Nested
  class TestNames {

    @Test
    void aJUnit4TestIsNamedByClassAndMethod() {
      assertEquals(
          "FooTest.bar",
          PitestSurvivors.shortTestName("com.x.FooTest.bar(com.x.FooTest)").toString());
    }

    /** {@code @Parameters(name = "{0}")} puts the parameter in brackets, whatever its digits. */
    @Test
    void aNumberInBracketsThatIsNoIndexStaysInTheName() {
      PitestSurvivors.TestName name =
          PitestSurvivors.shortTestName("com.x.FooTest.bar[12345678901](com.x.FooTest)");
      assertAll(
          () -> assertEquals("FooTest.bar[12345678901]", name.name(), "name"),
          () -> assertEquals(List.of(), name.invocation(), "invocation"));
    }

    @Test
    void aNumberWithALeadingZeroInBracketsStaysInTheName() {
      PitestSurvivors.TestName name =
          PitestSurvivors.shortTestName("com.x.FooTest.bar[007](com.x.FooTest)");
      assertAll(
          () -> assertEquals("FooTest.bar[007]", name.name(), "name"),
          () -> assertEquals(List.of(), name.invocation(), "invocation"));
    }

    @Test
    void theFirstJUnit4IndexIsZero() {
      assertEquals(
          List.of(0),
          PitestSurvivors.shortTestName("com.x.FooTest.bar[0](com.x.FooTest)").invocation());
    }

    @Test
    void aTenDigitNumberInBracketsIsNoIndex() {
      assertEquals(
          "FooTest.bar[2147483648]",
          PitestSurvivors.shortTestName("com.x.FooTest.bar[2147483648](com.x.FooTest)").toString());
    }

    @Test
    void aNineDigitNumberInBracketsIsAnIndex() {
      assertEquals(
          List.of(123456789),
          PitestSurvivors.shortTestName("com.x.FooTest.bar[123456789](com.x.FooTest)")
              .invocation());
    }

    @Test
    void aJUnit4ParameterizedTestIsNamedByClassMethodAndIndex() {
      assertEquals(
          "FooTest.bar[3]",
          PitestSurvivors.shortTestName("com.x.FooTest.bar[3](com.x.FooTest)").toString());
    }

    /**
     * {@code @Parameters(name = "{index}: fib({0})")} puts parentheses and a dot in the brackets, so
     * the method name ends at the parenthesis that opens the class name.
     */
    @Test
    void aJUnit4NameWithParenthesesAndADotKeepsThemInTheName() {
      PitestSurvivors.TestName name =
          PitestSurvivors.shortTestName("com.x.FibTest.test[0: fib(1.5)](com.x.FibTest)");
      assertAll(
          () -> assertEquals("FibTest.test[0: fib(1.5)]", name.name(), "name"),
          () -> assertEquals(List.of(), name.invocation(), "invocation"));
    }

    @Test
    void aNameThatEndsInAnOpenParenthesisIsCutThere() {
      assertEquals("FooTest.bar", PitestSurvivors.shortTestName("com.x.FooTest.bar(").toString());
    }

    static Stream<Arguments> platformIds() {
      return Stream.of(
          argumentSet(
              "Jupiter method",
              "com.x.FooTest.[engine:junit-jupiter]/[class:com.x.FooTest]/[method:bar()]",
              "FooTest.bar",
              List.of()),
          argumentSet(
              "Jupiter class",
              "com.x.FooTest.[engine:junit-jupiter]/[class:com.x.FooTest]",
              "FooTest",
              List.of()),
          argumentSet(
              "Jupiter method with an array parameter",
              "com.x.FooTest.[engine:junit-jupiter]/[class:com.x.FooTest]"
                  + "/[method:bar(java.lang.String%5B%5D)]",
              "FooTest.bar",
              List.of()),
          argumentSet(
              "Jupiter test template in a nested class",
              "org.postgresql.util.internal.PgBufferedOutputStreamTest."
                  + "[engine:junit-jupiter]/[class:org.postgresql.util.internal.PgBufferedOutputStreamTest]"
                  + "/[nested-class:ZeroTests]/[test-template:bufferFlushedOnlyWhenFull(int, int)]"
                  + "/[test-template-invocation:#38]",
              "PgBufferedOutputStreamTest$ZeroTests.bufferFlushedOnlyWhenFull",
              List.of(38)),
          argumentSet(
              "Jupiter method of a nested class inherited from another class",
              "com.x.FooTest.[engine:junit-jupiter]/[class:com.x.FooTest]"
                  + "/[fq-nested-class:com.x.Base$Inner]/[method:bar()]",
              "Base$Inner.bar",
              List.of()),
          argumentSet(
              "Jupiter dynamic test in a dynamic container",
              "com.x.FooTest.[engine:junit-jupiter]/[class:com.x.FooTest]"
                  + "/[test-factory:cases()]/[dynamic-container:#2]/[dynamic-test:#5]",
              "FooTest.cases",
              List.of(2, 5)),
          argumentSet(
              "Jupiter dynamic test whose index does not fit an int",
              "com.x.FooTest.[engine:junit-jupiter]/[class:com.x.FooTest]"
                  + "/[test-factory:cases()]/[dynamic-test:#12345678901]",
              "FooTest.cases",
              List.of()),
          argumentSet(
              "Jupiter method of a class template",
              "com.x.FooTest.[engine:junit-jupiter]/[class-template:com.x.FooTest]"
                  + "/[class-template-invocation:#2]/[method:bar()]",
              "FooTest.bar",
              List.of(2)),
          argumentSet(
              "Jupiter test template of a class template",
              "com.x.FooTest.[engine:junit-jupiter]/[class-template:com.x.FooTest]"
                  + "/[class-template-invocation:#2]/[test-template:bar(int)]"
                  + "/[test-template-invocation:#3]",
              "FooTest.bar",
              List.of(2, 3)),
          argumentSet(
              "Jupiter method of a nested class template",
              "com.x.Outer.[engine:junit-jupiter]/[class:com.x.Outer]"
                  + "/[nested-class-template:Inner]/[class-template-invocation:#1]/[method:bar()]",
              "Outer$Inner.bar",
              List.of(1)),
          argumentSet(
              "Jupiter method of a nested class template inherited from another class",
              "com.x.Outer.[engine:junit-jupiter]/[class:com.x.Outer]"
                  + "/[fq-nested-class-template:com.x.Base$Inner]/[class-template-invocation:#1]"
                  + "/[method:bar()]",
              "Base$Inner.bar",
              List.of(1)),
          argumentSet(
              "jqwik property",
              "com.x.FooTest.[engine:jqwik]/[class:com.x.FooTest]/[property:prop()]",
              "FooTest.prop",
              List.of()),
          argumentSet(
              "Vintage method",
              "com.x.FooTest.[engine:junit-vintage]/[runner:com.x.FooTest]"
                  + "/[test:bar(com.x.FooTest)]",
              "FooTest.bar",
              List.of()),
          argumentSet(
              "Vintage method of a class in a suite",
              "com.x.AllTests.[engine:junit-vintage]/[runner:com.x.AllTests]"
                  + "/[runner:com.x.FooTest]/[test:bar(com.x.FooTest)]",
              "FooTest.bar",
              List.of()),
          argumentSet(
              "Vintage parameterized method",
              "com.x.FooTest.[engine:junit-vintage]/[runner:com.x.FooTest]"
                  + "/[test:%5B0%5D]/[test:bar%5B0%5D(com.x.FooTest)]",
              "FooTest.bar",
              List.of(0)),
          argumentSet(
              "Vintage parameterized method with a named invocation",
              "com.x.FooTest.[engine:junit-vintage]/[runner:com.x.FooTest]"
                  + "/[test:%5Bfirst%5D]/[test:bar%5Bfirst%5D(com.x.FooTest)]",
              "FooTest.bar[first]",
              List.of()),
          argumentSet(
              "Vintage parameterized method with a slash and a percent sign in its invocation",
              "com.x.FooTest.[engine:junit-vintage]/[runner:com.x.FooTest]"
                  + "/[test:%5Ba%2Fb%25%5D]/[test:bar%5Ba%2Fb%25%5D(com.x.FooTest)]",
              "FooTest.bar[a/b%]",
              List.of()),
          argumentSet(
              "Vintage parameterized method whose invocation name holds parentheses",
              "com.x.FibTest.[engine:junit-vintage]/[runner:com.x.FibTest]"
                  + "/[test:%5B0%3A fib%281%29%5D]/[test:test%5B0%3A fib%281%29%5D(com.x.FibTest)]",
              "FibTest.test[0: fib(1)]",
              List.of()),
          argumentSet(
              "an engine with no class segment",
              "com.x.Suite.[engine:other]/[container:com.x.example]/[case:first]",
              "com.x.Suite.[engine:other]/[container:com.x.example]/[case:first]",
              List.of()));
    }

    @ParameterizedTest
    @MethodSource("platformIds")
    void aJUnitPlatformIdIsNamedByItsClassesAndMethodAndListsItsInvocation(
        String id, String name, List<Integer> invocation) {
      PitestSurvivors.TestName actual = PitestSurvivors.shortTestName(id);
      assertAll(
          () -> assertEquals(name, actual.name(), "name"),
          () -> assertEquals(invocation, actual.invocation(), "invocation"));
    }
  }
}
