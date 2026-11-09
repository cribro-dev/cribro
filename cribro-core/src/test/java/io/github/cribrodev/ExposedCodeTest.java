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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A changed line that decides where control goes exposes the unchanged lines it routes inputs to.
 *
 * <p>Each source marks its lines with comments: {@code // changed} is a line of the diff, and
 * {@code // exposed} is a line the change is expected to expose, in whichever method holds it.
 * Every other line is expected to stay out of every region.
 */
class ExposedCodeTest {

  private static final String ROOT = "lib/src/main/java";
  private static final String SAMPLE = ROOT + "/p/Sample.java";

  @TempDir Path directory;

  private final Map<String, NavigableSet<Integer>> changed = new LinkedHashMap<>();

  /** Writes a source and records its {@code // changed} lines as the diff of that path. */
  private void write(String path, String source) throws IOException {
    Path file = directory.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, source);
    changed.put(path, linesMarked(source, "// changed"));
  }

  private static NavigableSet<Integer> linesMarked(String source, String marker) {
    NavigableSet<Integer> lines = new TreeSet<>();
    List<String> all = source.lines().toList();
    for (int i = 0; i < all.size(); i++) {
      if (all.get(i).endsWith(marker)) {
        lines.add(i + 1);
      }
    }
    return lines;
  }

  private ExposedCode find() {
    return ExposedCode.find(directory.toFile(), changed, List.of(ROOT));
  }

  private List<String> changedTests(String sourceRoot) {
    return ExposedCode.find(directory.toFile(), changed, List.of(sourceRoot)).changedTests.stream()
        .map(Object::toString)
        .toList();
  }

  /** Returns each region as {@code method, trigger lines}, in the order the result lists them. */
  private static List<String> headers(ExposedCode exposed) {
    List<String> headers = new ArrayList<>();
    for (ExposedCode.Region region : exposed.regions) {
      headers.add(
          region.method + (region.called ? " called from " : " under ") + region.triggers);
    }
    return headers;
  }

  private static NavigableSet<Integer> exposedLines(ExposedCode exposed) {
    NavigableSet<Integer> lines = new TreeSet<>();
    exposed.regions.forEach(region -> lines.addAll(region.lines));
    return lines;
  }

  /** Asserts the region headers, and that the regions hold exactly the {@code // exposed} lines. */
  private void assertExposed(String source, List<String> expectedHeaders) {
    ExposedCode exposed = find();
    assertAll(
        () -> assertEquals(expectedHeaders, headers(exposed), "regions"),
        () ->
            assertEquals(
                linesMarked(source, "// exposed"), exposedLines(exposed), "exposed lines"));
  }

  @Test
  void aChangedCaseLabelExposesOnlyTheStatementsItFallsThroughTo() throws IOException {
    String source =
        """
        package p;
        class Sample {
          int run(int state, int c) {
            int out = 0;
            switch (state) {
              case 0:
              case 1: // changed
                out = c; // exposed
                break; // exposed
              case 2:
                out = -c;
                break;
            }
            return out;
          }
        }
        """;
    write(SAMPLE, source);
    assertExposed(source, List.of("run under [7]"));
  }

  @Test
  void aChangedEmptyLabelExposesEveryGroupUpToTheFirstThatEndsInAJump() throws IOException {
    String source =
        """
        package p;
        class Sample {
          int run(int state, int c) {
            int out = 0;
            switch (state) {
              case 0: // changed
              case 1:
                out = c; // exposed
              case 2:
                out++; // exposed
                { // exposed
                  return out; // exposed
                } // exposed
              case 3:
                out = -c;
                break;
            }
            return out;
          }
        }
        """;
    write(SAMPLE, source);
    assertExposed(source, List.of("run under [6]"));
  }

  @Test
  void aChangedConditionExposesBothBranchesAndNothingAfterTheIf() throws IOException {
    String source =
        """
        package p;
        class Sample {
          int run(int c) {
            int out = 0;
            if (c > 1) { // changed
              out = c; // exposed
            } else if (c < 0) { // exposed
              out = -c; // exposed
            } // exposed
            if (c == 0) {
              out = 1;
            }
            return out;
          }
        }
        """;
    write(SAMPLE, source);
    assertExposed(source, List.of("run under [5]"));
  }

  @Test
  void aChangedLoopConditionExposesTheLoopBody() throws IOException {
    String source =
        """
        package p;
        class Sample {
          int run(int c) {
            int out = 0;
            while (c > 1) { // changed
              out += c; // exposed
              c--; // exposed
            } // exposed
            for (int i = 0; i < c; i++) { // changed
              out++; // exposed
            } // exposed
            do { // exposed
              out--; // exposed
            } while (out > c); // changed
            for (int i : new int[] {c, 1}) { // changed
              out += i; // exposed
            } // exposed
            return out;
          }
        }
        """;
    write(SAMPLE, source);
    assertExposed(source, List.of("run under [5, 9, 14, 15]"));
  }

  @Test
  void aChangedStatementThatRoutesNoControlFlowExposesNothing() throws IOException {
    String source =
        """
        package p;
        class Sample {
          int run(int c) {
            int out = c + 1; // changed
            if (out > 1) {
              out = 0;
            }
            return out;
          }
        }
        """;
    write(SAMPLE, source);
    assertExposed(source, List.of());
  }

  /**
   * A call is matched by its simple name alone, so a call on another object is not followed. The
   * exposed method's own calls are not followed either, since the search goes one call deep, and a
   * called method with a changed line is measured as changed code instead.
   */
  @Test
  void aCallOnAChangedLineExposesTheBodyOfAMethodOfTheSameFile() throws IOException {
    String source =
        """
        package p;
        class Sample {
          Sample other;
          int run(int c) {
            int out = helper(c) + this.viaThis(c) + other.unrelated(c) + edited(c); // changed
            return out;
          }
          int helper(int c) {
            int twice = c * 2; // exposed
            return deeper(twice); // exposed
          }
          int viaThis(int c) {
            return c - 2; // exposed
          }
          int unrelated(int c) {
            return c - 1;
          }
          int deeper(int c) {
            return c + 3;
          }
          int edited(int c) {
            return c + 4; // changed
          }
        }
        """;
    write(SAMPLE, source);
    assertExposed(source, List.of("helper called from [5]", "viaThis called from [5]"));
  }

  @Test
  void aCallOnAnExposedLineExposesTheCalledMethodToo() throws IOException {
    String source =
        """
        package p;
        class Sample {
          int run(int c) {
            if (c > 0) { // changed
              return helper(c); // exposed
            } // exposed
            return 0;
          }
          int helper(int c) {
            return c * 2; // exposed
          }
        }
        """;
    write(SAMPLE, source);
    assertExposed(source, List.of("run under [4]", "helper called from [5]"));
  }

  @Test
  void aTestMethodWithAChangedLineIsListedByItsBinaryClassName() throws IOException {
    write(SAMPLE, "package p;\nclass Sample {}\n");
    write(
        "lib/src/test/java/p/SampleTest.java",
        """
        package p;
        import org.junit.jupiter.api.Nested;
        import org.junit.jupiter.api.Test;
        import org.junit.jupiter.params.ParameterizedTest;
        class SampleTest {
          @Test
          void untouched() {
            check(1);
          }
          @ParameterizedTest
          void edited(int c) {
            check(c + 1); // changed
          }
          void check(int c) {
            assert c > 0; // changed
          }
          @org.junit.jupiter.api.BeforeEach
          void setUp() {
            check(2); // changed
          }
          @org.testng.annotations.BeforeTest
          void beforeTest() {
            check(3); // changed
          }
          @Nested
          class WhenNested {
            @org.junit.Test
            void added() { // changed
            } // changed
          }
        }
        """);
    assertEquals(
        List.of("p.SampleTest.edited", "p.SampleTest$WhenNested.added"), changedTests(ROOT));
  }

  @Test
  void aTestOfAnotherModuleIsNotListed() throws IOException {
    write(SAMPLE, "package p;\nclass Sample {}\n");
    String test =
        """
        package p;
        class SampleTest {
          @org.junit.jupiter.api.Test
          void edited() {
            int c = 1; // changed
          }
        }
        """;
    write("lib/src/test/java/p/SampleTest.java", test);
    write("lib/sub/src/test/java/q/SampleTest.java", test.replace("package p;", "package q;"));
    write("other/src/test/java/r/SampleTest.java", test.replace("package p;", "package r;"));
    assertEquals(List.of("p.SampleTest.edited"), changedTests(ROOT));
  }

  @Test
  void aRootProjectListsOnlyTheTestsUnderItsOwnSrc() throws IOException {
    String test =
        """
        package p;
        class SampleTest {
          @org.junit.jupiter.api.Test
          void edited() {
            int c = 1; // changed
          }
        }
        """;
    write("src/test/java/p/SampleTest.java", test);
    write("other/src/test/java/r/SampleTest.java", test.replace("package p;", "package r;"));
    assertEquals(List.of("p.SampleTest.edited"), changedTests("src/main/java"));
  }

  @Test
  void aModulePrefixEndsAtTheSrcDirectoryThatHoldsTheRoot() {
    assertEquals(
        List.of("lib/src/", "src/", "generated/"),
        ExposedCode.moduleDirectories(
            List.of("lib/src/main/java", "lib/src/main/kotlin", "src/main/java", "generated")));
  }
}
