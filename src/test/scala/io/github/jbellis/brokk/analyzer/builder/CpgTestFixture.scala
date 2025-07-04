package io.github.jbellis.brokk.analyzer.builder

import io.github.jbellis.brokk.analyzer.implicits.PathExt.*
import io.joern.x2cpg.X2CpgConfig
import io.shiftleft.codepropertygraph.generated.Cpg
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Paths, StandardOpenOption}
import scala.jdk.CollectionConverters.*

trait CpgTestFixture[R <: X2CpgConfig[R]] extends AnyWordSpec with Matchers {

  import CpgTestFixture.*

  def project(code: String, path: String)(using config: R): MockProject[R] =
    MockProject(config, Set(CodeAndPath(code, path)))

  def emptyProject(using config: R): MockProject[R] = MockProject(config, Set.empty)

  protected implicit val defaultConfig: R

  protected def withTestConfig(f: (R) => Unit)(implicit config: R = defaultConfig): Unit = {
    val tempDir = Files.createTempDirectory("brokk-cpg-test-")
    try {
      val newConfig = config
        .withInputPath(tempDir.toString)
        .withOutputPath(tempDir.resolve("cpg.bin").toString)
      f(newConfig)
    } finally {
      tempDir.deleteRecursively
    }
  }

}

object CpgTestFixture {

  case class MockProject[R <: X2CpgConfig[R]](config: R, codeBase: Set[CodeAndPath]) {

    def moreCode(code: String, path: String): MockProject[R] = {
      val x = CodeAndPath(code, path)
      // By default, a set wont add an item if it already exists. Since we consider files of the same path
      // but different contents equivalent, they should be removed first before re-added
      val newCode = if codeBase.contains(x) then codeBase - x + x else codeBase + x
      this.copy(codeBase = newCode)
    }

    /**
     * Creates the source files described by this mock instance at the specified input location in the config.
     *
     * @return this project.
     */
    def buildProject: MockProject[R] = {
      val targetPath = Paths.get(config.inputPath)
      // Clear any existing contents then set-up project on disk
      Files.list(targetPath).toList.asScala.foreach(_.deleteRecursively)
      codeBase.foreach { case CodeAndPath(code, path) =>
        val newPath = targetPath.resolve(path)
        if !Files.exists(newPath.getParent) then Files.createDirectories(newPath.getParent)
        Files.writeString(targetPath, code, StandardOpenOption.CREATE)
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
    def buildCpg(using builder: IncrementalCpgBuilder[R]): Cpg = {
      buildProject
      builder.update(Cpg.empty, config)
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


