package it.robyjava.odindoc;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import it.robyjava.odindoc.Model.*;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Ricava l'interfaccia utente dal codice: finestre Swing e pagine JSP/HTML,
 * con campi, pulsanti, menu, messaggi e le azioni collegate ai dati.
 */
final class UiAnalyzer {

    private static final Map<String, String> SWING_KIND = new LinkedHashMap<>();
    static {
        SWING_KIND.put("JFrame", "finestra");
        SWING_KIND.put("Frame", "finestra");
        SWING_KIND.put("JWindow", "finestra");
        SWING_KIND.put("JDialog", "finestra di dialogo");
        SWING_KIND.put("Dialog", "finestra di dialogo");
        SWING_KIND.put("JInternalFrame", "finestra interna");
        SWING_KIND.put("JPanel", "pannello");
        SWING_KIND.put("JApplet", "applet");
    }
    private static final Map<String, String> INPUT_KIND = new LinkedHashMap<>();
    static {
        INPUT_KIND.put("JTextField", "testo");
        INPUT_KIND.put("JTextArea", "testo su più righe");
        INPUT_KIND.put("JPasswordField", "password");
        INPUT_KIND.put("JFormattedTextField", "valore");
        INPUT_KIND.put("JComboBox", "elenco a tendina");
        INPUT_KIND.put("JList", "elenco");
        INPUT_KIND.put("JSpinner", "numero");
        INPUT_KIND.put("JCheckBox", "casella di spunta");
        INPUT_KIND.put("JRadioButton", "scelta");
        INPUT_KIND.put("JToggleButton", "interruttore");
        INPUT_KIND.put("JEditorPane", "testo");
        INPUT_KIND.put("JTextPane", "testo");
        INPUT_KIND.put("JSlider", "cursore");
        INPUT_KIND.put("JDateChooser", "data");
        INPUT_KIND.put("JXDatePicker", "data");
        INPUT_KIND.put("JTable", "tabella");
        INPUT_KIND.put("JTree", "albero");
    }
    private static final Set<String> BUTTONS = new HashSet<>(Arrays.asList("JButton"));
    private static final Set<String> MENUS = new HashSet<>(Arrays.asList("JMenu", "JMenuBar", "JPopupMenu"));
    private static final Set<String> MENU_ITEMS = new HashSet<>(Arrays.asList("JMenuItem", "JCheckBoxMenuItem", "JRadioButtonMenuItem"));
    private static final Set<String> WIDGETS = new HashSet<>();
    static {
        WIDGETS.addAll(INPUT_KIND.keySet());
        WIDGETS.addAll(BUTTONS);
        WIDGETS.addAll(MENUS);
        WIDGETS.addAll(MENU_ITEMS);
        WIDGETS.add("JLabel");
    }

    private final JavaAnalyzer ja;
    private final Project p;
    private final Schema schema;

    UiAnalyzer(JavaAnalyzer ja, Schema schema) {
        this.ja = ja;
        this.p = ja.project();
        this.schema = schema;
    }

    // ================================================================ bundle

    /** Carica i testi dei file .properties: nelle cartelle dei sorgenti e nelle "resources" accanto. */
    static Map<String, String> loadBundles(List<Path> srcRoots, List<Path> extra) throws IOException {
        Set<Path> dirs = new LinkedHashSet<>();
        for (Path r : srcRoots) {
            dirs.add(r);
            Path parent = r.toAbsolutePath().getParent();
            if (parent != null && Files.isDirectory(parent.resolve("resources"))) dirs.add(parent.resolve("resources"));
        }
        dirs.addAll(extra);
        List<Path> files = new ArrayList<>();
        for (Path d : dirs) {
            if (!Files.isDirectory(d)) continue;
            try (Stream<Path> s = Files.walk(d)) {
                files.addAll(s.filter(f -> f.toString().endsWith(".properties")).collect(Collectors.toList()));
            }
        }
        // prima i file base, poi quelli in italiano, poi le altre lingue
        files.sort(Comparator.comparingInt(UiAnalyzer::localeRank).thenComparing(Path::toString));
        Map<String, String> out = new HashMap<>();
        for (Path f : files) {
            Properties pr = new Properties();
            try {
                pr.load(new StringReader(Util.read(f)));
            } catch (Exception e) {
                continue;
            }
            for (String k : pr.stringPropertyNames()) out.putIfAbsent(k, pr.getProperty(k));
        }
        return out;
    }

    private static int localeRank(Path f) {
        String n = f.getFileName().toString().replace(".properties", "");
        if (n.matches(".*_it(_IT)?$")) return 1;
        if (n.matches(".*_[a-z]{2}(_[A-Z]{2})?$")) return 2;
        return 0;
    }

    // ================================================================ Swing

    private static final class Widget {
        String type, var, text, tooltip;
        int order;
    }

    void swing() {
        for (Map.Entry<ClassInfo, TypeDeclaration<?>> e : ja.ast.entrySet()) {
            ClassInfo c = e.getKey();
            TypeDeclaration<?> td = e.getValue();
            if (!(td instanceof ClassOrInterfaceDeclaration) || ((ClassOrInterfaceDeclaration) td).isInterface()) continue;
            String base = swingBase(c);
            long widgets = td.findAll(ObjectCreationExpr.class).stream().filter(o -> WIDGETS.contains(o.getType().getNameAsString())).count();
            if (base == null && widgets < 3) continue;
            Screen s = new Screen();
            s.id = "swing/" + c.fqcn;
            s.kind = base != null ? SWING_KIND.get(base) : "componente grafico";
            s.cls = c;
            s.file = c.file;
            s.source = c.source;
            extract(td, c, s);
            if (s.fields.isEmpty() && s.actions.isEmpty() && s.menus.isEmpty() && s.title == null && s.headings.isEmpty()) continue;
            p.screens.put(s.id, s);
        }
        // azioni -> metodi, schermate aperte
        Map<String, Screen> byClass = new HashMap<>();
        for (Screen s : p.screens.values()) if (s.cls != null) byClass.put(s.cls.name, s);
        for (Screen s : p.screens.values()) {
            if (s.cls == null) continue;
            for (UiAction a : s.actions) {
                for (RawCall rc : a.raw) a.targets.addAll(ja.resolve(s.cls, rc));
                Set<String> opened = new TreeSet<>();
                for (String o : a.opens) if (byClass.containsKey(o)) opened.add(byClass.get(o).id);
                a.opens.clear();
                a.opens.addAll(opened);
                s.links.addAll(opened);
            }
            s.hash = screenHash(s);
        }
    }

    private Map<String, ClassInfo> bySimple;

    private String swingBase(ClassInfo c) {
        if (bySimple == null) {
            bySimple = new HashMap<>();
            for (ClassInfo x : p.classes.values()) bySimple.put(x.name, x);
        }
        String sup = c.superName;
        int guard = 0;
        while (sup != null && guard++ < 15) {
            if (SWING_KIND.containsKey(sup)) return sup;
            ClassInfo up = bySimple.get(sup);
            sup = up == null ? null : up.superName;
        }
        return null;
    }

    private void extract(TypeDeclaration<?> td, ClassInfo c, Screen s) {
        Map<String, Widget> w = new LinkedHashMap<>();
        List<Widget> order = new ArrayList<>();
        Map<String, UiAction> byVar = new LinkedHashMap<>();
        Set<String> listensThis = new LinkedHashSet<>();
        Map<String, List<String>> menuChildren = new LinkedHashMap<>();
        boolean hasTable = false;
        int n = 0;
        Map<ObjectCreationExpr, Widget> byNode = new IdentityHashMap<>();
        List<Widget> addOrder = new ArrayList<>();
        List<MethodCallExpr> adds = new ArrayList<>();
        for (Node node : td.findAll(Node.class)) {
            if (node instanceof ObjectCreationExpr) {
                ObjectCreationExpr oc = (ObjectCreationExpr) node;
                String t = oc.getType().getNameAsString();
                if (WIDGETS.contains(t)) {
                    Widget x = new Widget();
                    x.type = t;
                    x.order = n++;
                    x.var = assignedVar(oc);
                    if (x.var == null) x.var = "#" + x.order;
                    if (!oc.getArguments().isEmpty()) x.text = text(oc.getArgument(0), c);
                    if (t.equals("JTable")) hasTable = true;
                    w.put(x.var, x);
                    order.add(x);
                    byNode.put(oc, x);
                } else if ((t.equals("JFrame") || t.equals("JDialog")) && !oc.getArguments().isEmpty() && s.title == null) {
                    for (Expression a : oc.getArguments()) { String v = text(a, c); if (v != null) { s.title = v; break; } }
                } else if (t.equals("TitledBorder") && !oc.getArguments().isEmpty()) {
                    for (Expression a : oc.getArguments()) { String v = text(a, c); if (v != null) { s.headings.add(v); break; } }
                }
            } else if (node instanceof ExplicitConstructorInvocationStmt) {
                ExplicitConstructorInvocationStmt ec = (ExplicitConstructorInvocationStmt) node;
                if (s.title == null) for (Expression a : ec.getArguments()) { String v = text(a, c); if (v != null) { s.title = v; break; } }
            } else if (node instanceof ArrayInitializerExpr) {
                ArrayInitializerExpr ai = (ArrayInitializerExpr) node;
                if (ai.getValues().size() >= 2 && ai.getValues().stream().allMatch(v -> v instanceof StringLiteralExpr || v instanceof MethodCallExpr)) {
                    List<String> cols = new ArrayList<>();
                    for (Expression v : ai.getValues()) { String t = text(v, c); if (t != null) cols.add(t); }
                    if (cols.size() == ai.getValues().size()) s.columns.add(cols);
                }
            } else if (node instanceof MethodCallExpr) {
                MethodCallExpr mc = (MethodCallExpr) node;
                String name = mc.getNameAsString();
                String scope = scopeVar(mc);
                Widget x = scope == null ? null : w.get(scope);
                if (name.equals("add") && !mc.getArguments().isEmpty()) adds.add(mc);
                if ((name.equals("setText") || name.equals("setLabel")) && x != null && mc.getArguments().size() == 1) {
                    String v = text(mc.getArgument(0), c);
                    if (v != null) x.text = v;
                } else if (name.equals("setToolTipText") && x != null && mc.getArguments().size() == 1) {
                    x.tooltip = text(mc.getArgument(0), c);
                } else if (name.equals("setTitle") && (scope == null || scope.equals("this")) && mc.getArguments().size() == 1) {
                    String v = text(mc.getArgument(0), c);
                    if (v != null) s.title = v;
                } else if (name.equals("createTitledBorder") && !mc.getArguments().isEmpty()) {
                    for (Expression a : mc.getArguments()) { String v = text(a, c); if (v != null) { s.headings.add(v); break; } }
                } else if (name.equals("addTab") && !mc.getArguments().isEmpty()) {
                    String v = text(mc.getArgument(0), c);
                    if (v != null) s.headings.add("Scheda: " + v);
                } else if (name.startsWith("show") && name.endsWith("Dialog") && mc.getScope().isPresent() && mc.getScope().get().toString().endsWith("JOptionPane")) {
                    if (mc.getArguments().size() >= 2) {
                        String msg = ja.evalIn(mc.getArgument(1), c);
                        if (msg != null && msg.matches("(?s).*[A-Za-zÀ-ÿ]{3,}.*")) s.messages.add(Util.showSql(Util.collapse(msg)));
                    }
                } else if (name.equals("add") && x != null && (MENUS.contains(x.type)) && mc.getArguments().size() == 1) {
                    String child = argVar(mc.getArgument(0));
                    if (child != null) menuChildren.computeIfAbsent(scope, k -> new ArrayList<>()).add(child);
                } else if (name.equals("setJMenuBar") && mc.getArguments().size() == 1) {
                    String mb = argVar(mc.getArgument(0));
                    if (mb != null) menuChildren.computeIfAbsent("#root", k -> new ArrayList<>()).add(mb);
                } else if (name.equals("addActionListener") && scope != null && mc.getArguments().size() == 1) {
                    Expression h = mc.getArgument(0);
                    if (h instanceof ThisExpr) listensThis.add(scope);
                    else {
                        UiAction a = byVar.computeIfAbsent(scope, k -> new UiAction());
                        a.var = scope;
                        handler(h, a, c);
                    }
                }
            }
        }
        // actionPerformed della finestra stessa, con if (e.getSource() == btn) o getActionCommand
        for (MethodDeclaration md : td.getMethods()) {
            if (!md.getNameAsString().equals("actionPerformed") || md.getParameters().size() != 1 || !md.getBody().isPresent()) continue;
            boolean branched = false;
            for (IfStmt is : md.getBody().get().findAll(IfStmt.class)) {
                String cond = is.getCondition().toString();
                String var = null;
                Matcher m1 = Pattern.compile("getSource\\(\\)\\s*==\\s*(?:this\\.)?(\\w+)|(?:this\\.)?(\\w+)\\s*==\\s*\\w+\\.getSource\\(\\)|getSource\\(\\)\\.equals\\((?:this\\.)?(\\w+)\\)").matcher(cond);
                Matcher m2 = Pattern.compile("\"([^\"]+)\"").matcher(cond);
                if (m1.find()) var = m1.group(1) != null ? m1.group(1) : m1.group(2) != null ? m1.group(2) : m1.group(3);
                else if (cond.contains("ActionCommand") && m2.find()) {
                    String cmd = m2.group(1);
                    var = "#cmd:" + cmd;
                    Widget fake = new Widget();
                    fake.type = "JButton";
                    fake.var = var;
                    fake.text = cmd;
                    fake.order = n++;
                    w.putIfAbsent(var, fake);
                    for (Widget ww : w.values()) if (cmd.equals(ww.text)) { var = ww.var; break; }
                }
                if (var == null) continue;
                branched = true;
                UiAction a = byVar.computeIfAbsent(var, k -> new UiAction());
                a.var = var;
                collect(is.getThenStmt(), a, c);
            }
            if (!branched) {
                for (String v : listensThis) {
                    UiAction a = byVar.computeIfAbsent(v, k -> new UiAction());
                    a.var = v;
                    collect(md.getBody().get(), a, c);
                }
            }
        }
        // ordine in cui i componenti vengono aggiunti allo schermo (più affidabile dell'ordine di creazione)
        for (MethodCallExpr mc : adds) {
            Expression a0 = mc.getArgument(0);
            Widget x = a0 instanceof ObjectCreationExpr ? byNode.get(a0) : w.get(argVar(a0) == null ? "" : argVar(a0));
            if (x != null && !addOrder.contains(x)) addOrder.add(x);
        }
        List<Widget> seq = new ArrayList<>(addOrder);
        for (Widget x : order) if (!seq.contains(x)) seq.add(x);
        // campi: etichetta precedente + campo di input
        String pending = null;
        for (Widget x : seq) {
            if (x.type.equals("JLabel")) { if (x.text != null) pending = x.text; continue; }
            if (!INPUT_KIND.containsKey(x.type)) continue;
            UiField f = new UiField();
            f.kind = INPUT_KIND.get(x.type);
            f.name = x.var.startsWith("#") ? null : x.var;
            boolean ownText = (x.type.equals("JCheckBox") || x.type.equals("JRadioButton") || x.type.equals("JToggleButton")) && x.text != null;
            f.label = ownText ? x.text : pending != null ? pending : x.tooltip != null ? x.tooltip : humanize(x.var);
            if (!ownText) pending = null;
            if (f.label != null) f.label = f.label.replaceAll("[:*]+\\s*$", "").trim();
            if (x.type.equals("JTable")) hasTable = true;
            s.fields.add(f);
        }
        if (!hasTable) s.columns.clear();
        // azioni: pulsanti e voci di menu
        Map<String, MenuNode> nodes = new HashMap<>();
        for (Widget x : order) {
            if (MENUS.contains(x.type) || MENU_ITEMS.contains(x.type)) {
                MenuNode mn = new MenuNode();
                mn.var = x.var;
                mn.label = x.text != null ? x.text : humanize(x.var);
                nodes.put(x.var, mn);
            }
        }
        for (Map.Entry<String, UiAction> e : byVar.entrySet()) {
            UiAction a = e.getValue();
            Widget x = w.get(e.getKey());
            a.label = x != null && x.text != null ? x.text : x != null && x.tooltip != null ? x.tooltip : humanize(e.getKey());
            a.kind = x != null && MENU_ITEMS.contains(x.type) ? "voce di menu" : "pulsante";
            if (nodes.containsKey(e.getKey())) nodes.get(e.getKey()).action = a;
            s.actions.add(a);
        }
        Set<String> isChild = new HashSet<>();
        for (Map.Entry<String, List<String>> e : menuChildren.entrySet()) {
            MenuNode parent = nodes.get(e.getKey());
            for (String ch : e.getValue()) {
                MenuNode child = nodes.get(ch);
                if (child == null) continue;
                if (parent != null) { parent.children.add(child); isChild.add(ch); }
            }
        }
        for (Widget x : order) {
            if (!x.type.equals("JMenu") && !x.type.equals("JMenuBar") && !x.type.equals("JPopupMenu")) continue;
            if (isChild.contains(x.var)) continue;
            MenuNode mn = nodes.get(x.var);
            if (mn == null || mn.children.isEmpty()) continue;
            if (x.type.equals("JMenuBar")) s.menus.addAll(mn.children);
            else s.menus.add(mn);
        }
    }

    private void handler(Expression h, UiAction a, ClassInfo c) {
        if (h instanceof LambdaExpr) collect(((LambdaExpr) h).getBody(), a, c);
        else if (h instanceof ObjectCreationExpr && ((ObjectCreationExpr) h).getAnonymousClassBody().isPresent()) {
            for (BodyDeclaration<?> b : ((ObjectCreationExpr) h).getAnonymousClassBody().get())
                if (b instanceof MethodDeclaration && ((MethodDeclaration) b).getBody().isPresent()) collect(((MethodDeclaration) b).getBody().get(), a, c);
        } else if (h instanceof MethodReferenceExpr) {
            a.raw.add(new RawCall(((MethodReferenceExpr) h).getIdentifier(), -1, "this"));
        } else if (h instanceof ObjectCreationExpr) {
            // listener in una classe a parte: new SalvaListener(...) -> actionPerformed di quella classe
            a.raw.add(new RawCall("actionPerformed", 1, ((ObjectCreationExpr) h).getType().getNameAsString()));
        }
    }

    private void collect(Node body, UiAction a, ClassInfo c) {
        for (MethodCallExpr mc : body.findAll(MethodCallExpr.class)) {
            String n = mc.getNameAsString();
            if (n.equals("dispose") || (n.equals("setVisible") && mc.getArguments().size() == 1 && mc.getArgument(0).toString().equals("false") && !mc.getScope().isPresent()))
                a.closes = true;
            a.raw.add(ja.rawCall(mc, c));
            for (Expression arg : mc.getArguments()) {
                String v = ja.evalIn(arg, c);
                if (SqlAnalyzer.looksLikeSql(v))
                    SqlAnalyzer.analyze(v, schema).tables.forEach((t, o) -> a.directTables.computeIfAbsent(t, k -> new TreeSet<>()).addAll(o));
            }
        }
        for (ObjectCreationExpr oc : body.findAll(ObjectCreationExpr.class)) a.opens.add(oc.getType().getNameAsString());
    }

    private String text(Expression e, ClassInfo c) {
        String v = ja.evalIn(e, c);
        if (v == null || v.indexOf(Util.DYN) >= 0) return null;
        v = Util.collapse(v.replaceAll("<[^>]+>", " "));
        return v.isEmpty() || !v.matches("(?s).*[A-Za-zÀ-ÿ0-9].*") ? null : v;
    }

    private static String assignedVar(Node n) {
        Optional<Node> par = n.getParentNode();
        while (par.isPresent() && (par.get() instanceof CastExpr || par.get() instanceof EnclosedExpr)) par = par.get().getParentNode();
        if (!par.isPresent()) return null;
        Node pn = par.get();
        if (pn instanceof VariableDeclarator) return ((VariableDeclarator) pn).getNameAsString();
        if (pn instanceof AssignExpr) {
            Expression t = ((AssignExpr) pn).getTarget();
            if (t instanceof NameExpr) return ((NameExpr) t).getNameAsString();
            if (t instanceof FieldAccessExpr) return ((FieldAccessExpr) t).getNameAsString();
        }
        return null;
    }

    private static String scopeVar(MethodCallExpr mc) {
        if (!mc.getScope().isPresent()) return null;
        Expression s = mc.getScope().get();
        if (s instanceof ThisExpr) return "this";
        if (s instanceof NameExpr) return ((NameExpr) s).getNameAsString();
        if (s instanceof FieldAccessExpr && ((FieldAccessExpr) s).getScope() instanceof ThisExpr) return ((FieldAccessExpr) s).getNameAsString();
        return null;
    }

    private static String argVar(Expression e) {
        if (e instanceof NameExpr) return ((NameExpr) e).getNameAsString();
        if (e instanceof FieldAccessExpr) return ((FieldAccessExpr) e).getNameAsString();
        return null;
    }

    /** btnSalvaPratica -> "Salva pratica"; jButton1 -> "Pulsante 1". */
    static String humanize(String var) {
        if (var == null || var.startsWith("#")) return null;
        Matcher nb = Pattern.compile("^j(Button|MenuItem|Menu|TextField|ComboBox|CheckBox|Label|Table|List|RadioButton|TextArea)(\\d+)$").matcher(var);
        if (nb.matches()) return "elemento " + nb.group(2);
        String v = var.replaceFirst("^(btn|bt|button|cmd|mi|mnu|menu|item|txt|tf|text|lbl|cb|cbo|combo|chk|ck|rb|opt|tbl|table|lst|list|ta|pwd|dt|spn|jbtn|jtxt|jlbl|jcb|jmi)(?=[A-Z_])", "");
        v = v.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ').trim();
        if (v.isEmpty()) return var;
        return Character.toUpperCase(v.charAt(0)) + v.substring(1).toLowerCase(Locale.ROOT);
    }

    private String screenHash(Screen s) {
        StringBuilder sb = new StringBuilder(s.source == null ? "" : s.source.replaceAll("\\s+", " "));
        Set<String> owners = new TreeSet<>();
        for (UiAction a : s.actions) for (MethodInfo m : DocBuilder.closure(a.targets)) owners.add(m.owner.fqcn + ":" + m.owner.hash);
        sb.append(owners);
        return Util.hash(sb.toString());
    }

    // ================================================================ pagine web

    private static final Pattern ATTR = Pattern.compile("([\\w:.-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");
    private static final Set<String> PAGE_EXT = new HashSet<>(Arrays.asList("jsp", "jspx", "jspf", "html", "htm", "xhtml", "tag"));

    void web(List<Path> configured, List<Path> srcRoots) throws IOException {
        List<Path> roots = new ArrayList<>(configured);
        if (roots.isEmpty()) roots.addAll(autodetectWeb(srcRoots));
        if (roots.isEmpty()) return;
        // mappa url -> metodi Java
        Map<String, List<MethodInfo>> urlMap = new LinkedHashMap<>();
        Map<String, ClassInfo> byFqcn = p.classes;
        List<Path> xmls = new ArrayList<>();
        for (Path r : roots) xmls.addAll(Util.files(Collections.singletonList(r), new HashSet<>(Collections.singletonList("xml")), true));
        for (Path r : srcRoots) {
            Path parent = r.toAbsolutePath().getParent();
            if (parent != null && Files.isDirectory(parent.resolve("resources")))
                xmls.addAll(Util.files(Collections.singletonList(parent.resolve("resources")), new HashSet<>(Collections.singletonList("xml")), true));
        }
        for (Path x : xmls) {
            String fn = x.getFileName().toString();
            String t = Util.read(x);
            if (fn.equals("web.xml")) {
                Map<String, String> nameToClass = new HashMap<>();
                Matcher m = Pattern.compile("<servlet>.*?<servlet-name>\\s*(.*?)\\s*</servlet-name>.*?<servlet-class>\\s*(.*?)\\s*</servlet-class>.*?</servlet>", Pattern.DOTALL).matcher(t);
                while (m.find()) nameToClass.put(m.group(1), m.group(2));
                m = Pattern.compile("<servlet-mapping>.*?<servlet-name>\\s*(.*?)\\s*</servlet-name>(.*?)</servlet-mapping>", Pattern.DOTALL).matcher(t);
                while (m.find()) {
                    ClassInfo c = byFqcn.get(nameToClass.get(m.group(1)));
                    if (c == null) continue;
                    Matcher u = Pattern.compile("<url-pattern>\\s*(.*?)\\s*</url-pattern>").matcher(m.group(2));
                    while (u.find()) urlMap.put(u.group(1), handlerMethods(c));
                }
            } else if (fn.startsWith("struts") && t.contains("<action")) {
                Matcher m = Pattern.compile("<action\\b([^>]*)>").matcher(t);
                while (m.find()) {
                    Map<String, String> at = attrs(m.group(1));
                    String path = at.getOrDefault("path", at.get("name"));
                    ClassInfo c = byFqcn.get(at.getOrDefault("type", at.get("class")));
                    if (path == null || c == null) continue;
                    urlMap.put(path.startsWith("/") ? path : "/" + path, handlerMethods(c));
                }
            }
        }
        for (ClassInfo c : p.classes.values()) {
            for (String wp : c.webPaths) urlMap.putIfAbsent(slash(wp), handlerMethods(c));
            for (MethodInfo m : c.methods)
                for (String mp : m.webPaths) {
                    String base = c.webPaths.isEmpty() ? "" : slash(c.webPaths.get(0)).replaceAll("/\\*$", "");
                    urlMap.putIfAbsent(slash(base + slash(mp)), Collections.singletonList(m));
                }
        }

        for (Path root : roots) {
            List<Path> pages = Util.files(Collections.singletonList(root), PAGE_EXT, true);
            for (Path f : pages) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                if (rel.contains("WEB-INF/lib/")) continue;
                p.webFiles++;
                Screen s = page(f, rel, urlMap);
                if (s != null) p.screens.put(s.id, s);
            }
        }
        // collegamenti inversi
        for (Screen s : p.screens.values())
            for (String l : s.links) if (p.screens.containsKey(l)) p.screens.get(l).linkedFrom.add(s.id);
    }

    private List<Path> autodetectWeb(List<Path> srcRoots) {
        Set<Path> out = new LinkedHashSet<>();
        String[] names = {"webapp", "WebContent", "WebRoot", "web", "public_html", "jsp"};
        for (Path r : srcRoots) {
            Path cur = r.toAbsolutePath();
            for (int up = 0; up < 3 && cur != null; up++, cur = cur.getParent())
                for (String n : names) if (Files.isDirectory(cur.resolve(n))) out.add(cur.resolve(n));
        }
        return new ArrayList<>(out);
    }

    private static List<MethodInfo> handlerMethods(ClassInfo c) {
        List<MethodInfo> l = new ArrayList<>();
        for (MethodInfo m : c.methods)
            if (Arrays.asList("doGet", "doPost", "doPut", "doDelete", "service", "execute", "processRequest").contains(m.name)) l.add(m);
        if (l.isEmpty()) for (MethodInfo m : c.methods) if (m.signature.startsWith("public")) l.add(m);
        return l;
    }

    private Screen page(Path f, String rel, Map<String, List<MethodInfo>> urlMap) throws IOException {
        String raw = Util.read(f);
        Screen s = new Screen();
        s.id = "web/" + rel;
        s.kind = rel.endsWith(".jspf") || rel.endsWith(".tag") ? "frammento di pagina" : "pagina web";
        s.file = f.toString();
        s.source = raw;
        String t = raw;
        // SQL dentro gli scriptlet (vecchie JSP con JDBC diretto)
        UiAction load = new UiAction();
        load.label = "Apertura della pagina";
        load.kind = "caricamento";
        Matcher sc = Pattern.compile("<%(?![=@-])(.*?)%>", Pattern.DOTALL).matcher(t);
        while (sc.find()) {
            Matcher lit = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(sc.group(1));
            StringBuilder joined = new StringBuilder();
            while (lit.find()) joined.append(lit.group(1)).append(' ');
            for (String part : joined.toString().split("(?=\\b(?:SELECT|INSERT|UPDATE|DELETE|select|insert|update|delete)\\b)")) {
                if (SqlAnalyzer.looksLikeSql(part))
                    SqlAnalyzer.analyze(part, schema).tables.forEach((tb, o) -> load.directTables.computeIfAbsent(tb, k -> new TreeSet<>()).addAll(o));
            }
        }
        // testi dai bundle
        t = replaceAll(t, "<(?:fmt|bean|spring|s):(?:message|text)\\b([^>]*?)/?>", m -> {
            Map<String, String> a = attrs(m.group(1));
            String k = a.getOrDefault("key", a.getOrDefault("code", a.get("name")));
            return k == null ? "" : p.bundle.getOrDefault(k, k);
        });
        // messaggi JavaScript
        Matcher js = Pattern.compile("\\b(?:alert|confirm)\\s*\\(\\s*(['\"])((?:\\\\.|(?!\\1).)*)\\1").matcher(t);
        while (js.find()) if (js.group(2).matches(".*[A-Za-zÀ-ÿ]{3,}.*")) s.messages.add(js.group(2).replace("\\n", " ").replace("\\'", "'"));
        t = t.replaceAll("(?s)<%--.*?--%>", " ").replaceAll("(?s)<!--.*?-->", " ")
                .replaceAll("(?s)<%=.*?%>", "<...>").replaceAll("(?s)<%.*?%>", " ")
                .replaceAll("(?s)<script\\b.*?</script>", " ").replaceAll("(?s)<style\\b.*?</style>", " ");
        Matcher m = Pattern.compile("(?is)<title>(.*?)</title>").matcher(t);
        if (m.find()) s.title = clean(m.group(1));
        m = Pattern.compile("(?is)<(h[1-3]|legend)\\b[^>]*>(.*?)</\\1>").matcher(t);
        while (m.find()) { String h = clean(m.group(2)); if (!h.isEmpty() && !h.equals(s.title)) s.headings.add(h); }
        // colonne delle tabelle
        m = Pattern.compile("(?is)<table\\b.*?</table>").matcher(t);
        while (m.find()) {
            List<String> cols = new ArrayList<>();
            Matcher th = Pattern.compile("(?is)<th\\b[^>]*>(.*?)</th>").matcher(m.group());
            while (th.find()) { String c = clean(th.group(1)); if (!c.isEmpty()) cols.add(c); }
            if (cols.size() >= 2) s.columns.add(cols);
        }
        // etichette <label for="id">
        Map<String, String> labelFor = new HashMap<>();
        m = Pattern.compile("(?is)<label\\b([^>]*)>(.*?)</label>").matcher(t);
        while (m.find()) { String id = attrs(m.group(1)).get("for"); if (id != null) labelFor.put(id, clean(m.group(2))); }
        // moduli
        m = Pattern.compile("(?is)<((?:html:|s:|form:)?form)\\b([^>]*)>(.*?)</\\1>").matcher(t);
        while (m.find()) {
            Map<String, String> fa = attrs(m.group(2));
            String action = fa.getOrDefault("action", "");
            String inner = m.group(3);
            List<UiAction> buttons = new ArrayList<>();
            Matcher in = Pattern.compile("(?is)<((?:html:|s:|form:)?(?:input|select|textarea|text|password|checkbox|radio|textfield|submit|button|hidden|file))\\b([^>]*)>(?:(.*?)</\\1>)?").matcher(inner);
            int prevEnd = 0;
            while (in.find()) {
                String tag = in.group(1).toLowerCase(Locale.ROOT);
                Map<String, String> a = attrs(in.group(2));
                String type = a.getOrDefault("type", tag.contains(":") ? tag.substring(tag.indexOf(':') + 1) : tag).toLowerCase(Locale.ROOT);
                String before = clean(inner.substring(prevEnd, in.start()));
                prevEnd = in.end();
                if (type.equals("hidden")) continue;
                if (type.equals("submit") || type.equals("button") || type.equals("image") || type.equals("reset")) {
                    String label = a.getOrDefault("value", in.group(3) != null ? clean(in.group(3)) : null);
                    if (type.equals("reset")) continue;
                    UiAction b = new UiAction();
                    b.label = label == null || label.isEmpty() ? "Invia" : clean(label);
                    b.kind = "pulsante";
                    buttons.add(b);
                    continue;
                }
                UiField fld = new UiField();
                fld.name = a.getOrDefault("name", a.getOrDefault("property", a.get("path")));
                String id = a.get("id");
                String lbl = id != null ? labelFor.get(id) : null;
                if (lbl == null && !before.isEmpty()) {
                    String[] parts = before.split("\\s{2,}|\\|");
                    lbl = parts[parts.length - 1].trim();
                    if (lbl.length() > 60) lbl = null;
                }
                if (lbl == null || lbl.isEmpty()) lbl = a.getOrDefault("placeholder", humanize(fld.name));
                fld.label = lbl == null ? null : lbl.replaceAll("[:*]+\\s*$", "").trim();
                fld.kind = type.equals("select") || tag.endsWith("select") ? "elenco a tendina" : type.equals("textarea") || tag.endsWith("textarea") ? "testo su più righe"
                        : type.equals("checkbox") ? "casella di spunta" : type.equals("radio") ? "scelta" : type.equals("password") ? "password"
                        : type.equals("date") ? "data" : type.equals("file") ? "file da allegare" : "testo";
                s.fields.add(fld);
            }
            if (buttons.isEmpty()) { UiAction b = new UiAction(); b.label = "Invia"; b.kind = "pulsante"; buttons.add(b); }
            for (UiAction b : buttons) {
                b.url = normUrl(action, rel);
                b.targets.addAll(match(b.url, urlMap));
                if (b.url != null && isPage(b.url)) b.opens.add("web/" + b.url);
                s.actions.add(b);
            }
        }
        if (!load.directTables.isEmpty()) s.actions.add(0, load);
        // link verso altre pagine
        m = Pattern.compile("(?i)(?:href|location(?:\\.href)?\\s*=)\\s*=?\\s*[\"']([^\"'#?]+)").matcher(raw);
        while (m.find()) {
            String u = normUrl(m.group(1), rel);
            if (u == null) continue;
            if (isPage(u)) s.links.add("web/" + u);
            else if (!match(u, urlMap).isEmpty()) {
                UiAction b = new UiAction();
                b.label = "Collegamento a " + u;
                b.kind = "collegamento";
                b.url = u;
                b.targets.addAll(match(u, urlMap));
                if (s.actions.stream().noneMatch(x -> u.equals(x.url))) s.actions.add(b);
            }
        }
        if (s.title == null && !s.headings.isEmpty()) s.title = s.headings.get(0);
        if (s.fields.isEmpty() && s.actions.isEmpty() && s.title == null && s.links.isEmpty()) return null;
        StringBuilder h = new StringBuilder(raw.replaceAll("\\s+", " "));
        Set<String> owners = new TreeSet<>();
        for (UiAction a : s.actions) for (MethodInfo mi : DocBuilder.closure(a.targets)) owners.add(mi.owner.fqcn + ":" + mi.owner.hash);
        s.hash = Util.hash(h.append(owners).toString());
        return s;
    }

    private static boolean isPage(String u) {
        return u.matches("(?i).*\\.(jsp|jspx|html|htm|xhtml)$");
    }

    private static String normUrl(String u, String fromRel) {
        if (u == null) return null;
        u = u.replaceAll("\\$\\{pageContext\\.request\\.contextPath\\}|<%=\\s*request\\.getContextPath\\(\\)\\s*%>|\\$\\{ctx\\}|\\$\\{contextPath\\}", "").trim();
        if (u.isEmpty() || u.startsWith("http") || u.startsWith("mailto:") || u.startsWith("javascript:") || u.startsWith("#") || u.contains("${") || u.contains("<%")) return null;
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        if (!u.startsWith("/")) {
            int sl = fromRel.lastIndexOf('/');
            u = (sl >= 0 ? fromRel.substring(0, sl + 1) : "") + u;
        }
        Deque<String> parts = new ArrayDeque<>();
        for (String x : u.split("/")) {
            if (x.isEmpty() || x.equals(".")) continue;
            if (x.equals("..")) { if (!parts.isEmpty()) parts.removeLast(); } else parts.addLast(x);
        }
        return String.join("/", parts);
    }

    private static List<MethodInfo> match(String url, Map<String, List<MethodInfo>> map) {
        if (url == null) return Collections.emptyList();
        String u = "/" + url;
        List<MethodInfo> out = new ArrayList<>();
        for (Map.Entry<String, List<MethodInfo>> e : map.entrySet()) {
            String pat = e.getKey();
            boolean ok = pat.equals(u) || (pat + ".do").equals(u) || (pat + ".action").equals(u)
                    || (pat.endsWith("/*") && u.startsWith(pat.substring(0, pat.length() - 1)))
                    || (u.endsWith(pat) && pat.length() > 1 && !pat.equals("/"));
            if (ok) out.addAll(e.getValue());
        }
        return out;
    }

    private static String slash(String s) {
        return s.startsWith("/") ? s : "/" + s;
    }

    static Map<String, String> attrs(String s) {
        Map<String, String> m = new HashMap<>();
        Matcher a = ATTR.matcher(s);
        while (a.find()) m.put(a.group(1).toLowerCase(Locale.ROOT), a.group(3) != null ? a.group(3) : a.group(4));
        return m;
    }

    private static String clean(String s) {
        return Util.collapse(s.replaceAll("(?s)<[^>]+>", " ").replace("&nbsp;", " ").replace("&amp;", "&").replace("&agrave;", "à")
                .replace("&egrave;", "è").replace("&eacute;", "é").replace("&igrave;", "ì").replace("&ograve;", "ò").replace("&ugrave;", "ù"));
    }

    private static String replaceAll(String s, String regex, java.util.function.Function<Matcher, String> f) {
        Matcher m = Pattern.compile(regex).matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(f.apply(m)));
        m.appendTail(sb);
        return sb.toString();
    }
}
