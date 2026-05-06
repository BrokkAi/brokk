package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.assertSame;

import ai.brokk.analyzer.IAnalyzer.SourceLookupAlias;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;
import org.junit.jupiter.api.Test;

class RelaxedSourceLookupResolverTest {

    @Test
    void resolvesGoPathQualifiedClassName() {
        var file = projectFile("internal/storage/cache/cache.go");
        var store = CodeUnit.cls(file, "cache", "Store");
        var analyzer = new GoLookupAnalyzer() {
            @Override
            public List<CodeUnit> getAllDeclarations() {
                return List.of(store);
            }

            @Override
            public SequencedSet<CodeUnit> getDefinitions(String fqName) {
                return "cache.Store".equals(fqName) ? new LinkedHashSet<>(List.of(store)) : new LinkedHashSet<>();
            }
        };

        var lookups = RelaxedSourceLookupResolver.resolveLookups(
                analyzer, List.of("internal/storage/cache.Store"), CodeUnit::isClass);

        assertSame(store, lookups.get("internal/storage/cache.Store").codeUnit());
    }

    @Test
    void resolvesGoPathAndReceiverQualifiedMethodName() {
        var file = projectFile("lib/auth/db.go");
        var server = CodeUnit.cls(file, "auth", "Server");
        var generateCert = CodeUnit.fn(file, "auth", "Server.GenerateDatabaseCert");
        var analyzer = goAnalyzerWithMembers(Map.of(server, List.of(generateCert)));

        var lookups = RelaxedSourceLookupResolver.resolveLookups(
                analyzer, List.of("lib/auth.db.(*Server).GenerateDatabaseCert"), CodeUnit::isFunction);

        assertSame(
                generateCert,
                lookups.get("lib/auth.db.(*Server).GenerateDatabaseCert").codeUnit());
    }

    @Test
    void resolvesGoTopLevelFunctionFromPathQualifiedName() {
        var file = projectFile("internal/cmd/grpc.go");
        var newGrpcServer = CodeUnit.fn(file, "cmd", "NewGRPCServer");
        var analyzer = new GoLookupAnalyzer() {
            @Override
            public List<CodeUnit> getAllDeclarations() {
                return List.of(newGrpcServer);
            }
        };

        var lookups = RelaxedSourceLookupResolver.resolveLookups(
                analyzer, List.of("internal/cmd.NewGRPCServer"), CodeUnit::isFunction);

        assertSame(newGrpcServer, lookups.get("internal/cmd.NewGRPCServer").codeUnit());
    }

    @Test
    void resolvesGoReceiverSyntaxWithoutPath() {
        var file = projectFile("server/evaluation.go");
        var server = CodeUnit.cls(file, "server", "Server");
        var evaluate = CodeUnit.fn(file, "server", "Server.Evaluate");
        var analyzer = goAnalyzerWithMembers(Map.of(server, List.of(evaluate)));

        var lookups = RelaxedSourceLookupResolver.resolveLookups(
                analyzer, List.of("server.(*Server).Evaluate"), CodeUnit::isFunction);

        assertSame(evaluate, lookups.get("server.(*Server).Evaluate").codeUnit());
    }

    @Test
    void pathQualifiedGoMethodDisambiguatesDuplicatePackages() {
        var requested = "lib/auth.db.(*Server).GenerateDatabaseCert";
        var libFile = projectFile("lib/auth/db.go");
        var vendorFile = projectFile("vendor/auth/db.go");
        var libGenerateCert = CodeUnit.fn(libFile, "auth", "Server.GenerateDatabaseCert");
        var vendorGenerateCert = CodeUnit.fn(vendorFile, "auth", "Server.GenerateDatabaseCert");
        var analyzer = new GoLookupAnalyzer() {
            @Override
            public SequencedSet<CodeUnit> getDefinitions(String fqName) {
                return "auth.Server.GenerateDatabaseCert".equals(fqName)
                        ? new LinkedHashSet<>(List.of(vendorGenerateCert, libGenerateCert))
                        : new LinkedHashSet<>();
            }
        };

        var lookups = RelaxedSourceLookupResolver.resolveLookups(analyzer, List.of(requested), CodeUnit::isFunction);

        assertSame(libGenerateCert, lookups.get(requested).codeUnit());
    }

    private static IAnalyzer goAnalyzerWithMembers(Map<CodeUnit, List<CodeUnit>> membersByClass) {
        return new GoLookupAnalyzer() {
            @Override
            public List<CodeUnit> getAllDeclarations() {
                return List.copyOf(membersByClass.keySet());
            }

            @Override
            public List<CodeUnit> getMembersInClass(CodeUnit classUnit) {
                return membersByClass.getOrDefault(classUnit, List.of());
            }

            @Override
            public SequencedSet<CodeUnit> getDefinitions(String fqName) {
                var matches = membersByClass.values().stream()
                        .flatMap(Collection::stream)
                        .filter(codeUnit -> codeUnit.fqName().equals(fqName))
                        .toList();
                return new LinkedHashSet<>(matches);
            }
        };
    }

    private static ProjectFile projectFile(String relPath) {
        return new ProjectFile(Path.of("").toAbsolutePath(), relPath);
    }

    private static class GoLookupAnalyzer extends DisabledAnalyzer {
        @Override
        public Collection<SourceLookupAlias> sourceLookupAliases(String requestedName) {
            return switch (requestedName) {
                case "internal/storage/cache.Store" ->
                    List.of(
                            SourceLookupAlias.anySource(requestedName),
                            SourceLookupAlias.sourceDirectory("cache.Store", "internal/storage/cache"));
                case "lib/auth.db.(*Server).GenerateDatabaseCert" ->
                    List.of(
                            SourceLookupAlias.anySource(requestedName),
                            SourceLookupAlias.sourceFile("auth.Server.GenerateDatabaseCert", "lib/auth/db.go"));
                case "internal/cmd.NewGRPCServer" ->
                    List.of(
                            SourceLookupAlias.anySource(requestedName),
                            SourceLookupAlias.sourceDirectory("cmd.NewGRPCServer", "internal/cmd"));
                case "server.(*Server).Evaluate" ->
                    List.of(
                            SourceLookupAlias.anySource(requestedName),
                            SourceLookupAlias.anySource("server.Server.Evaluate"));
                default -> List.of(SourceLookupAlias.anySource(requestedName));
            };
        }
    }
}
