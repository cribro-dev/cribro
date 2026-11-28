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

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Renders the mutants a PIT run left alive, grouped by source file and line.
 *
 * <p>A mutant is live when its status is {@code SURVIVED} or {@code NO_COVERAGE}: the tests that
 * reached its line passed anyway, or no test reached it. {@code KILLED}, {@code TIMED_OUT}, and
 * {@code MEMORY_ERROR} mean the tests noticed the mutant and are counted as killed, and the count
 * names how many of them timed out or ran out of memory. Any other status, such as {@code
 * NON_VIABLE} for a mutant the JVM would not load, says nothing about the tests and is counted
 * under its own name.
 *
 * <p>Each line is printed once, with its source text, every live mutant on it, the number of tests
 * PIT recorded as reaching it, and the few of them that reach the most live mutants of the report.
 * A parameterized or dynamic test counts as one test, and its name lists the invocations that
 * reach the line, such as {@code CoreQuidemTest.test[1-3, 33]}. The mutants and their tests come
 * from the {@link MutantsFile} the PIT listener writes.
 */
public final class PitestSurvivors {

  /**
   * What the reader of a report with live mutants does with each of them.
   *
   * <p>The report carries it rather than the instructions every session loads, since only the
   * reader of a report needs it.
   */
  static final String WHAT_TO_DO =
      "For each live mutant, decide which case it is:\n"
          + "  - the tests miss behavior the change defines: add or strengthen a test,"
          + " next to the tests named for its line\n"
          + "  - no caller can observe the difference, so the mutant is equivalent:"
          + " say so in the pull request\n"
          + "  - you cannot tell: report it as unresolved";

  /**
   * What a run in which no test reached any changed line means.
   *
   * <p>Code no test of the module runs and code whose tests fail under PIT produce the same report,
   * so the reader is told both and where PIT records a test that failed.
   */
  static final String NOTHING_REACHED =
      "No test reached any of the changed lines. Either no test of this module exercises this code,"
          + " or the tests that do failed under PIT, which leaves them out silently; pit.log beside"
          + " the report names each test that failed.";

  /**
   * Why a changed line can have no mutant, for a reader who expected one.
   *
   * <p>It reflects PIT's default {@code avoidCallsTo} list, which the pitestDiff task keeps.
   */
  static final String AVOIDED_CALLS =
      "PIT generates no mutant on a line that calls a logging library.";

  /** How many of the tests reaching a line are named. */
  static final int TESTS_SHOWN = 3;

  private static final Pattern DESCRIPTION_SUFFIX = Pattern.compile("\\s+for \\S+::\\S+$");

  private static final Pattern LAMBDA = Pattern.compile("lambda\\$(.+)\\$\\d+");

  /** One live mutant, as the report prints it. */
  private static final class Mutant {
    private final String description;
    private final String method;
    private final Map<String, NavigableSet<List<Integer>>> tests;

    /**
     * @param tests the short name of each test that reaches the mutant, with the invocations of
     *     it PIT named; the set is empty for a test PIT names with no invocation
     */
    Mutant(String description, String method, Map<String, NavigableSet<List<Integer>>> tests) {
      this.description = description;
      this.method = method;
      this.tests = tests;
    }

    String description() {
      return description;
    }

    String method() {
      return method;
    }

    Map<String, NavigableSet<List<Integer>>> tests() {
      return tests;
    }
  }

  /** The live mutants of one source line, and the tests that reach it. */
  private static final class Line {
    private final int number;
    private final String source;
    private final List<Mutant> mutants;
    private final Map<String, NavigableSet<List<Integer>>> tests;

    Line(
        int number,
        String source,
        List<Mutant> mutants,
        Map<String, NavigableSet<List<Integer>>> tests) {
      this.number = number;
      this.source = source;
      this.mutants = mutants;
      this.tests = tests;
    }

    int number() {
      return number;
    }

    String source() {
      return source;
    }

    List<Mutant> mutants() {
      return mutants;
    }

    Map<String, NavigableSet<List<Integer>>> tests() {
      return tests;
    }
  }

  /** The live mutants of one source file. */
  private static final class FileMutants {
    private final String path;
    private final List<Line> lines;
    private final int mutantCount;

    FileMutants(String path, List<Line> lines) {
      this.path = path;
      this.lines = lines;
      this.mutantCount = lines.stream().mapToInt(line -> line.mutants().size()).sum();
    }

    String path() {
      return path;
    }

    List<Line> lines() {
      return lines;
    }

    int mutantCount() {
      return mutantCount;
    }
  }

  private final String scope;
  private final Comparator<String> testOrder;
  private final Map<String, Integer> statusCounts;
  private final int total;
  private final int mutatedLines;
  private final int linesWithBytecode;
  private final List<FileMutants> files;

  /**
   * @param mutatedLines the number of changed lines that carry at least one mutant
   * @param linesWithBytecode the number of changed lines with bytecode, or -1 where the run did not
   *     record them
   */
  private PitestSurvivors(
      String scope,
      Comparator<String> testOrder,
      Map<String, Integer> statusCounts,
      int total,
      int mutatedLines,
      int linesWithBytecode,
      List<FileMutants> files) {
    this.scope = scope;
    this.testOrder = testOrder;
    this.statusCounts = statusCounts;
    this.total = total;
    this.mutatedLines = mutatedLines;
    this.linesWithBytecode = linesWithBytecode;
    this.files = files;
  }

  /**
   * Reads a PIT run.
   *
   * @param scope what the run measured against, printed in the first line
   * @param mutantsFile the {@link MutantsFile} the run wrote, or a missing file where PIT generated
   *     no mutant on the changed lines and wrote no report
   * @param repository the directory the printed paths are relative to
   * @param sourceRoots absolute source directories, searched in order for a mutated file that
   *     {@code paths} does not name or names at a path that no longer exists
   * @param paths the repository-relative path of each changed file, keyed by {@link
   *     SourceFileKey}, as the {@link ChangedLinesFile} of the run records them; a Kotlin file may
   *     lie in a directory that does not follow its package, so its key alone finds no file
   * @param linesWithBytecode the changed lines with bytecode that hold code, as {@link
   *     CodeLines#holdsCode} decides, keyed by {@link SourceFileKey}, as the run records them in
   *     the file {@link ChangedLinesFile#WITH_BYTECODE} names, or null
   *     where the run wrote no such file; the report then counts the lines that carry a mutant
   *     and gives no total
   */
  public static PitestSurvivors read(
      String scope,
      File mutantsFile,
      File repository,
      List<File> sourceRoots,
      Map<String, String> paths,
      Map<String, ? extends Set<Integer>> linesWithBytecode)
      throws IOException {
    Set<String> withBytecode = new HashSet<>();
    if (linesWithBytecode != null) {
      linesWithBytecode.forEach(
          (key, lines) -> lines.forEach(line -> withBytecode.add(key + ":" + line)));
    }
    if (!mutantsFile.isFile()) {
      return new PitestSurvivors(
          scope,
          Comparator.naturalOrder(),
          new LinkedHashMap<>(),
          0,
          0,
          linesWithBytecode == null ? -1 : withBytecode.size(),
          new ArrayList<>());
    }
    List<MutantsFile.Mutant> reported = MutantsFile.read(mutantsFile.toPath());
    Set<String> mutated = new HashSet<>();
    Map<String, Integer> statusCounts = new LinkedHashMap<>();
    Map<String, TreeMap<Integer, List<Mutant>>> byFile = new HashMap<>();
    // Every mutant of a line names the tests that reach the line, so the same test names repeat
    // once per mutant, and each is shortened once.
    Map<String, TestName> shortTestNames = new HashMap<>();
    for (MutantsFile.Mutant mutant : reported) {
      statusCounts.merge(mutant.status(), 1, Integer::sum);
      int packageEnd = mutant.mutatedClass().lastIndexOf('.');
      String relative =
          SourceFileKey.of(
              packageEnd < 0 ? "" : mutant.mutatedClass().substring(0, packageEnd).replace('.', '/'),
              mutant.sourceFile());
      mutated.add(relative + ":" + mutant.lineNumber());
      if (!isLive(mutant.status())) {
        continue;
      }
      Map<String, NavigableSet<List<Integer>>> tests = new TreeMap<>();
      for (String test : mutant.tests()) {
        TestName name = shortTestNames.computeIfAbsent(test, PitestSurvivors::shortTestName);
        NavigableSet<List<Integer>> invocations =
            tests.computeIfAbsent(name.name(), unused -> new TreeSet<>(INVOCATION_ORDER));
        if (!name.invocation().isEmpty()) {
          invocations.add(name.invocation());
        }
      }
      String description =
          (mutant.status().equals("NO_COVERAGE") ? "no test reaches it: " : "survived: ")
              + DESCRIPTION_SUFFIX.matcher(mutant.description()).replaceFirst("");
      byFile
          .computeIfAbsent(relative, unused -> new TreeMap<>())
          .computeIfAbsent(mutant.lineNumber(), unused -> new ArrayList<>())
          .add(new Mutant(description, readableMethod(mutant.method()), tests));
    }
    SourceLines sourceLines = SourceLines.under(repository);
    List<FileMutants> files = new ArrayList<>();
    for (Map.Entry<String, TreeMap<Integer, List<Mutant>>> file : byFile.entrySet()) {
      String known = paths.get(file.getKey());
      File source =
          known != null && new File(repository, known).isFile()
              ? new File(repository, known)
              : locate(sourceRoots, file.getKey());
      String path =
          source == null ? file.getKey() : SourceLines.relativize(repository, source);
      List<Line> lines = new ArrayList<>();
      for (Map.Entry<Integer, List<Mutant>> line : file.getValue().entrySet()) {
        int number = line.getKey();
        Map<String, NavigableSet<List<Integer>>> tests = new TreeMap<>();
        for (Mutant mutant : line.getValue()) {
          mutant
              .tests()
              .forEach(
                  (name, invocations) ->
                      tests
                          .computeIfAbsent(name, unused -> new TreeSet<>(INVOCATION_ORDER))
                          .addAll(invocations));
        }
        lines.add(
            new Line(
                number,
                source == null ? "" : sourceLines.get(path, number).trim(),
                line.getValue(),
                tests));
      }
      files.add(new FileMutants(path, lines));
    }
    files.sort(
        Comparator.comparingInt(FileMutants::mutantCount)
            .reversed()
            .thenComparing(FileMutants::path));
    // A line most of the suite reaches is reached by tests unrelated to the change, so the tests
    // named for it are the ones that reach the most live mutants of the whole report.
    Map<String, Integer> liveMutantsReached = new HashMap<>();
    for (TreeMap<Integer, List<Mutant>> lines : byFile.values()) {
      for (List<Mutant> mutants : lines.values()) {
        for (Mutant mutant : mutants) {
          mutant.tests().keySet().forEach(test -> liveMutantsReached.merge(test, 1, Integer::sum));
        }
      }
    }
    Comparator<String> testOrder =
        Comparator.<String>comparingInt(test -> liveMutantsReached.getOrDefault(test, 0))
            .reversed()
            .thenComparing(Comparator.naturalOrder());
    // A line that carries a mutant has bytecode, so the total counts it whether the run recorded
    // the line or not.
    withBytecode.addAll(mutated);
    return new PitestSurvivors(
        scope,
        testOrder,
        statusCounts,
        reported.size(),
        mutated.size(),
        linesWithBytecode == null ? -1 : withBytecode.size(),
        files);
  }

  /**
   * Writes the whole report to a file and returns the text to print, cut to the limits.
   *
   * <p>The printed text ends with the path of that file and of each path in {@code otherPaths}, so
   * that a reader of a cut report knows where the rest is.
   *
   * @param outputFile the file the whole report is written to
   * @param otherPaths further files to name after the report, relative to the repository; a path
   *     that names no file is not printed
   */
  public String writeAndRender(
      File repository,
      File outputFile,
      int maxFiles,
      int maxLinesPerFile,
      int maxLines,
      boolean showAll,
      List<String> otherPaths)
      throws IOException {
    List<String> paths = new ArrayList<>();
    paths.add(SourceLines.relativize(repository, outputFile));
    // A run that analyzed no mutant leaves no HTML report, and a path that names no file is left out.
    otherPaths =
        otherPaths.stream()
            .filter(path -> new File(repository, path).exists())
            .collect(Collectors.toUnmodifiableList());
    paths.addAll(otherPaths);
    Files.createDirectories(outputFile.toPath().getParent());
    Files.writeString(
        outputFile.toPath(), render(DiffCoverageReport.Limits.NONE, otherPaths) + "\n");
    return render(
        showAll
            ? DiffCoverageReport.Limits.NONE
            : new DiffCoverageReport.Limits(maxFiles, maxLinesPerFile, maxLines),
        paths);
  }

  /** Returns whether a mutant with this status went unnoticed by the tests. */
  private static boolean isLive(String status) {
    return status.equals("SURVIVED") || status.equals("NO_COVERAGE");
  }

  /**
   * Returns the report text.
   *
   * @param limits how much of the report to print; {@link DiffCoverageReport.Limits#NONE} prints
   *     it whole
   * @param fullReportPaths repository-relative paths of the files that hold more, printed last
   */
  String render(DiffCoverageReport.Limits limits, List<String> fullReportPaths) {
    StringBuilder out = new StringBuilder();
    out.append("Mutation testing of changed lines ").append(scope).append('\n');
    if (total == 0) {
      out.append("  no mutant: ");
      if (linesWithBytecode < 0) {
        out.append("PIT generated none on the changed lines");
      } else if (linesWithBytecode == 0) {
        out.append("no changed line has bytecode PIT may mutate");
      } else {
        out.append("PIT generated none on the ")
            .append(linesWithBytecode)
            .append(' ')
            .append(changedLines(linesWithBytecode));
      }
    } else {
      out.append("  ")
          .append(total)
          .append(total == 1 ? " mutant on " : " mutants on ")
          .append(mutatedLines);
      if (linesWithBytecode >= 0) {
        out.append(" of ").append(linesWithBytecode);
      }
      out.append(' ')
          .append(changedLines(linesWithBytecode >= 0 ? linesWithBytecode : mutatedLines))
          .append(": ")
          .append(counts());
    }
    int liveLines = files.stream().mapToInt(file -> file.lines().size()).sum();
    if (liveLines > 0) {
      out.append("\n  ordered by live mutants per file, then by line");
    }
    if (total > 0 && statusCounts.getOrDefault("NO_COVERAGE", 0) == total) {
      out.append("\n\n").append(NOTHING_REACHED);
    }
    int filesLeft = limits.files;
    int rowsLeft = limits.lines;
    int filesShown = 0;
    for (FileMutants file : files) {
      if (filesLeft == 0 || rowsLeft == 0) {
        break;
      }
      filesLeft--;
      filesShown++;
      out.append("\n\n  ").append(file.path());
      int allowed = Math.min(limits.linesPerFile, rowsLeft);
      int shown = 0;
      for (Line line : file.lines()) {
        if (shown == allowed) {
          break;
        }
        shown++;
        appendLine(out, line);
      }
      rowsLeft -= shown;
      if (shown < file.lines().size()) {
        int more = file.lines().size() - shown;
        out.append("\n    ").append(more).append(more == 1 ? " more line" : " more lines");
      }
    }
    if (filesShown < files.size()) {
      int more = files.size() - filesShown;
      out.append("\n\n  ").append(more).append(more == 1 ? " more file" : " more files");
    }
    if (liveLines > 0) {
      out.append("\n\nLive mutants on ")
          .append(liveLines)
          .append(liveLines == 1 ? " line" : " lines")
          .append(" in ")
          .append(files.size())
          .append(files.size() == 1 ? " file." : " files.")
          .append("\n\n")
          .append(WHAT_TO_DO);
    }
    // The note explains a changed line with no mutant. Where the run did not record the lines with
    // bytecode, any changed line may lack one, so the note goes only with a report that already
    // asks the reader to act: a mutant survived, or none was generated.
    boolean unmutatedLine =
        linesWithBytecode < 0 ? liveLines > 0 || total == 0 : mutatedLines < linesWithBytecode;
    if (unmutatedLine) {
      out.append("\n\n").append(AVOIDED_CALLS);
    }
    if (!fullReportPaths.isEmpty()) {
      out.append("\n\nFull report:");
      for (String path : fullReportPaths) {
        out.append("\n  ").append(path);
      }
    }
    return out.toString();
  }

  /** Returns the noun that follows a count of changed lines. */
  private static String changedLines(int count) {
    return count == 1 ? "changed line" : "changed lines";
  }

  /**
   * The statuses that mean a test noticed the mutant, each with the words that name its share of
   * the killed count, or null for {@code KILLED}, whose share is the rest.
   */
  private static final Map<String, String> KILLED = killedStatuses();

  private static Map<String, String> killedStatuses() {
    Map<String, String> statuses = new LinkedHashMap<>();
    statuses.put("KILLED", null);
    statuses.put("TIMED_OUT", "timed out");
    statuses.put("MEMORY_ERROR", "out of memory");
    return statuses;
  }

  /**
   * Returns the status counts, killed first, in the words the report uses. The killed count names
   * how many of its mutants timed out or ran out of memory, where any did.
   */
  private String counts() {
    int killed = 0;
    List<String> shares = new ArrayList<>();
    for (Map.Entry<String, String> status : KILLED.entrySet()) {
      int count = statusCounts.getOrDefault(status.getKey(), 0);
      killed += count;
      if (status.getValue() != null && count > 0) {
        shares.add(count + " " + status.getValue());
      }
    }
    List<String> parts = new ArrayList<>();
    parts.add(
        killed + " killed" + (shares.isEmpty() ? "" : " (" + String.join(", ", shares) + ")"));
    parts.add(statusCounts.getOrDefault("SURVIVED", 0) + " survived");
    parts.add(statusCounts.getOrDefault("NO_COVERAGE", 0) + " not reached by any test");
    for (Map.Entry<String, Integer> entry : statusCounts.entrySet()) {
      if (!isLive(entry.getKey()) && !KILLED.containsKey(entry.getKey())) {
        parts.add(entry.getValue() + " " + entry.getKey());
      }
    }
    return String.join(", ", parts);
  }

  private void appendLine(StringBuilder out, Line line) {
    out.append("\n    ").append(line.number()).append(": ").append(line.source());
    for (Mutant mutant : line.mutants()) {
      out.append("\n        ").append(mutant.description()).append(", in ").append(mutant.method());
    }
    if (!line.tests().isEmpty()) {
      List<String> tests = new ArrayList<>(line.tests().keySet());
      tests.sort(testOrder);
      out.append("\n        reached by ")
          .append(tests.size())
          .append(tests.size() == 1 ? " test" : " tests")
          .append(tests.size() > TESTS_SHOWN ? ", among them:" : ":");
      for (String test : tests.subList(0, Math.min(TESTS_SHOWN, tests.size()))) {
        out.append("\n          ").append(test).append(invocations(line.tests().get(test)));
      }
    }
  }

  /** Returns the method name as a reader finds it in the source. */
  static String readableMethod(String method) {
    if (method.equals("<init>")) {
      return "a constructor";
    }
    if (method.equals("<clinit>")) {
      return "the static initializer";
    }
    Matcher lambda = LAMBDA.matcher(method);
    return lambda.matches() ? "a lambda in " + lambda.group(1) : method;
  }

  /**
   * A test as the report names it: the short name of the test, and the index of the invocation,
   * where the name PIT recorded carries one.
   */
  static final class TestName {
    private final String name;
    private final List<Integer> invocation;

    TestName(String name, List<Integer> invocation) {
      this.name = name;
      this.invocation = invocation;
    }

    String name() {
      return name;
    }

    /**
     * Returns the index of the invocation as the test engine numbers it, one number per level where
     * invocations nest, as dynamic tests in a dynamic container do; empty for a test with no
     * invocation.
     */
    List<Integer> invocation() {
      return invocation;
    }

    /** Returns the name the report prints for this invocation alone, such as {@code T.m[3]}. */
    @Override
    public String toString() {
      return invocation.isEmpty() ? name : name + "[" + index(invocation) + "]";
    }
  }

  /**
   * Returns {@code Class.method} for a test PIT names {@code pkg.Class.method(pkg.Class)}, and
   * {@code Class$Nested.method} for a JUnit Platform test, which PIT names by its unique id, such as
   * {@code pkg.Class.[engine:junit-jupiter]/[class:pkg.Class]/[nested-class:Nested]/[test-template:
   * method(int)]/[test-template-invocation:#3]}.
   *
   * <p>Each segment of a unique id is read by its type, after its value is decoded. A class segment,
   * such as {@code class}, {@code class-template}, or Vintage's {@code runner}, starts the name
   * afresh; a nested class segment adds {@code $Nested}. The last method segment names the method:
   * {@code method}, {@code test-template}, {@code test-factory}, or jqwik's {@code property}, and
   * Vintage's {@code test}, whose value is a JUnit 4 description such as {@code method[3](pkg.Class)}.
   * The invocation lists, outer level first, the number of each segment after the last class segment
   * whose value is {@code #} and a number, here {@code 3}, and the index in brackets that ends a
   * JUnit 4 method name. A JUnit 4 parameterized test that PIT runs itself is named {@code
   * pkg.Class.method[3](pkg.Class)}, with its index in brackets too. A JUnit Platform id with no
   * class segment this method knows is returned whole, since cutting it at dots would produce a name
   * no test has.
   */
  static TestName shortTestName(String name) {
    if (!name.contains("[engine:")) {
      int paren = name.lastIndexOf('(');
      String testClass =
          paren >= 0 && name.endsWith(")") ? name.substring(paren + 1, name.length() - 1) : null;
      if (testClass != null && name.startsWith(testClass + ".")) {
        TestName method = junit4Method(name.substring(testClass.length() + 1, paren));
        return new TestName(
            testClass.substring(testClass.lastIndexOf('.') + 1) + "." + method.name(),
            method.invocation());
      }
      TestName method = junit4Method(paren < 0 ? name : name.substring(0, paren));
      String qualified = method.name();
      int methodStart = qualified.lastIndexOf('.');
      int classStart = qualified.lastIndexOf('.', methodStart - 1);
      return new TestName(qualified.substring(classStart + 1), method.invocation());
    }
    Matcher segment = PLATFORM_SEGMENT.matcher(name);
    StringBuilder shortName = new StringBuilder();
    String method = null;
    List<Integer> invocation = new ArrayList<>();
    while (segment.find()) {
      String type = segment.group(1);
      String value = decode(segment.group(2));
      Matcher index = INVOCATION_INDEX.matcher(value);
      if (CLASS_SEGMENTS.contains(type)) {
        shortName.setLength(0);
        shortName.append(value.substring(value.lastIndexOf('.') + 1));
        method = null;
        invocation.clear();
      } else if (NESTED_CLASS_SEGMENTS.contains(type)) {
        shortName.append('$').append(value);
      } else if (METHOD_SEGMENTS.contains(type)) {
        int paren = value.lastIndexOf('(');
        method = paren < 0 ? value : value.substring(0, paren);
      } else if (index.matches()) {
        invocation.add(Integer.valueOf(index.group(1)));
      }
    }
    if (shortName.length() == 0) {
      return new TestName(name, List.of());
    }
    if (method == null) {
      return new TestName(shortName.toString(), List.copyOf(invocation));
    }
    TestName junit4 = junit4Method(method);
    invocation.addAll(junit4.invocation());
    return new TestName(shortName + "." + junit4.name(), List.copyOf(invocation));
  }

  /**
   * Returns a JUnit 4 method name without the index in brackets that ends it, such as {@code m}
   * and {@code 3} for {@code m[3]}; a name with no such index is returned whole.
   */
  private static TestName junit4Method(String method) {
    Matcher index = JUNIT4_INDEX.matcher(method);
    return index.matches()
        ? new TestName(index.group(1), List.of(Integer.valueOf(index.group(2))))
        : new TestName(method, List.of());
  }

  /**
   * Returns the value of a unique-id segment with its {@code %XX} escapes decoded. The JUnit
   * Platform escapes {@code %}, {@code +}, and the characters that delimit segments, so a value
   * holds no other {@code %} and no {@code +}; a value that is not escaped that way is returned as
   * it is.
   */
  private static String decode(String value) {
    try {
      return URLDecoder.decode(value, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return value;
    }
  }

  /**
   * Returns the invocations of one test in brackets, such as {@code [1-3, 33]}, with each run of
   * consecutive indexes as a range; empty for a test with no invocation.
   */
  static String invocations(NavigableSet<List<Integer>> invocations) {
    if (invocations.isEmpty()) {
      return "";
    }
    List<String> parts = new ArrayList<>();
    List<Integer> first = null;
    List<Integer> last = null;
    for (List<Integer> invocation : invocations) {
      if (last != null && follows(last, invocation)) {
        last = invocation;
        continue;
      }
      if (first != null) {
        parts.add(range(first, last));
      }
      first = invocation;
      last = invocation;
    }
    parts.add(range(first, last));
    return "[" + String.join(", ", parts) + "]";
  }

  /** Returns whether the invocation comes right after the previous one of the same level. */
  private static boolean follows(List<Integer> previous, List<Integer> invocation) {
    int depth = invocation.size();
    return previous.size() == depth
        && previous.subList(0, depth - 1).equals(invocation.subList(0, depth - 1))
        && previous.get(depth - 1) + 1 == invocation.get(depth - 1);
  }

  private static String range(List<Integer> first, List<Integer> last) {
    return first.equals(last) ? index(first) : index(first) + "-" + last.get(last.size() - 1);
  }

  /** Returns the index of an invocation, its levels separated by {@code /}, such as {@code 2/5}. */
  private static String index(List<Integer> invocation) {
    return invocation.stream().map(String::valueOf).collect(Collectors.joining("/"));
  }

  /** Orders invocations by their indexes, as numbers, outer level first. */
  private static final Comparator<List<Integer>> INVOCATION_ORDER =
      (a, b) -> {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
          int order = Integer.compare(a.get(i), b.get(i));
          if (order != 0) {
            return order;
          }
        }
        return Integer.compare(a.size(), b.size());
      };

  /**
   * The digits of an invocation index: a decimal with no leading zero and at most 9 digits, so it
   * always fits an {@code int}. A JUnit 4 name can hold any text in brackets, such as an 11-digit
   * parameter, and the name keeps what does not match.
   */
  private static final String INDEX = "(0|[1-9]\\d{0,8})";

  /** The value of a JUnit Platform segment naming one invocation, such as {@code #3}. */
  private static final Pattern INVOCATION_INDEX = Pattern.compile("#" + INDEX);

  /** A JUnit 4 parameterized test method with its default name, such as {@code pkg.C.m[3]}. */
  private static final Pattern JUNIT4_INDEX = Pattern.compile("(.*)\\[" + INDEX + "\\]");

  private static final Pattern PLATFORM_SEGMENT = Pattern.compile("\\[([a-z-]+):([^\\]]*)\\]");

  /**
   * Segments whose value is the binary name of a test class: Jupiter's, including those of a class
   * template and of a nested class named in full, Vintage's, and Spock's.
   */
  private static final Set<String> CLASS_SEGMENTS =
      Set.of(
          "class",
          "class-template",
          "fq-nested-class",
          "fq-nested-class-template",
          "runner",
          "spec");

  /** Segments whose value is the simple name of a class nested in the class before it. */
  private static final Set<String> NESTED_CLASS_SEGMENTS =
      Set.of("nested-class", "nested-class-template");

  /** Segments naming a test method: Jupiter's, Vintage's, and jqwik's. */
  private static final Set<String> METHOD_SEGMENTS =
      Set.of("method", "test-template", "test-factory", "test", "property");

  /** Returns the first file under the source roots with this relative path, or null. */
  private static File locate(List<File> sourceRoots, String path) {
    for (File root : sourceRoots) {
      File source = new File(root, path);
      if (source.isFile()) {
        return source;
      }
    }
    return null;
  }

}
