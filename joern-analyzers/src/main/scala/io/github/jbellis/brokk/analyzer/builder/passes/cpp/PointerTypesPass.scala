package io.github.jbellis.brokk.analyzer.builder.passes.cpp

import io.shiftleft.codepropertygraph.generated.nodes.TypeDecl
import io.shiftleft.codepropertygraph.generated.{Cpg, EdgeTypes}
import io.shiftleft.passes.ForkJoinParallelCpgPass
import io.shiftleft.semanticcpg.language.*

import scala.collection.mutable

/** Connects pointer types to the base type so that dynamically dispatched calls could be resolved.
  *
  * Requires [[io.joern.x2cpg.passes.base.TypeRefPass]] and [[io.joern.x2cpg.passes.base.TypeEvalPass]] as
  * pre-requisites but must run before [[io.joern.x2cpg.passes.typerelations.TypeHierarchyPass]] and any call graph
  * passes. Any orphaned pointer types will be pruned by [[PruneTypesPass]].
  */
class PointerTypesPass(cpg: Cpg) extends ForkJoinParallelCpgPass[TypeDecl](cpg) {

  private val pointersToUnderlyingTypes = mutable.Map.empty[TypeDecl, TypeDecl]

  override def init(): Unit = {
    cpg.typeDecl
      .filter(_.name.endsWith("*"))
      .foreach { pointerTypeDecl =>
        val strippedFullName = pointerTypeDecl.fullName.stripSuffix("*")
        cpg.typeDecl
          .isExternal(false) // only consider types where the declaration has been provided
          .fullNameExact(strippedFullName)
          .foreach { underlyingTypeDecl =>
            pointersToUnderlyingTypes.put(pointerTypeDecl, underlyingTypeDecl)
          }
      }
  }

  /** Each part is a "pointer" that will be registered as some external type.
    * @return
    *   an array of all pointer types in the CPG.
    */
  override def generateParts(): Array[TypeDecl] = pointersToUnderlyingTypes.keys.toArray

  /** Each task will begin by determining the type behind the pointer if it exists as a user-defined type. If this is
    * the case, we will "re-wire" the expressions to the user-defined type.
    *
    * This may be an "imprecise" way to model this, but as far as open-source call graph support goes, pointers aren't
    * handled explicitly anyway.
    *
    * @param builder
    *   the diff graph builder.
    * @param pointerTypeDecl
    *   a pointer type declaration.
    */
  override def runOnPart(builder: DiffGraphBuilder, pointerTypeDecl: TypeDecl): Unit = {
    pointersToUnderlyingTypes.get(pointerTypeDecl).foreach { underlyingTypeDecl =>
      rewirePointerType(builder, pointerTypeDecl, underlyingTypeDecl)
    }
  }

  private def rewirePointerType(
    builder: DiffGraphBuilder,
    pointerTypeDecl: TypeDecl,
    underlyingTypeDecl: TypeDecl
  ): Unit = {
    pointerTypeDecl.referencingType.filter(_.name.endsWith("*")).foreach { pointerType =>
      val pointerEvaluatedNodes = pointerType.evalTypeIn.l

      underlyingTypeDecl.referencingType.foreach { underlyingType =>
        pointerEvaluatedNodes.foreach { node =>
          // 1) Prune away edges to the "pointer" type decl
          node.outE(EdgeTypes.EVAL_TYPE).filter(_.dst == pointerType).foreach(builder.removeEdge)
          // 2) Add edges to underlying type
          builder.addEdge(node, underlyingType, EdgeTypes.EVAL_TYPE)
        }
      }
    }
  }

}
