package io.github.jbellis.brokk.analyzer.builder

import java.nio.file.Path

/** Decribes a file change. These are currently divided into 3 types: modified, added, and removed. We currently ignore
  * the concept of a "moved" file as this would require perhaps some kind of event hook or origin analysis.
  */
sealed trait FileChange {

  def path: Path

}

case class ModifiedFile(path: Path) extends FileChange

case class AddedFile(path: Path) extends FileChange

case class RemovedFile(path: Path) extends FileChange
