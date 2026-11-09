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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/** The versions of cribro and of the PIT and JaCoCo it was built against. */
public final class Versions {

  private static final Properties VERSIONS = load();

  private Versions() {}

  public static String cribro() {
    return VERSIONS.getProperty("cribro");
  }

  public static String pitest() {
    return VERSIONS.getProperty("pitest");
  }

  public static String pitestJunit5Plugin() {
    return VERSIONS.getProperty("pitestJunit5Plugin");
  }

  public static String jacoco() {
    return VERSIONS.getProperty("jacoco");
  }

  private static Properties load() {
    Properties versions = new Properties();
    try (InputStream in =
        Versions.class.getResourceAsStream("/io/github/cribrodev/cribro.properties")) {
      if (in == null) {
        throw new IllegalStateException("cribro.properties is missing from the cribro jar");
      }
      versions.load(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return versions;
  }
}
