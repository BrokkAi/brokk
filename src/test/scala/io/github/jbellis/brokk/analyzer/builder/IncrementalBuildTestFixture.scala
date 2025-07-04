package io.github.jbellis.brokk.analyzer.builder

import io.github.jbellis.brokk.analyzer.builder.CpgTestFixture.*
import io.github.jbellis.brokk.analyzer.implicits.CpgExt.*
import io.joern.x2cpg.X2CpgConfig
import io.shiftleft.codepropertygraph.generated.nodes.AstNode
import io.shiftleft.codepropertygraph.generated.{Cpg, EdgeTypes}
import io.shiftleft.semanticcpg.language.*

import scala.util.Using

trait IncrementalBuildTestFixture[R <: X2CpgConfig[R]] {
  this: CpgTestFixture[R] =>

  def testIncremental(beforeChange: MockProject[R], afterChange: MockProject[R])(using builder: IncrementalCpgBuilder[R]): Unit = {
    Using.Manager { use =>
      val initialCpg = use(beforeChange.buildCpg)
      afterChange.buildProject // place new files at the path
      val updatedCpg = initialCpg.updateWith(afterChange.config)
      val fromScratchCpg = use(afterChange.buildCpg)
      verifyConsistency(updatedCpg, fromScratchCpg)
    }
  }

  /**
   * Verifies/asserts that the 'updated' CPG is equivalent to the 'fromScratch' CPG and has no other oddities.
   *
   * @param updated     the incrementally updated CPG.
   * @param fromScratch the CPG built from scratch, i.e., not incrementally.
   */
  private def verifyConsistency(updated: Cpg, fromScratch: Cpg): Unit = {
    /**
     * Asserts that, in the updated CPG, there is at most 1 edge of the given kind between two nodes.
     *
     * @param edgeKind the edge kind to verify.
     */
    def assertSingleEdgePairs(edgeKind: String): Unit = {
      withClue(s"Detected more than one ${edgeKind} edge between the same two nodes") {
        updated.graph.allEdges
          .collect { case e if e.edgeKind == updated.graph.schema.getEdgeKindByLabel(edgeKind) => e }
          .groupCount { e => (e.src.id(), e.dst.id()) }
          .values
          .filter(_ > 1)
          .size shouldBe 0
      }
    }

    // Assert only one meta data node exists
    updated.metaData.size shouldBe 1

    // Assert no common odities in the CPG, i.e, might result from non-idempotency of base passes
    updated.expression.filter(_.in(EdgeTypes.AST).size != 1).size shouldBe 0
    assertSingleEdgePairs(EdgeTypes.AST)
    assertSingleEdgePairs(EdgeTypes.CALL)
    assertSingleEdgePairs(EdgeTypes.REF)
    assertSingleEdgePairs(EdgeTypes.ARGUMENT)
    assertSingleEdgePairs(EdgeTypes.CONTAINS)
    assertSingleEdgePairs(EdgeTypes.SOURCE_FILE)
    assertSingleEdgePairs(EdgeTypes.EVAL_TYPE)
    assertSingleEdgePairs(EdgeTypes.INHERITS_FROM)
    // The below may not be present right now, but worth checking
    assertSingleEdgePairs(EdgeTypes.CFG)
    assertSingleEdgePairs(EdgeTypes.CDG)
    assertSingleEdgePairs(EdgeTypes.REACHING_DEF)

    // Determine all major structures are present and loosely equivalent
    withClue("Not all methods are equivalent in the updated graph") {
      fromScratch.method.fullName.toSet shouldBe updated.method.fullName.toSet
    }
    withClue("Not all type declarations are equivalent in the updated graph") {
      fromScratch.typeDecl.fullName.toSet shouldBe updated.typeDecl.fullName.toSet
    }
    withClue("Not all namespace blocks are equivalent in the updated graph") {
      fromScratch.namespaceBlock.fullName.toSet shouldBe updated.namespaceBlock.fullName.toSet
    }
    withClue("Not all imports are equivalent in the updated graph") {
      fromScratch.imports.importedEntity.toSet shouldBe updated.imports.importedEntity.toSet
    }

    // Determine basic AST equivalence
    withClue("Not all methods have the same type decl parents") {
      fromScratch.method.map(m => (m.fullName -> m.typeDecl.map(_.fullName))).toSet shouldBe
        updated.method.map(m => (m.fullName -> m.typeDecl.map(_.fullName))).toSet
    }
    withClue("Not all namespace blocks have the same type decl children") {
      fromScratch.namespaceBlock.map(n => (n.name -> n.typeDecl.map(_.fullName))).toSet shouldBe
        updated.namespaceBlock.map(n => (n.name -> n.typeDecl.map(_.fullName))).toSet
    }
    withClue("Not all files have the source-file children") {
      fromScratch.file.map(f => (f.name -> f._sourceFileIn.cast[AstNode].code)).toSet shouldBe
        updated.file.map(f => (f.name -> f._sourceFileIn.cast[AstNode].code)).toSet
    }
  }

}
