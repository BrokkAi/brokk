package io.github.jbellis.brokk.analyzer.builder.languages

import io.github.jbellis.brokk.analyzer.builder.CpgBuilder
import io.github.jbellis.brokk.analyzer.builder.passes.cpp.PointerTypesPass
import io.github.jbellis.brokk.analyzer.implicits.CpgExt.createAndApply
import io.joern.c2cpg.Config as CConfig
import io.joern.c2cpg.astcreation.CGlobal
import io.joern.c2cpg.parser.FileDefaults
import io.joern.c2cpg.passes.*
import io.joern.x2cpg.SourceFiles
import io.joern.x2cpg.passes.frontend.TypeNodePass
import io.joern.x2cpg.utils.Report
import io.shiftleft.codepropertygraph.generated.{Cpg, Languages}
import io.shiftleft.passes.CpgPassBase

import java.util.concurrent.ForkJoinPool
import scala.util.Try

object CBuilder {

  given cBuilder: CpgBuilder[CConfig] with {

    override protected val language: String = "C/C++"

    override def sourceFileExtensions: Set[String] =
      FileDefaults.SourceFileExtensions ++ FileDefaults.HeaderFileExtensions + FileDefaults.PreprocessedExt

    override def createAst(cpg: Cpg, config: CConfig)(using pool: ForkJoinPool): Try[Cpg] = Try {
      createOrUpdateMetaData(cpg, Languages.NEWC, config.inputPath)
      val report            = new Report()
      val global            = new CGlobal()
      val preprocessedFiles = allPreprocessedFiles(config)

      // Binary Compatibility fix: Use manual execution for C++ AstCreationPass
      List(
        new AstCreationPass(cpg, preprocessedFiles, gatherFileExtensions(config), config, global, report),
        new AstCreationPass(cpg, preprocessedFiles, Set(FileDefaults.CHeaderFileExtension), config, global, report),
      ).foreach(cpg.createAndApply)
      // Types
      List(
        TypeNodePass.withRegisteredTypes(global.typesSeen(), cpg),
        new TypeDeclNodePass(cpg, config),
      ).foreach(cpg.createAndApply)
      // Functions & full names
      List(
        new FunctionDeclNodePass(cpg, global.unhandledMethodDeclarations(), config),
        new FullNameUniquenessPass(cpg)
      ).foreach(cpg.createAndApply)

      report.print()
      cpg
    }

    override def basePasses(cpg: Cpg): Iterator[CpgPassBase] =
      (super.basePasses(cpg).toList :+ new PointerTypesPass(cpg)).iterator

  }

  def gatherFileExtensions(config: CConfig): Set[String] = {
    FileDefaults.SourceFileExtensions ++
      FileDefaults.CppHeaderFileExtensions ++
      Option.when(config.withPreprocessedFiles)(FileDefaults.PreprocessedExt).toList
  }

  def allPreprocessedFiles(config: CConfig): List[String] = {
    if (config.withPreprocessedFiles) {
      SourceFiles
        .determine(
          config.inputPath,
          Set(FileDefaults.PreprocessedExt),
          ignoredDefaultRegex = Option(config.defaultIgnoredFilesRegex),
          ignoredFilesRegex = Option(config.ignoredFilesRegex),
          ignoredFilesPath = Option(config.ignoredFiles)
        )
    } else {
      List.empty
    }
  }

}
