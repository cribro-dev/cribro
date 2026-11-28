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
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * The per-line counters JaCoCo records, keyed by package-qualified path such as {@code
 * com/example/Sample.java}.
 *
 * <p>Where two sources record the same line, each counter keeps the larger value. JaCoCo numbers no
 * branch, so two reports covering the same line give no way to tell one branch reached twice from
 * two branches reached once. Taking the larger counter reports a branch one report reached and
 * another did not as partly taken, which asks for a test that already exists rather than hiding one
 * that is missing.
 */
public final class JacocoLines {

  /** Instruction and branch counters of one source line. */
  static final class Counters {
    final int coveredInstructions;
    final int coveredBranches;
    final int totalBranches;

    Counters(int coveredInstructions, int coveredBranches, int totalBranches) {
      this.coveredInstructions = coveredInstructions;
      this.coveredBranches = coveredBranches;
      this.totalBranches = totalBranches;
    }

    boolean isExecuted() {
      return coveredInstructions > 0;
    }

    boolean hasUntakenBranch() {
      return totalBranches > 0 && coveredBranches < totalBranches;
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof Counters)) {
        return false;
      }
      Counters that = (Counters) other;
      return coveredInstructions == that.coveredInstructions
          && coveredBranches == that.coveredBranches
          && totalBranches == that.totalBranches;
    }

    @Override
    public int hashCode() {
      return Objects.hash(coveredInstructions, coveredBranches, totalBranches);
    }

    @Override
    public String toString() {
      return "ci=" + coveredInstructions + " cb=" + coveredBranches + " tb=" + totalBranches;
    }
  }

  /** Collects the counters of a run that the caller has analyzed itself. */
  public static final class Builder {
    private final Map<String, Map<Integer, Counters>> bySourceFile = new HashMap<>();
    private final Map<String, Set<Integer>> methodLines = new HashMap<>();

    /**
     * Records that the run knows the source file, so that a file whose lines all emit no bytecode
     * is told apart from a file the run never compiled.
     */
    public Builder sourceFile(String sourceFileKey) {
      bySourceFile.computeIfAbsent(sourceFileKey, unused -> new HashMap<>());
      return this;
    }

    /** Records the counters of one line, as JaCoCo's {@code <line>} element carries them. */
    public Builder line(
        String sourceFileKey,
        int number,
        int coveredInstructions,
        int coveredBranches,
        int missedBranches) {
      Counters current =
          new Counters(coveredInstructions, coveredBranches, coveredBranches + missedBranches);
      bySourceFile
          .computeIfAbsent(sourceFileKey, unused -> new HashMap<>())
          .merge(number, current, JacocoLines::larger);
      return this;
    }

    /**
     * Records the first line of a method, lambda body included, as the {@code line} attribute of
     * JaCoCo's {@code <method>} element carries it.
     */
    public Builder method(String sourceFileKey, int firstLine) {
      methodLines.computeIfAbsent(sourceFileKey, unused -> new TreeSet<>()).add(firstLine);
      return this;
    }

    public JacocoLines build() {
      return new JacocoLines(bySourceFile, methodLines);
    }
  }

  private final Map<String, Map<Integer, Counters>> bySourceFile;
  private final Map<String, Set<Integer>> methodLines;

  private JacocoLines(
      Map<String, Map<Integer, Counters>> bySourceFile, Map<String, Set<Integer>> methodLines) {
    this.bySourceFile = bySourceFile;
    this.methodLines = methodLines;
  }

  /**
   * Returns the counters the given XML reports record.
   *
   * @throws IOException if a report cannot be read or is not well-formed XML
   */
  static JacocoLines read(List<File> reports) throws IOException {
    return read(reports, sourceFileKey -> true);
  }

  /**
   * Returns the counters the given XML reports record for the source files {@code wanted} accepts.
   *
   * <p>The reports are streamed, and the lines of every other file are skipped, so that the
   * report of a large module costs memory in proportion to the change rather than to the module.
   *
   * @throws IOException if a report cannot be read or is not well-formed XML
   */
  static JacocoLines read(List<File> reports, Predicate<String> wanted) throws IOException {
    XMLInputFactory factory = XMLInputFactory.newFactory();
    // A JaCoCo report declares report.dtd as a relative system id, and JaCoCo writes no such file
    // beside it, so a parser that loads it fails with FileNotFoundException.
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    Builder builder = new Builder();
    for (File report : reports) {
      try (InputStream in = Files.newInputStream(report.toPath())) {
        XMLStreamReader xml = factory.createXMLStreamReader(in);
        try {
          readReport(xml, wanted, builder);
        } finally {
          xml.close();
        }
      } catch (XMLStreamException e) {
        throw new IOException("cannot parse the JaCoCo report at " + report, e);
      }
    }
    return builder.build();
  }

  private static void readReport(XMLStreamReader xml, Predicate<String> wanted, Builder builder)
      throws XMLStreamException {
    String packageName = null;
    // The key of the source file being read, or null outside a source file the caller wants.
    String sourceFile = null;
    // The key of the source file the class being read was compiled from, or null where the caller
    // does not want that file or the class records none.
    String classSourceFile = null;
    while (xml.hasNext()) {
      if (xml.next() != XMLStreamConstants.START_ELEMENT) {
        continue;
      }
      switch (xml.getLocalName()) {
        case "package":
          packageName = xml.getAttributeValue(null, "name");
          sourceFile = null;
          classSourceFile = null;
          break;
        case "class":
          String sourceFileName = xml.getAttributeValue(null, "sourcefilename");
          classSourceFile =
              sourceFileName == null ? null : SourceFileKey.of(packageName, sourceFileName);
          if (classSourceFile != null && !wanted.test(classSourceFile)) {
            classSourceFile = null;
          }
          break;
        case "method":
          // JaCoCo leaves the attribute out for a method compiled without line numbers.
          int firstLine = attribute(xml, "line");
          if (classSourceFile != null && firstLine > 0) {
            builder.method(classSourceFile, firstLine);
          }
          break;
        case "sourcefile":
          String key = SourceFileKey.of(packageName, xml.getAttributeValue(null, "name"));
          sourceFile = wanted.test(key) ? key : null;
          if (sourceFile != null) {
            builder.sourceFile(sourceFile);
          }
          break;
        case "line":
          if (sourceFile != null) {
            builder.line(
                sourceFile,
                attribute(xml, "nr"),
                attribute(xml, "ci"),
                attribute(xml, "cb"),
                attribute(xml, "mb"));
          }
          break;
        default:
          break;
      }
    }
  }

  /** Returns the counters recorded for one line, or null where the report mentions no such line. */
  Counters get(String sourceFileKey, int line) {
    Map<Integer, Counters> lines = bySourceFile.get(sourceFileKey);
    return lines == null ? null : lines.get(line);
  }

  /**
   * Returns whether the line is the first line of a method or a lambda body of the given source
   * file, as the {@code line} attribute of JaCoCo's {@code <method>} element carries it: the first
   * line that holds an instruction, which for an empty body is its closing brace.
   */
  boolean startsMethod(String sourceFileKey, int line) {
    Set<Integer> lines = methodLines.get(sourceFileKey);
    return lines != null && lines.contains(line);
  }

  /** Returns whether any report read here mentions the given package-qualified source path. */
  boolean covers(String sourceFileKey) {
    return bySourceFile.containsKey(sourceFileKey);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof JacocoLines
        && bySourceFile.equals(((JacocoLines) other).bySourceFile)
        && methodLines.equals(((JacocoLines) other).methodLines);
  }

  @Override
  public int hashCode() {
    return Objects.hash(bySourceFile, methodLines);
  }

  @Override
  public String toString() {
    return bySourceFile + " methods " + methodLines;
  }

  private static Counters larger(Counters first, Counters second) {
    return new Counters(
        Math.max(first.coveredInstructions, second.coveredInstructions),
        Math.max(first.coveredBranches, second.coveredBranches),
        Math.max(first.totalBranches, second.totalBranches));
  }

  private static int attribute(XMLStreamReader xml, String name) {
    String value = xml.getAttributeValue(null, name);
    return value == null || value.isEmpty() ? 0 : Integer.parseInt(value);
  }
}
