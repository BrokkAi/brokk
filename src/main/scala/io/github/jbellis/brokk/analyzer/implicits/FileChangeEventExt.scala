package io.github.jbellis.brokk.analyzer.implicits

import io.github.jbellis.brokk.FileChangeEvent
import io.github.jbellis.brokk.FileChangeEvent.EventType
import io.github.jbellis.brokk.analyzer.builder.{AddedFile, FileChange, ModifiedFile, RemovedFile}
import scala.jdk.CollectionConverters.*

object FileChangeEventExt {

  extension (changeEvent: FileChangeEvent) {

    def toScala: FileChange = {
      changeEvent.`type`() match {
        case EventType.CREATE   => AddedFile(changeEvent.path().toString)
        case EventType.MODIFY   => ModifiedFile(changeEvent.path().toString)
        case EventType.DELETE   => RemovedFile(changeEvent.path().toString)
        case EventType.OVERFLOW => RemovedFile(changeEvent.path().toString) // approximate
      }
    }

  }

  extension (changeEvents: java.util.List[FileChangeEvent]) {

    def toScala: List[FileChange] = changeEvents.asScala.map(_.toScala).toList

  }

}
