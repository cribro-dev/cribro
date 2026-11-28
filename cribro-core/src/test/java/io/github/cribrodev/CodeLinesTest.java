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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A line with bytecode holds code of its own unless its text is only braces, closing parentheses,
 * semicolons, commas, and else, and it carries no branch and starts no method.
 */
class CodeLinesTest {

  static Stream<Arguments> bracesSeparatorsAndElse() {
    return Stream.of(
        argumentSet("a closing brace", "}"),
        argumentSet("an opening brace", "{"),
        argumentSet("an indented brace", "    }"),
        argumentSet("an else between braces", "} else {"),
        argumentSet("an else between braces without spaces", "}else{"),
        argumentSet("a lambda that ends a call", "});"),
        argumentSet("a lambda followed by another argument", "}),"),
        argumentSet("the end of a wrapped condition", ") {"),
        argumentSet("a brace and a line comment", "} // end of the loop"),
        argumentSet("a brace and a block comment", "} /* loop */"),
        argumentSet("a brace and an unclosed block comment", "} /* the loop"),
        argumentSet(
            "a brace and the ignore marker", "} // diff-coverage: ignore -- defensive safeguard"));
  }

  @ParameterizedTest
  @MethodSource("bracesSeparatorsAndElse")
  void bracesSeparatorsAndElseHoldNoCode(String source) {
    assertTrue(CodeLines.holdsNoCode(source), () -> "holdsNoCode(\"" + source + "\")");
  }

  static Stream<Arguments> code() {
    return Stream.of(
        argumentSet("an else if", "} else if (b) {"),
        argumentSet("a catch", "} catch (IOException e) {"),
        argumentSet("a finally", "} finally {"),
        argumentSet("the condition of a do loop", "} while (more);"),
        argumentSet("a call on what a lambda argument built", "}).run();"),
        argumentSet("the last argument of a wrapped call", "elseValue)"),
        argumentSet("a brace inside a string", "String close = \"}\";"),
        argumentSet("a comment alone", "// end of the loop"),
        argumentSet("an empty line", ""),
        argumentSet("whitespace alone", "   "));
  }

  @ParameterizedTest
  @MethodSource("code")
  void anythingElseHoldsCode(String source) {
    assertFalse(CodeLines.holdsNoCode(source), () -> "holdsNoCode(\"" + source + "\")");
  }

  static Stream<Arguments> lines() {
    return Stream.of(
        argumentSet("a brace", false, false, "}", false),
        argumentSet("a brace that carries a branch", true, false, "}", true),
        argumentSet("a brace that starts a method", false, true, "}", true),
        argumentSet("a statement", false, false, "x();", true),
        argumentSet("a line the working tree cannot show", false, false, "", true));
  }

  @ParameterizedTest
  @MethodSource("lines")
  void aBraceHoldsCodeOnlyWhereItCarriesABranchOrStartsAMethod(
      boolean carriesBranch, boolean startsMethod, String source, boolean holdsCode) {
    assertEquals(
        holdsCode,
        CodeLines.holdsCode(carriesBranch, startsMethod, source),
        () -> "holdsCode(" + carriesBranch + ", " + startsMethod + ", \"" + source + "\")");
  }
}
