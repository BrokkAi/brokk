package io.github.jbellis.brokk.analyzer.builder

import io.github.jbellis.brokk.analyzer.implicits.PathExt.*
import flatgraph.DiffGraphApplier
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.*
import org.scalatest.Assertion
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.File as JavaFile
import java.nio.file.{Files, Path}
import scala.util.Using

class FileChangeTest extends FileChangeTestFixture {

  "When no files are present, all incoming files should be assumed to be added" in {
    assertAgainstCpgWithPaths(
      existingFiles = Nil,
      newFiles = Seq(F("Foo.txt"), F("foo/Bar.txt"))
    ) { (cpg, projectRootPath) =>
      def fileName(relative: String): Path = projectRootPath.resolve(relative)

      IncrementalCpgBuilder.determineChangedFiles(cpg, projectRootPath) shouldBe List(
        AddedFile(fileName("Foo.txt")), AddedFile(fileName("foo/Bar.txt"))
      )
    }
  }

  "Changing a file should result in a modified file" in {
    assertAgainstCpgWithPaths(
      existingFiles = Seq(F("Foo.txt")),
      newFiles = Seq(F("Foo.txt", "changed"))
    ) { (cpg, projectRootPath) =>
      def fileName(relative: String): Path = projectRootPath.resolve(relative)

      IncrementalCpgBuilder.determineChangedFiles(cpg, projectRootPath) shouldBe List(
        ModifiedFile(fileName("Foo.txt"))
      )
    }
  }

}

case class FileAndContents(path: String, contents: String)

trait FileChangeTestFixture extends AnyWordSpec with Matchers {
  
  private def F(path: String, contents: String = "Mock contents"): FileAndContents = FileAndContents(path, contents)

  /**
   * Creates a CPG file system given the "existing" paths using some temporary directory as the root directory.
   *
   * @param existingFiles the files that should be associated with File nodes in the CPG. These should be relative.
   * @param newFiles      the files that are considered "new" files. These will be created as empty files in the root 
   *                      directory. These should be relative.
   * @param assertion     the test assertions to perform against a CPG containing existing files and the root directory of new files.
   * @return the test assertion.
   */
  def assertAgainstCpgWithPaths(existingFiles: Seq[FileAndContents], newFiles: Seq[FileAndContents])(assertion: (Cpg, Path) => Assertion): Assertion = {
    Using.resource(Cpg.empty) { cpg =>
      val tempDir = Files.createTempDirectory("brokk-file-change-test-")
      try {
        val builder = Cpg.newDiffGraphBuilder
        // Every CPG has a meta-data node. This contains the root path of the project. File nodes
        // have `name` properties relative to this.
        val metaData = NewMetaData().root(tempDir.toString)
        val fileNodes = existingFiles.map { case FileAndContents(path, contents) => NewFile().name(path).hash(contents.sha1) }
        // Build CPG
        (metaData +: fileNodes).foreach(builder.addNode)
        DiffGraphApplier.applyDiff(cpg.graph, builder)
        // Create dummy files
        newFiles.foreach { case FileAndContents(path, contents) =>
          val fullPath = tempDir.resolve(path)
          Files.writeString(fullPath, contents, java.nio.file.StandardOpenOption.CREATE)
        }

        // Run assertions
        assertion(cpg, tempDir)
      } finally {
        deleteRecursively(tempDir)
      }
    }
  }

  private def deleteRecursively(path: Path): Boolean = try {
    val f = path.toFile
    if (f.isDirectory) f.listFiles match {
      case files: Array[JavaFile] => files.map(_.toPath).foreach(deleteRecursively)
      case null =>
    }
    f.delete()
  } catch {
    case e: SecurityException => false
  }

}
