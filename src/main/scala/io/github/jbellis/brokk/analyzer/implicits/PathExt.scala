package io.github.jbellis.brokk.analyzer.implicits

import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Try, Using}

object PathExt {

  extension (path: Path) {

    /** Generates a SHA-1 hash of the file at the given path. If a directory is given, the hash is generated
     * recursively.
     *
     * @return
     * the SHA-1 hash of the contents within this path.
     */
    def sha1: String = {
      val messageDigest = MessageDigest.getInstance("SHA-1")
      digestPath(path, messageDigest)
    }

    private def digestPath(pathToDigest: Path, messageDigest: MessageDigest): String = {
      if (Files.isDirectory(pathToDigest)) {
        val allContents = Files.list(pathToDigest).map(p => digestPath(p, messageDigest)).toList.asScala.mkString
        messageDigest.digest(allContents.getBytes(StandardCharsets.UTF_8)).map("%02x".format(_)).mkString
      } else {
        Using.resource(FileInputStream(pathToDigest.toFile)) { fis =>
          val buffer = new Array[Byte](8192)
          Iterator
            .continually(fis.read(buffer))
            .takeWhile(_ != -1)
            .foreach(bytesRead => messageDigest.update(buffer, 0, bytesRead))
        }
        messageDigest.digest.map("%02x".format(_)).mkString
      }
    }

    /** If the path points to a file, it is deleted. If it points to a directory, it is deleted recursively. Note, this
     * propagates any exceptions resulting from [[java.nio.file.Files.delete]].
     */
    def deleteRecursively(suppressExceptions: Boolean = false): Unit = Try {
      if (Files.isDirectory(path)) {
        // Files.list may return `null`, so we wrap it in an `Option`
        Option(Files.list(path)).toList.flatMap(_.toList.asScala).foreach(_.deleteRecursively)
      } else {
        Files.delete(path)
      }
    } match {
      case Failure(e) if !suppressExceptions => throw e
      case _ => // ignore
    }

  }

}
