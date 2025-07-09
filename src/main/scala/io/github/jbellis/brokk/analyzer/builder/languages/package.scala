package io.github.jbellis.brokk.analyzer.builder

/** Aliases the builders in this package to simplify their availability.
 */
package object languages {

  given javaSrcBuilder: JavaSrcBuilder.javaBuilder.type = JavaSrcBuilder.javaBuilder

  given cBuilder: CBuilder.cBuilder.type = CBuilder.cBuilder

}
