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

import io.github.cribrodev.ChangedFile;
import io.github.cribrodev.ChangedLinesFile;
import io.github.cribrodev.SourceFileKey;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;
import org.pitest.bytecode.analysis.ClassTree;
import org.pitest.classpath.CodeSource;
import org.pitest.mutationtest.build.InterceptorType;
import org.pitest.mutationtest.build.MutationInterceptor;
import org.pitest.mutationtest.engine.Mutater;
import org.pitest.mutationtest.engine.MutationDetails;

/**
 * Drops every mutant whose line the branch did not change, and records which changed lines have
 * bytecode.
 *
 * <p>A mutant is matched to its source file through the package of its class and the file name
 * the class records, which is how a {@link io.github.cribrodev.ChangedLinesFile} keys its
 * entries. PIT numbers a mutant by the line of the instruction it changes, so a statement that
 * spans several lines keeps its mutants only where the changed line holds that instruction.
 *
 * <p>The filter runs in PIT's pre-scan as well as in the main scan. A dropped mutant costs no test
 * run, and where no mutant is left on any changed line, PIT skips its coverage pass and writes no
 * report. Each scan starts by writing the changed lines with bytecode to the file {@link
 * ChangedLinesFile#WITH_BYTECODE} names, so the file exists even where PIT writes no report.
 */
final class ChangedLinesFilter implements MutationInterceptor {

  private final Map<String, NavigableSet<Integer>> changed;
  private final Map<String, String> paths;
  private final Path withBytecode;

  /**
   * @param changed the changed line numbers, keyed by {@link SourceFileKey}
   * @param paths the repository-relative path of each changed file, keyed by {@link
   *     SourceFileKey}
   * @param withBytecode the file to write the changed lines with bytecode to
   */
  ChangedLinesFilter(
      Map<String, NavigableSet<Integer>> changed, Map<String, String> paths, Path withBytecode) {
    this.changed = changed;
    this.paths = paths;
    this.withBytecode = withBytecode;
  }

  @Override
  public InterceptorType type() {
    return InterceptorType.PRE_SCAN_FILTER;
  }

  /** PIT calls this once per scan with the classes it may mutate, before it scans the first. */
  @Override
  public void initialise(CodeSource code) {
    try {
      writeLinesWithBytecode(code.codeTrees());
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot write " + withBytecode, e);
    }
  }

  /**
   * Writes the changed lines that a method of one of the classes records in its line table, in the
   * {@link ChangedLinesFile} format.
   *
   * <p>The lines of a bridge method and of a synthetic method other than a lambda body are left
   * out: the compiler generated that code. JaCoCo leaves them out of a Java class too, but keeps
   * some synthetic code of a Kotlin class, such as a {@code $default} method, and leaves out some
   * code this method counts, such as a private empty constructor, so the two counts can differ.
   */
  void writeLinesWithBytecode(Stream<ClassTree> classes) throws IOException {
    Map<String, NavigableSet<Integer>> found = new TreeMap<>();
    classes.forEach(
        tree -> {
          ClassNode node = tree.rawNode();
          if (node.sourceFile == null) {
            return;
          }
          int packageEnd = node.name.lastIndexOf('/');
          String key =
              SourceFileKey.of(
                  packageEnd < 0 ? "" : node.name.substring(0, packageEnd), node.sourceFile);
          NavigableSet<Integer> lines = changed.get(key);
          if (lines == null) {
            return;
          }
          for (MethodNode method : node.methods) {
            if (!isWritten(method)) {
              continue;
            }
            for (AbstractInsnNode instruction : method.instructions) {
              if (instruction instanceof LineNumberNode
                  && lines.contains(((LineNumberNode) instruction).line)) {
                found
                    .computeIfAbsent(key, unused -> new TreeSet<>())
                    .add(((LineNumberNode) instruction).line);
              }
            }
          }
        });
    List<ChangedFile> files = new ArrayList<>();
    found.forEach((key, lines) -> files.add(new ChangedFile(paths.get(key), key, lines)));
    ChangedLinesFile.write(withBytecode, List.of(), files);
  }

  /** Returns whether the source holds the code of the method, rather than the compiler alone. */
  private static boolean isWritten(MethodNode method) {
    if ((method.access & Opcodes.ACC_BRIDGE) != 0) {
      return false;
    }
    return (method.access & Opcodes.ACC_SYNTHETIC) == 0 || method.name.startsWith("lambda$");
  }

  @Override
  public void begin(ClassTree clazz) {}

  @Override
  public Collection<MutationDetails> intercept(
      Collection<MutationDetails> mutations, Mutater mutater) {
    return mutations.stream().filter(this::onChangedLine).collect(Collectors.toList());
  }

  @Override
  public void end() {}

  private boolean onChangedLine(MutationDetails mutation) {
    String key =
        SourceFileKey.of(
            mutation.getClassName().getPackage().asInternalName(), mutation.getFilename());
    NavigableSet<Integer> lines = changed.get(key);
    return lines != null && lines.contains(mutation.getLineNumber());
  }
}
