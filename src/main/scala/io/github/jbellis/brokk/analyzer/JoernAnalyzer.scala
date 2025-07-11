package io.github.jbellis.brokk.analyzer

import io.github.jbellis.brokk.*
import io.joern.joerncli.CpgBasedTool
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.language.*
import io.shiftleft.codepropertygraph.generated.nodes.{Method, NamespaceBlock, TypeDecl}
import io.shiftleft.semanticcpg.language.*
import org.slf4j.LoggerFactory

import java.io.Closeable
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.parallel.CollectionConverters.IterableIsParallelizable
import scala.concurrent.ExecutionContext
import scala.io.Source
import scala.jdk.OptionConverters.RichOptional
import scala.util.Try
import scala.util.matching.Regex

/** An abstract base for language-specific analyzers. It implements the bulk of "IAnalyzer" using Joern's CPG, but
  * delegates language-specific operations (like building a CPG or constructing method signatures) to concrete
  * subclasses.
  */
abstract class JoernAnalyzer protected (sourcePath: Path, private[brokk] val cpg: Cpg)
    extends IAnalyzer
    with Closeable {

  // Logger instance for this class
  protected val logger = LoggerFactory.getLogger(getClass)

  // Convert to absolute filename immediately and verify it's a directory
  protected val absolutePath: Path = {
    val path = sourcePath.toAbsolutePath.toRealPath()
    require(path.toFile.isDirectory, s"Source path must be a directory: $path")
    path
  }

  // implicits at the top, you will regret it otherwise
  protected implicit val ec: ExecutionContext        = ExecutionContext.global
  protected implicit val callResolver: ICallResolver = NoResolve

  // Adjacency maps for pagerank
  private var adjacency: Map[String, Map[String, Int]]        = Map.empty
  private var reverseAdjacency: Map[String, Map[String, Int]] = Map.empty
  private var classesForPagerank: Set[String]                 = Set.empty

  // Cache for directChildren to avoid expensive CPG queries
  private val directChildrenCache = new ConcurrentHashMap[CodeUnit, java.util.List[CodeUnit]]()

  initializePageRank()

  /** Secondary constructor: create a new analyzer, loading a pre-built CPG from `preloadedPath`.
    */
  def this(sourcePath: Path, preloadedPath: Path) =
    this(sourcePath, CpgBasedTool.loadFromFile(preloadedPath.toString))

  /** Simplest constructor: build a brand new CPG for the given source path.
    */
  def this(sourcePath: Path) = this(sourcePath, Cpg.empty)

  /** Utility constructor that can override the language, or any other parameter. The default just calls
    * `this(sourcePath)`.
    */
  def this(sourcePath: Path, maybeUnused: Language) = this(sourcePath)

  override def isCpg: Boolean = true

  /** Return the method signature as a language-appropriate String, e.g. for Java: "public int foo(String bar)"
    */
  protected def methodSignature(m: Method): String

  // --- Abstract CodeUnit Creation Methods ---

  /** Create a CLASS CodeUnit from FQCN. Relies on language-specific heuristics. Prefer CodeUnit factories where
    * complete information is available.
    */
  def cuClass(fqcn: String, file: ProjectFile): Option[CodeUnit]

  /** Create a FUNCTION CodeUnit from FQN. Relies on language-specific heuristics. Prefer CodeUnit factories where
    * complete information is available.
    */
  def cuFunction(fqmn: String, file: ProjectFile): Option[CodeUnit]

  /** Create a FIELD CodeUnit from FQN. Relies on language-specific heuristics. Prefer CodeUnit factories where complete
    * information is available.
    */
  def cuField(fqfn: String, file: ProjectFile): Option[CodeUnit]
  // -----------------------------------------

  /** Transform method node fullName to a stable "resolved" name (e.g. removing lambda suffixes).
    */
  private[brokk] def resolveMethodName(methodName: String): String

  /** Possibly remove package names from a type string, or do other language-specific cleanup.
    */
  private[brokk] def sanitizeType(t: String): String

  /** Return all Method nodes that match the given fully qualified method name (subclasses can handle language-specific
    * naming).
    */
  protected def methodsFromName(resolvedMethodName: String): List[Method]

  /** Optional final override if the notion of "class in project" differs by language. By default, we check that the
    * typeDecl is present and has some members or methods.
    */
  def isClassInProject(className: String): Boolean = {
    val td = cpg.typeDecl.fullNameExact(className).l
    td.nonEmpty && !(td.member.isEmpty && td.method.isEmpty && td.derivedTypeDecl.isEmpty)
  }

  private def initializePageRank(): Unit = {
    if (cpg.metaData.headOption.isEmpty)
      throw new IllegalStateException("CPG root not found for " + absolutePath)

    // Initialize adjacency map
    adjacency = buildWeightedAdjacency()
    val reverseMap = TrieMap[String, TrieMap[String, Int]]()

    // Build reverse adjacency from the forward adjacency
    adjacency.par.foreach { case (src, tgtMap) =>
      tgtMap.foreach { case (dst, weight) =>
        val targetMap = reverseMap.getOrElseUpdate(dst, TrieMap.empty)
        targetMap.update(src, targetMap.getOrElse(src, 0) + weight)
      }
    }
    reverseAdjacency = reverseMap.map { case (src, tgtMap) => src -> tgtMap.toMap }.toMap

    classesForPagerank = (adjacency.keys ++ adjacency.values.flatMap(_.keys)).toSet
  }

  override def getMethodSource(fqName: String): Optional[String] = {
    val resolvedMethodName = resolveMethodName(fqName)
    val methods            = methodsFromName(resolvedMethodName)

    // static constructors often lack line info
    val sources = methods.flatMap { method =>
      for {
        file      <- toFile(method.filename)
        startLine <- method.lineNumber
        endLine   <- method.lineNumberEnd
      } yield scala.util
        .Using(Source.fromFile(file.absPath().toFile)) { source =>
          source.getLines().slice(startLine - 1, endLine).mkString("\n")
        }
        .toOption
    }.flatten

    if (sources.isEmpty) Optional.empty() else Optional.of(sources.mkString("\n\n"))
  }
  
  override def getClassSource(fqcn: String): String = cpg.typeDecl
    .fullNameExact(fqcn)
    .flatMap(_.content)
    .headOption
    .orNull

  /** Recursively builds a structural "skeleton" for a given TypeDecl. Language-specific details like method signatures
    * and filtering rules are handled by the concrete implementation.
    */
  protected def outlineTypeDecl(td: TypeDecl, indent: Int = 0): String

  /** Build a weighted adjacency map at the class level: className -> Map[targetClassName -> weight].
    */
  protected def buildWeightedAdjacency()(implicit callResolver: ICallResolver): Map[String, Map[String, Int]] = {
    val adjacencyMap   = TrieMap[String, TrieMap[String, Int]]()
    val primitiveTypes = Set("byte", "short", "int", "long", "float", "double", "boolean", "char", "void")

    def isRelevant(t: String): Boolean =
      !primitiveTypes.contains(t) && !t.startsWith("java.")

    cpg.typeDecl.l.par.foreach { td =>
      val sourceClass = td.fullName

      // (1) Collect calls
      td.method.call.callee.typeDecl.fullName
        .filter(isRelevant)
        .filter(_ != sourceClass)
        .foreach(increment(adjacencyMap, sourceClass, _))

      // (2) Collect field references
      td.member.typeFullName
        .map(_.replaceAll("""\[\]""", ""))
        .filter(isRelevant)
        .filter(_ != sourceClass)
        .foreach(increment(adjacencyMap, sourceClass, _))

      // (3) Collect "extends"/"implements" edges; these count 5x
      td.inheritsFromTypeFullName
        .filter(isRelevant)
        .filter(_ != sourceClass)
        .foreach(parent => increment(adjacencyMap, sourceClass, parent, 5))
    }
    adjacencyMap.map { case (src, tgtMap) => src -> tgtMap.toMap }.toMap
  }

  /** Increment a (source -> target) edge by `count` in an adjacency map.
    */
  protected def increment(
    map: TrieMap[String, TrieMap[String, Int]],
    source: String,
    target: String,
    count: Int = 1
  ): Unit = {
    val targetMap = map.getOrElseUpdate(source, TrieMap.empty)
    targetMap.update(target, targetMap.getOrElse(target, 0) + count)
  }

  override def getFileFor(fqName: String): java.util.Optional[ProjectFile] = {
    val scalaOpt = cpg.typeDecl.fullNameExact(fqName).headOption.flatMap(toFile)
    scalaOpt match
      case Some(file) => java.util.Optional.of(file)
      case None       => java.util.Optional.empty()
  }

  private def toFile(td: TypeDecl): Option[ProjectFile] = {
    if (td.filename.isEmpty || td.filename == "<empty>" || td.filename == "<unknown>") None
    else toFile(td.filename)
  }

  private[brokk] def toFile(relName: String): Option[ProjectFile] = {
    if (relName == null || relName.isEmpty || (relName.startsWith("<") && relName.endsWith(">"))) {
      None // Filter out pseudo-files like <empty>, <unknown>, etc.
    } else {
      Some(ProjectFile(absolutePath, relName))
    }
  }

  // using cpg.all doesn't work because there are always-present nodes for files and the ANY typedecl
  override def isEmpty: Boolean = cpg.member.isEmpty

  override def getAllDeclarations: java.util.List[CodeUnit] = {
    import scala.jdk.CollectionConverters.*
    cpg.typeDecl
      .filter(toFile(_).isDefined)
      .flatMap { td =>
        toFile(td).flatMap(file => cuClass(td.fullName, file))
      }
      .l
      .asJava
  }

  override def getSkeletonHeader(className: String): Optional[String] = {
    val decls = cpg.typeDecl.fullNameExact(className).l
    if (decls.isEmpty) Optional.empty()
    else Optional.of(headerString(decls.head, 0) + "  [... methods not shown ...]\n}")
  }

  private def headerString(td: TypeDecl, indent: Int): String = {
    val sb        = new StringBuilder
    val className = sanitizeType(td.name)
    sb.append("  " * indent).append("class ").append(className).append(" {\n")
    td.member.foreach { m =>
      val modifiers = m.modifier.map(_.modifierType.toLowerCase).filter(_.nonEmpty).mkString(" ")
      val modString = if (modifiers.nonEmpty) s"$modifiers " else ""
      val typeName  = sanitizeType(m.typeFullName)
      sb.append("  " * (indent + 1)).append(s"$modString$typeName ${m.name};\n")
    }
    sb.toString
  }

  /** Remove the trailing “:signature” **only**. Keep any earlier colon that is part of a CPG global-function name, e.g.
    * geometry.cpp:global_func:void(int) → geometry.cpp:global_func shapes.Circle.getArea:double() →
    * shapes.Circle.getArea
    */
  private def chopColon(full: String) =
    full.lastIndexOf(':') match
      case -1  => full
      case idx => full.substring(0, idx)

  /** For a given method node `m`, returns the CPG Method nodes of its callers. If `excludeSelfRefs` is true, we skip
    * callers whose TypeDecl matches `m.typeDecl`.
    */
  protected def callersOfMethodNode(m: Method, excludeSelfRefs: Boolean): List[Method] = {
    var callNodes = m.callIn
    if (excludeSelfRefs) {
      m.typeDecl.fullName.headOption.foreach { selfSourceFqn =>
        callNodes = callNodes.filterNot { call =>
          call.method.typeDecl.fullName.headOption.exists(callerSourceFqn =>
            partOfClass(selfSourceFqn, callerSourceFqn)
          )
        }
      }
    }
    callNodes.method.dedup.l // .method gets the calling method node(s)
  }

  protected def partOfClass(parentFqcn: String, childFqcn: String): Boolean = {
    childFqcn == parentFqcn ||
    childFqcn.startsWith(parentFqcn + ".") ||
    childFqcn.startsWith(parentFqcn + "$")
  }

  /** Return all methods that reference a given field "classFullName.fieldName".
    */
  protected def referencesToField(selfSource: String, fieldName: String, excludeSelfRefs: Boolean): List[String] = {
    var calls = cpg.call
      .nameExact("<operator>.fieldAccess")
      .where(_.argument(1).typ.fullNameExact(selfSource))
      .where(_.argument(2).codeExact(fieldName))

    if (excludeSelfRefs) {
      calls = calls.filterNot(call => partOfClass(selfSource, call.method.typeDecl.fullName.head))
    }
    calls.method.fullName
      .map(x => resolveMethodName(chopColon(x)))
      .distinct
      .l
  }

  /** Find references to a class used as a type:
    *   - fields typed with that class
    *   - parameters/locals typed with that class
    *   - classes that inherit from that class
    *   - methods that return that class
    */
  protected def referencesToClassAsType(classFullName: String): List[CodeUnit] = {
    val typePattern =
      "^" +
        "(?:struct |class |union |enum )?" + // optional C/C++ keyword prefix
        Regex.quote(classFullName) +
        "(\\$.*)?(\\[\\])?"

    // Fields typed with this class → return as field CodeUnits
    val fieldRefs = cpg.member
      .typeFullName(typePattern)
      .astParent
      .isTypeDecl
      .filter(td => !partOfClass(classFullName, td.fullName))
      .flatMap { td => // td is the TypeDecl containing the field
        toFile(td)
          .map { file => // Use map here since we return a List[CodeUnit] below
            // Find members matching the type pattern within this TypeDecl
            td.member
              .typeFullName(typePattern)
              .flatMap { member =>
                // Here we have all the parts: package, class, member
                val lastDot     = td.fullName.lastIndexOf('.')
                val packageName = if (lastDot > 0) td.fullName.substring(0, lastDot) else ""
                val className   = td.name
                val fieldName   = member.name

                // Create using exact 3-parameter factory
                Try(CodeUnit.field(file, packageName, s"$className.$fieldName")).toOption
              }
              .toList // Convert the final Iterator[CodeUnit] to List[CodeUnit]
          }
          .getOrElse(List.empty) // If toFile(td) was None, return empty list
      }                          // This outer flatMap now correctly flattens List[List[CodeUnit]] into List[CodeUnit]

    // Parameters typed with this class → return as function CodeUnits
    val paramRefs = cpg.parameter
      .typeFullName(typePattern)
      .method
      .filter(m => m.typeDecl.fullName.headOption.exists(ownerFqcn => !partOfClass(classFullName, ownerFqcn)))
      .flatMap { m =>
        m.typeDecl.headOption.flatMap { td =>
          toFile(td).flatMap { file =>
            val methodName = resolveMethodName(chopColon(m.fullName))
            val lastDot    = methodName.lastIndexOf('.')
            if (lastDot > 0) {
              val fullClassPath = methodName.substring(0, lastDot)
              val classLastDot  = fullClassPath.lastIndexOf('.')
              val packageName   = if (classLastDot > 0) fullClassPath.substring(0, classLastDot) else ""
              val className     = if (classLastDot > 0) fullClassPath.substring(classLastDot + 1) else fullClassPath
              val memberName    = methodName.substring(lastDot + 1)

              Try(CodeUnit.fn(file, packageName, s"$className.$memberName")).toOption
            } else {
              cuFunction(methodName, file)
            }
          }
        }
      }
      .l

    // Locals typed with this class → return as function CodeUnits
    val localRefs = cpg.local
      .typeFullName(typePattern)
      .method
      .filter(m => m.typeDecl.fullName.headOption.exists(ownerFqcn => !partOfClass(classFullName, ownerFqcn)))
      .flatMap { m =>
        m.typeDecl.headOption.flatMap { td =>
          toFile(td).flatMap { file =>
            val methodName = resolveMethodName(chopColon(m.fullName))
            val lastDot    = methodName.lastIndexOf('.')
            if (lastDot > 0) {
              val fullClassPath = methodName.substring(0, lastDot)
              val classLastDot  = fullClassPath.lastIndexOf('.')
              val packageName   = if (classLastDot > 0) fullClassPath.substring(0, classLastDot) else ""
              val className     = if (classLastDot > 0) fullClassPath.substring(classLastDot + 1) else fullClassPath
              val memberName    = methodName.substring(lastDot + 1)

              Try(CodeUnit.fn(file, packageName, s"$className.$memberName")).toOption
            } else {
              cuFunction(methodName, file)
            }
          }
        }
      }
      .l

    // Methods returning this class → return as function CodeUnits
    val methodReturnRefs = cpg.method
      .where(_.methodReturn.typeFullName(typePattern))
      .filter(m => m.typeDecl.fullName.headOption.exists(ownerFqcn => !partOfClass(classFullName, ownerFqcn)))
      .flatMap { m =>
        m.typeDecl.headOption.flatMap { td =>
          toFile(td).flatMap { file =>
            val methodName = resolveMethodName(chopColon(m.fullName))
            val lastDot    = methodName.lastIndexOf('.')
            if (lastDot > 0) {
              val fullClassPath = methodName.substring(0, lastDot)
              val classLastDot  = fullClassPath.lastIndexOf('.')
              val packageName   = if (classLastDot > 0) fullClassPath.substring(0, classLastDot) else ""
              val className     = if (classLastDot > 0) fullClassPath.substring(classLastDot + 1) else fullClassPath
              val memberName    = methodName.substring(lastDot + 1)

              Try(CodeUnit.fn(file, packageName, s"$className.$memberName")).toOption
            } else {
              cuFunction(methodName, file)
            }
          }
        }
      }
      .l

    // Classes that inherit from this class → return as class CodeUnits
    val inheritingClasses = cpg.typeDecl
      .filter(_.inheritsFromTypeFullName.contains(classFullName))
      .filter(td => !partOfClass(classFullName, td.fullName))
      .flatMap { td =>
        toFile(td).flatMap { file =>
          val lastDot     = td.fullName.lastIndexOf('.')
          val packageName = if (lastDot > 0) td.fullName.substring(0, lastDot) else ""
          val className   = td.name

          Try(CodeUnit.cls(file, packageName, className)).toOption
        }
      }
      .l

    (fieldRefs ++ paramRefs ++ localRefs ++ methodReturnRefs ++ inheritingClasses).toList.distinct
  }

  /** Recursively collects the fully-qualified names of all subclasses of the given class.
    */
  private[brokk] def allSubclasses(className: String): Set[String] = {
    val direct = cpg.typeDecl.l
      .filter { td =>
        td.inheritsFromTypeFullName.contains(className)
      }
      .map(_.fullName)
      .toSet
    direct ++ direct.flatMap { sub => allSubclasses(sub) }
  }

  override def getUses(symbol: String): java.util.List[CodeUnit] = {
    import scala.jdk.CollectionConverters.*

    logger.debug(s"Getting uses for symbol: '$symbol'")

    // (1) Method matches?
    // Expand method lookup to include overrides in subclasses.
    val baseMethodMatches = methodsFromName(symbol)
    logger.debug(s"Found ${baseMethodMatches.size} base method matches for '$symbol'")

    val resolvedSymbol = resolveMethodName(symbol) // Resolve once upfront
    logger.debug(s"Getting uses for symbol: '$symbol' (resolved: '$resolvedSymbol')")

    val expandedMethodMatches =
      if (resolvedSymbol.contains(".")) {
        val lastDot    = resolvedSymbol.lastIndexOf('.')
        val classPart  = resolvedSymbol.substring(0, lastDot)
        val methodPart = resolvedSymbol.substring(lastDot + 1)
        logger.debug(s"Symbol contains dot: classPart='$classPart', methodPart='$methodPart'")

        val subclasses = allSubclasses(classPart)
        logger.debug(s"Found ${subclasses.size} subclasses of '$classPart'")

        // candidate fully-qualified method names: original and each subclass override
        val expandedSymbols = (Set(resolvedSymbol) ++ subclasses.map(sub => s"$sub.$methodPart")).toList
        logger.debug(s"Expanded to ${expandedSymbols.size} symbol candidates: ${expandedSymbols.take(5).mkString(", ")}${if (expandedSymbols.size > 5) "..." else ""}")

        val expanded = expandedSymbols.flatMap(mn => methodsFromName(mn))
        logger.debug(s"After expanding, found ${expanded.size} method matches")
        expanded
      } else { // Symbol does not contain a dot, could be a global function or a simple class name
        // Check if it's a known method (global function in C/C++ context)
        val methods = methodsFromName(resolvedSymbol)
        if (methods.nonEmpty) {
          logger.debug(s"Symbol '$resolvedSymbol' (no dot) matches ${methods.size} methods (global functions).")
          methods // Return these as the matches
        } else {
          logger.debug(s"Symbol '$resolvedSymbol' (no dot) not a method, treating as potential class or unresolvable.")
          // If not a method, it might be a class name, which will be handled by section (3).
          // Or it's an unresolvable method name, in which case `baseMethodMatches` will be empty.
          baseMethodMatches // This will be empty if it's not a known method
        }
      }

    if (expandedMethodMatches.nonEmpty) {
      logger.debug(s"Processing ${expandedMethodMatches.size} matched methods")
      // Collect all CPG Method nodes of callers from all matched methods
      val callingCpgMethods =
        expandedMethodMatches.flatMap(m => callersOfMethodNode(m, excludeSelfRefs = false)).distinct
      logger.debug(s"Found ${callingCpgMethods.size} distinct calling CPG methods.")

      val results = callingCpgMethods.flatMap { cpgMethod =>
        val fileOpt =
          if (cpgMethod.filename.nonEmpty) toFile(cpgMethod.filename) else cpgMethod.typeDecl.headOption.flatMap(toFile)
        fileOpt.flatMap { file =>
          val isGlobalMethod = cpgMethod.astParent match {
            case parentNode: NamespaceBlock => true
            case parentNode: TypeDecl       => parentNode.name.endsWith("<global>")
            case _                          => false
          }

          // For global methods, CPG fullName might be "filename.ext:funcname".
          // For class methods, it's "pkg.Cls.method:sig" or "Cls.method:sig".
          // resolveMethodName(chopColon(...)) handles this.
          val baseFqn = resolveMethodName(chopColon(cpgMethod.fullName))

          val fqnForCu =
            if !baseFqn.contains(".") && !baseFqn.contains(":") then
              // synthesise package  “filename_ext”
              val fn       = baseFqn
              val fileName = Path.of(file.toString).getFileName.toString
              val dot      = fileName.lastIndexOf('.')
              val (stem, ext) =
                if dot > 0 then (fileName.substring(0, dot), fileName.substring(dot + 1))
                else (fileName, "")
              val pkg = if ext.nonEmpty then s"${stem}_${ext}" else stem
              s"$pkg.$fn"
            else baseFqn

          cuFunction(fqnForCu, file)
        }
      }
      logger.debug(
        s"Created ${results.size} CodeUnits for calling methods. Example: ${results.take(5).map(_.fqName()).mkString(", ")}"
      )
      return results.asJava
    }

    // (2) Possibly a field: com.foo.Bar.field (or resolvedSymbol equivalent)
    val lastDotInResolved = resolvedSymbol.lastIndexOf('.')
    if (lastDotInResolved > 0) {
      logger.debug("Trying to interpret as field reference")
      val classPart = resolvedSymbol.substring(0, lastDotInResolved)
      val fieldPart = resolvedSymbol.substring(lastDotInResolved + 1)
      logger.debug(s"Parsed as potential field: class='$classPart', field='$fieldPart'")

      val clsDecls = cpg.typeDecl.fullNameExact(classPart).l
      if (clsDecls.nonEmpty) {
        logger.debug(s"Found class declaration for: $classPart")
        val maybeFieldDecl = clsDecls.head.member.nameExact(fieldPart).l
        if (maybeFieldDecl.nonEmpty) {
          logger.debug(s"Found field declaration: $fieldPart")
          val refs = referencesToField(classPart, fieldPart, excludeSelfRefs = false)
          logger.debug(s"Found ${refs.size} references to field '$fieldPart'")

          val result = refs.flatMap { methodName =>
            val methods = cpg.method.fullName(s"$methodName:.*").l
            if (methods.nonEmpty) {
              methods.head.typeDecl.headOption.flatMap(toFile).flatMap { file =>
                cuFunction(methodName, file)
              }
            } else {
              logger.debug(s"No method node found for field reference: $methodName")
              None
            }
          }
          logger.debug(s"Returning ${result.size} field references")
          return result.asJava
        } else {
          logger.debug(s"No field named '$fieldPart' found in class '$classPart'")
        }
      } else {
        logger.debug(s"No class declaration found for: $classPart")
      }
    }

    // (3) Otherwise treat as a class (using original symbol for class name lookup)
    logger.debug("Treating symbol as a class")
    val classDecls = cpg.typeDecl.fullNameExact(symbol).l // Use original `symbol` for class lookup
    if (classDecls.isEmpty) {
      logger.warn(s"Symbol '$symbol' (resolved: '$resolvedSymbol') not found as a method, field, or class")
      throw new IllegalArgumentException(
        s"Symbol '$symbol' (resolved: '$resolvedSymbol') not found as a method, field, or class"
      )
    }

    logger.debug(s"Found ${classDecls.size} class declarations for '$symbol'")

    // Include the original class plus all subclasses
    val subclasses = allSubclasses(symbol) // Use original `symbol`
    logger.debug(s"Found ${subclasses.size} subclasses of '$symbol'")

    val allClasses = (classDecls.map(_.fullName).toSet ++ subclasses).toList
    logger.debug(s"Processing ${allClasses.size} classes in total")

    val methodUsesAsCpgMethods = allClasses.flatMap { cn =>
      cpg.typeDecl.fullNameExact(cn).l.flatMap { td =>
        td.method.l.flatMap(m => callersOfMethodNode(m, true)) // callersOfMethodNode now returns List[Method]
      }
    }.distinct // Ensure CPG methods are distinct before converting to CodeUnits
    logger.debug(s"Found ${methodUsesAsCpgMethods.size} distinct CPG methods using class methods (transitively).")

    // fieldRefUses already returns List[String] which are FQNs of methods referencing fields.
    // This needs to be consistent or converted carefully. For now, assume it's List[String] of method FQNs.
    val fieldRefMethodFqns = classDecls.flatMap { td =>
      td.member.l.flatMap(mem => referencesToField(td.fullName, mem.name, excludeSelfRefs = true))
    }.distinct
    logger.debug(s"Found ${fieldRefMethodFqns.size} distinct method FQNs referencing fields.")

    val typeUses = allClasses.flatMap { cn =>
      val uses = referencesToClassAsType(cn)
      logger.debug(s"Found ${uses.size} type uses for class: $cn")
      uses
    }
    logger.debug(s"Total type uses: ${typeUses.size}")

    // Convert methodUsesAsCpgMethods (List[Method]) to CodeUnits
    val methodUseUnits = methodUsesAsCpgMethods.flatMap { cpgMethod =>
      val fileOpt =
        if (cpgMethod.filename.nonEmpty) toFile(cpgMethod.filename) else cpgMethod.typeDecl.headOption.flatMap(toFile)
      fileOpt.flatMap { file =>
        val isGlobalMethod = cpgMethod.astParent match {
          case parentNode: NamespaceBlock => true
          case parentNode: TypeDecl       => parentNode.name.endsWith("<global>")
          case _                          => false
        }
        val fqnForCu = resolveMethodName(chopColon(cpgMethod.fullName))
        cuFunction(fqnForCu, file)
      }
    }
    logger.debug(s"Converted ${methodUsesAsCpgMethods.size} CPG methods to ${methodUseUnits.size} CodeUnits.")

    // Convert fieldRefMethodFqns (List[String] of method FQNs) to CodeUnits
    val fieldUseUnits = fieldRefMethodFqns.flatMap { methodFqnString =>
      // methodFqnString is already a resolved FQN of a method.
      // methodsFromName should be able to find the CPG Method node(s).
      val cpgMethods = methodsFromName(methodFqnString)
      cpgMethods.headOption.flatMap { cpgMethod => // Take first if multiple, or handle ambiguity if needed
        val fileOpt =
          if (cpgMethod.filename.nonEmpty) toFile(cpgMethod.filename) else cpgMethod.typeDecl.headOption.flatMap(toFile)
        fileOpt.flatMap { file =>
          // The fqnForCu should be the methodFqnString itself, as it's already resolved.
          // Or, re-resolve from CPG method to be absolutely sure it's canonical.
          val fqnForCuFromCpg = resolveMethodName(chopColon(cpgMethod.fullName))
          cuFunction(fqnForCuFromCpg, file)
        }
      }
    }
    logger.debug(
      s"Converted ${fieldRefMethodFqns.size} field-referencing method FQNs to ${fieldUseUnits.size} CodeUnits."
    )

    val results = (methodUseUnits ++ fieldUseUnits ++ typeUses).distinct
    logger.debug(s"Final results: ${results.size} distinct usage references for '$symbol'")
    results.asJava
  }

  /** Builds either a forward or reverse call graph from a starting method up to a given depth.
    */
  private def buildCallGraph(
    startingMethod: String,
    isIncoming: Boolean,
    maxDepth: Int
  ): java.util.Map[String, java.util.List[CallSite]] = {
    val result       = new java.util.HashMap[String, java.util.List[CallSite]]()
    val startMethods = cpg.method.filter(m => chopColon(m.fullName) == startingMethod).l
    if (startMethods.isEmpty) return result

    val visited          = mutable.Set[String]()
    val startMethodNames = startMethods.map(m => resolveMethodName(chopColon(m.fullName))).toSet
    visited ++= startMethodNames

    def shouldIncludeMethod(methodName: String): Boolean = {
      !methodName.startsWith("<operator>") &&
      !methodName.startsWith("java.") &&
      !methodName.startsWith("javax.") &&
      !methodName.startsWith("sun.") &&
      !methodName.startsWith("com.sun.")
    }

    def getSourceLine(call: io.shiftleft.codepropertygraph.generated.nodes.Call): String =
      call.code.trim.replaceFirst("^this\\.", "")

    def addCallSite(methodName: String, callSite: CallSite): Unit = {
      val existing = result.getOrDefault(methodName, new java.util.ArrayList[CallSite]())
      existing.add(callSite)
      result.put(methodName, existing)
    }

    def explore(methods: List[Method], currentDepth: Int): Unit = {
      if (currentDepth > maxDepth || methods.isEmpty) return
      val nextMethods = mutable.ListBuffer[Method]()

      methods.foreach { method =>
        val methodName = resolveMethodName(chopColon(method.fullName))
        val calls      = if (isIncoming) method.callIn.l else method.call.l

        calls.foreach { call =>
          if (isIncoming) {
            // The caller is the next method
            val callerMethod = call.method
            val callerName   = resolveMethodName(chopColon(callerMethod.fullName))

            if (!visited.contains(callerName) && shouldIncludeMethod(callerName)) {
              val callerFileOpt = callerMethod.typeDecl.headOption.flatMap(toFile)
              callerFileOpt.foreach { file =>
                cuFunction(callerName, file).foreach { cu =>
                  addCallSite(methodName, CallSite(cu, getSourceLine(call)))
                  visited += callerName
                  nextMethods += callerMethod
                }
              }
            }
          } else {
            // The callee is the next method
            val calleeFullName = chopColon(call.methodFullName)
            val calleeName     = resolveMethodName(calleeFullName)

            if (!visited.contains(calleeName) && shouldIncludeMethod(calleeName)) {
              val calleePattern = s"^${Regex.quote(calleeFullName)}.*"
              val calleeMethods = cpg.method.fullName(calleePattern).l
              if (calleeMethods.nonEmpty) {
                calleeMethods.head.typeDecl.headOption.flatMap(toFile).foreach { file =>
                  cuFunction(calleeName, file).foreach { cu =>
                    addCallSite(methodName, CallSite(cu, getSourceLine(call)))
                    visited += calleeName
                    nextMethods ++= calleeMethods
                  }
                }
              }
            }
          }
        }
      }
      explore(nextMethods.toList, currentDepth + 1)
    }

    explore(startMethods, 1)
    result
  }

  override def getCallgraphTo(methodName: String, depth: Int): java.util.Map[String, java.util.List[CallSite]] = {
    val resolvedMethodName = resolveMethodName(methodName)
    buildCallGraph(resolvedMethodName, isIncoming = true, maxDepth = depth)
  }

  override def getCallgraphFrom(methodName: String, depth: Int): java.util.Map[String, java.util.List[CallSite]] = {
    val resolvedMethodName = resolveMethodName(methodName)
    buildCallGraph(resolvedMethodName, isIncoming = false, maxDepth = depth)
  }

  override def searchDefinitions(pattern: String): java.util.List[CodeUnit] = {
    import scala.jdk.CollectionConverters.*
    // If the user did not include a wildcard, match the pattern anywhere
    val preparedPattern =
      if pattern.contains(".*") then pattern else s".*${Regex.quote(pattern)}.*"
    val ciPattern = "(?i)" + preparedPattern // case-insensitive substring match

    // Classes
    val matchingClasses = cpg.typeDecl
      .name(ciPattern)
      .fullName
      .filter(isClassInProject)
      .flatMap { className =>
        val classTypeDecl = cpg.typeDecl.fullNameExact(className).headOption
        classTypeDecl.flatMap(toFile).flatMap { file =>
          cuClass(className, file)
        }
      }
      .l

    // Methods
    val matchingMethods = cpg.method
      .nameNot("<.*>")
      .name(ciPattern)
      .filter { m => // A method is relevant if its parent class is in project OR it's a global
        val isGlobalHeuristic = m.astParent match {
          case parentNode: NamespaceBlock => true                                 // Directly in namespace (file scope)
          case parentNode: TypeDecl       => parentNode.name.endsWith("<global>") // file-scope pseudo-class
          case _                          => false
        }
        m.typeDecl.headOption.exists(td => isClassInProject(td.fullName)) || isGlobalHeuristic
      }
      .flatMap { m =>
        // For global methods, m.typeDecl might not be useful for file path, use m.filename
        val fileOpt = if (m.filename.nonEmpty) toFile(m.filename) else m.typeDecl.headOption.flatMap(toFile)
        fileOpt.flatMap { file =>
          val isGlobalMethod = m.astParent match {
            case parentNode: NamespaceBlock => true
            case parentNode: TypeDecl       => parentNode.method.isEmpty && parentNode.member.isEmpty
            case _                          => false
          }

          // CPG method fullName is the source of truth. resolveMethodName cleans it up.
          // CppAnalyzer.parseFqName will handle "filename.ext:funcname" or "pkg.Cls.method" etc.
          val baseFqn = resolveMethodName(chopColon(m.fullName))
          val fqnForCu =
            if !baseFqn.contains(".") && !baseFqn.contains(":") then
              val fileName = Path.of(file.toString).getFileName.toString
              val dot      = fileName.lastIndexOf('.')
              val (stem, ext) =
                if dot > 0 then (fileName.substring(0, dot), fileName.substring(dot + 1))
                else (fileName, "")
              val pkg = if ext.nonEmpty then s"${stem}_${ext}" else stem
              s"$pkg.$baseFqn"
            else baseFqn
          cuFunction(fqnForCu, file)
        }
      }
      .l

    // Fields
    val matchingFields = cpg.member
      .nameNot("<.*>") // Exclude pseudo-members whose own name is e.g. "<init>"
      .name(ciPattern)
      .filter { f =>
        val owningTypeDecl = f.typeDecl // This is the TypeDecl node for the class owning the field
        // Ensure the owning class itself is not a pseudo-class (e.g. "<operator>")
        // and that it's considered part of the project.
        !owningTypeDecl.name.matches("<.*>") &&
        isClassInProject(owningTypeDecl.fullName)
      }
      .flatMap { f =>
        val td = f.typeDecl // Get the TypeDecl node directly
        // Extract package and class part separately
        val className = td.name
        val packageName = if (td.fullName.contains('.')) {
          td.fullName.substring(0, td.fullName.lastIndexOf('.'))
        } else {
          ""
        }

        toFile(td).flatMap { file =>
          // Create the field with explicit parts: package name, and Class.member short name
          Try(CodeUnit.field(file, packageName, s"$className.${f.name}")).toOption
        }
      }
      .l // .l is now correctly called on the Traversal[CodeUnit] from flatMap

    (matchingClasses ++ matchingMethods ++ matchingFields).asJava
  }

  override def getDefinition(fqName: String): java.util.Optional[CodeUnit] = {
    // lots of similarity to searchDefinitions, but that uses pattern-matching fullName and this
    // uses fullNameExact so trying to share code seems like more trouble than it's worth

    // Try finding as a class
    val classMatch = cpg.typeDecl
      .fullNameExact(fqName)
      .filter(td => isClassInProject(td.fullName)) // Filter TypeDecl based on its fullName
      .flatMap { td =>
        toFile(td).flatMap { file =>
          cuClass(fqName, file)
        }
      }
      .headOption

    if (classMatch.isDefined) return java.util.Optional.of(classMatch.get)

    // Try finding as a method using the language-specific methodsFromName
    val methodCandidates = methodsFromName(fqName)

    if (methodCandidates.nonEmpty) {
      val theMethod = methodCandidates.head // Assuming methodsFromName returns best/single match first
      // For global methods, typeDecl might not give the file, use method.filename
      val fileOpt =
        if (theMethod.filename.nonEmpty) toFile(theMethod.filename)
        else theMethod.typeDecl.headOption.flatMap(toFile)

      val codeUnitOpt = fileOpt.flatMap(file => cuFunction(fqName, file))

      codeUnitOpt match {
        case Some(cu) => return java.util.Optional.of(cu)
        case None     => // Fall through if no suitable CodeUnit could be created
      }
    }

    // Try finding as a field (symbol must be className.fieldName)
    val lastDot = fqName.lastIndexOf('.')
    if (lastDot > 0) {
      val className = fqName.substring(0, lastDot)
      val fieldName = fqName.substring(lastDot + 1)

      val fieldMatch = cpg.member
        .nameExact(fieldName)
        .where(_.typeDecl.fullNameExact(className))
        .filter(f => isClassInProject(f.typeDecl.fullName)) // f.typeDecl is a TypeDecl node
        .flatMap { f =>
          val td = f.typeDecl // Get the TypeDecl node directly
          toFile(td).flatMap { file =>
            // Extract package and class properly
            val packageName = if (td.fullName.contains('.')) {
              td.fullName.substring(0, td.fullName.lastIndexOf('.'))
            } else {
              ""
            }
            val shortName = td.name + "." + fieldName
            // Pass just the package name, and the Class.member short name
            Try(CodeUnit.field(file, packageName, shortName)).toOption
          }
        }
        .headOption // Expecting only one field with this name in the class

      if (fieldMatch.isDefined) return java.util.Optional.of(fieldMatch.get)
    }

    // Not found as class, unique method, or field
    java.util.Optional.empty()
  }

  /** Weighted PageRank at the class level. If seedClassWeights is non-empty, seeds are assigned according to those
    * weights. Otherwise, all classes are seeded equally.
    */
  override def getPagerank(
    seedClassWeights: java.util.Map[String, java.lang.Double],
    k: Int,
    reversed: Boolean
  ): java.util.List[(CodeUnit, java.lang.Double)] = {
    import scala.jdk.CollectionConverters.*
    val seedWeights = seedClassWeights.asScala.view.mapValues(_.doubleValue()).toMap
    // restrict to classes (FQCNs) that are in the graph
    var validSeeds = seedWeights.keys.toSeq.filter(classesForPagerank.contains)
    if (validSeeds.isEmpty) validSeeds = classesForPagerank.toSeq

    val danglingNodes = classesForPagerank.par.filter { c =>
      adjacency.get(c).forall(_.isEmpty)
    }
    val damping = 0.85
    val epsilon = 1e-4
    val maxIter = 50

    val scores      = TrieMap[String, Double](classesForPagerank.toSeq.map(_ -> 0.0)*)
    val nextScores  = TrieMap[String, Double](classesForPagerank.toSeq.map(_ -> 0.0)*)
    val totalWeight = seedWeights.values.sum
    validSeeds.foreach { c =>
      scores(c) = seedWeights.getOrElse(c, 0.0) / (if (totalWeight == 0) 1 else totalWeight)
    }

    var iteration = 0
    var diffSum   = Double.MaxValue

    while (iteration < maxIter && diffSum > epsilon) {
      // zero nextScores
      classesForPagerank.par.foreach { c => nextScores(c) = 0.0 }

      val localDiffs = classesForPagerank.par.map { node =>
        val (inMap, outMap) = if (reversed) (adjacency, reverseAdjacency) else (reverseAdjacency, adjacency)
        val inboundSum = inMap
          .get(node)
          .map { inboundMap =>
            inboundMap.foldLeft(0.0) { case (acc, (u, weight)) =>
              val outLinks  = outMap(u)
              val outWeight = outLinks.values.sum.max(1)
              acc + (scores(u) * weight / outWeight)
            }
          }
          .getOrElse(0.0)

        var newScore = damping * inboundSum
        if (validSeeds.contains(node)) {
          newScore += (1.0 - damping) *
            (seedWeights.getOrElse(node, 0.0) / (if (totalWeight == 0) 1 else totalWeight))
        }
        nextScores(node) = newScore
        math.abs(scores(node) - newScore)
      }.sum

      // handle dangling nodes
      val danglingScore = danglingNodes.par.map(scores).sum
      if (danglingScore > 0.0 && validSeeds.nonEmpty) {
        validSeeds.par.foreach { seed =>
          val weight = seedWeights.getOrElse(seed, 0.0) / (if (totalWeight == 0) 1 else totalWeight)
          nextScores(seed) += damping * danglingScore * weight
        }
        // zero out dangling
        danglingNodes.par.foreach { dn => nextScores(dn) = 0.0 }
      }

      diffSum = localDiffs
      classesForPagerank.foreach { node => scores(node) = nextScores(node) }
      iteration += 1
    }

    val sortedAll = scores.toList.sortBy { case (_, s) => -s }
    val filteredSortedAll = sortedAll.filterNot { case (cls, _) =>
      // cls here is the FQCN string from the sorted scores
      seedWeights.keys.exists(seed => partOfClass(seed, cls))
    }

    // Coalesce inner classes: if both parent and inner class are present, keep only the parent.
    // Operates on a buffer of (CodeUnit, Double) tuples.
    def coalesceInnerClasses(initial: List[(CodeUnit, Double)], limit: Int): mutable.Buffer[(CodeUnit, Double)] = {
      var results     = initial.take(limit).toBuffer
      var offset      = limit
      var changed     = true
      val initialFqns = initial.map(_._1.fqName()).toSet // For quick lookups during addition

      while (changed) {
        changed = false
        val fqnsToRemove = mutable.Set[String]() // Store FQNs of inner classes to remove
        for (i <- results.indices) {
          val pFqn = results(i)._1.fqName()
          for (j <- results.indices if j != i) {
            val cFqn = results(j)._1.fqName()
            if (partOfClass(pFqn, cFqn)) fqnsToRemove += cFqn
          }
        }
        if (fqnsToRemove.nonEmpty) {
          changed = true
          results = results.filterNot { case (cu, _) => fqnsToRemove.contains(cu.fqName()) }
        }
        // Add new candidates until the limit is reached
        while (results.size < limit && offset < initial.size) {
          val candidate @ (cu, _) = initial(offset)
          offset += 1
          // Add if not already present (by FQN) and not marked for removal
          if (!results.exists(_._1.fqName() == cu.fqName()) && !fqnsToRemove.contains(cu.fqName())) {
            // Also ensure its potential parent isn't already in initialFqns if it's an inner class
            val isInner      = cu.fqName().contains('$')
            val parentFqnOpt = if (isInner) Some(cu.fqName().substring(0, cu.fqName().lastIndexOf('$'))) else None
            if (!isInner || parentFqnOpt.forall(p => !initialFqns.contains(p))) {
              results += candidate
              changed = true
            }
          }
        }
      }
      results // Buffer[(CodeUnit, Double)]
    }

    // Map sorted FQCNs to CodeUnit tuples, filtering out those without files
    val sortedCodeUnits = filteredSortedAll.flatMap { case (fqcn, score) =>
      getFileFor(fqcn).toScala.flatMap { file => // Use flatMap here
        cuClass(fqcn, file).map((_, score))      // Use cuClass
      }
    }

    // Coalesce and convert score to Java Double, filtering out zero scores
    coalesceInnerClasses(sortedCodeUnits, k)
      .map { case (cu, d) => (cu, java.lang.Double.valueOf(d)) }
      .filter(_._2 > 0.0) // Filter out results with zero score
      .asJava
  }

  override def getDeclarationsInFile(file: ProjectFile): java.util.Set[CodeUnit] = {
    import scala.jdk.CollectionConverters.*
    val declarations = mutable.Set[CodeUnit]()

    // Add class/struct/union declarations and their members/methods
    cpg.typeDecl
      .filenameExact(file.toString())
      .filterNot { td => // Filter out synthetic/internal TypeDecls
        val name     = td.name
        val fullName = td.fullName
        name == "<global>" || // Joern's pseudo-class for file-scope items
        (name.startsWith("<") && name.endsWith(
          ">"
        ) && name != "<global>") ||       // e.g. <operator>, <lambda>, but not file's <global> TypeDecl
        fullName.contains(":") ||         // Filter fullNames like "ns:func_type()"
        fullName.contains("(") ||         // Filter fullNames like "ns:func_type(int)"
        fullName.contains("<lambda>") ||  // Lambda TypeDecls by fullName
        fullName.startsWith("<operator>") // Operator TypeDecls by fullName
      }
      .foreach { td => // td is now a presumably valid user-defined type
        // Additional check: ensure the short name of the TypeDecl is also not problematic
        // This is because `cuClass` uses td.fullName, but the name part itself should be clean.
        if (!td.name.contains(":") && !td.name.contains("(") && !(td.name.startsWith("<") && td.name.endsWith(">"))) {
          cuClass(td.fullName, file).foreach(declarations.add)
        } else {
          logger.debug(
            s"Skipping TypeDecl in getDeclarationsInFile due to problematic short name: ${td.name} (fullName: ${td.fullName})"
          )
        }

        td.method
          .filterNot(m => m.name == "<global>" || m.name.startsWith("<operator>")) // Filter unwanted methods
          .foreach { m =>
            val methodFqn = resolveMethodName(chopColon(m.fullName))
            cuFunction(methodFqn, file).foreach(declarations.add)
          }

        td.member.filterNot(_.name == "outerClass").foreach { f =>
          val fieldFqn = td.fullName + "." + f.name // Simple concatenation for field FQN
          cuField(fieldFqn, file).foreach(declarations.add)
        }
      }

    // Add global/namespace-level functions
    cpg.method
      .filenameExact(file.toString())
      .filterNot { m => // Filter out synthetic/internal methods by name
        m.name == "<global>" || m.name.startsWith("<operator>")
      }
      .filterNot { m => // Filter out methods whose parent TypeDecl would have been filtered
        Option(m.astParent).collect { case td: TypeDecl => td }.exists { parentTd =>
          val name     = parentTd.name
          val fullName = parentTd.fullName
          // These are conditions that would filter out the parent TypeDecl.
          // We don't want to add methods of such synthetic/bogus classes independently.
          // Note: parentTd.name == "<global>" is NOT filtered here because methods within
          // the file's true global scope (often represented by a TypeDecl named <global>) are legitimate.
          (name.startsWith("<") && name.endsWith(">") && name != "<global>") ||
          name.contains(":") ||
          name.contains("(") ||
          fullName.contains("<lambda>") ||
          fullName.startsWith("<operator>")
        }
      }
      .foreach { m =>
        // Deduplication is handled by the `declarations` Set.
        // This loop will add true global functions (parent is NamespaceBlock)
        // or methods within the file's <global> TypeDecl if not filtered above.
        val baseFqn = resolveMethodName(chopColon(m.fullName))
        val fqnForCu =
          // Heuristic to create a package-like prefix for global functions if FQN is simple (no ns/class separators)
          if (!baseFqn.contains(".") && !baseFqn.contains("::") && !baseFqn.contains(":")) then {
            val fileName    = Path.of(file.toString).getFileName.toString
            val dot         = fileName.lastIndexOf('.')
            val (stem, ext) = if (dot > 0) (fileName.substring(0, dot), fileName.substring(dot + 1)) else (fileName, "")
            val pkg         = if (ext.nonEmpty) s"${stem}_${ext}" else stem // e.g., "myFile_cpp"
            s"$pkg.$baseFqn" // Results in "myFile_cpp.myGlobalFunc"
          } else {
            baseFqn // Handles "namespace::func" or "file.cpp:func" directly
          }
        cuFunction(fqnForCu, file).foreach(declarations.add)
      }

    declarations.toSet.asJava
  }

  override def close(): Unit = cpg.close()

  /** Returns the immediate children of the given CodeUnit based on Joern CPG analysis.
    *
    * This implementation queries the Code Property Graph (CPG) to find parent-child relationships between code
    * elements. The relationships are determined by the semantic structure of the analyzed code.
    *
    * '''CPG-based Child Resolution:'''
    *   - '''Classes:''' Children include methods and fields from the CPG TypeDecl
    *     - Filters out constructors (`<init>`), static initializers (`<clinit>`), and operators
    *     - Excludes synthetic fields like `outerClass`
    *     - Uses `resolveMethodName` for proper method name resolution
    *   - '''Modules:''' Children include all other declarations in the same source file
    *     - Useful for languages with file-based module systems
    *   - '''Functions/Fields:''' No children (return empty list)
    */
  override def directChildren(cu: CodeUnit): java.util.List[CodeUnit] =

    if cu == null then return java.util.List.of()

    // Check cache first to avoid expensive CPG queries
    directChildrenCache.computeIfAbsent(cu, _ => computeDirectChildren(cu))

  private def computeDirectChildren(cu: CodeUnit): java.util.List[CodeUnit] =
    import scala.jdk.CollectionConverters.*

    cu match
      // For classes: return methods and fields (short-circuit on synthetic ones)
      case cls if cls.isClass =>
        val children =
          cpg.typeDecl
            .fullNameExact(cls.fqName())
            .headOption
            .toList
            .flatMap { td =>
              toFile(td).toList.flatMap { file =>
                val methods =
                  td.method
                    .filterNot(m => m.name == "<init>" || m.name == "<clinit>" || m.name.startsWith("<operator>"))
                    .flatMap { m =>
                      val fqn = resolveMethodName(chopColon(m.fullName))
                      cuFunction(fqn, file)
                    }
                    .l

                val fields =
                  td.member
                    .filterNot(_.name == "outerClass")
                    .flatMap(f => cuField(s"${td.fullName}.${f.name}", file))
                    .l

                methods ++ fields
              }
            }
        // Use LinkedHashSet for efficient deduplication while preserving order
        val uniqueChildren = collection.mutable.LinkedHashSet[CodeUnit]()
        uniqueChildren ++= children
        uniqueChildren.toList.asJava

      // For module CUs: treat every other declaration in the same file as a “child”
      case mod if mod.isModule =>
        getDeclarationsInFile(mod.source()).asScala.filterNot(_ == mod).toList.asJava

      // Functions, fields, etc. have no children
      case _ => java.util.List.of()
}
