package io.github.jbellis.brokk.analyzer

import io.joern.c2cpg.passes.*
import io.joern.c2cpg.astcreation.CGlobal
import io.joern.c2cpg.{C2Cpg, Config as CConfig}
import io.joern.x2cpg.{SourceFiles, Defines as X2CpgDefines}
import io.joern.c2cpg.parser.FileDefaults
import io.shiftleft.codepropertygraph.generated.nodes.{Method, NamespaceBlock, TypeDecl}
import io.shiftleft.codepropertygraph.generated.{Cpg, Languages}
import io.joern.x2cpg.passes.frontend.TypeNodePass
import io.joern.x2cpg.utils.Report
import io.shiftleft.semanticcpg.language.*

import java.io.IOException
import java.nio.file.Path
import java.util.Optional
import scala.collection.mutable
import scala.util.Try
import scala.util.matching.Regex // Added for mutable.ListBuffer

/** Analyzer for C and C++ source files (leveraging joern c2cpg). */
class CppAnalyzer private(sourcePath: Path, cpgInit: Cpg)
  extends JoernAnalyzer(sourcePath, cpgInit) {

  def this(sourcePath: Path, preloadedPath: Path) =
    this(sourcePath, io.joern.joerncli.CpgBasedTool.loadFromFile(preloadedPath.toString))

  def this(sourcePath: Path, excludedFiles: java.util.Set[String]) =
    this(sourcePath, CppAnalyzer.createNewCpgForSource(sourcePath, excludedFiles))

  def this(sourcePath: Path) = this(sourcePath, java.util.Collections.emptySet[String]())

  override def isCpg: Boolean = true

  //---------------------------------------------------------------------
  // Language-specific helpers
  //---------------------------------------------------------------------

  override protected def methodSignature(m: Method): String = {
    try {
      val returnType = sanitizeType(m.methodReturn.typeFullName)
      val params = m.parameter.sortBy(_.order).filterNot(_.name == "this").l
        .map { p =>
          val paramType = sanitizeType(p.typeFullName)
          val paramName = p.name
          s"$paramType $paramName"
        }
        .mkString(", ")
      val signature = s"$returnType ${m.name}($params)"
      logger.trace(s"Generated signature for ${m.fullName} (name: ${m.name}): $signature")
      signature
    } catch {
      case e: Throwable =>
        logger.error(s"Exception in methodSignature for ${m.fullName} (name: ${m.name})", e)
        // Fallback signature to avoid crashing skeleton generation
        s"ErrorType ${m.name}(ErrorParams)"
    }
  }

  /**
   * Strip c2cpg duplicate suffix and signature from full names.
   * <name>:<sig> or <name><duplicate>N:sig  (N is an integer)
   */

  /**
   * Remove trailing “:signature” and `<duplicate>N` while keeping a possible
   * `file.ext:` prefix that identifies a global function.
   *
   * Examples
   * shapes.Circle.getArea:double()               -> shapes.Circle.getArea
   * geometry.cpp:global_func:void(int)           -> geometry.cpp:global_func
   * foo${io.joern.c2cpg.astcreation.Defines.DuplicateSuffix}0:int() -> foo
   */
  override private[brokk] def resolveMethodName(methodName: String): String =
    val dupSuffix = io.joern.c2cpg.astcreation.Defines.DuplicateSuffix

    // 1. Drop trailing CPG signature like ":returnType(paramTypes)"
    //    A CPG signature starts with a colon that is NOT part of a "::" sequence,
    //    and the signature string itself usually contains parentheses.
    val withoutSig = {
      val colonIdx = methodName.lastIndexOf(':')
      // Check if colonIdx is valid, not part of "::", and followed by a signature with "()"
      if (colonIdx > 0 && methodName.charAt(colonIdx - 1) != ':' && methodName.substring(colonIdx).contains('(')) {
        // Found a colon that's likely introducing a signature part e.g. "name:ret(params)"
        methodName.substring(0, colonIdx)
      } else {
        // No such signature pattern found, assume methodName is already without it,
        // or has a different structure (e.g. "ns::func", "file.ext:func").
        methodName
      }
    }

    // 2.  split at last colon – file-scope globals keep their prefix
    //    `withoutSig` is now like "ns::func" or "file.ext:func" or "ns::cls::method"
    val lastColon = withoutSig.lastIndexOf(':')
    val (prefix, namePart0) =
      if lastColon == -1 then ("", withoutSig)
      else (withoutSig.substring(0, lastColon + 1), withoutSig.substring(lastColon + 1))

    // 3.  strip <duplicate>N from the function / method part
    val namePart =
      namePart0.indexOf(dupSuffix) match
        case -1 => namePart0
        case i => namePart0.substring(0, i)

    prefix + namePart

  override private[brokk] def sanitizeType(t: String): String = {
    if (t == null || t.isEmpty) return ""
    var currentType = t.trim

    // Iteratively remove pointers, references, and array indicators from the end
    var changed = true
    while (changed) {
      changed = false
      val originalLength = currentType.length
      if (currentType.endsWith("*") || currentType.endsWith("&")) {
        currentType = currentType.dropRight(1).trim
      } else if (currentType.endsWith("[]")) {
        currentType = currentType.dropRight(2).trim
      }
      // Check common qualifiers at the end (e.g. "char * const")
      List("const", "volatile", "restrict").foreach { q =>
        if (currentType.endsWith(s" $q")) {
          currentType = currentType.dropRight(s" $q".length).trim
        } else if (currentType == q && currentType.length > 0) { // Avoid infinite loop if type was just "const"
          currentType = X2CpgDefines.Any // Or simply "" if preferred for "just a qualifier"
        }
      }
      if (originalLength != currentType.length) changed = true
    }

    // Remove leading qualifiers and keywords
    List("static", "extern", "inline", "typedef", "_Atomic",
      "const", "volatile", "restrict", // Also check leading ones
      "struct", "enum", "union").foreach { keyword =>
      if (currentType.startsWith(s"$keyword ")) {
        currentType = currentType.substring(keyword.length).trim
      }
    }

    // Handle namespaces: take last part after "::" or "."
    val lastDot = currentType.lastIndexOf('.')
    val lastColonColon = currentType.lastIndexOf("::")
    val sepIdx = math.max(lastDot, lastColonColon)

    val finalBase = if (sepIdx != -1) {
      val startIndex = if (currentType.substring(sepIdx).startsWith("::")) sepIdx + 2 else sepIdx + 1
      if (startIndex < currentType.length) currentType.substring(startIndex) else currentType
    } else {
      currentType
    }

    if (finalBase.isEmpty && t.nonEmpty && t != X2CpgDefines.Any) X2CpgDefines.Any
    else finalBase
  }


  override protected def methodsFromName(resolvedMethodName: String): List[Method] = {
    val escapedInitial = Regex.quote(resolvedMethodName)
    var methods = cpg.method.fullName(s"$escapedInitial:.*").l
    logger.trace(s"methodsFromName for '$resolvedMethodName': initial query for '$escapedInitial:.*' found ${methods.size} methods.")

    // Attempt 1: Replace last dot with colon (common for global/static functions in CPG)
    // e.g., test input "file_cpp.func" -> CPG "file.cpp:func"
    // Also handle cases where resolvedMethodName might already be "file.cpp:func" from a previous step.
    val potentialCpgFqns = mutable.ListBuffer[String]()
    // Construct a list of FQN patterns to try.
    val searchPatterns = mutable.ListBuffer[String]()
    searchPatterns += s"${Regex.quote(resolvedMethodName)}:.*" // Original attempt: exact FQN followed by :signature

    // If resolvedMethodName contains "::", it's a C++ namespaced function.
    // Anonymous namespaces are often represented as "<global>" by c2cpg.
    // e.g., if resolvedMethodName is "ns::func", also try "ns::<global>::func:.*"
    if (resolvedMethodName.contains("::")) {
      val parts = resolvedMethodName.split("::").toList
      if (parts.length > 1) {
        val funcName = parts.last
        val namespacePart = parts.init.mkString("::")
        searchPatterns += s"${Regex.quote(namespacePart)}::<global>::${Regex.quote(funcName)}:.*"
      }
    }

    // If resolvedMethodName contains '.', it might be a file-prefixed global or from user input "pkg.Class.method".
    // CPG might store file-prefixed globals as "file.ext:func".
    if (resolvedMethodName.contains('.')) {
      val lastDotIndex = resolvedMethodName.lastIndexOf('.')
      if (lastDotIndex > 0) {
        var fileOrClassPart = resolvedMethodName.substring(0, lastDotIndex)
        val memberPart = resolvedMethodName.substring(lastDotIndex + 1)

        // Convert file_ext to file.ext for file-prefixed globals
        val filePartNormalized = fileOrClassPart.replace("_cpp", ".cpp").replace("_c", ".c")
          .replace("_hpp", ".hpp").replace("_h", ".h")
          .replace("_cc", ".cc").replace("_hh", ".hh")
        if (filePartNormalized != fileOrClassPart) { // If a replacement happened, it's likely a file prefix
          searchPatterns += s"${Regex.quote(filePartNormalized)}:${Regex.quote(memberPart)}:.*"
        }
        // Also consider the case where it might be like "ns.Class.method" (less common for C++ fullNames in CPG but user might type it)
        // This is covered by the initial `resolvedMethodName` if it was already dot-separated.
        // If `resolvedMethodName` was `ns::Class::method` the first pattern handles it.
      }
    }

    logger.debug(s"methodsFromName for '$resolvedMethodName': initial search patterns: [${searchPatterns.mkString(", ")}]")

    // Try each pattern until methods are found
    val distinctPatterns = searchPatterns.distinct
    logger.trace(s"methodsFromName for '$resolvedMethodName': distinct search patterns: [${distinctPatterns.mkString(", ")}]")
    for (pattern <- distinctPatterns) {
      if (methods.isEmpty) { // Check if methods list is still empty before trying a new pattern
        logger.trace(s"Trying CPG query with fullName regex: '$pattern'")
        val currentAttemptMethods = cpg.method.fullName(pattern).l
        if (currentAttemptMethods.nonEmpty) {
          methods = currentAttemptMethods
          logger.trace(s"Found ${methods.size} methods with query '$pattern'")
        } else {
          logger.trace(s"No methods found with query '$pattern'")
        }
      }
    }

    // If standard patterns (including <global>) fail, and it's a namespaced function (contains ::),
    // try a broader search: find by short name and then check namespace.
    // This helps if the CPG uses unexpected filename prefixes for namespaced functions.
    if (methods.isEmpty && resolvedMethodName.contains("::")) {
      val parts = resolvedMethodName.split("::").toList
      val funcShortName = parts.last
      val expectedNamespacePrefix = parts.init.mkString("::") + "::" // e.g., "at::native::"

      logger.trace(s"Broadening search for namespaced function: shortName='$funcShortName', expectedNsPrefix='$expectedNamespacePrefix'")
      val candidatesByName = cpg.method.nameExact(funcShortName).l
      methods = candidatesByName.filter { m =>
        val cpgFullName = m.fullName // e.g., "pytorch.cpp:at::native::<global>::start_index:int(int,int,int)"
        val resolvedCpgFullName = resolveMethodName(cpgFullName) // e.g., "pytorch.cpp:at::native::<global>::start_index"

        val methodSimpleFilename = Path.of(m.filename).getFileName.toString
        val nameToCheckAgainstNs = if (resolvedCpgFullName.startsWith(methodSimpleFilename + ":")) {
          resolvedCpgFullName.substring(methodSimpleFilename.length + 1)
        } else {
          resolvedCpgFullName
        }

        // Elevated to println for test visibility
        logger.debug(
          s"""BROADENING SEARCH FILTER (for input '$resolvedMethodName', candidate method '${m.fullName}'):
             |  cpgFullName='${m.fullName}', resolvedCpgFullName='$resolvedCpgFullName'
             |  methodSimpleFilename='$methodSimpleFilename', nameToCheckAgainstNs='$nameToCheckAgainstNs'
             |  expectedNsPrefix='$expectedNamespacePrefix', funcShortName='$funcShortName'""".stripMargin
        )

        // Normalize nameToCheckAgainstNs to use '::' for comparison with expectedNsPrefix (which uses '::')
        // CPG might use "ns.member" (e.g. "at.native.start_index") while user query implies "ns::member" (e.g. "at::native::start_index").
        // The expectedNsPrefix already uses '::'.
        val normalizedNameToCheck = nameToCheckAgainstNs.replace(".", "::")
        logger.debug(s"  normalizedNameToCheckForComparison='$normalizedNameToCheck'")

        val directTargetName = expectedNamespacePrefix + funcShortName // This uses '::', e.g., "at::native::start_index"
        val directMatch = normalizedNameToCheck == directTargetName
        logger.debug(s"  directMatch target='$directTargetName', normalizedNameToCompare='$normalizedNameToCheck', result=$directMatch")

        val intermediateNsMatch = if (!directMatch &&
          normalizedNameToCheck.startsWith(expectedNamespacePrefix) &&
          normalizedNameToCheck.endsWith("::" + funcShortName)) {
          // This case handles "expectedNsPrefix" + "SomeOtherNs::" + "funcShortName"
          // e.g. normalizedNameToCheck = "at::native::<global>::start_index"
          //      expectedNsPrefix      = "at::native::"
          //      funcShortName         = "start_index"
          val middlePartWithTrailingColons = normalizedNameToCheck.substring(
            expectedNamespacePrefix.length,
            normalizedNameToCheck.length - funcShortName.length
          ) // Should extract "<global>::" or "some_anon_namespace::"
          logger.debug(s"  intermediateNsMatch candidate, middlePartWithTrailingColons='$middlePartWithTrailingColons'")
          val matchResult = middlePartWithTrailingColons.nonEmpty &&
            middlePartWithTrailingColons.endsWith("::") &&
            !middlePartWithTrailingColons.substring(0, middlePartWithTrailingColons.length - 2).contains("::")
          matchResult
        } else {
          if (!directMatch) {
            logger.debug(
              s"""  intermediateNsMatch candidate for '$normalizedNameToCheck' failed initial checks:
                 |    normalizedName ('$normalizedNameToCheck') does not start with expectedNsPrefix ('$expectedNamespacePrefix').
                 |    normalizedName ('$normalizedNameToCheck') does not end with '::${funcShortName}'.
                 |""".stripMargin
            )
          }
          false
        }
        logger.debug(s"  intermediateNsMatch result=$intermediateNsMatch")

        val finalMatch = directMatch || intermediateNsMatch
        logger.debug(s"  FINAL MATCH for ${m.fullName}: $finalMatch")
        finalMatch
      }
      if (methods.nonEmpty) {
        logger.debug(s"Found ${methods.size} methods by short name '$funcShortName' and namespace check for '$expectedNamespacePrefix'. First: ${methods.head.fullName}")
      } else {
        logger.trace(s"No methods found by short name and namespace check for '$funcShortName' in '$expectedNamespacePrefix'.")
      }
    }


    // Fallback 1: Try exact match for resolvedMethodName (no signature wildcard)
    // This might match methods where the signature part was already part of resolvedMethodName
    if (methods.isEmpty) {
      logger.debug(s"Fallback 1: Trying exact match for '$resolvedMethodName' (no signature wildcard).")
      methods = cpg.method.fullNameExact(resolvedMethodName).l
      if (methods.nonEmpty) logger.debug(s"Found ${methods.size} methods with exact match.")
    }

    // Fallback 2: If still empty and resolvedMethodName fits "file_prefix.func_name" pattern (heuristic for simple globals)
    if (methods.isEmpty && resolvedMethodName.count(_ == '.') == 1 && !resolvedMethodName.contains("::")) {
      val parts = resolvedMethodName.split('.')
      val fileNameGuess = parts.head.replace("_cpp", ".cpp").replace("_c", ".c")
        .replace("_h", ".h").replace("_hpp", ".hpp")
      val funcNameGuess = parts.last
      logger.trace(s"Attempting filename-filtered search: filename containing '$fileNameGuess', func name '$funcNameGuess'")
      methods = cpg.method.nameExact(funcNameGuess).where(_.filename(s".*$fileNameGuess")).l
      logger.trace(s"Found ${methods.size} methods by filename-filtered search.")
    }

    // Attempt 4: If it's a simple name (no dots or colons), it might be a global C function.
    // This could be noisy, so it's a later fallback.
    if (methods.isEmpty && !resolvedMethodName.contains('.') && !resolvedMethodName.contains(':')) {
      logger.trace(s"Trying broad search for global function matching name '$resolvedMethodName'.")
      // Global functions in C/C++ often have their `astParentType` as `NAMESPACE_BLOCK` (for file scope)
      // or directly under a `TYPE_DECL` that represents the file (a pseudo TypeDecl with no methods/members).
      methods = cpg.method.nameExact(resolvedMethodName)
        .filter { m =>
          m.astParent match {
            case parentNode: NamespaceBlock => true
            case parentNode: TypeDecl =>
              parentNode.method.isEmpty && parentNode.member.isEmpty
            case _ => false
          }
        }.l
      logger.trace(s"Found ${methods.size} methods by broad name search for global functions.")
    }

    logger.trace(s"Final result for '$resolvedMethodName': ${methods.size} methods -> ${methods.map(_.fullName).mkString("[", ", ", "]")}")
    methods
  }

  override protected def outlineTypeDecl(td: TypeDecl, indent: Int = 0): String = {
    val sb = new StringBuilder
    logger.trace(s"Outlining TypeDecl: ${td.fullName}, Name: ${td.name}, Code: ${td.code.take(50)}")
    // Query for methods whose semantic parent (TypeDecl) is this one.
    // This handles out-of-line definitions better than relying on AST parentage alone.

    // Part 1: Methods directly associated with the TypeDecl (e.g., in-line definitions)
    val directMethods = cpg.typeDecl.fullNameExact(td.fullName).method

    // Part 2: Out-of-line definitions (e.g., defined outside class body but belong to it)
    // We do this in two stages; the first stage is a simpler regex that Joern can apply its index to
    val prefixForStartsWith = s"${td.fullName}." // e.g., "MyNamespace.MyClass."
    // Regex for initial candidate selection: "MyNamespace\.MyClass\..*"
    val candidateSelectionRegex = Regex.quote(prefixForStartsWith) + ".*"
    // second stage
    val originalRegexPatternString = s"${Regex.quote(td.fullName)}\\..*:.*"
    val originalPattern = originalRegexPatternString.r // Compile for efficient matching

    val outOfLineMethods = cpg.method
      .fullName(candidateSelectionRegex) // Efficiently get candidates starting with "TypeFullName."
      .filter(method => originalPattern.matches(method.fullName)) // Apply original, more specific regex logic

    // Combine and deduplicate
    val availableMethods = (directMethods ++ outOfLineMethods).dedup.l

    logger.trace(s"Methods available for ${td.name} (${availableMethods.size}): ${availableMethods.map(m => m.name + ":" + m.signature).mkString(", ")}")
    availableMethods.foreach(m => logger.trace(s"Method in ${td.name}: ${m.name}, FullName: ${m.fullName}"))


    val typeKeyword = td.code.toLowerCase match {
      case c if c.startsWith("class ") => "class"
      case c if c.startsWith("struct ") => "struct"
      case c if c.startsWith("union ") => "union"
      case _ => if (td.inheritsFromTypeFullName.nonEmpty || td.method.nonEmpty) "class" else "struct"
    }

    sb.append("  " * indent).append(typeKeyword).append(" ").append(sanitizeType(td.name))
    // In C++, inheritance is more complex (public/private, virtual). This is a simplification.
    val inheritedTypes = td.inheritsFromTypeFullName.map(sanitizeType).mkString(", ")
    if (inheritedTypes.nonEmpty) {
      sb.append(" : ").append(inheritedTypes)
    }
    sb.append(" {\n")

    // Fields/Members
    td.member.foreach { f =>
      sb.append("  " * (indent + 1)).append(s"${sanitizeType(f.typeFullName)} ${f.name};\n")
    }

    // Methods
    if (availableMethods.nonEmpty) {
      if (td.member.nonEmpty) sb.append("\n")
      logger.trace(s"Processing ${availableMethods.size} methods for skeleton of ${td.fullName}")
      availableMethods.foreach { m =>
        try {
          logger.trace(s"Attempting signature for method ${m.fullName} (name: ${m.name}) in type ${td.fullName}")
          val sig = methodSignature(m)
          logger.trace(s"Signature for ${m.name} in ${td.fullName}: $sig")
          sb.append("  " * (indent + 1)).append(sig).append(" {...}\n")
        } catch {
          // Catching all Throwables because LinkageError or similar could occur if CPG is malformed,
          // though Exception should cover most cases from methodSignature itself.
          case e: Throwable => logger.error(s"Error generating signature for method ${m.fullName} (name: ${m.name}) in type ${td.fullName}. Skipping method.", e)
        }
      }
    } else {
      logger.trace(s"No methods found for skeleton of ${td.fullName}")
    }

    sb.append("  " * indent).append("}")
    sb.toString()
  }

  override def getSkeleton(fqName: String): Optional[String] = {
    // 1. Basic validation
    if (fqName == null || fqName.isEmpty) {
      logger.debug(s"getSkeleton: FQN is null or empty. Returning empty.")
      return Optional.empty()
    }
    // Filter for names that are clearly CPG internal artifacts or unlikely to be user-defined types/functions
    // Allow ':' for namespaces (e.g. my::ns::MyClass, my::ns::function) or file prefixes (e.g. file.cpp:func)
    if ((fqName.startsWith("<") && fqName.endsWith(">")) || fqName.contains("<operator>")) {
      logger.debug(s"getSkeleton: FQN '$fqName' looks like a CPG internal artifact (e.g. <global>, <operator>.add). Returning empty.")
      return Optional.empty()
    }
    logger.debug(s"getSkeleton: Received FQN='$fqName'")

    // 2. Try to find as a TypeDecl (class, struct, etc.)
    // C++ FQNs use '::', but input might use '.' from user or other CodeUnit sources.
    val cpgStyleFqnForType = fqName.replace('.', ':')
    logger.debug(s"getSkeleton: Trying TypeDecl lookup. Original FQN='$fqName', CPG-style for Type='$cpgStyleFqnForType'")

    // Attempt lookup with CPG style (foo::Bar), then with original fqName if different and first failed.
    val typeDecls: List[TypeDecl] = cpg.typeDecl.fullNameExact(cpgStyleFqnForType).l ++ (
      if (fqName != cpgStyleFqnForType) cpg.typeDecl.fullNameExact(fqName).l else List.empty
      ).distinct // distinct in case fqName and cpgStyleFqnForType were effectively the same after some CPG normalization

    typeDecls.headOption match {
      case Some(td) =>
        logger.debug(s"getSkeleton: Found TypeDecl: Name='${td.name}', FullName='${td.fullName}'")
        // Additional filter: td.name itself should not be an artifact.
        // td.name for "foo::Bar" is "Bar". td.name for "file.cpp:<global>" might be "<global>"
        if (td.name.contains(':') || td.name.contains('<') || td.name.contains('>')) {
          logger.debug(s"getSkeleton: TypeDecl name '${td.name}' (from FullName '${td.fullName}') seems like an artifact. Returning empty.")
          Optional.empty()
        } else {
          logger.debug(s"getSkeleton: Outlining TypeDecl '${td.fullName}'.")
          Optional.of(outlineTypeDecl(td))
        }
      case None =>
        // 3. Not a TypeDecl, try to find as a Method (global function, namespaced function)
        logger.debug(s"getSkeleton: No TypeDecl found for '$fqName' or '$cpgStyleFqnForType'. Trying Method lookup.")
        // resolveMethodName handles various C++ FQN styles and strips signatures/suffixes.
        // If fqName is "at::native::start_index", resolvedMethodFqn should be the same.
        // If fqName is "at::native::start_index:int(int,int,int)", it will be "at::native::start_index".
        // If fqName is "file.cpp:func:void()", it will be "file.cpp:func".
        val resolvedMethodFqn = resolveMethodName(fqName)
        logger.debug(s"getSkeleton: FQN for method lookup (after resolveMethodName on '$fqName') = '$resolvedMethodFqn'")

        val methods = methodsFromName(resolvedMethodFqn) // This should handle various C++ FQN styles for methods
        if (methods.nonEmpty) {
          logger.debug(s"getSkeleton: Found ${methods.size} method(s) for '$resolvedMethodFqn'.")
          val skeletons = methods.map { m =>
            val sig = methodSignature(m)
            logger.debug(s"getSkeleton: Method signature for '${m.fullName}' (name: ${m.name}) = '$sig'")
            s"$sig {...}"
          }.mkString("\n")
          Optional.of(skeletons)
        } else {
          logger.debug(s"getSkeleton: No methods found for '$resolvedMethodFqn' (original FQN '$fqName'). Returning empty.")
          Optional.empty()
        }
    }
  }

  protected[analyzer] def parseFqName(originalFqName: String, expectedType: CodeUnitType): CodeUnit.Tuple3[String, String, String] = {
    if (originalFqName == null || originalFqName.isEmpty) {
      val resultTuple = new CodeUnit.Tuple3("", "", "")
      return resultTuple
    }
    // Normalize C++ "::" to "." for consistent parsing, but keep original for logging if needed.
    val fqName = originalFqName.replace("::", ".")

    // Heuristic for global functions/fields prefixed by filename
    // Case 1: "file_ext.membername" (e.g., "geometry_cpp.global_func") - common test input style
    // Case 2: "file.ext:membername" (e.g., "geometry.cpp:global_func") - from CPG method.fullName
    if (expectedType == CodeUnitType.FUNCTION || expectedType == CodeUnitType.FIELD) {
      val colonIndex = fqName.lastIndexOf(':') // Check on originalFqName if : is significant and not replaced
      val dotIndex = fqName.lastIndexOf('.') // This is on the dot-normalized fqName

      if (colonIndex != -1 && originalFqName.substring(0, colonIndex).matches("^[^:]+\\.(c|cpp|h|hpp|cc|hh|cxx|hxx)$")) {
        // Handles "file.ext:membername" like "geometry.cpp:global_func"
        val filePart = originalFqName.substring(0, colonIndex) // e.g., "geometry.cpp"
        val memberPart = originalFqName.substring(colonIndex + 1) // e.g., "global_func"

        val lastDotInFile = filePart.lastIndexOf('.')
        // Basic validation that filePart looks like name.ext and memberPart is simple
        if (lastDotInFile > 0 && lastDotInFile < filePart.length - 1 &&
          !memberPart.contains('.') && !memberPart.contains(':')) {

          val fileBasename = filePart.substring(0, lastDotInFile) // "geometry"
          val fileExt = filePart.substring(lastDotInFile + 1) // "cpp"
          val pseudoPackage = s"${fileBasename.replace('.', '_')}_$fileExt" // "geometry_cpp"
          logger.debug(s"Parsed CPG-style global FQN (file.ext:member) '$originalFqName' as Pkg='$pseudoPackage', Cls='', Member='$memberPart'")
          val resultTuple = new CodeUnit.Tuple3(pseudoPackage, "", memberPart)
          return resultTuple
        }
      } else if (dotIndex != -1 && fqName.substring(0, dotIndex).matches("[a-zA-Z0-9_]+_(h|cpp|c|hpp|cc|hh|hxx|cxx)")) {
        // Handles "file_ext.membername" like "geometry_cpp.global_func"
        val packagePart = fqName.substring(0, dotIndex)
        val memberPart = fqName.substring(dotIndex + 1)
        if (!memberPart.contains('.') && !memberPart.contains(':')) { // Ensure memberPart is simple
          logger.debug(s"Parsed test-style global FQN (file_ext.member) '$originalFqName' (normalized: '$fqName') as Pkg='$packagePart', Cls='', Member='$memberPart'")
          val resultTuple = new CodeUnit.Tuple3(packagePart, "", memberPart)
          return resultTuple
        }
      }
    }

    // Standard parsing based on dot-separated fqName
    // Attempt 1: CPG lookup - Is fqName a fully qualified class/struct name? (using dotNormalized fqName)
    // We use originalFqName for CPG lookups if `::` is significant there, but fqName (dot-normalized) for splitting.
    if (expectedType == CodeUnitType.CLASS && cpg.typeDecl.fullNameExact(originalFqName).nonEmpty) {
      val lastDot = fqName.lastIndexOf('.')
      val (pkg, cls) = if (lastDot == -1) ("", fqName) else (fqName.substring(0, lastDot), fqName.substring(lastDot + 1))
      val resultTuple = new CodeUnit.Tuple3(pkg, cls, "")
      return resultTuple
    }

    // Attempt 2: CPG lookup - Parse as potentialClass.potentialMember
    val lastDotMemberSep = fqName.lastIndexOf('.')
    if (lastDotMemberSep != -1) {
      val potentialClassFullName = fqName.substring(0, lastDotMemberSep) // Class part from dot-normalized
      val potentialOriginalClassFullName = originalFqName.substring(0, originalFqName.replace("::", ".").lastIndexOf('.')) // reconstruct original class part for CPG lookup

      val memberName = fqName.substring(lastDotMemberSep + 1)

      // Use original structure for CPG lookup if it involved '::'
      val cpgLookupName = if (originalFqName.contains("::")) potentialOriginalClassFullName else potentialClassFullName

      if (cpg.typeDecl.fullNameExact(cpgLookupName).nonEmpty) {
        val classDotPkgSep = potentialClassFullName.lastIndexOf('.') // Use dot-normalized for splitting package
        val (pkg, cls) = if (classDotPkgSep == -1) ("", potentialClassFullName)
        else (potentialClassFullName.substring(0, classDotPkgSep), potentialClassFullName.substring(classDotPkgSep + 1))
        val resultTuple = new CodeUnit.Tuple3(pkg, cls, memberName)
        return resultTuple
      }
    }

    // Fallback Heuristics if CPG lookups didn't resolve
    val finalResultTuple: CodeUnit.Tuple3[String, String, String] = if (lastDotMemberSep == -1) { // Single identifier
      if (expectedType == CodeUnitType.CLASS) {
        if (fqName.contains(':') || fqName.contains('<') || fqName.contains('>')) {
          logger.debug(s"parseFqName: Single segment FQN '$originalFqName' for CLASS type contains ':', '<', or '>'. Treating as invalid.")
          new CodeUnit.Tuple3("", "", "")
        } else {
          new CodeUnit.Tuple3("", fqName, "") // (package="", class=fqName, member="")
        }
      } else { // FUNCTION or FIELD (global)
        new CodeUnit.Tuple3("", "", fqName) // (package="", class="", member=fqName)
      }
    } else { // Multiple segments: "ns.member", "cls.member", "ns.cls.member" based on dot-normalized fqName
      val partBeforeLastDot = fqName.substring(0, lastDotMemberSep)
      val partAfterLastDot = fqName.substring(lastDotMemberSep + 1)

      if (expectedType == CodeUnitType.CLASS) {
        if (partAfterLastDot.contains(':') || partAfterLastDot.contains('<') || partAfterLastDot.contains('>')) {
          logger.debug(s"parseFqName: FQN '$originalFqName' for CLASS type has ':', '<', or '>' in class part '$partAfterLastDot'. Treating as invalid.")
          new CodeUnit.Tuple3("", "", "")
        } else {
          // e.g. fqName = "shapes.Circle" (from "shapes::Circle") -> (pkg="shapes", cls="Circle", member="")
          new CodeUnit.Tuple3(partBeforeLastDot, partAfterLastDot, "")
        }
      } else { // FUNCTION or FIELD
        val deeperDot = partBeforeLastDot.lastIndexOf('.')
        if (deeperDot != -1) { // e.g. "pkg.Cls.method" or "ns1.ns2.func"
          val pkg = partBeforeLastDot.substring(0, deeperDot)
          val clsOrNs = partBeforeLastDot.substring(deeperDot + 1)
          // If clsOrNs starts with uppercase, assume it's a class, otherwise part of namespace path.
          // This is a heuristic. For C++, namespaces are common.
          // "at.native.start_index" -> pkg="at.native", cls="", member="start_index"
          // "shapes.Circle.getArea" -> pkg="shapes", cls="Circle", member="getArea"
          // "pkg.Cls.method" -> pkg="pkg", cls="Cls", member="method"
          if (clsOrNs.headOption.exists(_.isUpper)) { // Heuristic: if the part after the first dot is capitalized, assume Class.method
            new CodeUnit.Tuple3(pkg, clsOrNs, partAfterLastDot) // pkg="pkg", Cls="Cls", method="method"
          } else { // Assume it's a deeper namespace ns1.ns2.func or pkg.lowerCaseClass.method
            // If clsOrNs is not capitalized, treat partBeforeLastDot as the full package path.
            new CodeUnit.Tuple3(partBeforeLastDot, "", partAfterLastDot) // pkg="ns1.ns2" or "pkg.lowerCaseClass", cls="", member="func" or "method"
          }
        } else { // e.g. "shapes.func" or "Cls.method" (partBeforeLastDot has no dots)
          // If partBeforeLastDot looks like a file_ext prefix for globals, treat as package.
          val isLikelyFilePrefix = partBeforeLastDot.matches("[a-zA-Z0-9_]+_(h|cpp|c|hpp|cc|hh|hxx|cxx)") && !partBeforeLastDot.contains(".")
          if (isLikelyFilePrefix) {
            // Case: "file_ext.member" -> Pkg="file_ext", Cls="", Member="member"
            new CodeUnit.Tuple3(partBeforeLastDot, "", partAfterLastDot)
          } else if (partBeforeLastDot.headOption.exists(_.isUpper)) {
            // Case: "UpPeRcAsE.member" (and not a file_ext prefix) -> Pkg="", Cls="UpPeRcAsE", Member="member"
            // This covers "Cls.method" even if "Cls" is not a known TypeDecl.
            new CodeUnit.Tuple3("", partBeforeLastDot, partAfterLastDot)
          }
          else {
            // Case: "lowercase.member" (and not a file_ext prefix, and not starting with uppercase)
            // Assume Pkg="lowercase", Cls="", Member="member"
            new CodeUnit.Tuple3(partBeforeLastDot, "", partAfterLastDot)
          }
        }
      }
    }
    logger.debug(s"parseFqName for '$originalFqName' (normalized '$fqName', type $expectedType) -> Pkg='${finalResultTuple._1()}', Cls='${finalResultTuple._2()}', Mem='${finalResultTuple._3()}'")
    finalResultTuple
  }

  override def cuClass(fqcn: String, file: ProjectFile): Option[CodeUnit] = {
    val parts = parseFqName(fqcn, CodeUnitType.CLASS)
    val pkg: String = parts._1()
    val cls: String = parts._2()
    val mem: String = parts._3()
    if (cls.isEmpty || mem.nonEmpty) {
      logger.warn(s"Expected a class FQCN but parsing ($fqcn) resulted in: Pkg='$pkg', Class='$cls', Member='$mem'")
      None
    } else {
      Try(CodeUnit.cls(file, pkg, cls)).toOption
    }
  }

  override def cuFunction(fqmn: String, file: ProjectFile): Option[CodeUnit] = {
    val parts = parseFqName(fqmn, CodeUnitType.FUNCTION)
    var pkg: String = parts._1()
    val cls: String = parts._2()
    val mem: String = parts._3()

    if (mem.isEmpty) {
      logger.warn(s"Function/Method FQN '$fqmn' parsed to an empty member name. Pkg='$pkg', Class='$cls'. Skipping.")
      None
    } else {
      // Synthesise a package like  “filename_ext” for true global functions
      if pkg.isEmpty && cls.isEmpty then
        val fileName = file.toString
        val base = Path.of(fileName).getFileName.toString
        val dot = base.lastIndexOf('.')
        val (stem, ext) =
          if dot >= 0 then (base.substring(0, dot), base.substring(dot + 1))
          else (base, "")
        pkg = if ext.nonEmpty then s"${stem}_${ext}" else stem

      val shortNameForCodeUnit = if cls.nonEmpty then s"$cls.$mem" else mem
      Try(CodeUnit.fn(file, pkg, shortNameForCodeUnit)).toOption
    }
  }

  override def cuField(fqfn: String, file: ProjectFile): Option[CodeUnit] = {
    val parts = parseFqName(fqfn, CodeUnitType.FIELD)
    val pkg: String = parts._1().asInstanceOf[String]
    val cls: String = parts._2().asInstanceOf[String]
    val mem: String = parts._3().asInstanceOf[String]

    if (mem.isEmpty) { // Fields must have a member name
      logger.warn(s"Field FQN '$fqfn' parsed to an empty member name. Pkg='$pkg', Class='$cls'. Skipping.")
      None
    } else if (cls.isEmpty && !pkg.isEmpty && pkg.contains("_")) {
      // This could be a global variable "file_ext.varname"
      // CodeUnit.field expects (file, package, "Class.Field") or (file, package, "Field") if classless?
      // Current CodeUnit.field(pkg, shortName) implies shortName is Class.Field
      // Let's assume for now global fields are not directly represented by cuField if cls is empty.
      // Or if they are, shortName should just be the field name, and pkg is the file_ext.
      // The test data implies fields are always Cls.Field.
      logger.warn(s"Field FQN '$fqfn' parsed to an empty class name. Pkg='$pkg', Member='$mem'. This might be a global variable not fittable into 'Class.Field' CodeUnit structure. Skipping.")
      None
    } else if (cls.isEmpty) { // Class must also be non-empty for typical "Class.Field"
      logger.warn(s"Field FQN '$fqfn' parsed to an empty class name. Pkg='$pkg', Member='$mem'. Skipping.")
      None
    }
    else {
      val shortNameForCodeUnit = s"$cls.$mem"
      Try(CodeUnit.field(file, pkg, shortNameForCodeUnit)).toOption
    }
  }

  override def isClassInProject(className: String): Boolean = {
    cpg.typeDecl.fullNameExact(className).exists { td =>
      // Check if the TypeDecl node itself belongs to a file within the project source path
      // Also consider TypeDecls that might not have a file (e.g. builtins) if that's relevant, though usually not "in project".
      // For C++, header files can be outside sourcePath but used by project.
      // A simple check: if it's not explicitly external and has a source file we recognize.
      // Or, more inclusively: if it's defined in ANY file processed by c2cpg for this project.
      // The most robust is to check if the file location is under `absolutePath`.
      // td.isExternal is a direct Boolean in this CPG version.
      !td.isExternal || toFile(td.filename).exists(_.absPath().startsWith(absolutePath))
    }
  }

  override def getFunctionLocation(
                                    fqMethodName: String,
                                    paramNames: java.util.List[String]
                                  ): IAnalyzer.FunctionLocation = {

    val resolvedName = resolveMethodName(fqMethodName)
    // C++ methods often have overloads, so signature matters.
    // However, the current `methodsFromName` only uses resolvedMethodName.
    // We need a way to incorporate paramNames or types if available/needed for disambiguation.
    // For now, assume fqMethodName is sufficient or that methodsFromName handles it.

    val candidates = methodsFromName(resolvedName)

    if (candidates.isEmpty) {
      throw new SymbolNotFoundException(s"No methods found for FQCN: $fqMethodName (resolved: $resolvedName)")
    }

    // Simple case: if only one candidate, use it.
    // More complex logic would be needed here for overloads if paramNames were used for signature matching.
    val chosenMethod = candidates.head // Or some disambiguation logic

    val fileOpt = toFile(chosenMethod.filename)
    if (fileOpt.isEmpty || chosenMethod.lineNumber.isEmpty || chosenMethod.lineNumberEnd.isEmpty) {
      throw new SymbolNotFoundException(s"File or line info missing for method: ${chosenMethod.fullName}")
    }

    val file = fileOpt.get
    val startLine = chosenMethod.lineNumber.get
    val endLine = chosenMethod.lineNumberEnd.get

    val maybeCode = Try {
      val lines = scala.io.Source.fromFile(file.absPath().toFile).getLines().slice(startLine - 1, endLine).mkString("\n")
      Some(lines)
    }.getOrElse(None)

    if (maybeCode.isEmpty) {
      throw new SymbolNotFoundException(s"Could not read source code for method: ${chosenMethod.fullName}")
    }
    IAnalyzer.FunctionLocation(file, startLine, endLine, maybeCode.get)
  }
}

object CppAnalyzer extends GraphPassApplier[CConfig] {

  import scala.jdk.CollectionConverters.*

  def createNewCpgForSource(sourcePath: Path, excludedFiles: java.util.Set[String]): Cpg = {
    val absPath = sourcePath.toAbsolutePath.normalize()
    require(absPath.toFile.isDirectory, s"Source path must be a directory: $absPath")

    val config = CConfig()
      .withInputPath(absPath.toString)
      .withDefaultIgnoredFilesRegex(Nil)
      .withIgnoredFiles(excludedFiles.asScala.toSeq)
      .withIncludeComments(false)
    // Other CConfig options can be set here by chaining .withXyz methods, e.g.
    // .withIncludePathsAutoDiscovery(true)
    // .withDefines(Set("MY_DEFINE"))

    val newCpg = createAst(config).getOrElse {
      throw new IOException(s"Failed to create C/C++ CPG for $absPath")
    }
    applyPasses(newCpg).getOrElse {
      throw new IOException("Failed to apply post-processing on C/C++ CPG")
    }
  }

  override def createAst(config: CConfig): Try[Cpg] = withNewOrExistingCpg(config) { (cpg) =>
    createOrUpdateMetaData(cpg, Languages.NEWC, config.inputPath)
    val report = new Report()
    val global = new CGlobal()
    val preprocessedFiles = allPreprocessedFiles(config)
    new AstCreationPass(cpg, preprocessedFiles, gatherFileExtensions(config), config, global, report)
      .createAndApply()
    new AstCreationPass(cpg, preprocessedFiles, Set(FileDefaults.CHeaderFileExtension), config, global, report)
      .createAndApply()
    TypeNodePass.withRegisteredTypes(global.typesSeen(), cpg).createAndApply()
    new TypeDeclNodePass(cpg, config).createAndApply()
    new FunctionDeclNodePass(cpg, global.unhandledMethodDeclarations(), config).createAndApply()
    new FullNameUniquenessPass(cpg).createAndApply()
    report.print()
  }

  private def gatherFileExtensions(config: CConfig): Set[String] = {
    FileDefaults.SourceFileExtensions ++
      FileDefaults.CppHeaderFileExtensions ++
      Option.when(config.withPreprocessedFiles)(FileDefaults.PreprocessedExt).toList
  }

  private def allPreprocessedFiles(config: CConfig): List[String] = {
    if (config.withPreprocessedFiles) {
      SourceFiles
        .determine(
          config.inputPath,
          Set(FileDefaults.PreprocessedExt),
          ignoredDefaultRegex = Option(C2Cpg.DefaultIgnoredFolders),
          ignoredFilesRegex = Option(config.ignoredFilesRegex),
          ignoredFilesPath = Option(config.ignoredFiles)
        )
    } else {
      List.empty
    }
  }

}
