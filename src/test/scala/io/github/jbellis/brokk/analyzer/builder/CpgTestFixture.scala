package io.github.jbellis.brokk.analyzer.builder

import io.github.jbellis.brokk.analyzer.implicits.PathExt.*
import io.github.jbellis.brokk.analyzer.implicits.X2CpgConfigExt.*
import io.joern.x2cpg.X2CpgConfig
import io.shiftleft.codepropertygraph.generated.Cpg
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Paths, StandardOpenOption}
import scala.jdk.CollectionConverters.*

trait CpgTestFixture[R <: X2CpgConfig[R]] extends AnyWordSpec with Matchers with Inside {

  import CpgTestFixture.*

  def project(config: R, code: String, path: String): MockProject[R] =
    MockProject(config, Set(CodeAndPath(code, path)))

  def emptyProject(config: R): MockProject[R] = MockProject(config, Set.empty)

  protected implicit def defaultConfig: R

  protected def withTestConfig(f: R => Unit)(implicit initialConfig: R = defaultConfig): Unit = {
    val tempDir = Files.createTempDirectory("brokk-cpg-test-")
    try {
      val newConfig = initialConfig
        .withInputPath(tempDir.toString)
        .withOutputPath(tempDir.resolve("cpg.bin").toString)
      f(newConfig)
    } finally {
      tempDir.deleteRecursively()
    }
  }

}

object CpgTestFixture {

  case class MockProject[R <: X2CpgConfig[R]](config: R, codeBase: Set[CodeAndPath]) {

    def moreCode(code: String, path: String): MockProject[R] = {
      val x = CodeAndPath(code, path)
      // By default, a set won't add an item if it already exists. Since we consider files of the same path
      // but different contents equivalent, they should be removed first before re-added
      val newCode = if codeBase.contains(x) then codeBase - x + x else codeBase + x
      this.copy(codeBase = newCode)
    }

    /**
     * Creates the source files described by this mock instance at the specified input location in the config.
     *
     * @return this project.
     */
    def writeFiles: MockProject[R] = {
      val targetPath = Paths.get(config.inputPath)
      // Clear any existing contents (that aren't the CPG) then set-up project on disk
      Files.list(targetPath).toList.asScala
        .filterNot(_ == Paths.get(config.outputPath))
        .foreach(_.deleteRecursively)
      codeBase.foreach { case CodeAndPath(code, path) =>
        val newPath = targetPath.resolve(path)
        if !Files.exists(newPath.getParent) then Files.createDirectories(newPath.getParent)
        Files.writeString(newPath, code, StandardOpenOption.CREATE)
      }
      this
    }

    /**
     * Creates an initial build of a project from an empty CPG. This method builds the project before
     * creating the CPG automatically.
     *
     * @param builder the incremental CPG builder.
     * @return the resulting CPG.
     */
    def buildAndOpen(using builder: CpgBuilder[R]): Cpg = {
      writeFiles
      config.build.failed.foreach(e => throw e)
      config.open
    }

  }

  case class CodeAndPath(code: String, path: String) {
    override def equals(obj: Any): Boolean = {
      obj match {
        case CodeAndPath(_, otherPath) => otherPath == path
        case _ => false
      }
    }

    override def hashCode(): Int = path.hashCode()
  }

}


