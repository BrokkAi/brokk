package analyzer

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestResolveClassesMatchesShortNameAndNestedClass(t *testing.T) {
	t.Parallel()

	workspace := t.TempDir()
	writeAnalyzerFile(t, workspace, "src/main/java/com/example/service/UserService.java", ""+
		"package com.example.service;\n\n"+
		"public class UserService {\n"+
		"    private String id;\n"+
		"    public static class NestedWorker {\n"+
		"    }\n"+
		"}\n")

	service := New(workspace)

	classes := service.ResolveClasses([]string{"UserService", "NestedWorker"})
	if len(classes) != 2 {
		t.Fatalf("len(classes) = %d, want 2", len(classes))
	}
	if classes[0].FQName != "com.example.service.UserService" {
		t.Fatalf("classes[0].FQName = %q, want UserService fqName", classes[0].FQName)
	}
	if classes[1].FQName != "com.example.service.UserService.NestedWorker" {
		t.Fatalf("classes[1].FQName = %q, want nested class fqName", classes[1].FQName)
	}
}

func TestParseTreeSitterSymbolsJava(t *testing.T) {
	if !treeSitterEnabled() {
		t.Skip("tree-sitter requires cgo support on this machine")
	}

	symbols, ok := parseTreeSitterSymbols("src/main/java/com/example/service/UserService.java", ""+
		"package com.example.service;\n\n"+
		"public class UserService {\n"+
		"    private String cachedId;\n\n"+
		"    public String findUserById(String id) {\n"+
		"        return id;\n"+
		"    }\n"+
		"}\n")
	if !ok {
		t.Fatal("parseTreeSitterSymbols() = false, want true")
	}

	if len(filterByKind(symbols, "class")) != 1 {
		t.Fatalf("class count = %d, want 1", len(filterByKind(symbols, "class")))
	}
	if !filterByKind(symbols, "class")[0].HasBody {
		t.Fatalf("class HasBody = false, want true")
	}

	methods := filterByKind(symbols, "function")
	if len(methods) != 2 {
		t.Fatalf("len(methods) = %d, want 2", len(methods))
	}
	foundConstructor := false
	foundMethod := false
	for _, method := range methods {
		switch method.FQName {
		case "com.example.service.UserService.UserService":
			foundConstructor = true
			if method.HasBody {
				t.Fatalf("constructor HasBody = true, want false")
			}
		case "com.example.service.UserService.findUserById":
			foundMethod = true
			if !method.HasBody {
				t.Fatalf("findUserById HasBody = false, want true")
			}
		}
	}
	if !foundConstructor || !foundMethod {
		t.Fatalf("methods = %#v, want constructor and method fqNames", methods)
	}

	fields := filterByKind(symbols, "field")
	if len(fields) != 1 || fields[0].FQName != "com.example.service.UserService.cachedId" {
		t.Fatalf("fields = %#v, want java field fqName", fields)
	}
}

func TestParseTreeSitterSymbolsIncludeLeadingComments(t *testing.T) {
	if !treeSitterEnabled() {
		t.Skip("tree-sitter requires cgo support on this machine")
	}

	symbols, ok := parseTreeSitterSymbols("src/main/java/com/example/service/UserService.java", ""+
		"package com.example.service;\n\n"+
		"/**\n"+
		" * Service docs.\n"+
		" */\n"+
		"public class UserService {\n"+
		"    // Finds a user by id.\n"+
		"    public String findUserById(String id) {\n"+
		"        return id;\n"+
		"    }\n"+
		"}\n")
	if !ok {
		t.Fatal("parseTreeSitterSymbols() = false, want true")
	}

	classes := filterByKind(symbols, "class")
	if len(classes) != 1 || !strings.Contains(classes[0].Snippet, "Service docs.") {
		t.Fatalf("classes = %#v, want leading class comment in snippet", classes)
	}

	methods := filterByKind(symbols, "function")
	if len(methods) != 2 {
		t.Fatalf("len(methods) = %d, want 2", len(methods))
	}
	foundMethodComment := false
	for _, method := range methods {
		if method.FQName != "com.example.service.UserService.findUserById" {
			continue
		}
		foundMethodComment = true
		if !strings.Contains(method.Snippet, "// Finds a user by id.") {
			t.Fatalf("method snippet = %#v, want leading method comment in snippet", method)
		}
		if !strings.HasPrefix(strings.TrimSpace(method.Signature), "public String findUserById") {
			t.Fatalf("method.Signature = %q, want declaration signature without comment prefix", method.Signature)
		}
	}
	if !foundMethodComment {
		t.Fatalf("methods = %#v, want findUserById method", methods)
	}
}

func TestParseTreeSitterSymbolsGoMethodReceiver(t *testing.T) {
	if !treeSitterEnabled() {
		t.Skip("tree-sitter requires cgo support on this machine")
	}

	symbols, ok := parseTreeSitterSymbols("internal/service/user_service.go", ""+
		"package service\n\n"+
		"type UserService struct {\n"+
		"    cacheKey string\n"+
		"}\n\n"+
		"func (s *UserService) FindUser(id string) string {\n"+
		"    return id\n"+
		"}\n")
	if !ok {
		t.Fatal("parseTreeSitterSymbols() = false, want true")
	}

	classes := filterByKind(symbols, "class")
	if len(classes) != 1 || classes[0].FQName != "service.UserService" {
		t.Fatalf("classes = %#v, want service.UserService", classes)
	}

	methods := filterByKind(symbols, "function")
	if len(methods) != 1 || methods[0].FQName != "service.UserService.FindUser" {
		t.Fatalf("methods = %#v, want service.UserService.FindUser", methods)
	}

	fields := filterByKind(symbols, "field")
	if len(fields) != 1 || fields[0].FQName != "service.UserService.cacheKey" {
		t.Fatalf("fields = %#v, want service.UserService.cacheKey", fields)
	}
}

func TestParseTreeSitterSymbolsPythonDecoratedDefinitions(t *testing.T) {
	if !treeSitterEnabled() {
		t.Skip("tree-sitter requires cgo support on this machine")
	}

	symbols, ok := parseTreeSitterSymbols("pkg/service.py", ""+
		"import json\n\n"+
		"@tracked\n"+
		"class UserService:\n"+
		"    @cached\n"+
		"    def find_user(self, user_id):\n"+
		"        return user_id\n")
	if !ok {
		t.Fatal("parseTreeSitterSymbols() = false, want true")
	}

	classes := filterByKind(symbols, "class")
	if len(classes) != 1 || classes[0].FQName != "pkg.service.UserService" {
		t.Fatalf("classes = %#v, want pkg.service.UserService", classes)
	}
	if !strings.Contains(classes[0].Snippet, "@tracked") {
		t.Fatalf("class snippet = %q, want decorator text", classes[0].Snippet)
	}

	methods := filterByKind(symbols, "function")
	if len(methods) != 1 || methods[0].FQName != "pkg.service.UserService.find_user" {
		t.Fatalf("methods = %#v, want pkg.service.UserService.find_user", methods)
	}
	if !strings.Contains(methods[0].Snippet, "@cached") {
		t.Fatalf("method snippet = %q, want decorator text", methods[0].Snippet)
	}
}

func TestParseTreeSitterSymbolsIncludeLeadingPythonComments(t *testing.T) {
	if !treeSitterEnabled() {
		t.Skip("tree-sitter requires cgo support on this machine")
	}

	symbols, ok := parseTreeSitterSymbols("pkg/service.py", ""+
		"# Service docs.\n"+
		"class UserService:\n"+
		"    # Finds a user by id.\n"+
		"    def find_user(self, user_id):\n"+
		"        return user_id\n")
	if !ok {
		t.Fatal("parseTreeSitterSymbols() = false, want true")
	}

	classes := filterByKind(symbols, "class")
	if len(classes) != 1 || !strings.Contains(classes[0].Snippet, "# Service docs.") {
		t.Fatalf("classes = %#v, want leading python class comment in snippet", classes)
	}

	methods := filterByKind(symbols, "function")
	if len(methods) != 1 || !strings.Contains(methods[0].Snippet, "# Finds a user by id.") {
		t.Fatalf("methods = %#v, want leading python method comment in snippet", methods)
	}
	if !strings.HasPrefix(strings.TrimSpace(methods[0].Signature), "def find_user") {
		t.Fatalf("methods[0].Signature = %q, want method signature without comment prefix", methods[0].Signature)
	}
}

func TestParseTreeSitterSymbolsPythonInitUsesPackageName(t *testing.T) {
	if !treeSitterEnabled() {
		t.Skip("tree-sitter requires cgo support on this machine")
	}

	symbols, ok := parseTreeSitterSymbols("pkg/__init__.py", ""+
		"class UserService:\n"+
		"    pass\n")
	if !ok {
		t.Fatal("parseTreeSitterSymbols() = false, want true")
	}

	classes := filterByKind(symbols, "class")
	if len(classes) != 1 || classes[0].FQName != "pkg.UserService" {
		t.Fatalf("classes = %#v, want pkg.UserService", classes)
	}
}

func TestParseTreeSitterSymbolsJavaScriptExportedArrowFunction(t *testing.T) {
	if !treeSitterEnabled() {
		t.Skip("tree-sitter requires cgo support on this machine")
	}

	symbols, ok := parseTreeSitterSymbols("web/user-service.js", ""+
		"import { http } from './http';\n\n"+
		"export const loadUser = (id) => {\n"+
		"  return http.get(id);\n"+
		"};\n")
	if !ok {
		t.Fatal("parseTreeSitterSymbols() = false, want true")
	}

	methods := filterByKind(symbols, "function")
	if len(methods) != 1 || methods[0].FQName != "web.user-service.loadUser" {
		t.Fatalf("methods = %#v, want web.user-service.loadUser", methods)
	}
}

func TestParseTreeSitterSymbolsNormalizesQuotedAndComputedTypeScriptNames(t *testing.T) {
	if !treeSitterEnabled() {
		t.Skip("tree-sitter requires cgo support on this machine")
	}

	tsSymbols, ok := parseTreeSitterSymbols("web/user-service.ts", ""+
		"export class UserService {\n"+
		"  'load-user'(): void {}\n"+
		"  #loadUser(): void {}\n"+
		"  [Symbol.iterator](): Iterator<string> { throw new Error(); }\n"+
		"}\n")
	if !ok {
		t.Fatal("parseTreeSitterSymbols(ts) = false, want true")
	}

	tsMethods := filterByKind(tsSymbols, "function")
	foundQuoted := false
	foundPrivate := false
	foundComputed := false
	for _, method := range tsMethods {
		if method.FQName == "web.user-service.UserService.load-user" {
			foundQuoted = true
		}
		if method.FQName == "web.user-service.UserService.loadUser" {
			foundPrivate = true
		}
		if method.FQName == "web.user-service.UserService.Symbol.iterator" {
			foundComputed = true
		}
	}
	if !foundQuoted || !foundPrivate || !foundComputed {
		t.Fatalf("tsMethods = %#v, want normalized quoted, private, and computed method names", tsMethods)
	}
}

func TestParseTreeSitterFileCapturesJavaScriptImports(t *testing.T) {
	if !treeSitterEnabled() {
		t.Skip("tree-sitter requires cgo support on this machine")
	}

	analysis, ok := parseTreeSitterFile("web/user-service.js", ""+
		"import { http } from './http';\n"+
		"const legacyCache = require('./legacy-cache');\n"+
		"import { cache } from './cache';\n\n"+
		"export const loadUser = (id) => {\n"+
		"  return http.get(cache.key(id));\n"+
		"};\n")
	if !ok {
		t.Fatal("parseTreeSitterFile() = false, want true")
	}
	if len(analysis.imports) != 3 {
		t.Fatalf("len(analysis.imports) = %d, want 3", len(analysis.imports))
	}
	if analysis.imports[1] != "const legacyCache = require('./legacy-cache');" {
		t.Fatalf("analysis.imports[1] = %q, want wrapped CommonJS import", analysis.imports[1])
	}
}

func TestParseTreeSitterSymbolsTypeScriptExportedInterfaceAndAlias(t *testing.T) {
	if !treeSitterEnabled() {
		t.Skip("tree-sitter requires cgo support on this machine")
	}

	symbols, ok := parseTreeSitterSymbols("web/user-service.ts", ""+
		"export interface UserRecord {\n"+
		"  id: string;\n"+
		"}\n\n"+
		"export type UserId = string;\n\n"+
		"export class UserService {\n"+
		"  findUser(id: UserId): UserRecord {\n"+
		"    return { id };\n"+
		"  }\n"+
		"}\n")
	if !ok {
		t.Fatal("parseTreeSitterSymbols() = false, want true")
	}

	classes := filterByKind(symbols, "class")
	foundRecord := false
	foundAlias := false
	foundService := false
	for _, classSymbol := range classes {
		switch classSymbol.FQName {
		case "web.user-service.UserRecord":
			foundRecord = true
		case "web.user-service.UserId":
			foundAlias = true
		case "web.user-service.UserService":
			foundService = true
		}
	}
	if !foundRecord || !foundAlias || !foundService {
		t.Fatalf("classes = %#v, want interface, alias, and class captures", classes)
	}
}

func TestParseTreeSitterSymbolsTypeScriptDefaultNamedSignatures(t *testing.T) {
	if !treeSitterEnabled() {
		t.Skip("tree-sitter requires cgo support on this machine")
	}

	symbols, ok := parseTreeSitterSymbols("web/factory.ts", ""+
		"export interface UserFactory {\n"+
		"  new (id: string): UserFactory;\n"+
		"  (id: string): UserFactory;\n"+
		"  [index: string]: UserFactory;\n"+
		"}\n")
	if !ok {
		t.Fatal("parseTreeSitterSymbols() = false, want true")
	}

	methods := filterByKind(symbols, "function")
	foundCtor := false
	foundCall := false
	for _, method := range methods {
		if method.FQName == "web.factory.UserFactory.new" {
			foundCtor = true
		}
		if method.FQName == "web.factory.UserFactory.[call]" {
			foundCall = true
		}
	}
	if !foundCtor || !foundCall {
		t.Fatalf("methods = %#v, want default named constructor and call signatures", methods)
	}

	fields := filterByKind(symbols, "field")
	if len(fields) != 1 || fields[0].FQName != "web.factory.UserFactory.[index]" {
		t.Fatalf("fields = %#v, want default named index signature", fields)
	}
}

func TestParseTreeSitterFileCapturesJavaImportsSupertypesAndTests(t *testing.T) {
	if !treeSitterEnabled() {
		t.Skip("tree-sitter requires cgo support on this machine")
	}

	analysis, ok := parseTreeSitterFile("src/test/java/com/example/service/UserServiceTest.java", ""+
		"package com.example.service;\n\n"+
		"import java.util.List;\n"+
		"import org.junit.jupiter.api.Test;\n\n"+
		"public class UserServiceTest extends BaseService implements Runnable {\n"+
		"    @Test\n"+
		"    public void runsTest() {\n"+
		"    }\n"+
		"}\n")
	if !ok {
		t.Fatal("parseTreeSitterFile() = false, want true")
	}
	if !analysis.containsTests {
		t.Fatal("analysis.containsTests = false, want true")
	}
	if len(analysis.imports) != 2 {
		t.Fatalf("len(analysis.imports) = %d, want 2", len(analysis.imports))
	}

	classes := filterByKind(analysis.symbols, "class")
	if len(classes) != 1 {
		t.Fatalf("class count = %d, want 1", len(classes))
	}
	want := []string{"BaseService", "Runnable"}
	if strings.Join(classes[0].RawSupertypes, ",") != strings.Join(want, ",") {
		t.Fatalf("classes[0].RawSupertypes = %#v, want %#v", classes[0].RawSupertypes, want)
	}
}

func TestParseTreeSitterFileCapturesGoTests(t *testing.T) {
	if !treeSitterEnabled() {
		t.Skip("tree-sitter requires cgo support on this machine")
	}

	analysis, ok := parseTreeSitterFile("internal/service/user_service_test.go", ""+
		"package service\n\n"+
		"import \"testing\"\n\n"+
		"func TestUserService(t *testing.T) {\n"+
		"}\n")
	if !ok {
		t.Fatal("parseTreeSitterFile() = false, want true")
	}
	if !analysis.containsTests {
		t.Fatal("analysis.containsTests = false, want true")
	}
}

func TestDedupeSymbolsPrefersDefinitionWithBody(t *testing.T) {
	symbols := dedupeSymbols([]Symbol{
		{
			Kind:      "function",
			FQName:    "pkg.UserService.loadUser",
			Signature: "loadUser(id: string): User;",
			Snippet:   "loadUser(id: string): User;",
			HasBody:   false,
		},
		{
			Kind:      "function",
			FQName:    "pkg.UserService.loadUser",
			Signature: "loadUser(id: string): User;",
			Snippet:   "loadUser(id: string): User { return user; }",
			HasBody:   true,
		},
	})

	if len(symbols) != 1 {
		t.Fatalf("len(symbols) = %d, want 1", len(symbols))
	}
	if !symbols[0].HasBody {
		t.Fatalf("symbols[0].HasBody = false, want true")
	}
	if !strings.Contains(symbols[0].Snippet, "return user") {
		t.Fatalf("symbols[0].Snippet = %q, want body-bearing definition", symbols[0].Snippet)
	}
}

func TestResolveMethodsMatchesShortNameThroughFallbacks(t *testing.T) {
	t.Parallel()

	workspace := t.TempDir()
	writeAnalyzerFile(t, workspace, "src/main/java/com/example/service/UserService.java", ""+
		"package com.example.service;\n\n"+
		"public class UserService {\n"+
		"    public UserService() {\n"+
		"    }\n"+
		"    public String findUserById(String id) {\n"+
		"        return id;\n"+
		"    }\n"+
		"}\n")

	service := New(workspace)

	methods := service.ResolveMethods([]string{"findUserById", "UserService"})
	if len(methods) != 2 {
		t.Fatalf("len(methods) = %d, want 2", len(methods))
	}
	if methods[0].FQName != "com.example.service.UserService.findUserById" {
		t.Fatalf("methods[0].FQName = %q, want method fqName", methods[0].FQName)
	}
	if methods[1].FQName != "com.example.service.UserService.UserService" {
		t.Fatalf("methods[1].FQName = %q, want constructor fqName", methods[1].FQName)
	}
}

func TestCompleteSymbolsAddsParentClassForNestedShortQuery(t *testing.T) {
	t.Parallel()

	workspace := t.TempDir()
	writeAnalyzerFile(t, workspace, "src/main/java/com/example/ui/Chrome.java", ""+
		"package com.example.ui;\n\n"+
		"public class Chrome {\n"+
		"    public static class AnalyzerStatusStrip {\n"+
		"    }\n"+
		"}\n")

	service := New(workspace)
	completions := service.CompleteSymbols("Chrome", 10)
	if len(completions) < 2 {
		t.Fatalf("len(completions) = %d, want at least 2", len(completions))
	}
	if completions[0].Detail != "com.example.ui.Chrome" {
		t.Fatalf("completions[0].Detail = %q, want parent class first", completions[0].Detail)
	}

	foundNested := false
	for _, completion := range completions {
		if completion.Detail == "com.example.ui.Chrome.AnalyzerStatusStrip" {
			foundNested = true
			break
		}
	}
	if !foundNested {
		t.Fatalf("nested completion missing from %#v", completions)
	}
}

func TestRenderSymbolAddsImportsAndWrappedPayload(t *testing.T) {
	t.Parallel()

	workspace := t.TempDir()
	writeAnalyzerFile(t, workspace, "src/main/java/com/example/service/UserService.java", ""+
		"package com.example.service;\n\n"+
		"import java.util.List;\n\n"+
		"public class UserService {\n"+
		"    public String findUserById(String id) {\n"+
		"        return id;\n"+
		"    }\n"+
		"}\n")

	service := New(workspace)
	methods := service.ResolveMethods([]string{"findUserById"})
	if len(methods) != 1 {
		t.Fatalf("len(methods) = %d, want 1", len(methods))
	}

	rendered := service.RenderSymbol(methods[0])
	if !strings.Contains(rendered, "<imports>") || !strings.Contains(rendered, "<methods class=\"com.example.service.UserService\"") {
		t.Fatalf("rendered = %q, want imports and methods wrapper", rendered)
	}
}

func TestAnalyzerTracksTreeSitterTestAndSupertypeMetadata(t *testing.T) {
	workspace := t.TempDir()
	writeAnalyzerFile(t, workspace, "src/test/java/com/example/service/UserServiceTest.java", ""+
		"package com.example.service;\n\n"+
		"import java.util.List;\n"+
		"import org.junit.jupiter.api.Test;\n\n"+
		"public class UserServiceTest extends BaseService implements Runnable {\n"+
		"    @Test\n"+
		"    public void runsTest() {\n"+
		"    }\n"+
		"}\n")

	service := New(workspace)
	if !service.ContainsTests("src/test/java/com/example/service/UserServiceTest.java") {
		t.Fatal("ContainsTests() = false, want true")
	}

	supertypes := service.RawSupertypes("com.example.service.UserServiceTest")
	want := []string{"BaseService", "Runnable"}
	if strings.Join(supertypes, ",") != strings.Join(want, ",") {
		t.Fatalf("RawSupertypes() = %#v, want %#v", supertypes, want)
	}

	group, ok := service.DefinitionGroup("com.example.service.UserServiceTest.runsTest", "function")
	if !ok {
		t.Fatal("DefinitionGroup() = false, want true")
	}
	rendered := service.RenderDefinitionGroup(group)
	if !strings.Contains(rendered, "import org.junit.jupiter.api.Test;") {
		t.Fatalf("rendered = %q, want tree-sitter imports in wrapper", rendered)
	}
}

func TestResolvePythonInitPackageClass(t *testing.T) {
	workspace := t.TempDir()
	writeAnalyzerFile(t, workspace, "pkg/__init__.py", ""+
		"class UserService:\n"+
		"    pass\n")

	service := New(workspace)
	classes := service.ResolveClasses([]string{"pkg.UserService"})
	if len(classes) != 1 || classes[0].FQName != "pkg.UserService" {
		t.Fatalf("classes = %#v, want pkg.UserService", classes)
	}
}

func TestSkeletonHeaderReturnsClassAndFields(t *testing.T) {
	t.Parallel()

	workspace := t.TempDir()
	writeAnalyzerFile(t, workspace, "src/main/java/com/example/service/UserService.java", ""+
		"package com.example.service;\n\n"+
		"public class UserService {\n"+
		"    private String cachedId;\n"+
		"    public static class NestedWorker {\n"+
		"    }\n"+
		"    public String findUserById(String id) {\n"+
		"        return id;\n"+
		"    }\n"+
		"}\n")

	service := New(workspace)
	header, ok := service.SkeletonHeader("com.example.service.UserService")
	if !ok {
		t.Fatal("SkeletonHeader() = false, want true")
	}
	if !strings.Contains(header, "private String cachedId;") {
		t.Fatalf("header = %q, want field signature", header)
	}
	if !strings.Contains(header, "public static class NestedWorker { ... }") {
		t.Fatalf("header = %q, want nested class summary", header)
	}
	if strings.Contains(header, "findUserById") {
		t.Fatalf("header = %q, want no method body or method signature", header)
	}
}

func TestResolvePythonClassAndMethod(t *testing.T) {
	t.Parallel()

	workspace := t.TempDir()
	writeAnalyzerFile(t, workspace, "pkg/service.py", ""+
		"import json\n\n"+
		"class UserService:\n"+
		"    def find_user(self, user_id):\n"+
		"        return user_id\n")

	service := New(workspace)
	classes := service.ResolveClasses([]string{"UserService"})
	if len(classes) != 1 || classes[0].FQName != "pkg.service.UserService" {
		t.Fatalf("classes = %#v, want pkg.service.UserService", classes)
	}

	methods := service.ResolveMethods([]string{"find_user"})
	if len(methods) != 1 || methods[0].FQName != "pkg.service.UserService.find_user" {
		t.Fatalf("methods = %#v, want pkg.service.UserService.find_user", methods)
	}

	rendered := service.RenderSymbol(methods[0])
	if !strings.Contains(rendered, "import json") || !strings.Contains(rendered, "<methods class=\"pkg.service.UserService\"") {
		t.Fatalf("rendered = %q, want python imports and methods wrapper", rendered)
	}
}

func TestResolveTypescriptInterfaceAndMethod(t *testing.T) {
	t.Parallel()

	workspace := t.TempDir()
	writeAnalyzerFile(t, workspace, "web/user-service.ts", ""+
		"import { HttpClient } from './http';\n\n"+
		"export interface UserRecord {\n"+
		"  id: string;\n"+
		"}\n\n"+
		"export class UserService {\n"+
		"  private cache: Map<string, UserRecord>;\n\n"+
		"  findUser(id: string) {\n"+
		"    return this.cache.get(id);\n"+
		"  }\n"+
		"}\n")

	service := New(workspace)
	classes := service.ResolveClasses([]string{"UserRecord", "UserService"})
	if len(classes) != 2 {
		t.Fatalf("len(classes) = %d, want 2", len(classes))
	}
	if classes[0].FQName != "web.user-service.UserRecord" {
		t.Fatalf("classes[0].FQName = %q, want UserRecord", classes[0].FQName)
	}
	if classes[1].FQName != "web.user-service.UserService" {
		t.Fatalf("classes[1].FQName = %q, want UserService", classes[1].FQName)
	}

	methods := service.ResolveMethods([]string{"findUser"})
	if len(methods) != 1 || methods[0].FQName != "web.user-service.UserService.findUser" {
		t.Fatalf("methods = %#v, want web.user-service.UserService.findUser", methods)
	}

	completions := service.CompleteSymbols("UserSer", 10)
	found := false
	for _, completion := range completions {
		if completion.Detail == "web.user-service.UserService" {
			found = true
			break
		}
	}
	if !found {
		t.Fatalf("completions = %#v, want UserService completion", completions)
	}
}

func TestSkeletonHeaderFormatsPythonWithoutJavaBraces(t *testing.T) {
	t.Parallel()

	workspace := t.TempDir()
	writeAnalyzerFile(t, workspace, "pkg/service.py", ""+
		"class UserService:\n"+
		"    class NestedWorker:\n"+
		"        pass\n\n"+
		"    def find_user(self, user_id):\n"+
		"        return user_id\n")

	service := New(workspace)
	header, ok := service.SkeletonHeader("pkg.service.UserService")
	if !ok {
		t.Fatal("SkeletonHeader() = false, want true")
	}
	if strings.Contains(header, "{") {
		t.Fatalf("header = %q, want python-style skeleton without braces", header)
	}
	if !strings.Contains(header, "class NestedWorker:") {
		t.Fatalf("header = %q, want nested python class", header)
	}
	if strings.Contains(header, "find_user") {
		t.Fatalf("header = %q, want no method entries", header)
	}
}

func TestResolveCSharpClassAndMethod(t *testing.T) {
	t.Parallel()

	workspace := t.TempDir()
	writeAnalyzerFile(t, workspace, "src/UserService.cs", ""+
		"using System.Collections.Generic;\n\n"+
		"namespace Example.Services {\n"+
		"    public class UserService {\n"+
		"        private string cacheKey;\n"+
		"        public string FindUser(string id) {\n"+
		"            return id;\n"+
		"        }\n"+
		"    }\n"+
		"}\n")

	service := New(workspace)
	classes := service.ResolveClasses([]string{"UserService"})
	if len(classes) != 1 || classes[0].FQName != "Example.Services.UserService" {
		t.Fatalf("classes = %#v, want Example.Services.UserService", classes)
	}
	methods := service.ResolveMethods([]string{"FindUser"})
	if len(methods) != 1 || methods[0].FQName != "Example.Services.UserService.FindUser" {
		t.Fatalf("methods = %#v, want Example.Services.UserService.FindUser", methods)
	}
}

func TestResolveRustTypeAndMethod(t *testing.T) {
	t.Parallel()

	workspace := t.TempDir()
	writeAnalyzerFile(t, workspace, "src/user_service.rs", ""+
		"use std::collections::HashMap;\n\n"+
		"pub struct UserService {\n"+
		"    pub cache: HashMap<String, String>,\n"+
		"}\n\n"+
		"impl UserService {\n"+
		"    pub fn find_user(&self, id: &str) -> Option<&String> {\n"+
		"        self.cache.get(id)\n"+
		"    }\n"+
		"}\n")

	service := New(workspace)
	classes := service.ResolveClasses([]string{"UserService"})
	if len(classes) != 1 || classes[0].FQName != "src.user_service.UserService" {
		t.Fatalf("classes = %#v, want src.user_service.UserService", classes)
	}
	methods := service.ResolveMethods([]string{"find_user"})
	if len(methods) != 1 || methods[0].FQName != "src.user_service.UserService.find_user" {
		t.Fatalf("methods = %#v, want src.user_service.UserService.find_user", methods)
	}
}

func TestResolvePHPClassAndMethod(t *testing.T) {
	t.Parallel()

	workspace := t.TempDir()
	writeAnalyzerFile(t, workspace, "src/UserService.php", ""+
		"<?php\n"+
		"namespace Example\\Services;\n\n"+
		"use Example\\Models\\User;\n\n"+
		"class UserService {\n"+
		"    private $cache;\n"+
		"    public function findUser($id) {\n"+
		"        return $id;\n"+
		"    }\n"+
		"}\n")

	service := New(workspace)
	classes := service.ResolveClasses([]string{"UserService"})
	if len(classes) != 1 || classes[0].FQName != "Example.Services.UserService" {
		t.Fatalf("classes = %#v, want Example.Services.UserService", classes)
	}
	methods := service.ResolveMethods([]string{"findUser"})
	if len(methods) != 1 || methods[0].FQName != "Example.Services.UserService.findUser" {
		t.Fatalf("methods = %#v, want Example.Services.UserService.findUser", methods)
	}
}

func TestResolveScalaClassAndMethod(t *testing.T) {
	t.Parallel()

	workspace := t.TempDir()
	writeAnalyzerFile(t, workspace, "src/UserService.scala", ""+
		"package example.service\n\n"+
		"import scala.collection.mutable.Map\n\n"+
		"class UserService {\n"+
		"  private val cache = Map.empty[String, String]\n"+
		"  def findUser(id: String) = cache.get(id)\n"+
		"}\n")

	service := New(workspace)
	classes := service.ResolveClasses([]string{"UserService"})
	if len(classes) != 1 || classes[0].FQName != "example.service.UserService" {
		t.Fatalf("classes = %#v, want example.service.UserService", classes)
	}
	methods := service.ResolveMethods([]string{"findUser"})
	if len(methods) != 1 || methods[0].FQName != "example.service.UserService.findUser" {
		t.Fatalf("methods = %#v, want example.service.UserService.findUser", methods)
	}
}

func TestResolveSQLDefinitions(t *testing.T) {
	t.Parallel()

	workspace := t.TempDir()
	writeAnalyzerFile(t, workspace, "db/schema.sql", ""+
		"create table users (\n"+
		"  id integer primary key\n"+
		");\n\n"+
		"create function find_user(user_id integer) returns integer as $$\n"+
		"begin\n"+
		"  return user_id;\n"+
		"end;\n"+
		"$$ language plpgsql;\n")

	service := New(workspace)
	classes := service.ResolveClasses([]string{"users"})
	if len(classes) != 1 || classes[0].FQName != "db.schema.users" {
		t.Fatalf("classes = %#v, want db.schema.users", classes)
	}
	methods := service.ResolveMethods([]string{"find_user"})
	if len(methods) != 1 || methods[0].FQName != "db.schema.find_user" {
		t.Fatalf("methods = %#v, want db.schema.find_user", methods)
	}
}

func TestRenderDefinitionGroupCombinesJavaMethodOverloads(t *testing.T) {
	t.Parallel()

	workspace := t.TempDir()
	writeAnalyzerFile(t, workspace, "src/main/java/com/example/service/UserService.java", ""+
		"package com.example.service;\n\n"+
		"import java.util.List;\n\n"+
		"public class UserService {\n"+
		"    public String findUserById(String id) {\n"+
		"        return id;\n"+
		"    }\n\n"+
		"    public String findUserById(long id) {\n"+
		"        return Long.toString(id);\n"+
		"    }\n"+
		"}\n")

	service := New(workspace)
	group, ok := service.DefinitionGroup("com.example.service.UserService.findUserById", "function")
	if !ok {
		t.Fatal("DefinitionGroup() = false, want true")
	}
	if len(group.Symbols) != 2 {
		t.Fatalf("len(group.Symbols) = %d, want 2", len(group.Symbols))
	}

	rendered := service.RenderDefinitionGroup(group)
	if strings.Count(rendered, "public String findUserById") != 2 {
		t.Fatalf("rendered = %q, want both overloads", rendered)
	}
	if strings.Count(rendered, "<methods class=\"com.example.service.UserService\"") != 1 {
		t.Fatalf("rendered = %q, want one grouped methods block", rendered)
	}
}

func TestCompleteSymbolsPrefersClassThenShallowerPackage(t *testing.T) {
	t.Parallel()

	workspace := t.TempDir()
	writeAnalyzerFile(t, workspace, "src/main/java/com/example/UserService.java", ""+
		"package com.example;\n\n"+
		"public class UserService {\n"+
		"    public String userService() {\n"+
		"        return \"ok\";\n"+
		"    }\n"+
		"}\n")
	writeAnalyzerFile(t, workspace, "src/main/java/com/example/deep/UserService.java", ""+
		"package com.example.deep;\n\n"+
		"public class UserService {\n"+
		"}\n")

	service := New(workspace)
	completions := service.CompleteSymbols("UserService", 10)
	if len(completions) < 3 {
		t.Fatalf("len(completions) = %d, want at least 3", len(completions))
	}
	if completions[0].Type != "class" || completions[0].Detail != "com.example.UserService" {
		t.Fatalf("completions[0] = %#v, want shallower class first", completions[0])
	}
	if completions[1].Type != "class" || completions[1].Detail != "com.example.deep.UserService" {
		t.Fatalf("completions[1] = %#v, want deeper class second", completions[1])
	}
	if completions[2].Type != "function" || completions[2].Detail != "com.example.UserService.UserService" {
		t.Fatalf("completions[2] = %#v, want constructor after class candidates", completions[2])
	}
	if len(completions) < 5 {
		t.Fatalf("len(completions) = %d, want at least 5", len(completions))
	}
	if completions[3].Type != "function" || completions[3].Detail != "com.example.UserService.userService" {
		t.Fatalf("completions[3] = %#v, want method after shallower constructor", completions[3])
	}
	if completions[4].Type != "function" || completions[4].Detail != "com.example.deep.UserService.UserService" {
		t.Fatalf("completions[4] = %#v, want deeper constructor after shallower method", completions[4])
	}
}

func TestCompleteSymbolsPrefersExactOverPrefixAndSubstring(t *testing.T) {
	t.Parallel()

	workspace := t.TempDir()
	writeAnalyzerFile(t, workspace, "src/main/java/com/example/User.java", ""+
		"package com.example;\n\n"+
		"public class User {\n"+
		"}\n")
	writeAnalyzerFile(t, workspace, "src/main/java/com/example/UserService.java", ""+
		"package com.example;\n\n"+
		"public class UserService {\n"+
		"}\n")
	writeAnalyzerFile(t, workspace, "src/main/java/com/example/GetUser.java", ""+
		"package com.example;\n\n"+
		"public class GetUser {\n"+
		"}\n")

	service := New(workspace)
	completions := service.CompleteSymbols("User", 10)
	if len(completions) < 3 {
		t.Fatalf("len(completions) = %d, want at least 3", len(completions))
	}
	if completions[0].Detail != "com.example.User" {
		t.Fatalf("completions[0] = %#v, want exact match first", completions[0])
	}
	if completions[1].Detail != "com.example.UserService" {
		t.Fatalf("completions[1] = %#v, want prefix match second", completions[1])
	}
	if completions[2].Detail != "com.example.GetUser" {
		t.Fatalf("completions[2] = %#v, want substring match after prefix", completions[2])
	}
}

func TestCompleteSymbolsPrefersCamelHumpWordStarts(t *testing.T) {
	t.Parallel()

	workspace := t.TempDir()
	writeAnalyzerFile(t, workspace, "src/main/java/com/example/UserService.java", ""+
		"package com.example;\n\n"+
		"public class UserService {\n"+
		"}\n")
	writeAnalyzerFile(t, workspace, "src/main/java/com/example/UsableState.java", ""+
		"package com.example;\n\n"+
		"public class UsableState {\n"+
		"}\n")

	service := New(workspace)
	completions := service.CompleteSymbols("US", 10)
	if len(completions) < 2 {
		t.Fatalf("len(completions) = %d, want at least 2", len(completions))
	}
	if completions[0].Detail != "com.example.UserService" {
		t.Fatalf("completions[0] = %#v, want camel-hump UserService first", completions[0])
	}
}

func writeAnalyzerFile(t *testing.T, workspace string, relativePath string, content string) {
	t.Helper()
	absolutePath := filepath.Join(workspace, filepath.FromSlash(relativePath))
	if err := os.MkdirAll(filepath.Dir(absolutePath), 0o755); err != nil {
		t.Fatalf("os.MkdirAll() error = %v", err)
	}
	if err := os.WriteFile(absolutePath, []byte(content), 0o644); err != nil {
		t.Fatalf("os.WriteFile() error = %v", err)
	}
}
