package io.joern.javasrc2cpg

import io.joern.javasrc2cpg.scope.Scope
import io.joern.javasrc2cpg.util.NameConstants
import io.shiftleft.codepropertygraph.generated.NodeTypes

package object astcreation {

  extension (scope: Scope) {

    def getAstParentInfo(includeMethod: Boolean = false): (String, String) = {
      scope.enclosingMethod
        .map { scope =>
          // this will include signature to avoid collisions
          (NodeTypes.METHOD, scope.method.fullName)
        }
        .filter(_ => includeMethod)
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
