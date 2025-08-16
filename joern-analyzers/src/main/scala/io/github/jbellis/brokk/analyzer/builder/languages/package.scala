package io.github.jbellis.brokk.analyzer.builder

/** Aliases the builders in this package to simplify their availability.
  */
package object languages {

  given cBuilder: CBuilder.cBuilder.type = CBuilder.cBuilder

}
