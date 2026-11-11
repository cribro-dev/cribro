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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The decisions the report makes before and after the counts: which ref the diff is taken against,
 * and which sources the counts can still be trusted for.
 *
 * <p>DiffCoverageTaskRunTest, in the Gradle plugin, drives the whole report through a synthetic project; what is
 * here is the logic those runs carry, tested on its own.
 */
class DiffCoverageTest {

  private static final String MEASURED = "nullaway/src/main/java/Sample.java";
  private static final long EARLIER = 1_000_000_000L;
  private static final long LATER = 2_000_000_000L;

  @TempDir Path directory;

  private File writeAt(String path, long modified) throws IOException {
    Path file = directory.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, "x\n");
    Files.setLastModifiedTime(file, FileTime.fromMillis(modified));
    return file.toFile();
  }

  private List<String> staleAmong(String... measuredPaths) throws IOException {
    return DiffCoverage.staleSources(
        directory.toFile(), List.of(measuredPaths), List.of(writeAt("report.xml", EARLIER)));
  }

  @Nested
  class AgainstOneReport {

    @Test
    void aSourceEditedAfterTheReportIsStale() throws IOException {
      writeAt(MEASURED, LATER);
      assertEquals(List.of(MEASURED), staleAmong(MEASURED));
    }

    @Test
    void aSourceOlderThanTheReportIsNotStale() throws IOException {
      writeAt(MEASURED, EARLIER - 1000);
      assertEquals(List.of(), staleAmong(MEASURED));
    }

    /** A source written in the same run as the report is not an edit that moved a line. */
    @Test
    void aSourceAsOldAsTheReportIsNotStale() throws IOException {
      writeAt(MEASURED, EARLIER);
      assertEquals(List.of(), staleAmong(MEASURED));
    }

    @Test
    void aFileTheCountsDidNotComeFromIsNotChecked() throws IOException {
      writeAt("nullaway/src/test/java/SampleTest.java", LATER);
      assertEquals(List.of(), staleAmong());
    }

    @Test
    void aMeasuredPathThatNoLongerExistsIsNotStale() throws IOException {
      assertEquals(List.of(), staleAmong("nullaway/src/main/java/Deleted.java"));
    }
  }

  @Nested
  class TheBaseRef {

    @Test
    void anIntegrationUpstreamIsTriedBeforeTheDefaultBranches() {
      assertEquals(
          List.of("origin/develop", "origin/HEAD", "origin/main", "origin/master"),
          ChangedLines.baseCandidates(null, "origin/develop", "feature/x"));
    }

    @Test
    void aBranchWithNoUpstreamFallsBackToTheDefaultBranches() {
      assertEquals(
          ChangedLines.DEFAULT_BRANCHES, ChangedLines.baseCandidates(null, null, "feature/x"));
    }

    /**
     * In a fork workflow the branch tracks the fork, so after the first push its upstream holds
     * every line the branch changed and diffing against it would report an empty change.
     */
    @Test
    void anUpstreamThatIsThisBranchPushedToAForkIsLeftOut() {
      assertEquals(
          ChangedLines.DEFAULT_BRANCHES,
          ChangedLines.baseCandidates(null, "vs/feature/x", "feature/x"));
    }

    /**
     * Falling through from the ref the caller named to another one would report a different set of
     * lines as changed, under a base the caller did not choose.
     */
    @Test
    void anExplicitRefIsTheOnlyCandidate() {
      assertEquals(
          List.of("release-1.0"),
          ChangedLines.baseCandidates("release-1.0", "origin/develop", "feature/x"));
    }
  }

  @Nested
  class RecognisingThisBranchPushed {

    @Test
    void anUpstreamNamedAfterThisBranchOnARemoteIsThisBranch() {
      assertTrue(ChangedLines.isOwnPushedBranch("vs/feature/x", "feature/x"));
    }

    @Test
    void anUpstreamNamingAnotherBranchIsNotThisBranch() {
      assertFalse(ChangedLines.isOwnPushedBranch("origin/develop", "feature/x"));
    }

    /** Only the remote is dropped, so a branch named after another one's tail stays its own. */
    @Test
    void anUpstreamWhoseTailMatchesButWhosePathDoesNotIsAnotherBranch() {
      assertFalse(ChangedLines.isOwnPushedBranch("vs/feature/x", "x"));
    }

    @Test
    void aLocalUpstreamWithNoRemoteSegmentIsComparedWhole() {
      assertTrue(ChangedLines.isOwnPushedBranch("x", "x"));
    }

    @Test
    void aDetachedHeadHasNoBranchToRecognise() {
      assertFalse(ChangedLines.isOwnPushedBranch("origin/master", null));
    }
  }

  @Nested
  class AgainstSeveralReports {

    /** The newest report decides, since it is the one the counts were read from. */
    @Test
    void aSourceOlderThanTheNewestReportIsNotStale() throws IOException {
      File older = writeAt("first.xml", EARLIER);
      File newer = writeAt("second.xml", LATER);
      writeAt(MEASURED, LATER - 1000);
      assertEquals(
          List.of(),
          DiffCoverage.staleSources(
              directory.toFile(), List.of(MEASURED), List.of(older, newer)));
    }
  }

  @Test
  void theGradleFilterNamesEachTestMethodInItsOwnQuotedArgument() {
    assertEquals(
        "--tests 'p.ATest.one' --tests 'p.ATest$Inner.two'",
        DiffCoverage.gradleTestFilter(List.of("p.ATest.one", "p.ATest$Inner.two")));
  }

  /** The file lies at the root of its source root, so its path spells none of its package. */
  @Test
  void aKotlinFileIsMeasuredAgainstTheCountersOfThePackageItDeclares() throws Exception {
    new TestGitRepository(directory).initWithOneCommit();
    Path source = directory.resolve("m/src/main/kotlin/Calc.kt");
    Files.createDirectories(source.getParent());
    Files.writeString(source, "package demo.math\n\nfun two() = 2\n");
    File report = directory.resolve("jacoco.xml").toFile();
    Files.writeString(
        report.toPath(),
        "<report name=\"test\"><package name=\"demo/math\"><sourcefile name=\"Calc.kt\">"
            + "<line nr=\"3\" mi=\"0\" ci=\"4\" mb=\"0\" cb=\"0\"/>"
            + "</sourcefile></package></report>");

    String text =
        DiffCoverage.in(new Git(directory.toFile()))
            .baseRef("master")
            .sourceRoots(List.of("m/src/main/kotlin"))
            .reports(List.of(report))
            .outputFile(directory.resolve("diff-coverage.txt").toFile())
            .describe();

    assertLinesMatch(
        List.of(">> the files >>", "Total: 1/1 changed lines executed.", ">> the report paths >>"),
        text.lines().toList());
  }
}
