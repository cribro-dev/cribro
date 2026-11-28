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

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * Finds the unchanged lines that a change sends new inputs to, and the test methods it added or
 * edited.
 *
 * <p>A changed line that decides where control goes, such as a {@code case} label, the condition
 * of an {@code if} or a loop, or a call, can route new inputs into lines nobody edited. Those lines
 * are not owed a test by the change, but they run under conditions they did not run under before,
 * and line coverage of the changed lines alone says nothing about them.
 *
 * <p>Three shapes count:
 *
 * <ul>
 *   <li>a changed {@code case} label exposes the statements it falls through to, up to the end
 *       of the first group whose last statement is a {@code break}, {@code continue}, {@code
 *       return}, or {@code throw};
 *   <li>a changed condition of an {@code if}, {@code while}, {@code do}, or {@code for} exposes the
 *       statements it guards, including an {@code else} chain;
 *   <li>a call on a changed or exposed line exposes the body of the method it calls, where that
 *       method is declared in the same file under the same name, one call deep.
 * </ul>
 *
 * <p>Sources are parsed with the javac of the running JDK, without resolving a symbol, so a call is
 * matched by its simple name and every overload of that name counts. Where the JVM has no javac,
 * nothing is found.
 *
 * <p>A region names its method by the signature as the source spells it, without annotations and
 * type arguments: {@code helper(int)}, {@code helper(List, String...)}. The names of the enclosing
 * classes come first, as in {@code Builder.build()}, except the name of the class a file holds
 * alone at its top level; an anonymous class is named after its supertype, as in {@code (anonymous
 * Runnable).run()}, and a constructor is {@code new} and its class, as in {@code new Builder(int)}.
 * Where two methods of a file get one name, such as overloads that differ in the bound of a type
 * variable or methods of two local classes, each name ends with the line its declaration starts on,
 * as in {@code f(T) at line 12}. Two such methods whose declarations start on one line still share
 * a name.
 */
final class ExposedCode {

  /** Unchanged lines of one method that a change reaches through the lines named as triggers. */
  static final class Region {
    final String path;

    /** The name of the method, as the class comment of {@link ExposedCode} spells it. */
    final String method;

    /** The changed lines that route control here, or the call sites for a called method. */
    final NavigableSet<Integer> triggers = new TreeSet<>();

    /** Whether the method is reached through a call rather than through its own control flow. */
    final boolean called;

    final NavigableSet<Integer> lines = new TreeSet<>();

    Region(String path, String method, boolean called) {
      this.path = path;
      this.method = method;
      this.called = called;
    }
  }

  /** A test method the change added or edited, as a test filter names it. */
  static final class TestMethod {
    /** The binary name of the class, with {@code $} before a nested class. */
    final String className;

    final String method;

    TestMethod(String className, String method) {
      this.className = className;
      this.method = method;
    }

    @Override
    public String toString() {
      return className + "." + method;
    }
  }

  /**
   * Annotations whose simple name marks a test method in JUnit 4, JUnit Jupiter, TestNG, and
   * jqwik, such as {@code Test}, {@code ParameterizedTest}, {@code TestFactory}, and {@code
   * Property}; TestNG's {@code BeforeTest} and {@code AfterTest} mark setup and teardown.
   */
  private static final Pattern TEST_ANNOTATION =
      Pattern.compile("(?!Before|After)(?:\\w+Test|Test\\w*|Property|Example)");

  final List<Region> regions = new ArrayList<>();
  final List<TestMethod> changedTests = new ArrayList<>();

  /** Whether the JVM had no javac to parse with, so that the report can say why it is empty. */
  boolean noParser;

  private ExposedCode() {}

  /**
   * Parses the changed files and returns what the change exposes in this module.
   *
   * <p>A changed file under one of the source roots is searched for exposed lines. Any other
   * changed Java file under the {@code src} directory that holds the roots, such as {@code
   * src/test/java} beside {@code src/main/java}, is searched for test methods with a changed line.
   * Tests kept in a module of their own are not found.
   *
   * @param repository the working tree the paths are relative to
   * @param changed the changed lines, keyed by repository-relative path
   * @param sourceRoots repository-relative source directories of the module
   */
  static ExposedCode find(
      File repository, Map<String, NavigableSet<Integer>> changed, List<String> sourceRoots) {
    ExposedCode result = new ExposedCode();
    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    if (javac == null) {
      result.noParser = true;
      return result;
    }
    List<String> moduleDirectories = moduleDirectories(sourceRoots);
    for (Map.Entry<String, NavigableSet<Integer>> entry : new TreeMap<>(changed).entrySet()) {
      String path = entry.getKey();
      if (!path.endsWith(".java")) {
        continue;
      }
      boolean main = ChangedLines.underSourceRoot(path, sourceRoots) != null;
      if (!main && moduleDirectories.stream().noneMatch(path::startsWith)) {
        continue;
      }
      Parsed parsed = Parsed.of(javac, repository, path);
      if (parsed == null) {
        continue;
      }
      if (main) {
        result.regions.addAll(parsed.exposed(path, entry.getValue()));
      } else {
        result.changedTests.addAll(parsed.testMethods(entry.getValue()));
      }
    }
    return result;
  }

  /**
   * Returns the prefixes of the paths that belong to the module of the source roots: the {@code
   * src} directory that holds a root, such as {@code pgjdbc/src/} for {@code pgjdbc/src/main/java},
   * or the root itself where it has no {@code src} segment.
   *
   * <p>Ending at {@code src/} keeps a module out of its neighbors' paths: a root project's {@code
   * src/} prefix does not match {@code other/src/test/java}, and {@code lib/src/} does not match a
   * nested module's {@code lib/sub/src/test/java}.
   */
  static List<String> moduleDirectories(List<String> sourceRoots) {
    List<String> directories = new ArrayList<>();
    for (String sourceRoot : sourceRoots) {
      String root = sourceRoot.replace('\\', '/');
      int src = root.lastIndexOf("/src/");
      String directory =
          root.startsWith("src/") ? "src/" : src >= 0 ? root.substring(0, src + 5) : root + "/";
      if (!directories.contains(directory)) {
        directories.add(directory);
      }
    }
    return directories;
  }

  /** A call by simple name, resolved against the file's methods once the whole file is scanned. */
  private static final class Call {
    final MethodTree caller;
    final String name;
    final int line;

    Call(MethodTree caller, String name, int line) {
      this.caller = caller;
      this.name = name;
      this.line = line;
    }
  }

  /** One parsed compilation unit, with the positions its trees map to lines through. */
  private static final class Parsed {
    final CompilationUnitTree unit;
    final SourcePositions positions;

    private Parsed(CompilationUnitTree unit, SourcePositions positions) {
      this.unit = unit;
      this.positions = positions;
    }

    /** Returns the parsed file, or null where it cannot be read or parsed. */
    static Parsed of(JavaCompiler javac, File repository, String path) {
      String text;
      try {
        text = Files.readString(repository.toPath().resolve(path));
      } catch (IOException | RuntimeException e) {
        return null;
      }
      JavaFileObject source =
          new SimpleJavaFileObject(URI.create("string:///" + path), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
              return text;
            }
          };
      // Syntax errors go to the listener and are dropped: the tests compiled this file, so an error
      // here is a newer syntax this javac does not know, and the trees it still builds are used.
      JavacTask task =
          (JavacTask)
              javac.getTask(
                  null, null, diagnostic -> {}, List.of("-proc:none"), null, List.of(source));
      try {
        for (CompilationUnitTree unit : task.parse()) {
          return new Parsed(unit, Trees.instance(task).getSourcePositions());
        }
      } catch (IOException | RuntimeException e) {
        return null;
      }
      return null;
    }

    int startLine(Tree tree) {
      return line(positions.getStartPosition(unit, tree));
    }

    int endLine(Tree tree) {
      return line(positions.getEndPosition(unit, tree));
    }

    private int line(long position) {
      return position < 0 ? -1 : (int) unit.getLineMap().getLineNumber(position);
    }

    /** Adds the lines a tree spans to {@code lines}. */
    void addLines(Tree tree, NavigableSet<Integer> lines) {
      int start = startLine(tree);
      int end = endLine(tree);
      if (start > 0 && end >= start) {
        for (int line = start; line <= end; line++) {
          lines.add(line);
        }
      }
    }

    boolean touches(Tree tree, NavigableSet<Integer> changed) {
      int start = startLine(tree);
      int end = endLine(tree);
      return start > 0 && end >= start && !changed.subSet(start, true, end, true).isEmpty();
    }

    /**
     * Returns the name of each method of the file, as the class comment of {@link ExposedCode}
     * spells it.
     */
    private Map<MethodTree, String> signatures() {
      Map<MethodTree, String> signatures = new LinkedHashMap<>();
      boolean alone =
          unit.getTypeDecls().stream().filter(ClassTree.class::isInstance).count() == 1;
      new TreeScanner<Void, Void>() {
        /** The names of the enclosing classes, innermost first. */
        private final Deque<String> classes = new ArrayDeque<>();

        /** The name of the anonymous class whose body is scanned next. */
        private String anonymous;

        @Override
        public Void visitNewClass(NewClassTree tree, Void unused) {
          scan(tree.getEnclosingExpression(), unused);
          scan(tree.getIdentifier(), unused);
          scan(tree.getTypeArguments(), unused);
          scan(tree.getArguments(), unused);
          if (tree.getClassBody() != null) {
            anonymous = "(anonymous " + typeName(tree.getIdentifier()) + ")";
            scan(tree.getClassBody(), unused);
          }
          return null;
        }

        @Override
        public Void visitClass(ClassTree tree, Void unused) {
          String name = tree.getSimpleName().toString();
          classes.push(name.isEmpty() ? anonymous : name);
          try {
            return super.visitClass(tree, unused);
          } finally {
            classes.pop();
          }
        }

        @Override
        public Void visitMethod(MethodTree tree, Void unused) {
          signatures.put(tree, signature(tree, classes, alone));
          return super.visitMethod(tree, unused);
        }
      }.scan(unit, null);
      Map<String, Long> uses = new TreeMap<>();
      signatures.values().forEach(name -> uses.merge(name, 1L, Long::sum));
      signatures.replaceAll(
          (method, name) -> uses.get(name) > 1 ? name + " at line " + startLine(method) : name);
      return signatures;
    }

    /**
     * Returns the signature of a method as the class comment of {@link ExposedCode} spells it.
     *
     * @param classes the names of the enclosing classes, innermost first, as the class comment of
     *     {@link ExposedCode} spells them
     * @param alone whether the outermost class is the only top-level class of the file, whose name
     *     the signature leaves out
     */
    private String signature(MethodTree method, Deque<String> classes, boolean alone) {
      List<String> owners = new ArrayList<>(classes);
      Collections.reverse(owners);
      String outermost = owners.get(0);
      if (alone) {
        owners.remove(0);
      }
      StringBuilder out = new StringBuilder();
      String name = method.getName().toString();
      if (name.equals("<init>")) {
        out.append("new ");
        name = owners.isEmpty() ? outermost : owners.remove(owners.size() - 1);
      }
      for (String owner : owners) {
        out.append(owner).append('.');
      }
      out.append(name).append('(');
      List<? extends VariableTree> parameters = method.getParameters();
      for (int i = 0; i < parameters.size(); i++) {
        if (i > 0) {
          out.append(", ");
        }
        out.append(parameterType(parameters.get(i)));
      }
      return out.append(')').toString();
    }

    /**
     * Returns the type of a parameter as {@link #typeName(Tree)} spells it, with {@code ...} for
     * the last array dimension of a variable-arity parameter.
     */
    private String parameterType(VariableTree parameter) {
      String type = typeName(parameter.getType());
      // javac reads String... as String[], and ends the type's span at the ... it read.
      if (source(parameter.getType()).endsWith("...")) {
        return type.substring(0, type.length() - "[]".length()) + "...";
      }
      return type;
    }

    /** Returns a type as the source spells it, without annotations and type arguments. */
    private String typeName(Tree type) {
      if (type instanceof AnnotatedTypeTree) {
        return typeName(((AnnotatedTypeTree) type).getUnderlyingType());
      }
      if (type instanceof ParameterizedTypeTree) {
        return typeName(((ParameterizedTypeTree) type).getType());
      }
      if (type instanceof ArrayTypeTree) {
        return typeName(((ArrayTypeTree) type).getType()) + "[]";
      }
      if (type instanceof MemberSelectTree) {
        MemberSelectTree member = (MemberSelectTree) type;
        return typeName(member.getExpression()) + "." + member.getIdentifier();
      }
      return type.toString();
    }

    /** Returns the source text of a tree, or an empty string where its position is unknown. */
    private String source(Tree tree) {
      long start = positions.getStartPosition(unit, tree);
      long end = positions.getEndPosition(unit, tree);
      if (start < 0 || end < start) {
        return "";
      }
      try {
        CharSequence text = unit.getSourceFile().getCharContent(true);
        return text.subSequence((int) start, (int) end).toString();
      } catch (IOException e) {
        return "";
      }
    }

    /** Returns the regions of this file, own control flow first, then the called methods. */
    List<Region> exposed(String path, NavigableSet<Integer> changed) {
      Map<MethodTree, Region> own = new LinkedHashMap<>();
      Map<String, List<MethodTree>> byName = new TreeMap<>();
      Map<MethodTree, String> signatures = signatures();
      List<Call> calls = new ArrayList<>();
      new TreeScanner<Void, Void>() {
        private final Deque<MethodTree> methods = new ArrayDeque<>();

        @Override
        public Void visitMethod(MethodTree tree, Void unused) {
          byName.computeIfAbsent(tree.getName().toString(), name -> new ArrayList<>()).add(tree);
          methods.push(tree);
          try {
            return super.visitMethod(tree, unused);
          } finally {
            methods.pop();
          }
        }

        @Override
        public Void visitSwitch(SwitchTree tree, Void unused) {
          List<? extends CaseTree> cases = tree.getCases();
          for (int i = 0; i < cases.size(); i++) {
            CaseTree label = cases.get(i);
            if (label.getStatements() == null || !changed.contains(startLine(label))) {
              continue;
            }
            for (int j = i; j < cases.size(); j++) {
              List<? extends StatementTree> statements = cases.get(j).getStatements();
              if (statements == null) {
                break;
              }
              expose(startLine(label), statements);
              if (!statements.isEmpty() && jumps(statements.get(statements.size() - 1))) {
                break;
              }
            }
          }
          return super.visitSwitch(tree, unused);
        }

        @Override
        public Void visitIf(IfTree tree, Void unused) {
          if (touches(tree.getCondition(), changed)) {
            List<StatementTree> guarded = new ArrayList<>();
            guarded.add(tree.getThenStatement());
            if (tree.getElseStatement() != null) {
              guarded.add(tree.getElseStatement());
            }
            expose(startLine(tree.getCondition()), guarded);
          }
          return super.visitIf(tree, unused);
        }

        @Override
        public Void visitWhileLoop(WhileLoopTree tree, Void unused) {
          if (touches(tree.getCondition(), changed)) {
            expose(startLine(tree.getCondition()), List.of(tree.getStatement()));
          }
          return super.visitWhileLoop(tree, unused);
        }

        @Override
        public Void visitDoWhileLoop(DoWhileLoopTree tree, Void unused) {
          if (touches(tree.getCondition(), changed)) {
            expose(startLine(tree.getCondition()), List.of(tree.getStatement()));
          }
          return super.visitDoWhileLoop(tree, unused);
        }

        @Override
        public Void visitForLoop(ForLoopTree tree, Void unused) {
          List<Tree> header = new ArrayList<>(tree.getInitializer());
          if (tree.getCondition() != null) {
            header.add(tree.getCondition());
          }
          header.addAll(tree.getUpdate());
          if (header.stream().anyMatch(part -> touches(part, changed))) {
            expose(startLine(tree), List.of(tree.getStatement()));
          }
          return super.visitForLoop(tree, unused);
        }

        @Override
        public Void visitEnhancedForLoop(EnhancedForLoopTree tree, Void unused) {
          if (touches(tree.getExpression(), changed)) {
            expose(startLine(tree.getExpression()), List.of(tree.getStatement()));
          }
          return super.visitEnhancedForLoop(tree, unused);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
          MethodTree caller = methods.peek();
          String name = calledName(tree.getMethodSelect());
          if (caller != null && name != null) {
            calls.add(new Call(caller, name, startLine(tree)));
          }
          return super.visitMethodInvocation(tree, unused);
        }

        private void expose(int trigger, List<? extends StatementTree> statements) {
          MethodTree method = methods.peek();
          if (method == null) {
            return;
          }
          Region region =
              own.computeIfAbsent(method, key -> new Region(path, signatures.get(key), false));
          region.triggers.add(trigger);
          for (StatementTree statement : statements) {
            addLines(statement, region.lines);
          }
        }
      }.scan(unit, null);

      List<Region> result = new ArrayList<>();
      for (Region region : own.values()) {
        region.lines.removeAll(changed);
        if (!region.lines.isEmpty()) {
          result.add(region);
        }
      }

      // One call deep: a call on a changed or exposed line reaches the body of the callee.
      NavigableSet<Integer> reaching = new TreeSet<>(changed);
      for (Region region : result) {
        reaching.addAll(region.lines);
      }
      Map<MethodTree, Region> called = new LinkedHashMap<>();
      for (Call call : calls) {
        if (!reaching.contains(call.line)) {
          continue;
        }
        for (MethodTree callee : byName.getOrDefault(call.name, List.of())) {
          // A callee with a changed line is measured as changed code already.
          if (callee == call.caller || callee.getBody() == null || touches(callee, changed)) {
            continue;
          }
          Region region =
              called.computeIfAbsent(
                  callee,
                  key -> {
                    Region body = new Region(path, signatures.get(key), true);
                    for (StatementTree statement : key.getBody().getStatements()) {
                      addLines(statement, body.lines);
                    }
                    return body;
                  });
          region.triggers.add(call.line);
        }
      }
      for (Region region : called.values()) {
        if (!region.lines.isEmpty()) {
          result.add(region);
        }
      }
      return result;
    }

    /** Returns the test methods of this file that span a changed line. */
    List<TestMethod> testMethods(NavigableSet<Integer> changed) {
      List<TestMethod> found = new ArrayList<>();
      String packageName =
          unit.getPackageName() == null ? "" : unit.getPackageName().toString() + ".";
      new TreeScanner<Void, Void>() {
        private final Deque<String> classes = new ArrayDeque<>();

        @Override
        public Void visitClass(ClassTree tree, Void unused) {
          String name = tree.getSimpleName().toString();
          if (name.isEmpty()) {
            // An anonymous class holds no test method a filter can name.
            return null;
          }
          classes.push(classes.isEmpty() ? packageName + name : classes.peek() + "$" + name);
          try {
            return super.visitClass(tree, unused);
          } finally {
            classes.pop();
          }
        }

        @Override
        public Void visitMethod(MethodTree tree, Void unused) {
          if (!classes.isEmpty() && isTest(tree) && touches(tree, changed)) {
            found.add(new TestMethod(classes.peek(), tree.getName().toString()));
          }
          return null;
        }
      }.scan(unit, null);
      return found;
    }
  }

  /** Returns whether control never leaves the statement to the one after it. */
  private static boolean jumps(StatementTree statement) {
    switch (statement.getKind()) {
      case BREAK:
      case CONTINUE:
      case RETURN:
      case THROW:
        return true;
      case BLOCK:
        List<? extends StatementTree> inner = ((BlockTree) statement).getStatements();
        return !inner.isEmpty() && jumps(inner.get(inner.size() - 1));
      default:
        return false;
    }
  }

  private static boolean isTest(MethodTree method) {
    for (AnnotationTree annotation : method.getModifiers().getAnnotations()) {
      String name = annotation.getAnnotationType().toString();
      String simple = name.substring(name.lastIndexOf('.') + 1);
      if (TEST_ANNOTATION.matcher(simple).matches()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the simple name of a call this file can declare, or null for a call on another object.
   *
   * <p>{@code parse(x)} and {@code this.parse(x)} count; {@code other.parse(x)} does not, since
   * without symbols nothing says the receiver is of this class.
   */
  private static String calledName(ExpressionTree select) {
    if (select instanceof IdentifierTree) {
      return ((IdentifierTree) select).getName().toString();
    }
    if (select instanceof MemberSelectTree) {
      MemberSelectTree member = (MemberSelectTree) select;
      if (member.getExpression() instanceof IdentifierTree
          && ((IdentifierTree) member.getExpression()).getName().contentEquals("this")) {
        return member.getIdentifier().toString();
      }
    }
    return null;
  }
}
