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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.maven.execution.MavenSession;
import org.codehaus.plexus.classworlds.realm.ClassRealm;

/**
 * Decides which copy of cribro acts in a build that loads it more than once.
 *
 * <p>A user may load cribro with {@code -Dmaven.ext.class.path} in a build whose
 * {@code .mvn/extensions.xml} or {@code pom.xml} loads it too, and each copy lives in a class realm
 * of its own, with its own listeners. The copy the build loads acts, as the build applying cribro
 * itself wins over the Gradle init script; the copy from {@code maven.ext.class.path} stands down.
 * Where the build itself loads two copies, from {@code .mvn/extensions.xml} and from a POM, the
 * one registered first acts.
 *
 * <p>Every copy registers its class loader in the repository session's data, which all realms
 * share, before any mojo runs. A copy then asks whether it acts at each event, when every copy has
 * registered.
 */
final class Ownership {

  private static final String KEY = "io.github.cribrodev.copies";

  /** The realm Maven creates for the jars of {@code maven.ext.class.path}. */
  private static final String EXT_REALM = "maven.ext";

  private Ownership() {}

  static void register(MavenSession session) {
    copies(session).addIfAbsent(Ownership.class.getClassLoader());
  }

  /** Returns whether this copy of cribro was loaded with {@code -Dmaven.ext.class.path}. */
  static boolean loadedFromExtClassPath() {
    return isExtRealm(Ownership.class.getClassLoader());
  }

  /** Returns whether this copy of cribro acts in the session. */
  static boolean acts(MavenSession session) {
    List<ClassLoader> copies = copies(session);
    ClassLoader owner = null;
    for (ClassLoader copy : copies) {
      if (!isExtRealm(copy)) {
        owner = copy;
        break;
      }
    }
    if (owner == null && !copies.isEmpty()) {
      owner = copies.get(0);
    }
    return owner == Ownership.class.getClassLoader();
  }

  private static boolean isExtRealm(ClassLoader loader) {
    return loader instanceof ClassRealm && ((ClassRealm) loader).getId().equals(EXT_REALM);
  }

  @SuppressWarnings("unchecked")
  private static CopyOnWriteArrayList<ClassLoader> copies(MavenSession session) {
    return (CopyOnWriteArrayList<ClassLoader>)
        session
            .getRepositorySession()
            .getData()
            .computeIfAbsent(KEY, CopyOnWriteArrayList<ClassLoader>::new);
  }
}
