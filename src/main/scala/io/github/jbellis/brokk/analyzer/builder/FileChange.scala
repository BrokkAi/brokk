package io.github.jbellis.brokk.analyzer.builder

import java.nio.file.{InvalidPathException, Path}
import java.util.regex.Matcher

/** Describes a file change. These are currently divided into 3 types: modified, added, and removed. We currently ignore
  * the concept of a "moved" file as this would require perhaps some kind of event hook or origin analysis.
  */
sealed trait FileChange {

  /** @return
    *   the absolute file path as a string if this is a non-synthetic node.
    */
  def fullName: String

  /** @return
    *   the simple name of the file.
    */
  def name: String = fullName.split(Matcher.quoteReplacement(java.io.File.separator)).last

  /** @return
    *   a [[Path]] instance of name.
    */
  @throws[InvalidPathException]
  def path: Path = Path.of(fullName)

}

case class ModifiedFile(fullName: String) extends FileChange

case class AddedFile(fullName: String) extends FileChange

case class RemovedFile(fullName: String) extends FileChange
