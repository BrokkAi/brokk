package io.github.jbellis.brokk.analyzer.implicits

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object StringExt {

  extension (stringToDigest: String) {

    def sha1: String = {
      val messageDigest = MessageDigest.getInstance("SHA-1")
      digestString(stringToDigest, messageDigest)
    }

    private def digestString(string: String, messageDigest: MessageDigest): String =
      string.getBytes(StandardCharsets.UTF_8).map("%02x".format(_)).mkString

  }

}
