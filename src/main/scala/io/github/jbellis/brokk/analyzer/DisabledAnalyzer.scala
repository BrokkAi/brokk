package io.github.jbellis.brokk.analyzer

import java.util
import java.util.{Collections, Optional}

/** An IAnalyzer implementation that does nothing and returns empty results.
  */
class DisabledAnalyzer extends IAnalyzer {
  override def isEmpty: Boolean =
    true

  override def isCpg: Boolean =
    false

  override def getAllDeclarations: util.List[CodeUnit] =
    Collections.emptyList()

  override def getMembersInClass(fqClass: String): util.List[CodeUnit] =
    Collections.emptyList()

  override def getDeclarationsInFile(file: ProjectFile): util.Set[CodeUnit] =
    Collections.emptySet()

  override def getPagerank(
    seedClassWeights: java.util.Map[String, java.lang.Double],
    k: Int,
    reversed: Boolean = false
  ): java.util.List[scala.Tuple2[CodeUnit, java.lang.Double]] =
    Collections.emptyList()

  override def getSkeleton(className: String): Optional[String] =
    Optional.empty()

  override def getSkeletonHeader(className: String): Optional[String] =
    Optional.empty()

  override def getFileFor(fqcn: String): java.util.Optional[ProjectFile] = {
    java.util.Optional.empty()
  }

  override def searchDefinitions(pattern: String): util.List[CodeUnit] =
    Collections.emptyList()

  override def getUses(symbol: String): util.List[CodeUnit] =
    Collections.emptyList()

  override def getMethodSource(methodName: String): Optional[String] =
    Optional.empty()

  override def getClassSource(className: String): java.lang.String =
    ""

  override def getCallgraphTo(methodName: String, depth: Int): java.util.Map[String, java.util.List[CallSite]] =
    Collections.emptyMap()

  override def getCallgraphFrom(methodName: String, depth: Int): java.util.Map[String, java.util.List[CallSite]] =
    Collections.emptyMap()

  override def getSymbols(sources: java.util.Set[CodeUnit]): java.util.Set[String] = {
    Collections.emptySet()
  }
}
