package it.robyjava.odindoc;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import it.robyjava.odindoc.Model.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Analizza i sorgenti Java: classi, metodi, SQL passato a JDBC, chiamate tra metodi, punti di ingresso. */
final class JavaAnalyzer {

    private static final Set<String> STRINGY = new HashSet<>(Arrays.asList("String", "StringBuilder", "StringBuffer", "var", "CharSequence"));
    private static final Set<String> SKIP_ARG_CALLS = new HashSet<>(Arrays.asList(
            "append", "insert", "concat", "equals", "equalsIgnoreCase", "contains", "startsWith", "endsWith", "indexOf",
            "replace", "replaceAll", "debug", "info", "warn", "error", "trace", "fine", "finer", "finest", "severe",
            "warning", "log", "println", "print", "printf", "format", "matches", "split"));
    private static final Set<String> PASS_THROUGH = new HashSet<>(Arrays.asList(
            "toString", "trim", "strip", "toUpperCase", "toLowerCase", "intern", "formatted", "replace", "replaceAll"));
    private static final Set<String> ENTRY_ANNOTATIONS = new HashSet<>(Arrays.asList(
            "GET", "POST", "PUT", "DELETE", "PATCH", "Path", "RequestMapping", "GetMapping", "PostMapping", "PutMapping",
            "DeleteMapping", "PatchMapping", "Scheduled", "Schedule", "WebMethod", "EventListener", "JmsListener", "KafkaListener"));
    private static final Set<String> SERVLET_METHODS = new HashSet<>(Arrays.asList("doGet", "doPost", "doPut", "doDelete", "service"));

    private final Schema schema;
    private final Project project = new Project();
    /** "Classe.CAMPO" -> espressione di inizializzazione */
    private final Map<String, Expression> constExpr = new HashMap<>();
    private final Map<String, String> constCache = new HashMap<>();
    private final Map<String, List<String>> constBySimpleName = new HashMap<>();
    private final Set<String> evaluating = new HashSet<>();
    private final Map<String, String> typeSuper = new HashMap<>();

    JavaAnalyzer(Schema schema) {
        this.schema = schema;
    }

    Project analyze(List<Path> roots, boolean includeTests) throws IOException {
        List<Path> files = Util.files(roots, Collections.singleton("java"), includeTests);
        project.files = files.size();
        ParserConfiguration cfg = new ParserConfiguration();
        cfg.setLanguageLevel(ParserConfiguration.LanguageLevel.RAW);
        JavaParser parser = new JavaParser(cfg);

        List<CompilationUnit> units = new ArrayList<>();
        List<Path> unitFiles = new ArrayList<>();
        List<String> unitSrc = new ArrayList<>();
        for (Path f : files) {
            String src = Util.read(f);
            ParseResult<CompilationUnit> r = parser.parse(src);
            if (r.getResult().isPresent()) {
                units.add(r.getResult().get());
                unitFiles.add(f);
                unitSrc.add(src);
                if (!r.isSuccessful()) project.parseErrors.add(f.getFileName() + ": letto con errori (" + r.getProblems().size() + " problemi)");
            } else {
                project.parseErrors.add(f.getFileName() + ": non leggibile");
            }
        }
        // passo 1: costanti
        for (CompilationUnit cu : units) {
            for (TypeDeclaration<?> td : cu.findAll(TypeDeclaration.class)) {
                String tn = td.getNameAsString();
                if (td instanceof ClassOrInterfaceDeclaration) {
                    ClassOrInterfaceDeclaration c = (ClassOrInterfaceDeclaration) td;
                    if (!c.getExtendedTypes().isEmpty()) typeSuper.put(tn, c.getExtendedTypes().get(0).getNameAsString());
                }
                for (FieldDeclaration fd : td.getFields()) {
                    for (VariableDeclarator vd : fd.getVariables()) {
                        if (!vd.getInitializer().isPresent()) continue;
                        String key = tn + "." + vd.getNameAsString();
                        constExpr.put(key, vd.getInitializer().get());
                        constBySimpleName.computeIfAbsent(vd.getNameAsString(), k -> new ArrayList<>()).add(key);
                    }
                }
            }
        }
        // passo 2: classi e metodi
        for (int i = 0; i < units.size(); i++) {
            CompilationUnit cu = units.get(i);
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            for (TypeDeclaration<?> td : cu.findAll(TypeDeclaration.class)) {
                if (!(td instanceof ClassOrInterfaceDeclaration) && !(td instanceof EnumDeclaration)) continue;
                if (td.getParentNode().isPresent() && td.getParentNode().get() instanceof ObjectCreationExpr) continue;
                ClassInfo ci = classInfo(td, pkg, unitFiles.get(i), unitSrc.get(i));
                project.classes.put(ci.fqcn, ci);
            }
        }
        resolveCalls();
        return project;
    }

    // ------------------------------------------------------------ classi

    private ClassInfo classInfo(TypeDeclaration<?> td, String pkg, Path file, String src) {
        ClassInfo ci = new ClassInfo();
        ci.pkg = pkg;
        ci.name = td.getNameAsString();
        StringBuilder outer = new StringBuilder();
        Optional<Node> p = td.getParentNode();
        while (p.isPresent()) {
            if (p.get() instanceof TypeDeclaration) outer.insert(0, ((TypeDeclaration<?>) p.get()).getNameAsString() + ".");
            p = p.get().getParentNode();
        }
        ci.fqcn = (pkg.isEmpty() ? "" : pkg + ".") + outer + ci.name;
        ci.file = file.toString();
        ci.lines = td.getRange().map(r -> r.end.line - r.begin.line + 1).orElse(0);
        ci.javadoc = td.getJavadoc().map(j -> Util.javadoc(j.getDescription().toText())).orElse(null);
        if (td instanceof EnumDeclaration) ci.kind = "enum";
        else {
            ClassOrInterfaceDeclaration c = (ClassOrInterfaceDeclaration) td;
            ci.kind = c.isInterface() ? "interfaccia" : c.isAbstract() ? "classe astratta" : "classe";
            if (!c.getExtendedTypes().isEmpty()) {
                if (c.isInterface()) c.getExtendedTypes().forEach(t -> ci.interfaces.add(t.getNameAsString()));
                else ci.superName = c.getExtendedTypes().get(0).getNameAsString();
            }
            c.getImplementedTypes().forEach(t -> ci.interfaces.add(t.getNameAsString()));
        }
        td.getAnnotations().forEach(a -> ci.annotations.add(a.getNameAsString()));
        for (FieldDeclaration fd : td.getFields())
            for (VariableDeclarator vd : fd.getVariables()) ci.fieldTypes.put(vd.getNameAsString(), simpleType(vd.getType().asString()));

        Node clean = td.clone();
        for (Comment c : clean.getAllContainedComments()) c.remove();
        clean.removeComment();
        // testo originale della classe (con i suoi numeri di riga), non quello riformattato
        String[] lines = src.split("\r?\n", -1);
        ci.source = td.getRange().map(r -> {
            StringBuilder sb = new StringBuilder();
            for (int k = Math.max(0, r.begin.line - 1); k < Math.min(lines.length, r.end.line); k++) sb.append(lines[k]).append('\n');
            return sb.toString();
        }).orElse(td.toString());
        ci.hash = Util.hash(clean.toString());

        for (BodyDeclaration<?> bd : td.getMembers()) {
            if (bd instanceof MethodDeclaration || bd instanceof ConstructorDeclaration) {
                ci.methods.add(method((CallableDeclaration<?>) bd, ci));
            }
        }
        return ci;
    }

    private MethodInfo method(CallableDeclaration<?> cd, ClassInfo ci) {
        MethodInfo m = new MethodInfo();
        m.owner = ci;
        m.name = cd instanceof ConstructorDeclaration ? "<costruttore>" : cd.getNameAsString();
        m.argc = cd.getParameters().size();
        m.signature = Util.collapse(cd.getDeclarationAsString(true, false, true));
        m.javadoc = cd.getJavadoc().map(j -> Util.javadoc(j.getDescription().toText())).orElse(null);
        m.line = cd.getRange().map(r -> r.begin.line).orElse(0);
        m.lines = cd.getRange().map(r -> r.end.line - r.begin.line + 1).orElse(0);
        m.entryKind = entryKind(cd, ci);

        BlockStmt body = null;
        if (cd instanceof MethodDeclaration) body = ((MethodDeclaration) cd).getBody().orElse(null);
        else body = ((ConstructorDeclaration) cd).getBody();
        if (body == null) return m;

        Ctx ctx = new Ctx(ci);
        for (Parameter p : cd.getParameters()) ctx.types.put(p.getNameAsString(), simpleType(p.getType().asString()));

        for (Node n : body.findAll(Node.class)) {
            if (n instanceof VariableDeclarator) {
                VariableDeclarator vd = (VariableDeclarator) n;
                String type = simpleType(vd.getType().asString());
                ctx.types.put(vd.getNameAsString(), type);
                if (STRINGY.contains(type)) {
                    String v = vd.getInitializer().map(e -> eval(e, ctx)).orElse("");
                    if (type.equals("var") && vd.getInitializer().isPresent() && v.equals(String.valueOf(Util.DYN))) continue;
                    ctx.vars.put(vd.getNameAsString(), v);
                }
            } else if (n instanceof AssignExpr) {
                AssignExpr ae = (AssignExpr) n;
                if (!(ae.getTarget() instanceof NameExpr)) continue;
                String var = ((NameExpr) ae.getTarget()).getNameAsString();
                if (!ctx.vars.containsKey(var)) continue;
                if (ae.getOperator() == AssignExpr.Operator.ASSIGN) {
                    ctx.flush(var);
                    ctx.vars.put(var, eval(ae.getValue(), ctx));
                } else if (ae.getOperator() == AssignExpr.Operator.PLUS) {
                    ctx.vars.put(var, ctx.vars.get(var) + eval(ae.getValue(), ctx));
                }
            } else if (n instanceof MethodCallExpr) {
                MethodCallExpr mc = (MethodCallExpr) n;
                String name = mc.getNameAsString();
                m.rawCalls.add(new Model.RawCall(name, mc.getArguments().size(), typeHint(mc, ctx)));
                if (name.equals("append") && isOutermostAppend(mc)) {
                    Expression root = mc;
                    Deque<Expression> args = new ArrayDeque<>();
                    while (root instanceof MethodCallExpr && ((MethodCallExpr) root).getNameAsString().equals("append")) {
                        MethodCallExpr a = (MethodCallExpr) root;
                        if (a.getArguments().size() == 1) args.push(a.getArgument(0));
                        if (!a.getScope().isPresent()) break;
                        root = a.getScope().get();
                    }
                    if (root instanceof NameExpr && ctx.vars.containsKey(((NameExpr) root).getNameAsString())) {
                        String var = ((NameExpr) root).getNameAsString();
                        StringBuilder sb = new StringBuilder(ctx.vars.get(var));
                        for (Expression a : args) sb.append(eval(a, ctx));
                        ctx.vars.put(var, sb.toString());
                    }
                } else if (name.equals("setLength") && mc.getScope().isPresent() && mc.getScope().get() instanceof NameExpr) {
                    String var = ((NameExpr) mc.getScope().get()).getNameAsString();
                    if (ctx.vars.containsKey(var)) { ctx.flush(var); ctx.vars.put(var, ""); }
                } else if (!SKIP_ARG_CALLS.contains(name)) {
                    for (Expression a : mc.getArguments()) ctx.record(eval(a, ctx), a);
                }
            } else if (n instanceof ReturnStmt) {
                ((ReturnStmt) n).getExpression().ifPresent(e -> ctx.record(eval(e, ctx), e));
            }
        }
        for (String var : ctx.vars.keySet()) ctx.flush(var);

        // pulizia: niente duplicati, niente pezzi intermedi che sono prefisso di una query più completa
        List<String[]> cands = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String[] c : ctx.found) if (seen.add(Util.collapse(c[0]))) cands.add(c);
        for (String[] c : cands) {
            String norm = Util.collapse(c[0]);
            boolean prefix = false;
            for (String[] o : cands) {
                String on = Util.collapse(o[0]);
                if (on.length() > norm.length() && on.startsWith(norm)) { prefix = true; break; }
            }
            if (prefix) continue;
            SqlUse u = SqlAnalyzer.analyze(c[0], schema);
            u.line = Integer.parseInt(c[1]);
            m.sql.add(u);
        }
        return m;
    }

    private static boolean isOutermostAppend(MethodCallExpr mc) {
        Optional<Node> p = mc.getParentNode();
        if (p.isPresent() && p.get() instanceof MethodCallExpr) {
            MethodCallExpr parent = (MethodCallExpr) p.get();
            return !(parent.getNameAsString().equals("append") && parent.getScope().isPresent() && parent.getScope().get() == mc);
        }
        return true;
    }

    private String typeHint(MethodCallExpr mc, Ctx ctx) {
        if (!mc.getScope().isPresent()) return "this";
        Expression s = mc.getScope().get();
        if (s instanceof ThisExpr) return "this";
        if (s instanceof SuperExpr) return "super";
        if (s instanceof NameExpr) {
            String n = ((NameExpr) s).getNameAsString();
            if (ctx.types.containsKey(n)) return ctx.types.get(n);
            if (ctx.cls.fieldTypes.containsKey(n)) return ctx.cls.fieldTypes.get(n);
            if (!n.isEmpty() && Character.isUpperCase(n.charAt(0))) return n;
            return null;
        }
        if (s instanceof FieldAccessExpr && ((FieldAccessExpr) s).getScope() instanceof ThisExpr) {
            return ctx.cls.fieldTypes.get(((FieldAccessExpr) s).getNameAsString());
        }
        return null;
    }

    private String entryKind(CallableDeclaration<?> cd, ClassInfo ci) {
        String n = cd.getNameAsString();
        if (cd instanceof MethodDeclaration) {
            MethodDeclaration md = (MethodDeclaration) cd;
            if (n.equals("main") && md.isStatic() && md.getParameters().size() == 1) return "main (avvio da riga di comando)";
            for (AnnotationExpr a : md.getAnnotations()) if (ENTRY_ANNOTATIONS.contains(a.getNameAsString())) return "@" + a.getNameAsString();
            if (SERVLET_METHODS.contains(n) && "HttpServlet".equals(ci.superName)) return "servlet " + n;
            if (n.equals("run") && md.getParameters().isEmpty() && (ci.interfaces.contains("Runnable") || "Thread".equals(ci.superName) || "TimerTask".equals(ci.superName))) return "thread/timer run";
            if (n.equals("execute") && (ci.interfaces.contains("Job") || ci.interfaces.contains("StatefulJob"))) return "job schedulato";
            if (n.equals("call") && ci.interfaces.contains("Callable")) return "callable";
            if (n.equals("onMessage") && ci.interfaces.contains("MessageListener")) return "listener messaggi";
            if ((n.equals("contextInitialized") || n.equals("contextDestroyed")) && ci.interfaces.contains("ServletContextListener")) return "avvio/arresto applicazione web";
        }
        return null;
    }

    // ------------------------------------------------------------ valutazione stringhe

    private final class Ctx {
        final ClassInfo cls;
        final Map<String, String> vars = new LinkedHashMap<>();
        final Map<String, String> types = new HashMap<>();
        final List<String[]> found = new ArrayList<>();
        Node lastNode;

        Ctx(ClassInfo cls) { this.cls = cls; }

        void record(String v, Node at) {
            if (SqlAnalyzer.looksLikeSql(v)) found.add(new String[]{v.trim(), String.valueOf(at.getRange().map(r -> r.begin.line).orElse(0))});
            lastNode = at;
        }

        void flush(String var) {
            String v = vars.get(var);
            if (v != null && SqlAnalyzer.looksLikeSql(v)) found.add(new String[]{v.trim(), lastNode == null ? "0" : String.valueOf(lastNode.getRange().map(r -> r.begin.line).orElse(0))});
        }
    }

    private static final String D = String.valueOf(Util.DYN);

    private String eval(Expression e, Ctx ctx) {
        if (e instanceof StringLiteralExpr) return ((StringLiteralExpr) e).asString();
        if (e.getClass().getSimpleName().equals("TextBlockLiteralExpr")) {
            try { return String.valueOf(e.getClass().getMethod("asString").invoke(e)); }
            catch (Exception ex) { return ((LiteralStringValueExpr) e).getValue(); }
        }
        if (e instanceof CharLiteralExpr) return ((CharLiteralExpr) e).getValue();
        if (e instanceof LiteralStringValueExpr) return ((LiteralStringValueExpr) e).getValue();
        if (e instanceof BooleanLiteralExpr) return String.valueOf(((BooleanLiteralExpr) e).getValue());
        if (e instanceof NullLiteralExpr) return "";
        if (e instanceof EnclosedExpr) return eval(((EnclosedExpr) e).getInner(), ctx);
        if (e instanceof CastExpr) return eval(((CastExpr) e).getExpression(), ctx);
        if (e instanceof ConditionalExpr) return eval(((ConditionalExpr) e).getThenExpr(), ctx);
        if (e instanceof BinaryExpr) {
            BinaryExpr b = (BinaryExpr) e;
            if (b.getOperator() == BinaryExpr.Operator.PLUS) return eval(b.getLeft(), ctx) + eval(b.getRight(), ctx);
            return D;
        }
        if (e instanceof NameExpr) {
            String n = ((NameExpr) e).getNameAsString();
            if (ctx != null && ctx.vars.containsKey(n)) return ctx.vars.get(n);
            if (ctx != null && ctx.types.containsKey(n)) return D; // variabile locale non stringa
            return constant(ctx == null ? null : ctx.cls.name, n);
        }
        if (e instanceof FieldAccessExpr) {
            FieldAccessExpr f = (FieldAccessExpr) e;
            String field = f.getNameAsString();
            if (f.getScope() instanceof ThisExpr) return constant(ctx == null ? null : ctx.cls.name, field);
            String scope = f.getScope().toString();
            int d = scope.lastIndexOf('.');
            if (d >= 0) scope = scope.substring(d + 1);
            String v = constKey(scope + "." + field);
            return v == null ? D : v;
        }
        if (e instanceof MethodCallExpr) {
            MethodCallExpr mc = (MethodCallExpr) e;
            String n = mc.getNameAsString();
            Optional<Expression> sc = mc.getScope();
            if (PASS_THROUGH.contains(n) && sc.isPresent()) return eval(sc.get(), ctx);
            if (n.equals("append") && sc.isPresent() && mc.getArguments().size() == 1) return eval(sc.get(), ctx) + eval(mc.getArgument(0), ctx);
            if (n.equals("concat") && sc.isPresent() && mc.getArguments().size() == 1) return eval(sc.get(), ctx) + eval(mc.getArgument(0), ctx);
            if (n.equals("format") && sc.isPresent() && sc.get().toString().equals("String") && mc.getArguments().size() > 0)
                return eval(mc.getArgument(0), ctx).replaceAll("%[-0-9.]*[sdfx]", D);
            return D;
        }
        if (e instanceof ObjectCreationExpr) {
            ObjectCreationExpr oc = (ObjectCreationExpr) e;
            String t = oc.getType().getNameAsString();
            if (t.equals("StringBuilder") || t.equals("StringBuffer") || t.equals("String")) {
                if (oc.getArguments().isEmpty()) return "";
                Expression a = oc.getArgument(0);
                if (a instanceof IntegerLiteralExpr) return "";
                return eval(a, ctx);
            }
            return D;
        }
        return D;
    }

    /** Risolve una costante per nome semplice partendo dalla classe corrente, poi superclassi, poi nome unico nel progetto. */
    private String constant(String typeName, String name) {
        String t = typeName;
        int guard = 0;
        while (t != null && guard++ < 10) {
            String v = constKey(t + "." + name);
            if (v != null) return v;
            t = typeSuper.get(t);
        }
        List<String> keys = constBySimpleName.get(name);
        if (keys != null && keys.size() == 1) {
            String v = constKey(keys.get(0));
            if (v != null) return v;
        }
        return D;
    }

    private String constKey(String key) {
        if (constCache.containsKey(key)) return constCache.get(key);
        Expression ex = constExpr.get(key);
        if (ex == null || !evaluating.add(key)) return null;
        String v;
        try {
            Ctx c = new Ctx(new ClassInfo());
            c.cls.name = key.substring(0, key.indexOf('.'));
            v = eval(ex, c);
        } finally {
            evaluating.remove(key);
        }
        constCache.put(key, v);
        return v;
    }

    private static String simpleType(String t) {
        int g = t.indexOf('<');
        if (g >= 0) t = t.substring(0, g);
        t = t.replace("[]", "").replace("...", "").trim();
        int d = t.lastIndexOf('.');
        return d >= 0 ? t.substring(d + 1) : t;
    }

    // ------------------------------------------------------------ grafo delle chiamate

    private void resolveCalls() {
        Map<String, List<ClassInfo>> bySimple = new HashMap<>();
        Map<String, List<MethodInfo>> byName = new HashMap<>();
        for (ClassInfo c : project.classes.values()) {
            bySimple.computeIfAbsent(c.name, k -> new ArrayList<>()).add(c);
            for (MethodInfo m : c.methods) byName.computeIfAbsent(m.name + "#" + m.argc, k -> new ArrayList<>()).add(m);
        }
        for (ClassInfo c : project.classes.values()) {
            for (MethodInfo m : c.methods) {
                for (Model.RawCall rc : m.rawCalls) {
                    List<MethodInfo> targets = new ArrayList<>();
                    if ("this".equals(rc.typeHint)) {
                        find(c, rc, bySimple, targets, new HashSet<>(), false);
                    } else if ("super".equals(rc.typeHint)) {
                        if (c.superName != null && bySimple.containsKey(c.superName))
                            for (ClassInfo s : bySimple.get(c.superName)) find(s, rc, bySimple, targets, new HashSet<>(), false);
                    } else if (rc.typeHint != null) {
                        if (bySimple.containsKey(rc.typeHint)) {
                            for (ClassInfo t : bySimple.get(rc.typeHint)) {
                                find(t, rc, bySimple, targets, new HashSet<>(), false);
                                // interfaccia o classe astratta: anche le implementazioni
                                for (ClassInfo impl : project.classes.values())
                                    if (impl.interfaces.contains(t.name) || t.name.equals(impl.superName)) find(impl, rc, bySimple, targets, new HashSet<>(), true);
                            }
                        }
                    } else {
                        List<MethodInfo> l = byName.get(rc.name + "#" + rc.argc);
                        if (l != null && l.size() == 1) targets.add(l.get(0));
                    }
                    for (MethodInfo t : targets) {
                        if (t == m) continue;
                        m.calls.add(t);
                        t.calledBy.add(m);
                    }
                }
            }
        }
    }

    private void find(ClassInfo c, Model.RawCall rc, Map<String, List<ClassInfo>> bySimple, List<MethodInfo> out, Set<ClassInfo> seen, boolean onlyOwn) {
        if (!seen.add(c)) return;
        boolean found = false;
        for (MethodInfo m : c.methods) if (m.name.equals(rc.name) && m.argc == rc.argc) { out.add(m); found = true; }
        if (found || onlyOwn) return;
        if (c.superName != null && bySimple.containsKey(c.superName))
            for (ClassInfo s : bySimple.get(c.superName)) find(s, rc, bySimple, out, seen, false);
    }
}
