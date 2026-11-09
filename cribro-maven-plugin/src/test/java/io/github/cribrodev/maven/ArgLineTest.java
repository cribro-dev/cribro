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
package io.github.cribrodev.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Surefire's argLine as cribro reads it, to find a JaCoCo agent and to pass the rest to PIT. */
class ArgLineTest {

  @Test
  void aNameTheProjectDefinesIsReplacedAndAnUnknownOneStays() {
    Properties properties = new Properties();
    properties.setProperty("argLine", "-javaagent:/r/org.jacoco.agent-0.8.16-runtime.jar=destfile=x");
    assertEquals(
        "-javaagent:/r/org.jacoco.agent-0.8.16-runtime.jar=destfile=x -Xmx1g @{other}",
        ArgLine.expandLate("@{argLine} -Xmx1g @{other}", properties));
  }

  @Test
  void quotesKeepWhitespaceInsideOneArgumentAndAreDropped() {
    assertEquals(
        List.of("-Da=b c", "-Dd='e'", "-Xmx1g"),
        ArgLine.split("  \"-Da=b c\" -Dd=\"'e'\"\t-Xmx1g "));
  }

  @Test
  void aQuotedArgumentSplitsBackWhole() {
    String argument = "-javaagent:/my dir/jacocoagent.jar=destfile=/my dir/jacoco.exec";
    assertEquals(List.of(argument, "-Xmx1g"), ArgLine.split(ArgLine.quote(argument) + " -Xmx1g"));
  }

  @Test
  void anAgentWithNoDestfileWritesJacocoExecInTheWorkingDirectory() {
    ArgLine.JacocoAgent agent =
        ArgLine.findJacocoAgent(List.of("-Xmx1g", "-javaagent:/r/jacocoagent.jar"));
    File workingDirectory = new File("/work");
    assertEquals(new File(workingDirectory, "jacoco.exec"), agent.destFile(workingDirectory));
    assertTrue(agent.writesFile(), "an agent with no output option writes a file");
  }

  @Test
  void anAgentsRelativeDestfileResolvesAgainstTheWorkingDirectory() {
    ArgLine.JacocoAgent agent =
        ArgLine.findJacocoAgent(
            List.of(
                "-javaagent:/r/org.jacoco.agent-0.8.16-runtime.jar=destfile=target/j.exec,append=true"));
    File workingDirectory = new File("/work");
    assertEquals(new File(workingDirectory, "target/j.exec"), agent.destFile(workingDirectory));
  }

  @Test
  void anAgentThatServesASocketWritesNoFile() {
    ArgLine.JacocoAgent agent =
        ArgLine.findJacocoAgent(List.of("-javaagent:/r/jacocoagent.jar=output=tcpserver"));
    assertFalse(agent.writesFile(), "output=tcpserver");
  }

  @Test
  void anotherJavaAgentIsNotJacoco() {
    List<String> arguments = List.of("-javaagent:/r/mockito-core-5.jar", "-Xmx1g");
    assertNull(ArgLine.findJacocoAgent(arguments));
    assertEquals(arguments, ArgLine.withoutJacocoAgents(arguments));
  }

  @Test
  void pitGetsTheArgumentsWithoutTheJacocoAgent() {
    assertEquals(
        List.of("-Xmx1g"),
        ArgLine.withoutJacocoAgents(List.of("-javaagent:/r/jacocoagent.jar=destfile=x", "-Xmx1g")));
  }
}
