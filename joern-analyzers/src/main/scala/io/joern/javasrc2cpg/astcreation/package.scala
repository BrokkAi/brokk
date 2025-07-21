package io.joern.javasrc2cpg

import io.joern.javasrc2cpg.scope.Scope
import io.joern.javasrc2cpg.util.NameConstants
import io.shiftleft.codepropertygraph.generated.NodeTypes
import io.shiftleft.codepropertygraph.generated.nodes.Method.PropertyDefaults

package object astcreation {

  extension (scope: Scope) {

    def getAstParentInfo(prioritizeMethodAstParent: Boolean = false): (String, String) = {
      scope.enclosingMethod
        .map { scope =>
          (NodeTypes.METHOD, scope.method.fullName)
        }
        .filter((_, fullName) => prioritizeMethodAstParent && fullName != PropertyDefaults.FullName)
        .orElse {
          scope.enclosingTypeDecl
            .map { scope =>
              (NodeTypes.TYPE_DECL, scope.typeDecl.fullName)
            }
        }
        .orElse {
          scope.enclosingNamespace.map { scope =>
            (NodeTypes.NAMESPACE_BLOCK, scope.namespace.fullName)
          }
        }
        .getOrElse((NameConstants.Unknown, NameConstants.Unknown))
    }

  }
}
