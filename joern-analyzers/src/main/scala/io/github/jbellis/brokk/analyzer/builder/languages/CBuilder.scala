package io.github.jbellis.brokk.analyzer.builder.languages

import io.github.jbellis.brokk.analyzer.builder.CpgBuilder
import io.github.jbellis.brokk.analyzer.builder.passes.cpp.PointerTypesPass
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
      val astCreationPass1 = new AstCreationPass(cpg, preprocessedFiles, gatherFileExtensions(config), config, global, report)
      val diffBuilder1 = io.shiftleft.codepropertygraph.generated.Cpg.newDiffGraphBuilder
      astCreationPass1.init()
      val parts1 = astCreationPass1.generateParts()
      for (part <- parts1) {
        astCreationPass1.runOnPart(diffBuilder1, part)
      }
      astCreationPass1.finish()
      flatgraph.DiffGraphApplier.applyDiff(cpg.graph, diffBuilder1)

      val astCreationPass2 = new AstCreationPass(cpg, preprocessedFiles, Set(FileDefaults.CHeaderFileExtension), config, global, report)
      val diffBuilder2 = io.shiftleft.codepropertygraph.generated.Cpg.newDiffGraphBuilder
      astCreationPass2.init()
      val parts2 = astCreationPass2.generateParts()
      for (part <- parts2) {
        astCreationPass2.runOnPart(diffBuilder2, part)
      }
      astCreationPass2.finish()
      flatgraph.DiffGraphApplier.applyDiff(cpg.graph, diffBuilder2)
      // Binary Compatibility fix: Use manual execution for potential Joern library passes
      val typeNodePass        = TypeNodePass.withRegisteredTypes(global.typesSeen(), cpg)
      val typeNodeDiffBuilder = io.shiftleft.codepropertygraph.generated.Cpg.newDiffGraphBuilder
      typeNodePass.init()
      val typeNodeParts = typeNodePass.generateParts()
      for (part <- typeNodeParts) {
        typeNodePass.runOnPart(typeNodeDiffBuilder, part)
      }
      typeNodePass.finish()
      flatgraph.DiffGraphApplier.applyDiff(cpg.graph, typeNodeDiffBuilder)

      val typeDeclNodePass    = new TypeDeclNodePass(cpg, config)
      val typeDeclDiffBuilder = io.shiftleft.codepropertygraph.generated.Cpg.newDiffGraphBuilder
      typeDeclNodePass.init()
      val typeDeclParts = typeDeclNodePass.generateParts()
      for (part <- typeDeclParts) {
        typeDeclNodePass.runOnPart(typeDeclDiffBuilder, part)
      }
      typeDeclNodePass.finish()
      flatgraph.DiffGraphApplier.applyDiff(cpg.graph, typeDeclDiffBuilder)

      val functionDeclNodePass    = new FunctionDeclNodePass(cpg, global.unhandledMethodDeclarations(), config)
      val functionDeclDiffBuilder = io.shiftleft.codepropertygraph.generated.Cpg.newDiffGraphBuilder
      functionDeclNodePass.init()
      val functionDeclParts = functionDeclNodePass.generateParts()
      for (part <- functionDeclParts) {
        functionDeclNodePass.runOnPart(functionDeclDiffBuilder, part)
      }
      functionDeclNodePass.finish()
      flatgraph.DiffGraphApplier.applyDiff(cpg.graph, functionDeclDiffBuilder)

      val fullNameUniquenessPass = new FullNameUniquenessPass(cpg)
      val fullNameDiffBuilder    = io.shiftleft.codepropertygraph.generated.Cpg.newDiffGraphBuilder
      fullNameUniquenessPass.init()
      val fullNameParts = fullNameUniquenessPass.generateParts()
      for (part <- fullNameParts) {
        fullNameUniquenessPass.runOnPart(fullNameDiffBuilder, part)
      }
      fullNameUniquenessPass.finish()
      flatgraph.DiffGraphApplier.applyDiff(cpg.graph, fullNameDiffBuilder)
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
