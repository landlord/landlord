package com.github.huntc

import com.github.huntc.landlord.JvmExecutor.resolvePaths
import java.net.URL
import java.nio.file.{ Path, Paths }
import scala.collection.immutable.Seq

package object landlord {
  private[landlord] val ClassProfilesProperty = "landlord.class-profile"

  private[landlord] def classPathStrings(cp: String): Seq[String] =
    cp.split(":").toVector

  private[landlord] def classPathUrls(base: Path, classPath: Seq[String]): Seq[URL] =
    classPath.flatMap(cp => resolvePaths(base, Paths.get(cp)).map(_.toUri.toURL))
}
