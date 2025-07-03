package io.github.jbellis.brokk.analyzer.implicits

import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.util.Using
import scala.jdk.CollectionConverters.*

object PathExt {

  extension(pathToDigest: Path)  {

    /**
     * Generates a SHA-1 hash of the file at the given path. If a directory is given, the hash is generated recursively.
     *
     * @return the SHA-1 hash of the contents within this path.
     */
    def sha1: String = {
      val messageDigest = MessageDigest.getInstance("SHA-1")
      digestPath(pathToDigest, messageDigest)
    }

    private def digestPath(path: Path, messageDigest: MessageDigest): String = {
      if (Files.isDirectory(path)) {
        val allContents = Files.list(path).map(p => digestPath(p, messageDigest)).toList.asScala.mkString
        messageDigest.digest(allContents.getBytes(StandardCharsets.UTF_8)).map("%02x".format(_)).mkString
      } else {
        Using.resource(FileInputStream(path.toFile)) { fis =>
          val buffer = new Array[Byte](8192)
          Iterator.continually(fis.read(buffer))
            .takeWhile(_ != -1)
            .foreach(bytesRead => messageDigest.update(buffer, 0, bytesRead))
        }
        messageDigest.digest.map("%02x".format(_)).mkString
      }
    }

  }

}
