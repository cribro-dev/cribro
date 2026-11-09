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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The plugin replaces Gradle's bundled JaCoCo only with a newer one, so the comparison decides
 * whether a build on a future Gradle is downgraded.
 */
class CribroPluginTest {

  @Test
  void aLaterPatchIsNewer() {
    assertTrue(CribroPlugin.compareVersions("0.8.13", "0.8.16") < 0);
  }

  /** Compared as text, 0.8.9 would sort after 0.8.16. */
  @Test
  void partsCompareAsNumbers() {
    assertTrue(CribroPlugin.compareVersions("0.8.9", "0.8.16") < 0);
  }

  @Test
  void aSnapshotComparesByItsNumbers() {
    assertEquals(0, CribroPlugin.compareVersions("0.8.16-SNAPSHOT", "0.8.16"));
  }

  @Test
  void aNewerBundledVersionIsKept() {
    assertTrue(CribroPlugin.compareVersions("0.9.1", "0.8.16") > 0);
  }
}
