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

import java.util.regex.Pattern;

/**
 * Decides whether a line with bytecode holds code of its own, for every report that counts changed
 * or exposed lines: the diff coverage block, the exposed-code block, and the mutation summary.
 *
 * <p>A line that {@link #holdsNoCode}, carries no branch, and starts no method holds no code of its
 * own. The compiler gives such a line an instruction of the statement it closes: the jump over an
 * {@code else} block, the jump back to a loop's condition, a method's implicit return. Whether that
 * instruction ran is reported by the lines and branches of that statement, so counting the brace as
 * well would count the statement twice. A method or a lambda with an empty body has its only
 * instruction on its closing brace, which is the method's first line, so that line holds code. A
 * line that carries a branch holds code whatever its text, so that the branch totals keep every
 * decision the compiler emitted.
 *
 * <p>This class is loaded both by the build and by the PIT process, so it depends on nothing but
 * the JDK.
 */
public final class CodeLines {

  /** A comment on a source line: a block comment, one left open at the end, or a line comment. */
  private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/|/\\*.*|//.*");

  /** Braces, closing parentheses, semicolons, commas, whitespace, and the keyword else, alone. */
  private static final Pattern BRACES_SEPARATORS_AND_ELSE =
      Pattern.compile("(?:[\\s{}),;]|\\belse\\b)+");

  private CodeLines() {}

  /**
   * Returns whether a line with bytecode holds code of its own.
   *
   * @param carriesBranch whether an instruction of the line is a branch the report counts
   * @param startsMethod whether the line is the first line of a method or a lambda body
   * @param source the text of the line, or an empty string where the working tree cannot show it
   */
  public static boolean holdsCode(boolean carriesBranch, boolean startsMethod, String source) {
    return carriesBranch || startsMethod || !holdsNoCode(source);
  }

  /**
   * Returns whether the source line holds only braces, closing parentheses, semicolons, commas,
   * and the keyword {@code else} once its comments are removed, such as <code>}</code>, <code>
   * } else {</code>, <code>});</code>, or <code>) {</code>.
   *
   * <p>A line with anything else on it holds code, so <code>} else if (b) {</code>, <code>} catch
   * (E e) {</code>, and <code>} finally {</code> do. An empty line, a comment alone, and a line the
   * working tree cannot show give {@code false}: their text says nothing about the instructions
   * the compiler put on them.
   */
  static boolean holdsNoCode(String source) {
    String code = COMMENT.matcher(source).replaceAll(" ").strip();
    return !code.isEmpty() && BRACES_SEPARATORS_AND_ELSE.matcher(code).matches();
  }
}
