package io.github.jbellis.brokk.analyzer.builder

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
      existingPaths = Nil,
      newPaths = Seq("Foo.txt", "foo/Bar.txt")
    ) { (cpg, projectRootPath) =>
      def fileName(relative: String): Path = projectRootPath.resolve(relative)

      IncrementalCpgBuilder.determineChangedFiles(cpg, projectRootPath) shouldBe List(
        AddedFile(fileName("Foo.txt")), AddedFile(fileName("foo/Bar.txt"))
      )
    }
  }

}

trait FileChangeTestFixture extends AnyWordSpec with Matchers {

  /**
   * Creates a CPG file system given the "existing" paths using some temporary directory as the root directory.
   *
   * @param existingPaths the paths that should be associated with File nodes in the CPG. These should be relative.
   * @param newPaths      what are considered "new" paths. These will be created as empty files in the root directory. These should be relative.
   * @param assertion
   * @return
   */
  def assertAgainstCpgWithPaths(existingPaths: Seq[String], newPaths: Seq[String])(assertion: (Cpg, Path) => Assertion): Assertion = {
    Using.resource(Cpg.empty) { cpg =>
      val tempDir = Files.createTempDirectory("brokk-file-change-test-")
      try {
        val builder = Cpg.newDiffGraphBuilder
        // Every CPG has a meta-data node. This contains the root path of the project. File nodes
        // have `name` properties relative to this.
        val metaData = NewMetaData().root(tempDir.toString)
        val fileNodes = existingPaths.map(p => NewFile().name(p))
        // Build CPG
        (metaData +: fileNodes).foreach(builder.addNode)
        DiffGraphApplier.applyDiff(cpg.graph, builder)
        // Create dummy files
        newPaths.map(tempDir.resolve(_)).foreach { p =>
          Files.writeString(p, "Dummy value", java.nio.file.StandardOpenOption.CREATE)
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
