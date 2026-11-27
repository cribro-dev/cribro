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
package io.github.cribrodev.pitest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.cribrodev.ChangedLinesFile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;

import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.ServiceLoader;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;
import org.pitest.bytecode.analysis.ClassTree;
import org.pitest.classinfo.ClassName;
import org.pitest.mutationtest.build.InterceptorType;
import org.pitest.mutationtest.build.MutationInterceptorFactory;
import org.pitest.mutationtest.engine.Location;
import org.pitest.mutationtest.engine.MutationDetails;
import org.pitest.mutationtest.engine.MutationIdentifier;

/**
 * A mutant is kept when its line is a changed line of the source file its class records, found
 * through the package of the class and the file name.
 */
class ChangedLinesFilterTest {

  private static final ChangedLinesFilter FILTER =
      new ChangedLinesFilter(
          Map.of("com/x/A.java", lines(10, 11), "B.java", lines(5)),
          Map.of("com/x/A.java", "src/com/x/A.java", "B.java", "src/B.java"),
          Path.of("unused"));

  private static NavigableSet<Integer> lines(Integer... numbers) {
    return new TreeSet<>(List.of(numbers));
  }

  private static MutationDetails mutant(String className, String file, int line) {
    Location location = new Location(ClassName.fromString(className), "m", "()V");
    return new MutationDetails(
        new MutationIdentifier(location, 0, "NEGATE_CONDITIONALS"), file, "negated", line, 0);
  }

  /** Returns the lines of the mutants the filter keeps out of one mutant at the given line. */
  private static List<Integer> kept(String className, String file, int line) {
    return FILTER.intercept(List.of(mutant(className, file, line)), null).stream()
        .map(MutationDetails::getLineNumber)
        .toList();
  }

  @Test
  void aMutantOnAChangedLineIsKept() {
    assertEquals(List.of(11), kept("com.x.A", "A.java", 11));
  }

  @Test
  void aMutantOnTheLineAfterTheChangeIsDropped() {
    assertEquals(List.of(), kept("com.x.A", "A.java", 12));
  }

  @Test
  void aMutantOnTheLineBeforeTheChangeIsDropped() {
    assertEquals(List.of(), kept("com.x.A", "A.java", 9));
  }

  /**
   * PIT's pre-scan runs only {@code PRE_SCAN_*} interceptors and skips the coverage pass when it
   * finds no mutant, so a filter of another type leaves a change with no mutable line paying for a
   * run of the whole suite.
   */
  @Test
  void theFilterRunsInThePreScan() {
    assertEquals(InterceptorType.PRE_SCAN_FILTER, FILTER.type());
  }

  @Test
  void pitFindsTheFactoryThroughServiceLoader() {
    List<String> factories = new ArrayList<>();
    ServiceLoader.load(MutationInterceptorFactory.class)
        .forEach(factory -> factories.add(factory.getClass().getName()));

    assertTrue(
        factories.contains(ChangedLinesFilterFactory.class.getName()),
        () -> "registered factories: " + factories);
  }

  /** Without the file, the factory would have no lines to keep, and PIT would mutate every line. */
  @Test
  void theFactoryRefusesToStartWithoutTheChangedLinesFile() {
    String previous = System.clearProperty(ChangedLinesFile.PITEST_PROPERTY);
    try {
      assertThrows(
          IllegalStateException.class,
          () -> new ChangedLinesFilterFactory().createInterceptor(null));
    } finally {
      if (previous != null) {
        System.setProperty(ChangedLinesFile.PITEST_PROPERTY, previous);
      }
    }
  }

  /** Returns a class compiled from the given file, with the given methods. */
  private static ClassTree compiled(String name, String sourceFile, MethodNode... methods) {
    ClassNode node = new ClassNode();
    node.name = name;
    node.sourceFile = sourceFile;
    node.methods.addAll(List.of(methods));
    return new ClassTree(node);
  }

  /** Returns a method whose line table records the given lines. */
  private static MethodNode method(String name, int access, Integer... lines) {
    MethodNode method = new MethodNode(access, name, "()V", null, null);
    for (int line : lines) {
      LabelNode label = new LabelNode();
      method.instructions.add(label);
      method.instructions.add(new LineNumberNode(line, label));
      method.instructions.add(new InsnNode(Opcodes.NOP));
    }
    return method;
  }

  @Test
  void theLinesWithBytecodeAreTheChangedLinesTheCompilerDidNotGenerate(@TempDir Path dir)
      throws IOException {
    Path withBytecode = dir.resolve("changed-lines-with-bytecode.txt");
    new ChangedLinesFilter(
            Map.of("com/x/A.java", lines(10, 12, 13, 14, 15)),
            Map.of("com/x/A.java", "src/com/x/A.java"),
            withBytecode)
        .writeLinesWithBytecode(
            Stream.of(
                compiled(
                    "com/x/A",
                    "A.java",
                    method("m", Opcodes.ACC_PUBLIC, 10, 11),
                    method("m", Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC, 12),
                    method("access$000", Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, 13)),
                compiled(
                    "com/x/A$Inner",
                    "A.java",
                    method("lambda$m$0", Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, 14)),
                compiled("com/y/A", "A.java", method("m", Opcodes.ACC_PUBLIC, 15))));

    assertEquals(
        Map.of("com/x/A.java", lines(10, 14)),
        ChangedLinesFile.read(withBytecode),
        "lines with bytecode");
    assertEquals(
        Map.of("com/x/A.java", "src/com/x/A.java"), ChangedLinesFile.readPaths(withBytecode));
  }

  @Test
  void aClassInTheDefaultPackageRecordsItsLinesUnderItsFileName(@TempDir Path dir)
      throws IOException {
    Path withBytecode = dir.resolve("changed-lines-with-bytecode.txt");
    new ChangedLinesFilter(Map.of("B.java", lines(5)), Map.of("B.java", "src/B.java"), withBytecode)
        .writeLinesWithBytecode(
            Stream.of(compiled("B", "B.java", method("m", Opcodes.ACC_PUBLIC, 5))));

    assertEquals(Map.of("B.java", lines(5)), ChangedLinesFile.read(withBytecode));
  }

  @Test
  void aChangedFileWithNoLineWithBytecodeLeavesTheFileEmpty(@TempDir Path dir)
      throws IOException {
    Path withBytecode = dir.resolve("changed-lines-with-bytecode.txt");
    new ChangedLinesFilter(
            Map.of("com/x/A.java", lines(3)),
            Map.of("com/x/A.java", "src/com/x/A.java"),
            withBytecode)
        .writeLinesWithBytecode(
            Stream.of(compiled("com/x/A", "A.java", method("m", Opcodes.ACC_PUBLIC, 4))));

    assertEquals(Map.of(), ChangedLinesFile.read(withBytecode));
  }

  @Test
  void aNestedClassIsMatchedThroughTheFileItRecords() {
    assertEquals(List.of(10), kept("com.x.A$Inner", "A.java", 10));
  }

  @Test
  void aFileOfTheSameNameInAnotherPackageIsDropped() {
    assertEquals(List.of(), kept("com.y.A", "A.java", 10));
  }

  @Test
  void aClassInTheDefaultPackageIsMatchedByFileNameAlone() {
    assertEquals(List.of(5), kept("B", "B.java", 5));
  }
}
