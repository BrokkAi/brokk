/*
 * Copyright 2025 The Joern Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * Modifications copyright 2025 Brokk, Inc. and made available under the GPLv3.
 *
 * The original file can be found at https://github.com/joernio/joern/blob/3e923e15368e64648e6c5693ac014a2cac83990a/joern-cli/frontends/javasrc2cpg/src/main/scala/io/joern/javasrc2cpg/util/Delombok.scala
 */
package io.joern.javasrc2cpg.util

import io.joern.javasrc2cpg.util.Delombok.DelombokMode.*
import io.shiftleft.semanticcpg.utils.FileUtil.*
import io.shiftleft.semanticcpg.utils.{ExternalCommand, FileUtil}
import org.slf4j.LoggerFactory

import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.concurrent.Executors
import scala.collection.parallel.CollectionConverters.*
import scala.collection.parallel.ExecutionContextTaskSupport
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try, Using}
import scala.jdk.StreamConverters.*

object Delombok {

  enum DelombokMode {
    case NoDelombok  extends DelombokMode // Don't run delombok at all.
    case Default     extends DelombokMode
    case TypesOnly   extends DelombokMode
    case RunDelombok extends DelombokMode
  }

  case class DelombokRunResult(path: Path, isDelombokedPath: Boolean)

  private val logger = LoggerFactory.getLogger(this.getClass)

  private def systemJavaPath: String = {
    sys.env
      .get("JAVA_HOME")
      .flatMap { javaHome =>
        val javaExecutable = Paths.get(javaHome, "bin", "java")

        Option.when(Files.exists(javaExecutable) && Files.isExecutable(javaExecutable)) {
          javaExecutable.absolutePathAsString
        }
      }
      .getOrElse("java")
  }

  private def delombokToTempDirCommand(inputPath: Path, outputDir: Path, analysisJavaHome: Option[String]) = {
    val javaPath = analysisJavaHome.getOrElse(systemJavaPath)
    val classPathArg = Try(FileUtil.newTemporaryFile("classpath")) match {
      case Success(file) =>
        FileUtil.deleteOnExit(file)
        // Write classpath to a file to work around Windows length limits.
        Files.writeString(file, System.getProperty("java.class.path"))
        s"@${file.absolutePathAsString}"

      case Failure(t) =>
        logger.warn(
          s"Failed to create classpath file for delombok execution. Results may be missing on Windows systems",
          t
        )
        System.getProperty("java.class.path")
    }
    val command =
      Seq(
        javaPath,
        "-Xmx1G",
        "-cp",
        classPathArg,
        "lombok.launch.Main",
        "delombok",
        inputPath.absolutePathAsString,
        "-d",
        outputDir.absolutePathAsString
      )
    logger.debug(s"Executing delombok with command ${command.mkString(" ")}")
    command
  }

  def delombokPackageRoot(
    projectDir: Path,
    relativePackageRoot: Path,
    delombokTempDir: Path,
    analysisJavaHome: Option[String]
  ): Try[String] = {
    val rootIsFile = Files.isRegularFile(projectDir.resolve(relativePackageRoot))
    val relativeOutputPath =
      if (rootIsFile) Option(relativePackageRoot.getParent).map(_.toString).getOrElse(".")
      else relativePackageRoot.toString
    val inputDir = projectDir.resolve(relativePackageRoot)

    val childPath = (delombokTempDir / relativeOutputPath).toAbsolutePath.normalize()

    Try(childPath.createWithParentsIfNotExists(asDirectory = true)).flatMap { packageOutputDir =>
      ExternalCommand
        .run(delombokToTempDirCommand(inputDir, packageOutputDir, analysisJavaHome))
        .logIfFailed()
        .toTry
        .map(_ => delombokTempDir.absolutePathAsString)
    }
  }

  def run(
    inputPath: Path,
    fileInfo: List[SourceParser.FileInfo],
    analysisJavaHome: Option[String]
  ): DelombokRunResult = {
    Try(Files.createTempDirectory("delombok")) match {
      case Failure(_) =>
        logger.warn(s"Could not create temporary directory for delombok output. Scanning original sources instead")
        DelombokRunResult(inputPath, false)

      case Success(tempDir) =>
        FileUtil.deleteOnExit(tempDir)

        // Use a dedicated thread pool with exactly one thread per core to avoid
        // ForkJoinPool compensation threads (which spawn additional workers).
        val cores = Runtime.getRuntime.availableProcessors()
        Using.resource(Executors.newFixedThreadPool(cores)) { executor =>
          val executorService = ExecutionContext.fromExecutorService(executor)
          val (lombokedPackageRoots, normalPackageRoots) = PackageRootFinder
            .packageRootsFromFiles(inputPath, fileInfo)
            .partition(containsLombokedJava(inputPath, _))

          logger.debug(
            s"Found ${normalPackageRoots.size} packages without Lombok usage, and ${lombokedPackageRoots.size} that use Lombok. Given input path: $inputPath"
          )

          // Delombok affected packages
          val parPackageRoots = lombokedPackageRoots.par
          parPackageRoots.tasksupport = new ExecutionContextTaskSupport(executorService)
          parPackageRoots.foreach { relativeRoot =>
            delombokPackageRoot(inputPath, relativeRoot, tempDir, analysisJavaHome)
          }
          // Simply move "normal" Java code to temp directory
          normalPackageRoots.foreach { relativeRoot =>
            Try {
              val sourceDir      = inputPath.resolve(relativeRoot)
              val destinationDir = tempDir.resolve(relativeRoot)

              Using.resource(Files.walk(sourceDir)) { walk =>
                walk
                  .toScala(Iterator)
                  .filter(Files.isRegularFile(_))
                  .foreach { sourcePath =>
                    val destinationPath = destinationDir.resolve(sourceDir.relativize(sourcePath))
                    val parentDir       = destinationPath.getParent
                    if !Files.isDirectory(parentDir) then Files.createDirectories(parentDir)
                    Files.copy(sourcePath, destinationPath, StandardCopyOption.REPLACE_EXISTING)
                  }
              }
            }.failed.foreach { e =>
              logger.error("Exception encountered while moving Java code to temporary directory!", e)
            }
          }
        }
        DelombokRunResult(tempDir, true)
    }
  }

  /** Performs a simple checks if any Java file within a given directory tree contains Lombok imports.
    *
    * @param inputPath
    *   The root path of the given package.
    * @param relativePackageRoot
    *   The relative package directory of the package to scan.
    * @return
    *   `true` if a Java file with a Lombok import is found, `false` otherwise.
    */
  private def containsLombokedJava(inputPath: Path, relativePackageRoot: Path): Boolean = {
    def checkFile(path: Path): Boolean =
      Using.resource(Files.lines(path))(_.toScala(Iterator).exists(_.trim.startsWith("import lombok.")))

    val packageRoot = inputPath.resolve(relativePackageRoot)
    if !Files.isDirectory(packageRoot) then checkFile(packageRoot)
    else {
      Using.resource(Files.walk(packageRoot)) { walk =>
        walk
          .toScala(Iterator)
          .filter(path => Files.isRegularFile(path) && path.toString.endsWith(".java"))
          .exists(checkFile)
      }
    }
  }

  def parseDelombokModeOption(delombokModeStr: Option[String]): DelombokMode = {
    delombokModeStr.map(_.toLowerCase) match {
      case None                 => Default
      case Some("no-delombok")  => NoDelombok
      case Some("default")      => Default
      case Some("types-only")   => TypesOnly
      case Some("run-delombok") => RunDelombok
      case Some(value) =>
        logger.warn(s"Found unrecognised delombok mode `$value`. Using default instead.")
        Default
    }
  }
}
