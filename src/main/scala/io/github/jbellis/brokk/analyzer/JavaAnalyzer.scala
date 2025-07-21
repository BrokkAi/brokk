package io.github.jbellis.brokk.analyzer

import io.github.jbellis.brokk.analyzer.builder.CpgBuilder
import io.github.jbellis.brokk.analyzer.builder.languages.javaSrcBuilder
import io.github.jbellis.brokk.analyzer.implicits.X2CpgConfigExt.*
import io.joern.javasrc2cpg.Config
import io.joern.joerncli.CpgBasedTool
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.{Expression, Method, TypeDecl}
import io.shiftleft.semanticcpg.language.*
import org.slf4j.LoggerFactory

import java.nio.file.{Files, Path}
import java.util
import java.util.Optional
import scala.annotation.tailrec
import scala.io.Source
import scala.util.boundary.break
import scala.util.matching.Regex
import scala.util.{Try, Using, boundary}

/** A concrete analyzer for Java source code, extending AbstractAnalyzer with Java-specific logic for building the CPG,
  * method signatures, etc.
  */
class JavaAnalyzer private (sourcePath: Path, cpgInit: Cpg) extends JoernAnalyzer[Config](sourcePath, cpgInit) {

  def this(sourcePath: Path, preloadedPath: Path) =
    this(sourcePath, CpgBasedTool.loadFromFile(preloadedPath.toString))

  def this(sourcePath: Path, excludedFiles: java.util.Set[String], cpgPath: Path) =
    this(sourcePath, JavaAnalyzer.createNewCpgForSource(sourcePath, excludedFiles, cpgPath))

  override def isCpg: Boolean = true

  override val fullNameSeparators: Seq[String] = Seq(".", "$")

  override def defaultConfig: Config = JavaAnalyzer.defaultConfig

  override implicit val defaultBuilder: CpgBuilder[Config] = javaSrcBuilder

  /** Java-specific method signature builder.
    */
  override protected def methodSignature(m: Method): String = {
    val knownModifiers = Map(
      "public"       -> "public",
      "private"      -> "private",
      "protected"    -> "protected",
      "static"       -> "static",
      "final"        -> "final",
      "abstract"     -> "abstract",
      "native"       -> "native",
      "synchronized" -> "synchronized"
    )

    val modifiers = m.modifier
      .map { modNode =>
        knownModifiers.getOrElse(modNode.modifierType.toLowerCase, "")
      }
      .filter(_.nonEmpty)

    val modString  = if (modifiers.nonEmpty) modifiers.mkString(" ") + " " else ""
    val returnType = sanitizeType(m.methodReturn.typeFullName)
    val paramList = m.parameter
      .sortBy(_.order)
      .filterNot(_.name == "this")
      .l
      .map(p => s"${sanitizeType(p.typeFullName)} ${p.name}")
      .mkString(", ")

    s"$modString$returnType ${m.name}($paramList)"
  }

  /** Java-specific logic for removing lambda suffixes, nested class numeric suffixes, etc.
    */
  override private[brokk] def resolveMethodName(methodName: String): String = {

    val lambdaPattern    = "(?:lambda\\$)+([^$]+)(?:\\$\\d+)+".r
    val anonClassPattern = ".*\\$\\d+$".r

    @tailrec
    def resolve(remaining: List[String], acc: List[String]): List[String] = {
      remaining match {
        // 1. Base Case: No more tokens to process. Return the accumulated result in the correct order.
        case Nil => acc.reverse

        // 2. Recursive Case: The current token is a lambda, e.g., "lambda$parent$1".
        //    Extract the parent method name (recursively if lambdas are nested) and stop and return
        //    what we have accumulated.
        case lambdaPattern(parentMethod) :: _ =>
          (parentMethod :: acc).reverse

        // 3. Recursive Case: The current token is an anonymous class, e.g., "MyClass$1". We stop, add the base class
        //    and method name, and return what we  have accumulated.
        case anonClass :: methodName :: _ if anonClassPattern.matches(anonClass) =>
          (methodName.takeWhile(_ != ':') :: anonClass :: acc).reverse

        // 4. Recursive Case: A regular token. Add it to the accumulator and continue.
        case head :: tail =>
          resolve(tail, head :: acc)
      }
    }

    val tokens = methodName.split('.').toList
    resolve(tokens, Nil).mkString(".")
  }

  override private[brokk] def parentMethodName(expression: Expression | Method): String =
    resolveMethodName(super.parentMethodName(expression))

  override private[brokk] def sanitizeType(t: String): String = {
    def processType(input: String): String = {
      val isArray   = input.endsWith("[]")
      val base      = if (isArray) input.dropRight(2) else input
      val shortName = base.split("\\.").lastOption.getOrElse(base)
      if (isArray) s"$shortName[]" else shortName
    }

    if (t.contains("<")) {
      val mainType      = t.substring(0, t.indexOf("<"))
      val genericPart   = t.substring(t.indexOf("<") + 1, t.lastIndexOf(">"))
      val processedMain = processType(mainType)
      val processedParams = genericPart
        .split(",")
        .map { param =>
          val trimmed = param.trim
          if (trimmed.contains("<")) sanitizeType(trimmed)
          else processType(trimmed)
        }
        .mkString(", ")
      s"$processedMain<$processedParams>"
    } else processType(t)
  }

  override protected def methodsFromName(resolvedMethodName: String): List[Method] = {
    val escaped = Regex.quote(resolvedMethodName)
    cpg.method.fullName(escaped + ":.*").l
  }

  /** Recursively builds a structural "skeleton" for a given TypeDecl.
    */
  override protected def outlineTypeDecl(td: TypeDecl, indent: Int = 0): String = {
    val sb = new StringBuilder

    val className = sanitizeType(td.name)
    sb.append("  " * indent).append("class ").append(className).append(" {\n")

    // Methods: skip any whose name starts with "<lambda>"
    td.method.filterNot(_.name.startsWith("<lambda>")).foreach { m =>
      sb.append("  " * (indent + 1))
        .append(methodSignature(m))
        .append(" {...}\n")
    }

    // Fields: skip any whose name is exactly "outerClass"
    td.member.filterNot(_.name == "outerClass").foreach { f =>
      sb.append("  " * (indent + 1))
        .append(s"${sanitizeType(f.typeFullName)} ${f.name};\n")
    }

    // Nested classes: skip any named "<lambda>N" or purely numeric suffix
    td.astChildren.isTypeDecl
      .filterNot { nested =>
        nested.name.startsWith("<lambda>") ||
        nested.name.split("\\$").exists(_.forall(_.isDigit))
      }
      .foreach { nested =>
        sb.append(outlineTypeDecl(nested, indent + 1)).append("\n")
      }

    sb.append("  " * indent).append("}")
    sb.toString
  }

  override def getFunctionLocation(
    fqMethodName: String,
    paramNames: java.util.List[String]
  ): IAnalyzer.FunctionLocation = {
    import scala.jdk.CollectionConverters.*

    var methodPattern = Regex.quote(fqMethodName)
    var allCandidates = cpg.method.fullName(s"$methodPattern:.*").l

    if (allCandidates.size == 1) {
      return toFunctionLocation(allCandidates.head)
    }

    if (allCandidates.isEmpty) {
      // Try to resolve the method name without the package (but with the class)
      val shortName = fqMethodName.split('.').takeRight(2).mkString(".")
      methodPattern = Regex.quote(shortName)
      allCandidates = cpg.method.fullName(s".*$methodPattern:.*").l
    }

    val paramList = paramNames.asScala.toList
    val matched = allCandidates.filter { m =>
      val actualNames = m.parameter
        .filterNot(_.name == "this")
        .sortBy(_.order)
        .map(_.name)
        .l
      actualNames == paramList
    }

    if (matched.isEmpty) {
      throw new SymbolNotFoundException(
        s"No methods found in $fqMethodName matching provided parameter names $paramList"
      )
    }

    if (matched.size > 1) {
      throw new SymbolAmbiguousException(s"Multiple methods match $fqMethodName with parameter names $paramList")
    }

    toFunctionLocation(matched.head)
  }

  /** Turns a method node into a FunctionLocation. Throws SymbolNotFoundError if file/line info or code extraction
    * fails.
    */
  private def toFunctionLocation(chosen: Method): IAnalyzer.FunctionLocation = {
    // chosen.typeDecl is a Traversal. Get the TypeDecl node, then its filename.
    val fileOpt = chosen.typeDecl.headOption.flatMap(td => toFile(td.filename))
    if (fileOpt.isEmpty || chosen.lineNumber.isEmpty || chosen.lineNumberEnd.isEmpty) {
      throw new SymbolNotFoundException("File or line info missing for chosen method.")
    }

    val file  = fileOpt.get
    val start = chosen.lineNumber.get
    val end   = chosen.lineNumberEnd.get

    Try(
      Using.resource(Source.fromFile(file.absPath().toFile))(
        _.getLines()
          .slice(start - 1, end) // Use slice for safer indexing
          .mkString("\n")
      )
    ).toOption match {
      case Some(code) => IAnalyzer.FunctionLocation(file, start, end, code)
      case None       => throw new SymbolNotFoundException("Could not read code for chosen method.")
    }
  }

  /** Parses a Java fully qualified name into its components using CPG lookups first, then falling back to heuristics.
    * For classes: packageName = everything up to last dot, className = last segment, memberName = empty For members:
    * packageName = everything up to class, className = class part, memberName = member
    *
    * @param expectedType
    *   The type of CodeUnit expected by the caller (CLASS, FUNCTION, or FIELD).
    */
  protected[analyzer] def parseFqName(
    fqName: String,
    expectedType: CodeUnitType
  ): CodeUnit.Tuple3[String, String, String] = boundary {
    if (fqName == null || fqName.isEmpty) {
      break(new CodeUnit.Tuple3("", "", ""))
    }

    // Attempt 1: CPG lookup - Is fqName a fully qualified class name?
    // This path is only valid if the caller expects a class.
    if (expectedType == CodeUnitType.CLASS && cpg.typeDecl.fullNameExact(fqName).nonEmpty) {
      val lastDot = fqName.lastIndexOf('.')
      val (pkg, cls) =
        if (lastDot == -1) ("", fqName) else (fqName.substring(0, lastDot), fqName.substring(lastDot + 1))
      break(new CodeUnit.Tuple3(pkg, cls, ""))
    }

    // Attempt 2: CPG lookup - Parse as potentialClass.potentialMember
    val lastDotMemberSep = fqName.lastIndexOf('.')
    if (lastDotMemberSep != -1) {
      val potentialClassFullName = fqName.substring(0, lastDotMemberSep)
      val potentialMemberName    = fqName.substring(lastDotMemberSep + 1)

      cpg.typeDecl.fullNameExact(potentialClassFullName).headOption.foreach { td =>
        val classDotPkgSep = potentialClassFullName.lastIndexOf('.')
        val (pkg, cls) =
          if (classDotPkgSep == -1) ("", potentialClassFullName)
          else
            (potentialClassFullName.substring(0, classDotPkgSep), potentialClassFullName.substring(classDotPkgSep + 1))

        if (
          expectedType == CodeUnitType.FUNCTION && (potentialMemberName == "<init>" || td.method
            .nameExact(potentialMemberName)
            .nonEmpty)
        ) {
          break(new CodeUnit.Tuple3(pkg, cls, potentialMemberName))
        }
        if (expectedType == CodeUnitType.FIELD && td.member.nameExact(potentialMemberName).nonEmpty) {
          break(new CodeUnit.Tuple3(pkg, cls, potentialMemberName))
        }
      }
    }

    // Fallback heuristic using expectedType, if CPG lookups did not resolve the FQN according to expectedType.
    if (lastDotMemberSep == -1) { // No dots in fqName (e.g., "MyClass" or "myMethod")
      if (expectedType == CodeUnitType.CLASS) {
        // Caller expects a class. Treat fqName as a simple class name in default package.
        break(new CodeUnit.Tuple3("", fqName, "")) // (pkg="", cls=fqName, member="")
      } else {                                     // Caller expects FUNCTION or FIELD.
        // Treat fqName as a simple member name with no class or package.
        break(new CodeUnit.Tuple3("", "", fqName)) // (pkg="", cls="", member=fqName)
      }
    } else { // Dots are present in fqName
      val partAfterLastDot  = fqName.substring(lastDotMemberSep + 1)
      val partBeforeLastDot = fqName.substring(0, lastDotMemberSep)

      if (expectedType == CodeUnitType.CLASS) {
        // Caller expects a class. Treat partAfterLastDot as class name, partBeforeLastDot as package.
        // e.g., fqName = "com.example.MyClass" -> (pkg="com.example", cls="MyClass", member="")
        break(new CodeUnit.Tuple3(partBeforeLastDot, partAfterLastDot, ""))
      } else { // Caller expects FUNCTION or FIELD
        // Caller expects a member. Treat partAfterLastDot as member name.
        // partBeforeLastDot is the FQCN of the class containing the member.
        // e.g., fqName = "com.example.MyClass.myMethod"
        // memberName = "myMethod"
        // fqClassName = "com.example.MyClass"
        val fqClassName = partBeforeLastDot
        val memberName  = partAfterLastDot

        val classLastDot = fqClassName.lastIndexOf('.')
        val (pkg, cls) = if (classLastDot == -1) {
          // No dot in fqClassName, so it's a simple class name in default package.
          // e.g. fqClassName = "MyClass" -> (pkg="", cls="MyClass")
          ("", fqClassName)
        } else {
          // Dot found in fqClassName, split into package and simple class name.
          // e.g. fqClassName = "com.example.MyClass" -> (pkg="com.example", cls="MyClass")
          (fqClassName.substring(0, classLastDot), fqClassName.substring(classLastDot + 1))
        }
        break(new CodeUnit.Tuple3(pkg, cls, memberName))
      }
    }
  }

  /** Builds a structural skeleton for a given class by name
    */
  override def getSkeleton(fqName: String): Optional[String] = {
    val decls = cpg.typeDecl.fullNameExact(fqName).l
    if (decls.isEmpty) Optional.empty() else Optional.of(outlineTypeDecl(decls.head))
  }

  // --- Implementations of Abstract CodeUnit Creation ---
  override def cuClass(fqcn: String, file: ProjectFile): Option[CodeUnit] = {
    val parts = parseFqName(fqcn, CodeUnitType.CLASS)
    if (!parts._3().isEmpty) { // Member part should be empty for a class
      throw new IllegalArgumentException(
        s"Expected a class FQCN but parsing indicated a member: $fqcn. Parsed as: Pkg='${parts._1()}', Class='${parts
            ._2()}', Member='${parts._3()}'"
      )
    }
    if (parts._2().isEmpty && !fqcn.isEmpty) { // Class name part should not be empty if fqcn was not empty
      throw new IllegalArgumentException(s"Parsed class name is empty for FQCN: $fqcn. Parsed as: Pkg='${parts
          ._1()}', Class='${parts._2()}', Member='${parts._3()}'")
    }
    Try(CodeUnit.cls(file, parts._1(), parts._2())).toOption
  }

  override def cuFunction(fqmn: String, file: ProjectFile): Option[CodeUnit] = {
    val parts = parseFqName(fqmn, CodeUnitType.FUNCTION)
    if (parts._3().isEmpty) { // Member part (method name) must not be empty
      throw new IllegalArgumentException(
        s"Expected a method FQCN but parsing indicated it was not a member: $fqmn. Parsed as: Pkg='${parts
            ._1()}', Class='${parts._2()}', Member='${parts._3()}'"
      )
    }
    if (parts._2().isEmpty) { // Class name part must not be empty for a method
      throw new IllegalArgumentException(s"Parsed class name is empty for method FQCN: $fqmn. Parsed as: Pkg='${parts
          ._1()}', Class='${parts._2()}', Member='${parts._3()}'")
    }
    val pkg        = parts._1()
    val className  = parts._2()
    val methodName = parts._3()
    Try(CodeUnit.fn(file, pkg, s"$className.$methodName")).toOption
  }

  override def cuField(fqfn: String, file: ProjectFile): Option[CodeUnit] = {
    val parts = parseFqName(fqfn, CodeUnitType.FIELD)
    if (parts._3().isEmpty) { // Member part (field name) must not be empty
      throw new IllegalArgumentException(
        s"Expected a field FQCN but parsing indicated it was not a member: $fqfn. Parsed as: Pkg='${parts
            ._1()}', Class='${parts._2()}', Member='${parts._3()}'"
      )
    }
    if (parts._2().isEmpty) { // Class name part must not be empty for a field
      throw new IllegalArgumentException(s"Parsed class name is empty for field FQCN: $fqfn. Parsed as: Pkg='${parts
          ._1()}', Class='${parts._2()}', Member='${parts._3()}'")
    }
    val pkg       = parts._1()
    val className = parts._2()
    val fieldName = parts._3()
    Try(CodeUnit.field(file, pkg, s"$className.$fieldName")).toOption
  }
  // -----------------------------------------------------
}

object JavaAnalyzer {

  private val logger = LoggerFactory.getLogger(getClass)
  private def defaultConfig = Config()
    .withDefaultIgnoredFilesRegex(Nil)
    .withDisableFileContent(false) // lets us use `.offset` and `.offsetEnd` on AST nodes

  import scala.jdk.CollectionConverters.*

  private def createNewCpgForSource(sourcePath: Path, excludedFiles: java.util.Set[String], cpgPath: Path): Cpg = {
    val absPath = sourcePath.toAbsolutePath.toRealPath()
    require(absPath.toFile.isDirectory, s"Source path must be a directory: $absPath")

    if Files.exists(cpgPath) then
      logger.info(s"Deleting existing CPG at '$cpgPath' to ensure a fresh build.")
      Files.delete(cpgPath)
    logger.info(s"Creating Java CPG at '$cpgPath'")

    // Build the CPG
    defaultConfig
      .withInputPath(absPath.toString)
      .withOutputPath(cpgPath.toString)
      .withIgnoredFiles(excludedFiles.asScala.toSeq)
      .buildAndThrow()
      .open
  }

}
