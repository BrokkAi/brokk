package io.github.jbellis.brokk.analyzer.builder

import flatgraph.DiffGraphApplier
import io.github.jbellis.brokk.analyzer.implicits.StringExt.*
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.*
import org.scalatest.Assertion
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

class FileChangeTest extends FileChangeTestFixture {

  "When no files are present, all incoming files should be assumed to be added" in {
    assertAgainstCpgWithPaths(
      existingFiles = Nil,
      newFiles = Seq(F("Foo.txt"), F("foo/Bar.txt"))
    ) { (cpg, projectRootPath, absFileName) =>
      IncrementalCpgBuilder.determineChangedFiles(cpg, projectRootPath) shouldBe List(
        AddedFile(absFileName("Foo.txt")), AddedFile(absFileName("foo/Bar.txt"))
      )
    }
  }

  "Changing a file should result in a modified file" in {
    assertAgainstCpgWithPaths(
      existingFiles = Seq(F("Foo.txt")),
      newFiles = Seq(F("Foo.txt", "changed"))
    ) { (cpg, projectRootPath, absFileName) =>
      IncrementalCpgBuilder.determineChangedFiles(cpg, projectRootPath) shouldBe List(
        ModifiedFile(absFileName("Foo.txt"))
      )
    }
  }

  "Removing files should result in removed file changes" in {
    assertAgainstCpgWithPaths(
      existingFiles = Seq(F("Foo.txt"), F("foo/Bar.txt")),
      newFiles = Nil
    ) { (cpg, projectRootPath, absFileName) =>
      IncrementalCpgBuilder.determineChangedFiles(cpg, projectRootPath) shouldBe List(
        RemovedFile(absFileName("Foo.txt")), RemovedFile(absFileName("foo/Bar.txt"))
      )
    }
  }

  "Moving a file should result in removed and added file change" in {
    assertAgainstCpgWithPaths(
      existingFiles = Seq(F("Foo.txt")),
      newFiles = Seq(F("bar/Foo.txt"))
    ) { (cpg, projectRootPath, absFileName) =>
      IncrementalCpgBuilder.determineChangedFiles(cpg, projectRootPath) shouldBe List(
        RemovedFile(absFileName("Foo.txt")), AddedFile(absFileName("bar/Foo.txt"))
      )
    }
  }

  "A composite of addition, modification, and removal operations should result in the respective change objects" in {
    assertAgainstCpgWithPaths(
      existingFiles = Seq(F("Foo.txt", "removed"), F("foo/Bar.txt", "changed")),
      newFiles = Seq(F("foo/Bar.txt"), F("foo/Baz.txt", "new"))
    ) { (cpg, projectRootPath, absFileName) =>
      IncrementalCpgBuilder.determineChangedFiles(cpg, projectRootPath) shouldBe List(
        RemovedFile(absFileName("Foo.txt")), ModifiedFile(absFileName("foo/Bar.txt")), AddedFile(absFileName("foo/Baz.txt"))
      )
    }
  }

}

case class FileAndContents(path: String, contents: String)

trait FileChangeTestFixture extends AnyWordSpec with Matchers {

  protected def F(path: String, contents: String = "Mock contents"): FileAndContents = FileAndContents(path, contents)

  /**
   * Creates a CPG file system given the "existing" paths using some temporary directory as the root directory.
   *
   * @param existingFiles the files that should be associated with File nodes in the CPG. These should be relative.
   * @param newFiles      the files that are considered "new" files. These will be created as empty files in the root
   *                      directory. These should be relative.
   * @param assertion     the test assertions to perform against a CPG containing existing files and the root directory of new files.
   * @return the test assertion.
   */
  def assertAgainstCpgWithPaths(existingFiles: Seq[FileAndContents], newFiles: Seq[FileAndContents])(assertion: (Cpg, Path, String => Path) => Assertion): Assertion = {
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
          val parent = fullPath.getParent
          if (!Files.exists(parent)) Files.createDirectories(parent)
          Files.createFile(fullPath)
          Files.writeString(fullPath, contents)
        }

        // Run assertions
        val absFileNameProvider = (relativeName: String) => tempDir.resolve(relativeName)
        assertion(cpg, tempDir, absFileNameProvider)
      } finally {
        deleteRecursively(tempDir)
      }
    }
  }

  private def deleteRecursively(path: Path): Boolean = try {
    val f = path.toFile
    if (Files.isDirectory(path)) {
      Files.list(path).toList.asScala match {
        case files: Iterable[Path] => files.foreach(deleteRecursively)
        case null =>
      }
    }
    f.delete()
  } catch {
    case e: SecurityException => false
  }

}
