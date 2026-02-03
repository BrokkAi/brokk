package ai.brokk.analyzer.java;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.usages.UsageHit;
import ai.brokk.project.IProject;
import ai.brokk.util.FileUtil;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FileASTRequestor;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Uses Eclipse JDT to perform precise usage analysis for Java code.
 */
@NullMarked
public class JdtUsageAnalyzer {
    private static final Logger log = LoggerFactory.getLogger(JdtUsageAnalyzer.class);

    /**
     * Finds precise usages of a target CodeUnit within a set of candidate files.
     */
    public static Set<UsageHit> findUsages(CodeUnit target, Set<ProjectFile> candidateFiles, IProject project) {
        if (candidateFiles.isEmpty()) {
            return Collections.emptySet();
        }

        String[] sourceRoots = inferSourceRoots(project);
        String[] classpath = new String[0]; // TODO: Support library classpath if needed

        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);

        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.VERSION_21, options);
        parser.setCompilerOptions(options);

        parser.setEnvironment(classpath, sourceRoots, null, true);

        String[] sourceFiles =
                candidateFiles.stream().map(pf -> pf.absPath().toString()).toArray(String[]::new);

        UsageCollector collector = new UsageCollector(target, candidateFiles, project);

        try {
            parser.createASTs(sourceFiles, null, new String[0], collector, null);
        } catch (Exception e) {
            log.error("JDT analysis failed for {}", target.fqName(), e);
        }

        return collector.getHits();
    }

    private static String[] inferSourceRoots(IProject project) {
        Set<ProjectFile> allFiles = project.getAllFiles();
        Set<String> roots = new HashSet<>();

        // Standard directory heuristic
        for (ProjectFile pf : allFiles) {
            String path = pf.relPath().toString().replace('\\', '/');
            if (path.contains("src/main/java/")) {
                int idx = path.indexOf("src/main/java/");
                roots.add(project.getRoot().resolve(path.substring(0, idx + 13)).toString());
            } else if (path.contains("src/test/java/")) {
                int idx = path.indexOf("src/test/java/");
                roots.add(project.getRoot().resolve(path.substring(0, idx + 13)).toString());
            }
        }

        // Fallback: use project root if no standard structure detected
        if (roots.isEmpty()) {
            roots.add(project.getRoot().toString());
        }

        return roots.toArray(String[]::new);
    }

    private static class UsageCollector extends FileASTRequestor {
        private final CodeUnit target;
        private final Map<String, ProjectFile> pathToFile;
        private final IProject project;
        private final Set<UsageHit> hits = Collections.synchronizedSet(new HashSet<>());

        UsageCollector(CodeUnit target, Set<ProjectFile> candidateFiles, IProject project) {
            this.target = target;
            this.project = project;
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
            int[] lineStarts = FileUtil.computeLineStarts(content);

            ast.accept(new ASTVisitor() {
                @Override
                public boolean visit(SimpleName node) {
                    if (node.isDeclaration()) return true;

                    // Filter out names that are parts of method invocations to avoid double counting
                    ASTNode parent = node.getParent();
                    if (parent instanceof MethodInvocation mi && mi.getName() == node) return true;
                    if (parent instanceof MethodDeclaration md && md.getName() == node) return true;

                    // Filter imports
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

                private void checkBinding(IBinding binding, ASTNode node) {
                    if (binding == null) return;

                    String key = getDeclarationKey(binding);
                    if (key == null) return;

                    // Match against target. FQNs in CodeUnit use '.' for nesting usually.
                    // Precise matching should ideally use binding keys, but here we check FQN.
                    String fqn = getFqn(binding);
                    if (target.fqName().equals(fqn)) {
                        recordHit(node);
                    }
                }

                private void recordHit(ASTNode node) {
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

                    // Enclosing CodeUnit resolution
                    CodeUnit enclosing = resolveEnclosing(node);

                    hits.add(new UsageHit(file, lineIdx + 1, start, end, enclosing, 1.0, snippet.toString()));
                }

                private CodeUnit resolveEnclosing(ASTNode node) {
                    ASTNode current = node.getParent();
                    while (current != null) {
                        if (current instanceof MethodDeclaration md) {
                            IMethodBinding b = md.resolveBinding();
                            if (b != null) return createCodeUnit(b, CodeUnitType.FUNCTION);
                        } else if (current instanceof TypeDeclaration td) {
                            ITypeBinding b = td.resolveBinding();
                            if (b != null) return createCodeUnit(b, CodeUnitType.CLASS);
                        }
                        current = current.getParent();
                    }
                    // Fallback to first type in file or generic file unit
                    return new CodeUnit(file, CodeUnitType.CLASS, "", "Unknown");
                }

                private CodeUnit createCodeUnit(IBinding binding, CodeUnitType type) {
                    String fqn = getFqn(binding);
                    String pkg = "";
                    String name = fqn;
                    int lastDot = fqn.lastIndexOf('.');
                    if (lastDot > 0) {
                        pkg = fqn.substring(0, lastDot);
                        name = fqn.substring(lastDot + 1);
                    }
                    return new CodeUnit(file, type, pkg, name);
                }

                private String getFqn(IBinding binding) {
                    if (binding instanceof ITypeBinding tb) {
                        return tb.getErasure().getQualifiedName().replace('$', '.');
                    } else if (binding instanceof IMethodBinding mb) {
                        IMethodBinding decl = mb.getMethodDeclaration();
                        String typeFqn = getFqn(decl.getDeclaringClass());
                        String name =
                                decl.isConstructor() ? decl.getDeclaringClass().getName() : decl.getName();
                        return typeFqn + "." + name;
                    } else if (binding instanceof IVariableBinding vb) {
                        ITypeBinding owner = vb.getDeclaringClass();
                        String parent = owner != null ? getFqn(owner) : "unknown";
                        return parent + "." + vb.getName();
                    }
                    return binding.getName();
                }

                private String getDeclarationKey(IBinding binding) {
                    if (binding instanceof IMethodBinding mb)
                        return mb.getMethodDeclaration().getKey();
                    if (binding instanceof ITypeBinding tb)
                        return tb.getTypeDeclaration().getKey();
                    if (binding instanceof IVariableBinding vb)
                        return vb.getVariableDeclaration().getKey();
                    return binding.getKey();
                }
            });
        }
    }
}
