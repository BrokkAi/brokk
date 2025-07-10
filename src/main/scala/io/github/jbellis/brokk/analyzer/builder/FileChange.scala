package io.github.jbellis.brokk.analyzer.builder

import java.nio.file.{InvalidPathException, Path}

/** Decribes a file change. These are currently divided into 3 types: modified, added, and removed. We currently ignore
  * the concept of a "moved" file as this would require perhaps some kind of event hook or origin analysis.
  */
sealed trait FileChange {

  /**
   * @return the absolute file path as a string if this is a non-synthetic node.
   */
  def name: String

  /**
   * @return a [[Path]] instance of name.
   */
  @throws[InvalidPathException]
  def path: Path = Path.of(name)

}

case class ModifiedFile(name: String) extends FileChange

case class AddedFile(name: String) extends FileChange

case class RemovedFile(name: String) extends FileChange
