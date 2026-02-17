package ai.brokk.analyzer.java;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.usages.UsageHit;
import ai.brokk.project.IProject;
import java.util.*;
import java.util.stream.Collectors;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.FileASTRequestor;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Uses Eclipse JDT to perform precise usage analysis for Java code.
 */
public class JdtUsageAnalyzer {
    private static final Logger log = LoggerFactory.getLogger(JdtUsageAnalyzer.class);

    /**
     * Extracts a method signature from an IMethodBinding.
     * Package-private for testing.
     */
    static String extractMethodSignature(IMethodBinding mb) {
        ITypeBinding[] paramTypes = mb.getParameterTypes();
        if (paramTypes.length == 0) {
            return "()";
        }
        return Arrays.stream(paramTypes).map(t -> t.getErasure().getName()).collect(Collectors.joining(", ", "(", ")"));
    }

    /**
     * Parses Java source code and extracts method FQ names with signatures.
     * Intended for testing signature consistency with other analyzers.
     */
    public static Set<String> extractMethodSignatures(String sourceCode, IProject project) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(sourceCode.toCharArray());
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setUnitName("Test.java");

        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.latestSupportedJavaVersion(), options);
        parser.setCompilerOptions(options);

        String[] sourceRoots = inferSourceRoots(project);
        parser.setEnvironment(new String[0], sourceRoots, null, true);

        CompilationUnit cu = (CompilationUnit) parser.createAST(null);
        Set<String> signatures = new HashSet<>();

        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                IMethodBinding mb = node.resolveBinding();
                if (mb != null) {
                    String fqn = getFqn(mb);
                    String sig = extractMethodSignature(mb);
                    signatures.add(fqn + sig);
                }
                return super.visit(node);
            }
        });

        return signatures;
    }

    static String getFqn(IBinding binding) {
        switch (binding) {
            case ITypeBinding tb -> {
                return tb.getErasure().getQualifiedName().replace('$', '.');
            }
            case IMethodBinding mb -> {
                IMethodBinding decl = mb.getMethodDeclaration();
                ITypeBinding declaringClass = decl.getDeclaringClass();
                String typeFqn = declaringClass != null ? getFqn(declaringClass) : "unknown";
                String name = decl.isConstructor()
                        ? (declaringClass != null ? declaringClass.getName() : "unknown")
                        : decl.getName();
                return typeFqn + "." + name;
            }
            case IVariableBinding vb -> {
                ITypeBinding owner = vb.getDeclaringClass();
                String parent = owner != null ? getFqn(owner) : "unknown";
                return parent + "." + vb.getName();
            }
            default -> {}
        }
        return binding.getName();
    }

    /**
     * Finds precise usages of a target CodeUnit within a set of candidate files.
     */
    public static Set<UsageHit> findUsages(CodeUnit target, Set<ProjectFile> candidateFiles, IProject project) {
        if (candidateFiles.isEmpty()) {
            return Collections.emptySet();
        }

        String[] sourceRoots = inferSourceRoots(project);
        String[] classpath = new String[0];

        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);

        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.latestSupportedJavaVersion(), options);
        parser.setCompilerOptions(options);

        parser.setEnvironment(classpath, sourceRoots, null, true);

        String[] sourceFiles =
                candidateFiles.stream().map(pf -> pf.absPath().toString()).toArray(String[]::new);

        UsageCollector collector = new UsageCollector(target, candidateFiles);

        try {
            parser.createASTs(sourceFiles, null, new String[0], collector, null);
        } catch (Exception e) {
            log.error("JDT analysis failed for {}", target.fqName(), e);
        }

        return collector.getHits();
    }

    private static String[] inferSourceRoots(IProject project) {
        // We include the project root to ensure we don't filter out any source files or test directories,
        // regardless of layout.
        Set<String> roots = new HashSet<>();
        roots.add(project.getRoot().toAbsolutePath().toString());

        // We also add standard directory heuristics. While the root covers everything,
        // explicitly providing known source roots can help JDT's resolution performance
        // and package name mapping in some nested scenarios.
        String[] standardPaths = {"src/main/java", "src/test/java", "src/main/kotlin", "src/test/kotlin"};

        for (String path : standardPaths) {
            java.nio.file.Path sourcePath = project.getRoot().resolve(path);
            if (java.nio.file.Files.exists(sourcePath)) {
                roots.add(sourcePath.toAbsolutePath().toString());
            }
        }

        return roots.toArray(String[]::new);
    }

    private static class UsageCollector extends FileASTRequestor {
        private final CodeUnit target;
        private final Map<String, ProjectFile> pathToFile;
        private final Set<UsageHit> hits = Collections.synchronizedSet(new HashSet<>());

        UsageCollector(CodeUnit target, Set<ProjectFile> candidateFiles) {
            this.target = target;
            this.pathToFile = candidateFiles.stream()
                    .collect(Collectors.toMap(pf -> pf.absPath().toString(), pf -> pf));
        }

        public Set<UsageHit> getHits() {
            return hits;
        }

        @Override
        public void acceptAST(String sourceFilePath, CompilationUnit ast) {
            ProjectFile file = pathToFile.get(sourceFilePath);
            if (file == null) return;

            String content = file.read().orElse("");
            if (content.isEmpty()) return;

            String[] lines = content.split("\\R", -1);

            ast.accept(new ASTVisitor() {
                @Override
                public boolean visit(SimpleName node) {
                    if (node.isDeclaration()) return true;

                    ASTNode parent = node.getParent();
                    if (parent instanceof MethodInvocation mi && Objects.equals(mi.getName(), node)) return true;
                    if (parent instanceof MethodDeclaration md && Objects.equals(md.getName(), node)) return true;

                    ASTNode walk = node;
                    while (walk != null) {
                        if (walk instanceof ImportDeclaration) return false;
                        walk = walk.getParent();
                    }

                    checkBinding(node.resolveBinding(), node);
                    return true;
                }

                @Override
                public boolean visit(MethodInvocation node) {
                    checkBinding(node.resolveMethodBinding(), node);
                    return true;
                }

                @Override
                public boolean visit(ClassInstanceCreation node) {
                    checkBinding(node.resolveConstructorBinding(), node);
                    return true;
                }

                @Override
                public boolean visit(SimpleType node) {
                    checkBinding(node.resolveBinding(), node);
                    return false; // Prevent visiting children (SimpleName) to avoid duplicates
                }

                private void checkBinding(@Nullable IBinding binding, ASTNode node) {
                    if (binding == null) return;

                    String fqn = JdtUsageAnalyzer.getFqn(binding);
                    if (target.fqName().equals(fqn)) {
                        if (target.hasSignature() && binding instanceof IMethodBinding mb) {
                            String foundSig = JdtUsageAnalyzer.extractMethodSignature(mb);
                            if (!Objects.equals(target.signature(), foundSig)) {
                                log.debug(
                                        "Signature Mismatch for {}: Target='{}' vs Found='{}'",
                                        fqn,
                                        target.signature(),
                                        foundSig);
                                return;
                            }
                        }
                        recordHit(node);
                    }
                }

                private void recordHit(ASTNode node) {
                    CodeUnit enclosing = resolveEnclosing(node);
                    if (enclosing == null) return;

                    int start = node.getStartPosition();
                    int end = start + node.getLength();
                    int lineIdx = ast.getLineNumber(start) - 1;

                    int startLine = Math.max(0, lineIdx - 3);
                    int endLine = Math.min(lines.length - 1, lineIdx + 3);
                    StringBuilder snippet = new StringBuilder();
                    for (int i = startLine; i <= endLine; i++) {
                        snippet.append(lines[i]);
                        if (i < endLine) snippet.append("\n");
                    }

                    hits.add(new UsageHit(file, lineIdx + 1, start, end, enclosing, 1.0, snippet.toString()));
                }

                private @Nullable CodeUnit resolveEnclosing(ASTNode node) {
                    ASTNode current = node.getParent();
                    while (current != null) {
                        if (current instanceof MethodDeclaration md) {
                            IMethodBinding b = md.resolveBinding();
                            if (b != null) return createCodeUnit(b, CodeUnitType.FUNCTION);
                        } else if (current instanceof VariableDeclarationFragment vdf) {
                            ASTNode parent = vdf.getParent();
                            if (parent instanceof FieldDeclaration) {
                                IVariableBinding vb = vdf.resolveBinding();
                                if (vb != null) return createCodeUnit(vb, CodeUnitType.FIELD);
                            }
                        } else if (current instanceof FieldDeclaration fd) {
                            // If the usage is attached to the FieldDeclaration directly (like the Type),
                            // and there is exactly one fragment, we can attribute it to that field.
                            if (fd.fragments().size() == 1) {
                                Object frag = fd.fragments().get(0);
                                if (frag instanceof VariableDeclarationFragment vdf) {
                                    IVariableBinding vb = vdf.resolveBinding();
                                    if (vb != null) return createCodeUnit(vb, CodeUnitType.FIELD);
                                }
                            }
                        } else if (current instanceof TypeDeclaration td) {
                            ITypeBinding b = td.resolveBinding();
                            if (b != null) return createCodeUnit(b, CodeUnitType.CLASS);
                        }
                        current = current.getParent();
                    }
                    return null;
                }

                private CodeUnit createCodeUnit(IBinding binding, CodeUnitType type) {
                    String fqn = JdtUsageAnalyzer.getFqn(binding);
                    String pkg = "";
                    String name = fqn;
                    int lastDot = fqn.lastIndexOf('.');
                    if (lastDot > 0) {
                        pkg = fqn.substring(0, lastDot);
                        name = fqn.substring(lastDot + 1);
                    }

                    // Extract signature for methods
                    String signature = null;
                    if (type == CodeUnitType.FUNCTION && binding instanceof IMethodBinding mb) {
                        signature = JdtUsageAnalyzer.extractMethodSignature(mb);
                    }

                    return new CodeUnit(file, type, pkg, name, signature);
                }
            });
        }
    }
}
