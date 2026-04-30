package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Path;
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
        var analyzer = new DisabledAnalyzer() {
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
        var analyzer = analyzerWithMembers(Map.of(server, List.of(generateCert)));

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
        var analyzer = new DisabledAnalyzer() {
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
        var analyzer = analyzerWithMembers(Map.of(server, List.of(evaluate)));

        var lookups = RelaxedSourceLookupResolver.resolveLookups(
                analyzer, List.of("server.(*Server).Evaluate"), CodeUnit::isFunction);

        assertSame(evaluate, lookups.get("server.(*Server).Evaluate").codeUnit());
    }

    private static IAnalyzer analyzerWithMembers(Map<CodeUnit, List<CodeUnit>> membersByClass) {
        return new DisabledAnalyzer() {
            @Override
            public List<CodeUnit> getAllDeclarations() {
                return List.copyOf(membersByClass.keySet());
            }

            @Override
            public List<CodeUnit> getMembersInClass(CodeUnit classUnit) {
                return membersByClass.getOrDefault(classUnit, List.of());
            }
        };
    }

    private static ProjectFile projectFile(String relPath) {
        return new ProjectFile(Path.of("").toAbsolutePath(), relPath);
    }
}
