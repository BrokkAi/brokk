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
 * The original file can be found at https://github.com/joernio/joern/blob/3e923e15368e64648e6c5693ac014a2cac83990a/joern-cli/frontends/x2cpg/src/main/scala/io/joern/x2cpg/passes/frontend/TypeNodePass.scala
 */
package io.github.jbellis.brokk.analyzer.builder.passes.idempotent.frontend

import io.shiftleft.codepropertygraph.generated.nodes.NewType
import io.shiftleft.codepropertygraph.generated.{Cpg, Properties}
import io.shiftleft.passes.CpgPass
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.language.types.structure.NamespaceTraversal

import scala.collection.mutable

/** Modified version of TypeNodePass that avoids re-creating type nodes for type nodes already present.
  */
class TypeNodePass protected (registeredTypes: List[String], cpg: Cpg, getTypesFromCpg: Boolean)
    extends CpgPass(cpg, "types") {

  protected def typeDeclTypes: mutable.Set[String] = {
    val typeDeclTypes = mutable.Set[String]()
    cpg.typeDecl.foreach { typeDecl =>
      typeDeclTypes += typeDecl.fullName
      typeDeclTypes ++= typeDecl.inheritsFromTypeFullName
    }
    typeDeclTypes
  }

  protected def typeFullNamesFromCpg: Set[String] = {
    cpg.all
      .map(_.property(Properties.TypeFullName))
      .filter(_ != null)
      .toSet
  }

  protected def existingTypesFromCpg: Set[String] =
    cpg.typ.fullName.toSet

  protected def fullToShortName(typeName: String): String = TypeNodePass.fullToShortName(typeName)

  override def run(diffGraph: DiffGraphBuilder): Unit = {
    val typeFullNameValues =
      if (getTypesFromCpg)
        typeFullNamesFromCpg
      else
        registeredTypes.toSet

    val usedTypesSet = typeDeclTypes ++ typeFullNameValues
    usedTypesSet.remove("<empty>")
    val usedTypes =
      usedTypesSet
        .filterInPlace(!_.endsWith(NamespaceTraversal.globalNamespaceName))
        .distinct
        .sorted

    val existingTypes = existingTypesFromCpg

    usedTypes
      .filterNot(existingTypes.contains)
      .foreach { typeName =>
        val shortName = fullToShortName(typeName)
        val node = NewType()
          .name(shortName)
          .fullName(typeName)
          .typeDeclFullName(typeName)
        diffGraph.addNode(node)
      }
  }
}

object TypeNodePass {
  def withTypesFromCpg(cpg: Cpg): TypeNodePass = {
    new TypeNodePass(Nil, cpg, getTypesFromCpg = true)
  }

  def withRegisteredTypes(registeredTypes: List[String], cpg: Cpg): TypeNodePass = {
    new TypeNodePass(registeredTypes, cpg, getTypesFromCpg = false)
  }

  def fullToShortName(typeName: String): String = {
    if (typeName.endsWith(">")) {
      // special case for typeFullName with generics as suffix
      typeName.takeWhile(c => c != ':' && c != '<').split('.').lastOption.getOrElse(typeName)
    } else {
      typeName.takeWhile(_ != ':').split('.').lastOption.getOrElse(typeName)
    }
  }
}
