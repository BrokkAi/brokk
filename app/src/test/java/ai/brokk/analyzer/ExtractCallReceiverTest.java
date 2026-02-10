package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.project.IProject;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive tests for extractCallReceiver method across all analyzer implementations. Tests language-specific method
 * reference detection and class name extraction. Tests are written against the current heuristic logic and verify known
 * edge cases.
 */
public class ExtractCallReceiverTest {

    private static class MockProject implements IProject {
        public Path getRoot() {
            return Path.of("/test");
        }

        public String getName() {
            return "test";
        }

        public Set<String> getExcludedDirectories() {
            return Set.of();
        }
    }

    private final IProject mockProject = new MockProject();

    @Test
    @DisplayName("Java analyzer - extractCallReceiver with various method references")
    void testJavaAnalyzerExtractClassName() {
        var analyzer = Languages.JAVA.createAnalyzer(mockProject);

        // Valid Java method references
        assertEquals(Optional.of("MyClass"), analyzer.extractCallReceiver("MyClass.myMethod"));
        assertEquals(Optional.of("com.example.MyClass"), analyzer.extractCallReceiver("com.example.MyClass.myMethod"));
        assertEquals(Optional.of("java.lang.String"), analyzer.extractCallReceiver("java.lang.String.valueOf"));
        assertEquals(Optional.of("List"), analyzer.extractCallReceiver("List.get"));

        // Valid with camelCase methods
        assertEquals(Optional.of("HttpClient"), analyzer.extractCallReceiver("HttpClient.sendRequest"));
        assertEquals(Optional.of("StringBuilder"), analyzer.extractCallReceiver("StringBuilder.append"));

        // New: Method calls with parameters
        assertEquals(Optional.of("SwingUtil"), analyzer.extractCallReceiver("SwingUtil.runOnEdt(...)"));
        assertEquals(Optional.of("SwingUtilities"), analyzer.extractCallReceiver("SwingUtilities.invokeLater(task)"));
        assertEquals(Optional.of("EventQueue"), analyzer.extractCallReceiver("EventQueue.invokeAndWait(runnable)"));
        assertEquals(
                Optional.of("JOptionPane"),
                analyzer.extractCallReceiver("JOptionPane.showMessageDialog(parent, message)"));

        // Test case for GitRepo.sanitizeBranchName(...)
        assertEquals(Optional.of("GitRepo"), analyzer.extractCallReceiver("GitRepo.sanitizeBranchName(...)"));

        // Invalid cases - should return empty
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("MyClass"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("myMethod"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("MyClass."));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver(".myMethod"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver(""));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("   "));

        // Edge cases consistent with heuristic
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("myclass.myMethod")); // lowercase class
        assertEquals(
                Optional.empty(),
                analyzer.extractCallReceiver("MyClass.MyMethod")); // uppercase method (not typical Java)

        // Inner-class style using $ is not recognized by this heuristic (tests ensure no exception)
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("com.example.Outer$Inner.method"));

        // Unicode names - heuristic limited to ASCII-style checks
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("ÜnicodeClass.method"));
    }

    @Test
    @DisplayName("C++ analyzer - extractCallReceiver with :: separator and templates")
    void testCppAnalyzerExtractClassName() {
        var analyzer = new CppAnalyzer(mockProject);

        // Valid C++ method references
        assertEquals(Optional.of("MyClass"), analyzer.extractCallReceiver("MyClass::myMethod"));
        assertEquals(Optional.of("namespace::MyClass"), analyzer.extractCallReceiver("namespace::MyClass::myMethod"));
        assertEquals(Optional.of("std::string"), analyzer.extractCallReceiver("std::string::c_str"));

        // Templates are stripped/unsupported by regex heuristic -> should return empty
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("std::vector<int>::size"));

        // Nested namespaces
        assertEquals(Optional.of("ns1::ns2::Class"), analyzer.extractCallReceiver("ns1::ns2::Class::method"));

        // Invalid cases - should return empty
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("MyClass"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("myMethod"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("MyClass::"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("::myMethod"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver(""));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("   "));

        // C++ doesn't use dots for method references
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("MyClass.myMethod"));

        // Dollar-sign odd identifiers are not supported by the simple heuristic
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("ns$::Class::method"));
    }

    @Test
    @DisplayName("Rust analyzer - extractCallReceiver with :: separator")
    void testRustAnalyzerExtractClassName() {
        var analyzer = new RustAnalyzer(mockProject);

        // Valid Rust method references
        assertEquals(Optional.of("MyStruct"), analyzer.extractCallReceiver("MyStruct::new"));
        assertEquals(
                Optional.of("std::collections::HashMap"),
                analyzer.extractCallReceiver("std::collections::HashMap::insert"));
        assertEquals(Optional.of("Vec"), analyzer.extractCallReceiver("Vec::push"));

        // Snake case methods (typical in Rust)
        assertEquals(Optional.of("HttpClient"), analyzer.extractCallReceiver("HttpClient::send_request"));
        assertEquals(Optional.of("std::fs::File"), analyzer.extractCallReceiver("std::fs::File::create_new"));

        // Module paths
        assertEquals(
                Optional.of("crate::utils::Helper"),
                analyzer.extractCallReceiver("crate::utils::Helper::do_something"));

        // Invalid cases - should return empty
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("MyStruct"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("new"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("MyStruct::"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("::new"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver(""));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("   "));

        // Rust doesn't use dots for method references
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("MyStruct.new"));
    }

    @Test
    @DisplayName("Python analyzer - extractCallReceiver with . separator")
    void testPythonAnalyzerExtractClassName() {
        var analyzer = new PythonAnalyzer(mockProject);

        // Valid Python method references
        assertEquals(Optional.of("MyClass"), analyzer.extractCallReceiver("MyClass.my_method"));
        assertEquals(Optional.of("requests.Session"), analyzer.extractCallReceiver("requests.Session.get"));
        assertEquals(Optional.of("os.path"), analyzer.extractCallReceiver("os.path.join"));

        // Mixed case
        assertEquals(Optional.of("HttpClient"), analyzer.extractCallReceiver("HttpClient.send_request"));
        assertEquals(Optional.of("json"), analyzer.extractCallReceiver("json.loads"));

        // Module paths
        assertEquals(Optional.of("package.module.Class"), analyzer.extractCallReceiver("package.module.Class.method"));

        // Invalid cases - should return empty
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("MyClass"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("my_method"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("MyClass."));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver(".my_method"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver(""));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("   "));
    }

    @Test
    @DisplayName("Default analyzer - extractCallReceiver returns empty by default")
    void testDefaultAnalyzerExtractClassName() {
        // Use DisabledAnalyzer which uses default implementation
        var analyzer = new DisabledAnalyzer(mockProject);

        // Default is now Optional.empty() and should not throw
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("MyClass.myMethod"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("com.example.Service.process"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("MyClass"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver(""));
    }

    @Test
    @DisplayName("JavaScript/TypeScript analyzers - extract class names correctly")
    void testJsTsExtractClassName() {
        var js = new JavascriptAnalyzer(mockProject);
        var ts = new TypescriptAnalyzer(mockProject);

        // These should return empty due to lowercase anchors (heuristic for built-ins)
        assertEquals(Optional.empty(), js.extractCallReceiver("console.log"));
        assertEquals(Optional.empty(), ts.extractCallReceiver("document.querySelector"));

        // Built-in constructors with PascalCase should be extracted (they are legitimate classes)
        assertEquals(Optional.of("Array"), ts.extractCallReceiver("Array.isArray"));

        // These should extract PascalCase class names
        assertEquals(Optional.of("MyClass"), js.extractCallReceiver("MyClass.myMethod"));
        assertEquals(Optional.of("Component"), ts.extractCallReceiver("React.Component.render"));
    }

    @Test
    @DisplayName("JS/TS extractForJsTs - extracts from optional chaining")
    void testJsTsExtractsFromOptionalChaining() {
        assertEquals(Optional.of("MyClass"), ClassNameExtractor.extractForJsTs("MyClass?.doWork()"));
        assertEquals(Optional.of("MyClass"), ClassNameExtractor.extractForJsTs("  MyClass?.doWork(arg1, arg2)  "));
        assertEquals(Optional.of("MyClass"), ClassNameExtractor.extractForJsTs("MyClass?.doWork"));
    }

    @Test
    @DisplayName("JS/TS extractForJsTs - extracts from prototype chain")
    void testJsTsExtractsFromPrototypeChain() {
        assertEquals(Optional.of("Array"), ClassNameExtractor.extractForJsTs("Array.prototype.map"));
        assertEquals(Optional.of("Array"), ClassNameExtractor.extractForJsTs("Array.prototype['map']"));
    }

    @Test
    @DisplayName("JS/TS extractForJsTs - picks rightmost PascalCase before method")
    void testJsTsPicksRightmostPascalCaseBeforeMethod() {
        assertEquals(Optional.of("MyClass"), ClassNameExtractor.extractForJsTs("MyNamespace.MyClass.run"));
        assertEquals(Optional.of("Observable"), ClassNameExtractor.extractForJsTs("rxjs.Observable.of"));
        assertEquals(Optional.of("SwingUtilities"), ClassNameExtractor.extractForJsTs("SwingUtilities.invokeLater"));
    }

    @Test
    @DisplayName("JS/TS extractForJsTs - handles generics and type args")
    void testJsTsHandlesGenericsAndTypeArgs() {
        assertEquals(Optional.of("Map"), ClassNameExtractor.extractForJsTs("Map<string, number>.set"));
        assertEquals(
                Optional.of("Map"),
                ClassNameExtractor.extractForJsTs("  Map < string , Array<number> >  . set ( 'k', 1 ) "));
        assertEquals(Optional.of("MyClass"), ClassNameExtractor.extractForJsTs("MyClass.method<T>()"));
        assertEquals(Optional.of("MyClass"), ClassNameExtractor.extractForJsTs("MyClass.method<T extends Foo, U>()"));
    }

    @Test
    @DisplayName("JS/TS extractForJsTs - normalizes bracket property access")
    void testJsTsNormalizesBracketPropertyAccess() {
        assertEquals(Optional.of("Foo"), ClassNameExtractor.extractForJsTs("Foo['bar']()"));
        assertEquals(Optional.of("Foo"), ClassNameExtractor.extractForJsTs("Foo[\"bar\"]"));
        assertEquals(Optional.of("Foo"), ClassNameExtractor.extractForJsTs("Foo['bar'].baz"));
    }

    @Test
    @DisplayName("JS/TS extractForJsTs - does not extract lowercase anchors")
    void testJsTsDoesNotExtractLowercaseAnchors() {
        assertTrue(ClassNameExtractor.extractForJsTs("console.log").isEmpty());
        assertTrue(ClassNameExtractor.extractForJsTs("document.querySelector").isEmpty());
        assertTrue(ClassNameExtractor.extractForJsTs("window.fetch").isEmpty());
    }

    @Test
    @DisplayName("JS/TS extractForJsTs - bare PascalCase now matches")
    void testJsTsBarePascalCaseNowMatches() {
        // Bare PascalCase class names should now be extracted
        assertEquals(Optional.of("BubleStore"), ClassNameExtractor.extractForJsTs("BubleStore"));
        assertEquals(Optional.of("BubbleState"), ClassNameExtractor.extractForJsTs("BubbleState"));
        assertEquals(Optional.of("Array"), ClassNameExtractor.extractForJsTs("Array"));
        assertEquals(Optional.of("ResultMsg"), ClassNameExtractor.extractForJsTs("ResultMsg"));

        // Dotted references still work as before
        assertEquals(Optional.of("BubleStore"), ClassNameExtractor.extractForJsTs("BubleStore.save"));
        assertEquals(Optional.of("BubleStore"), ClassNameExtractor.extractForJsTs("BubleStore.save(...)"));
        assertEquals(Optional.of("BubleStore"), ClassNameExtractor.extractForJsTs("BubleStore.save(arg)"));

        // Still reject non-PascalCase bare names
        assertTrue(ClassNameExtractor.extractForJsTs("console").isEmpty());
        assertTrue(ClassNameExtractor.extractForJsTs("document").isEmpty());
        assertTrue(ClassNameExtractor.extractForJsTs("window").isEmpty());
        assertTrue(ClassNameExtractor.extractForJsTs("camelCase").isEmpty());
    }

    @Test
    @DisplayName("JS/TS extractForJsTs - respects parentheses when finding last dot")
    void testJsTsRespectsParenthesesWhenFindingLastDot() {
        // The last dot outside parens is between "MyClass" and "method"
        assertEquals(
                Optional.of("MyClass"), ClassNameExtractor.extractForJsTs("MyClass.method(call.with.dots(and, more))"));
        // Method without call still ok
        assertEquals(Optional.of("MyClass"), ClassNameExtractor.extractForJsTs("MyClass.method   "));
    }

    @Test
    @DisplayName("Scala analyzer - extractCallReceiver with various method references")
    void testScalaAnalyzerExtractClassName() {
        var analyzer = new ScalaAnalyzer(mockProject);

        // Valid Scala method references - standard camelCase
        assertEquals(Optional.of("MyClass"), analyzer.extractCallReceiver("MyClass.myMethod"));
        assertEquals(
                Optional.of("scala.collection.immutable.List"),
                analyzer.extractCallReceiver("scala.collection.immutable.List.apply"));
        assertEquals(Optional.of("Option"), analyzer.extractCallReceiver("Option.getOrElse"));
        assertEquals(Optional.of("List"), analyzer.extractCallReceiver("List.map"));
        assertEquals(Optional.of("Future"), analyzer.extractCallReceiver("Future.successful"));

        // Valid with snake_case methods (common in Scala)
        assertEquals(Optional.of("MyClass"), analyzer.extractCallReceiver("MyClass.my_method"));
        assertEquals(Optional.of("Config"), analyzer.extractCallReceiver("Config.get_value"));

        // Symbolic operators (common in Scala collections)
        assertEquals(Optional.of("MyList"), analyzer.extractCallReceiver("MyList.++"));
        assertEquals(Optional.of("List"), analyzer.extractCallReceiver("List.::"));
        assertEquals(Optional.of("Set"), analyzer.extractCallReceiver("Set.+"));
        assertEquals(Optional.of("Map"), analyzer.extractCallReceiver("Map.+="));

        // Type parameters with square brackets should be stripped
        assertEquals(Optional.of("List"), analyzer.extractCallReceiver("List[Int].map"));
        assertEquals(Optional.of("Map"), analyzer.extractCallReceiver("Map[String, Int].get"));
        assertEquals(Optional.of("Option"), analyzer.extractCallReceiver("Option[List[String]].flatMap"));

        // Method calls with parameters
        assertEquals(Optional.of("Future"), analyzer.extractCallReceiver("Future.apply(...)"));
        assertEquals(Optional.of("List"), analyzer.extractCallReceiver("List.fill(10)"));

        // Package-qualified references
        assertEquals(
                Optional.of("scala.concurrent.Future"),
                analyzer.extractCallReceiver("scala.concurrent.Future.successful"));
        assertEquals(
                Optional.of("akka.actor.ActorSystem"), analyzer.extractCallReceiver("akka.actor.ActorSystem.create"));

        // Invalid cases - should return empty
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("MyClass"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("myMethod"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("MyClass."));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver(".myMethod"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver(""));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("   "));

        // Edge cases consistent with heuristic
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("myclass.myMethod")); // lowercase class
        assertEquals(
                Optional.empty(), analyzer.extractCallReceiver("MyClass.MyMethod")); // uppercase method (not typical)

        // Whitespace handling
        assertEquals(Optional.of("MyClass"), analyzer.extractCallReceiver("  MyClass.myMethod  "));
    }

    @Test
    @DisplayName("Scala extractForScala - handles complex type parameters")
    void testScalaExtractsWithComplexTypeParams() {
        assertEquals(Optional.of("Either"), ClassNameExtractor.extractForScala("Either[Error, Result].fold"));
        assertEquals(Optional.of("Function1"), ClassNameExtractor.extractForScala("Function1[A, B].apply"));
        // Nested type params
        assertEquals(Optional.of("Future"), ClassNameExtractor.extractForScala("Future[Option[List[Int]]].map"));
    }

    @Test
    @DisplayName("Scala extractForScala - handles symbolic method names")
    void testScalaExtractsSymbolicMethods() {
        // Common symbolic operators in Scala
        assertEquals(Optional.of("List"), ClassNameExtractor.extractForScala("List.:::"));
        assertEquals(Optional.of("String"), ClassNameExtractor.extractForScala("String.++"));
        assertEquals(Optional.of("Int"), ClassNameExtractor.extractForScala("Int.+"));
        assertEquals(Optional.of("Boolean"), ClassNameExtractor.extractForScala("Boolean.&&"));
        assertEquals(Optional.of("Option"), ClassNameExtractor.extractForScala("Option.>>"));
    }

    @Test
    @DisplayName("C# analyzer - extractCallReceiver with PascalCase methods")
    void testCSharpAnalyzerExtractClassName() {
        var analyzer = new CSharpAnalyzer(mockProject);

        // Valid C# method references - both class and method use PascalCase
        assertEquals(Optional.of("MyClass"), analyzer.extractCallReceiver("MyClass.MyMethod"));
        assertEquals(Optional.of("Console"), analyzer.extractCallReceiver("Console.WriteLine"));
        assertEquals(Optional.of("String"), analyzer.extractCallReceiver("String.IsNullOrEmpty"));
        assertEquals(Optional.of("File"), analyzer.extractCallReceiver("File.ReadAllText"));

        // Namespace-qualified references
        assertEquals(Optional.of("System.IO.File"), analyzer.extractCallReceiver("System.IO.File.ReadAllText"));
        assertEquals(Optional.of("System.Console"), analyzer.extractCallReceiver("System.Console.WriteLine"));
        assertEquals(
                Optional.of("System.Collections.Generic.List"),
                analyzer.extractCallReceiver("System.Collections.Generic.List.Add"));

        // Generic types with angle brackets
        assertEquals(Optional.of("List"), analyzer.extractCallReceiver("List<int>.Add"));
        assertEquals(Optional.of("Dictionary"), analyzer.extractCallReceiver("Dictionary<string, int>.TryGetValue"));
        assertEquals(Optional.of("Task"), analyzer.extractCallReceiver("Task<User>.ConfigureAwait"));

        // Nested generics
        assertEquals(Optional.of("Task"), analyzer.extractCallReceiver("Task<List<string>>.Wait"));

        // Nullable types in generics
        assertEquals(Optional.of("Task"), analyzer.extractCallReceiver("Task<User?>.ConfigureAwait"));

        // Null-conditional operator
        assertEquals(Optional.of("MyClass"), analyzer.extractCallReceiver("MyClass?.MyMethod"));
        assertEquals(Optional.of("List"), analyzer.extractCallReceiver("List<int>?.Add"));

        // Method calls with parameters
        assertEquals(Optional.of("Console"), analyzer.extractCallReceiver("Console.WriteLine(message)"));
        assertEquals(Optional.of("File"), analyzer.extractCallReceiver("File.WriteAllText(path, content)"));

        // Invalid cases - should return empty
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("MyClass"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("MyMethod"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("MyClass."));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver(".MyMethod"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver(""));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("   "));

        // C# specific: lowercase method names are INVALID (unlike Java)
        assertEquals(
                Optional.empty(), analyzer.extractCallReceiver("MyClass.myMethod")); // lowercase method - invalid C#
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("myClass.MyMethod")); // lowercase class

        // Whitespace handling
        assertEquals(Optional.of("MyClass"), analyzer.extractCallReceiver("  MyClass.MyMethod  "));
    }

    @Test
    @DisplayName("C# extractForCSharp - handles complex generic types")
    void testCSharpExtractsComplexGenerics() {
        assertEquals(
                Optional.of("Dictionary"),
                ClassNameExtractor.extractForCSharp("Dictionary<string, List<int>>.ContainsKey"));
        assertEquals(Optional.of("Func"), ClassNameExtractor.extractForCSharp("Func<int, string>.Invoke"));
        assertEquals(
                Optional.of("IEnumerable"),
                ClassNameExtractor.extractForCSharp("IEnumerable<KeyValuePair<string, int>>.GetEnumerator"));
    }

    @Test
    @DisplayName("C# extractForCSharp - handles async patterns")
    void testCSharpExtractsAsyncPatterns() {
        assertEquals(Optional.of("Task"), ClassNameExtractor.extractForCSharp("Task.Run"));
        assertEquals(Optional.of("Task"), ClassNameExtractor.extractForCSharp("Task<int>.FromResult"));
        assertEquals(Optional.of("ValueTask"), ClassNameExtractor.extractForCSharp("ValueTask<string>.AsTask"));
    }

    @Test
    @DisplayName("Go analyzer - extractCallReceiver with packages and structs")
    void testGoAnalyzerExtractClassName() {
        var analyzer = new GoAnalyzer(mockProject);

        // Package.Function patterns (lowercase package, PascalCase function)
        assertEquals(Optional.of("http"), analyzer.extractCallReceiver("http.ListenAndServe"));
        assertEquals(Optional.of("fmt"), analyzer.extractCallReceiver("fmt.Println"));
        assertEquals(Optional.of("strings"), analyzer.extractCallReceiver("strings.Contains"));
        assertEquals(Optional.of("os"), analyzer.extractCallReceiver("os.Open"));

        // Package.Type patterns (struct references)
        assertEquals(Optional.of("http"), analyzer.extractCallReceiver("http.Server"));
        assertEquals(Optional.of("http"), analyzer.extractCallReceiver("http.Request"));
        assertEquals(Optional.of("strings"), analyzer.extractCallReceiver("strings.Builder"));
        assertEquals(Optional.of("sync"), analyzer.extractCallReceiver("sync.Mutex"));
        assertEquals(Optional.of("context"), analyzer.extractCallReceiver("context.Context"));

        // Receiver.Method patterns (instance method calls)
        assertEquals(Optional.of("myServer"), analyzer.extractCallReceiver("myServer.ListenAndServe"));
        assertEquals(Optional.of("builder"), analyzer.extractCallReceiver("builder.WriteString"));
        assertEquals(Optional.of("mutex"), analyzer.extractCallReceiver("mutex.Lock"));

        // Nested package references
        assertEquals(Optional.of("net.http"), analyzer.extractCallReceiver("net.http.Get"));
        assertEquals(Optional.of("encoding.json"), analyzer.extractCallReceiver("encoding.json.Marshal"));

        // Method calls with parameters
        assertEquals(Optional.of("fmt"), analyzer.extractCallReceiver("fmt.Printf(format, args)"));
        assertEquals(Optional.of("http"), analyzer.extractCallReceiver("http.HandleFunc(pattern, handler)"));

        // Unexported (lowercase) methods - valid in Go
        assertEquals(Optional.of("myPackage"), analyzer.extractCallReceiver("myPackage.internalFunc"));
        assertEquals(Optional.of("server"), analyzer.extractCallReceiver("server.handleRequest"));

        // Invalid cases - should return empty
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("http"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("Server"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("http."));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver(".Server"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver(""));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("   "));

        // Whitespace handling
        assertEquals(Optional.of("http"), analyzer.extractCallReceiver("  http.Server  "));
    }

    @Test
    @DisplayName("Go extractForGo - handles standard library patterns")
    void testGoExtractsStdLibPatterns() {
        // io package
        assertEquals(Optional.of("io"), ClassNameExtractor.extractForGo("io.Reader"));
        assertEquals(Optional.of("io"), ClassNameExtractor.extractForGo("io.Writer"));
        assertEquals(Optional.of("io"), ClassNameExtractor.extractForGo("io.Copy"));

        // bufio package
        assertEquals(Optional.of("bufio"), ClassNameExtractor.extractForGo("bufio.Scanner"));
        assertEquals(Optional.of("bufio"), ClassNameExtractor.extractForGo("bufio.NewReader"));

        // time package
        assertEquals(Optional.of("time"), ClassNameExtractor.extractForGo("time.Duration"));
        assertEquals(Optional.of("time"), ClassNameExtractor.extractForGo("time.Now"));
        assertEquals(Optional.of("time"), ClassNameExtractor.extractForGo("time.Sleep"));
    }

    @Test
    @DisplayName("Go extractForGo - handles interface types")
    void testGoExtractsInterfaceTypes() {
        assertEquals(Optional.of("io"), ClassNameExtractor.extractForGo("io.ReadWriter"));
        assertEquals(Optional.of("http"), ClassNameExtractor.extractForGo("http.Handler"));
        assertEquals(Optional.of("http"), ClassNameExtractor.extractForGo("http.ResponseWriter"));
        assertEquals(Optional.of("sort"), ClassNameExtractor.extractForGo("sort.Interface"));
    }

    @Test
    @DisplayName("PHP analyzer - extractCallReceiver with :: and -> operators")
    void testPhpAnalyzerExtractClassName() {
        var analyzer = new PhpAnalyzer(mockProject);

        // Static method calls (::)
        assertEquals(Optional.of("MyClass"), analyzer.extractCallReceiver("MyClass::staticMethod"));
        assertEquals(Optional.of("DateTime"), analyzer.extractCallReceiver("DateTime::createFromFormat"));
        assertEquals(Optional.of("PDO"), analyzer.extractCallReceiver("PDO::prepare"));
        assertEquals(Optional.of("Exception"), analyzer.extractCallReceiver("Exception::getMessage"));

        // Static method calls with parameters
        assertEquals(Optional.of("MyClass"), analyzer.extractCallReceiver("MyClass::staticMethod()"));
        assertEquals(Optional.of("Str"), analyzer.extractCallReceiver("Str::random(16)"));

        // Class constants (also use ::)
        assertEquals(Optional.of("MyClass"), analyzer.extractCallReceiver("MyClass::CONSTANT"));
        assertEquals(Optional.of("PDO"), analyzer.extractCallReceiver("PDO::FETCH_ASSOC"));

        // Namespaced class references
        assertEquals(
                Optional.of("Illuminate\\Support\\Str"),
                analyzer.extractCallReceiver("Illuminate\\Support\\Str::random"));
        assertEquals(Optional.of("App\\Models\\User"), analyzer.extractCallReceiver("App\\Models\\User::find"));
        assertEquals(
                Optional.of("Symfony\\Component\\HttpFoundation\\Request"),
                analyzer.extractCallReceiver("Symfony\\Component\\HttpFoundation\\Request::create"));

        // Instance method calls (->)
        assertEquals(Optional.of("$user"), analyzer.extractCallReceiver("$user->getName"));
        assertEquals(Optional.of("$this"), analyzer.extractCallReceiver("$this->processRequest"));
        assertEquals(Optional.of("$request"), analyzer.extractCallReceiver("$request->input"));
        assertEquals(Optional.of("$response"), analyzer.extractCallReceiver("$response->json"));

        // Instance method calls with parameters
        assertEquals(Optional.of("$user"), analyzer.extractCallReceiver("$user->save()"));
        assertEquals(Optional.of("$query"), analyzer.extractCallReceiver("$query->where(column, value)"));

        // Invalid cases - should return empty
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("MyClass"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("$user"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("MyClass::"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("::staticMethod"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("$user->"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("->method"));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver(""));
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("   "));

        // PHP doesn't use dots for method calls
        assertEquals(Optional.empty(), analyzer.extractCallReceiver("MyClass.method"));

        // Whitespace handling
        assertEquals(Optional.of("MyClass"), analyzer.extractCallReceiver("  MyClass::staticMethod  "));
        assertEquals(Optional.of("$user"), analyzer.extractCallReceiver("  $user->getName  "));
    }

    @Test
    @DisplayName("PHP extractForPhp - handles Laravel/Symfony patterns")
    void testPhpExtractsFrameworkPatterns() {
        // Laravel patterns
        assertEquals(Optional.of("Route"), ClassNameExtractor.extractForPhp("Route::get"));
        assertEquals(Optional.of("DB"), ClassNameExtractor.extractForPhp("DB::table"));
        assertEquals(Optional.of("Auth"), ClassNameExtractor.extractForPhp("Auth::user"));
        assertEquals(Optional.of("Cache"), ClassNameExtractor.extractForPhp("Cache::remember"));

        // Eloquent ORM
        assertEquals(Optional.of("User"), ClassNameExtractor.extractForPhp("User::find"));
        assertEquals(Optional.of("Post"), ClassNameExtractor.extractForPhp("Post::where"));
        assertEquals(Optional.of("$model"), ClassNameExtractor.extractForPhp("$model->save"));

        // Symfony patterns
        assertEquals(Optional.of("Response"), ClassNameExtractor.extractForPhp("Response::create"));
        assertEquals(Optional.of("$container"), ClassNameExtractor.extractForPhp("$container->get"));
    }

    @Test
    @DisplayName("PHP extractForPhp - handles special PHP identifiers")
    void testPhpExtractsSpecialIdentifiers() {
        // $this reference
        assertEquals(Optional.of("$this"), ClassNameExtractor.extractForPhp("$this->render"));
        assertEquals(Optional.of("$this"), ClassNameExtractor.extractForPhp("$this->validate"));

        // self and static (would need :: not ->)
        assertEquals(Optional.of("self"), ClassNameExtractor.extractForPhp("self::getInstance"));
        assertEquals(Optional.of("static"), ClassNameExtractor.extractForPhp("static::create"));
        assertEquals(Optional.of("parent"), ClassNameExtractor.extractForPhp("parent::__construct"));

        // Chained instance calls are not supported (conservative extraction)
        assertEquals(Optional.empty(), ClassNameExtractor.extractForPhp("$this->service->doWork"));
        assertEquals(Optional.empty(), ClassNameExtractor.extractForPhp("$user->profile->getName"));

        // Leading backslash for fully-qualified namespace
        assertEquals(Optional.of("\\App\\Models\\User"), ClassNameExtractor.extractForPhp("\\App\\Models\\User::find"));
    }

    @Test
    @DisplayName("Edge cases - whitespace and special characters")
    void testEdgeCases() {
        var javaAnalyzer = Languages.JAVA.createAnalyzer(mockProject);
        var cppAnalyzer = new CppAnalyzer(mockProject);

        // Whitespace handling
        assertEquals(Optional.of("MyClass"), javaAnalyzer.extractCallReceiver("  MyClass.myMethod  "));
        assertEquals(Optional.of("MyClass"), cppAnalyzer.extractCallReceiver("  MyClass::myMethod  "));

        // Multiple separators
        assertEquals(Optional.of("ns1::ns2::Class"), cppAnalyzer.extractCallReceiver("ns1::ns2::Class::method"));
        assertEquals(
                Optional.of("com.example.deep.Class"),
                javaAnalyzer.extractCallReceiver("com.example.deep.Class.method"));

        // Empty parts
        assertEquals(Optional.empty(), javaAnalyzer.extractCallReceiver("..method"));
        // C++ starts-with-:: remains empty
        assertEquals(Optional.empty(), cppAnalyzer.extractCallReceiver("::method")); // starts with ::
    }
}
