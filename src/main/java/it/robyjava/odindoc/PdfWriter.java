package it.robyjava.odindoc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.zip.Deflater;

/**
 * Scrittore PDF minimale, solo JDK: font standard (Helvetica, Courier) con codifica Windows-1252,
 * impaginazione automatica, tabelle, blocchi di codice, link interni, segnalibri.
 */
final class PdfWriter {

    static final float PW = 595.28f, PH = 841.89f, ML = 50, MR = 50, MT = 58, MB = 58;
    static final float CW = PW - ML - MR;

    static final float[] BLACK = {0.13f, 0.13f, 0.15f}, GRAY = {0.45f, 0.45f, 0.48f}, LIGHT = {0.95f, 0.96f, 0.97f},
            LINE = {0.84f, 0.86f, 0.89f}, ACCENT = {0.11f, 0.27f, 0.47f}, ACCENT2 = {0.85f, 0.90f, 0.96f},
            WHITE = {1, 1, 1}, WARN_BG = {1.0f, 0.95f, 0.85f}, WARN = {0.80f, 0.45f, 0.05f},
            OK_BG = {0.90f, 0.96f, 0.91f}, OK = {0.18f, 0.55f, 0.27f}, CODE_BG = {0.965f, 0.965f, 0.955f};

    enum Font {
        REG("F1", "Helvetica"), BOLD("F2", "Helvetica-Bold"), IT("F3", "Helvetica-Oblique"), MONO("F4", "Courier"), MONOB("F5", "Courier-Bold");
        final String id, base;
        Font(String id, String base) { this.id = id; this.base = base; }
    }

    static final class Link { float x, y, w, h, ty; int target; }

    static final class Page {
        final StringBuilder c = new StringBuilder();
        final List<Link> links = new ArrayList<>();
    }

    static final class Mark {
        int level, page;
        float y;
        String title;
    }

    final List<Page> pages = new ArrayList<>();
    final List<Mark> marks = new ArrayList<>();
    Page cur;
    float y;

    // ------------------------------------------------------------ metriche

    private static final short[] HELV = {278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278, 556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556, 1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778, 667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556, 333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556, 556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584};
    private static final short[] HELVB = {278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278, 278, 556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 333, 333, 584, 584, 584, 611, 975, 722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611, 833, 722, 778, 667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 333, 278, 333, 584, 556, 333, 556, 611, 556, 611, 556, 333, 611, 611, 278, 278, 556, 278, 889, 611, 611, 611, 611, 389, 556, 333, 611, 556, 778, 556, 556, 500, 389, 280, 389, 584};
    private static final Charset CP1252 = Charset.forName("windows-1252");

    static float width(Font f, float size, String s) {
        if (f == Font.MONO || f == Font.MONOB) return s.length() * 0.6f * size;
        short[] w = f == Font.BOLD ? HELVB : HELV;
        float tot = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 32 && c <= 126) tot += w[c - 32];
            else {
                String base = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
                char b = base.isEmpty() ? 'a' : base.charAt(0);
                tot += (b >= 32 && b <= 126) ? w[b - 32] : 556;
            }
        }
        return tot * size / 1000f;
    }

    static List<String> wrap(Font f, float size, String text, float maxW) {
        List<String> out = new ArrayList<>();
        if (text == null) text = "";
        for (String para : clean(text).split("\n", -1)) {
            if (para.isEmpty()) { out.add(""); continue; }
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                String cand = line.length() == 0 ? word : line + " " + word;
                if (width(f, size, cand) <= maxW) { line.setLength(0); line.append(cand); continue; }
                if (line.length() > 0) { out.add(line.toString()); line.setLength(0); }
                while (width(f, size, word) > maxW && word.length() > 1) {
                    int k = word.length() - 1;
                    while (k > 1 && width(f, size, word.substring(0, k)) > maxW) k--;
                    out.add(word.substring(0, k));
                    word = word.substring(k);
                }
                line.append(word);
            }
            out.add(line.toString());
        }
        return out;
    }

    static String clean(String s) {
        s = Util.showSql(s).replace("\t", "    ").replace("\r", "");
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '\n' || c >= 32) sb.append(CP1252.newEncoder().canEncode(c) ? c : '?');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ primitive

    void newPage() {
        cur = new Page();
        pages.add(cur);
        y = PH - MT;
    }

    boolean need(float h) {
        if (cur == null || y - h < MB) { newPage(); return true; }
        return false;
    }

    private static String n(float v) {
        if (v == (int) v) return Integer.toString((int) v);
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String rgb(float[] c) { return n(c[0]) + " " + n(c[1]) + " " + n(c[2]); }

    void text(float x, float yy, Font f, float size, String s, float[] color) {
        cur.c.append("BT /").append(f.id).append(' ').append(n(size)).append(" Tf ").append(rgb(color)).append(" rg ")
                .append(n(x)).append(' ').append(n(yy)).append(" Td ").append(str(s)).append(" Tj ET\n");
    }

    void rect(float x, float yy, float w, float h, float[] fill, float[] stroke, float lw) {
        cur.c.append("q ");
        if (fill != null) cur.c.append(rgb(fill)).append(" rg ");
        if (stroke != null) cur.c.append(n(lw)).append(" w ").append(rgb(stroke)).append(" RG ");
        cur.c.append(n(x)).append(' ').append(n(yy)).append(' ').append(n(w)).append(' ').append(n(h)).append(" re ");
        cur.c.append(fill != null && stroke != null ? "B" : fill != null ? "f" : "S").append(" Q\n");
    }

    void line(float x1, float y1, float x2, float y2, float[] color, float lw) {
        cur.c.append("q ").append(n(lw)).append(" w ").append(rgb(color)).append(" RG ").append(n(x1)).append(' ').append(n(y1))
                .append(" m ").append(n(x2)).append(' ').append(n(y2)).append(" l S Q\n");
    }

    void arrow(float x1, float y1, float x2, float y2, float[] color) {
        line(x1, y1, x2, y2, color, 0.8f);
        double a = Math.atan2(y2 - y1, x2 - x1);
        float l = 6, s = 0.45f;
        float ax = (float) (x2 - l * Math.cos(a - s)), ay = (float) (y2 - l * Math.sin(a - s));
        float bx = (float) (x2 - l * Math.cos(a + s)), by = (float) (y2 - l * Math.sin(a + s));
        cur.c.append("q ").append(rgb(color)).append(" rg ").append(n(x2)).append(' ').append(n(y2)).append(" m ")
                .append(n(ax)).append(' ').append(n(ay)).append(" l ").append(n(bx)).append(' ').append(n(by)).append(" l f Q\n");
    }

    private static String str(String s) {
        byte[] b = clean(s).replace("\n", " ").getBytes(CP1252);
        StringBuilder sb = new StringBuilder("(");
        for (byte x : b) {
            int v = x & 0xff;
            if (v == '(' || v == ')' || v == '\\') sb.append('\\').append((char) v);
            else if (v < 32 || v > 126) sb.append('\\').append(String.format("%03o", v));
            else sb.append((char) v);
        }
        return sb.append(')').toString();
    }

    // ------------------------------------------------------------ blocchi

    void space(float h) {
        if (cur == null) newPage();
        y -= h;
        if (y < MB) newPage();
    }

    void heading(int level, String title) {
        if (level == 1) {
            newPage();
            mark(1, title);
            rect(ML, y - 30, CW, 36, ACCENT, null, 0);
            text(ML + 12, y - 18, Font.BOLD, 17, title, WHITE);
            y -= 52;
        } else if (level == 2) {
            need(70);
            mark(2, title);
            y -= 8;
            text(ML, y - 12, Font.BOLD, 13, title, ACCENT);
            y -= 18;
            line(ML, y, ML + CW, y, ACCENT2, 1.2f);
            y -= 10;
        } else {
            need(60);
            y -= 4;
            text(ML, y - 10, Font.BOLD, 10.5f, title, BLACK);
            y -= 17;
        }
    }

    private void mark(int level, String title) {
        Mark m = new Mark();
        m.level = level;
        m.title = title;
        m.page = pages.size() - 1;
        m.y = y + 10;
        marks.add(m);
    }

    void para(String text, Font f, float size, float[] color) {
        float lead = size * 1.38f;
        for (String l : wrap(f, size, text, CW)) {
            need(lead);
            text(ML, y - size, f, size, l, color);
            y -= lead;
        }
        y -= 4;
    }

    void para(String text) { para(text, Font.REG, 9.5f, BLACK); }

    void bullet(String text, int indent) {
        float size = 9.5f, lead = size * 1.38f, x = ML + 10 + indent * 12;
        List<String> lines = wrap(Font.REG, size, text, CW - (x - ML) - 10);
        for (int i = 0; i < lines.size(); i++) {
            need(lead);
            if (i == 0) text(x, y - size, Font.BOLD, size, "•", ACCENT);
            text(x + 10, y - size, Font.REG, size, lines.get(i), BLACK);
            y -= lead;
        }
        y -= 1;
    }

    void code(String code) {
        float size = 7.6f, lead = size * 1.3f;
        int maxChars = (int) ((CW - 12) / (0.6f * size));
        List<String> lines = new ArrayList<>();
        for (String l : clean(code).split("\n")) {
            while (l.length() > maxChars) { lines.add(l.substring(0, maxChars)); l = "  " + l.substring(maxChars); }
            lines.add(l);
        }
        need(lead * Math.min(3, lines.size()) + 6);
        rect(ML, y - 3, CW, 3, CODE_BG, null, 0);
        y -= 3;
        for (String l : lines) {
            if (need(lead)) { rect(ML, y - 3, CW, 3, CODE_BG, null, 0); y -= 3; }
            rect(ML, y - lead, CW, lead, CODE_BG, null, 0);
            text(ML + 6, y - size, Font.MONO, size, l, BLACK);
            y -= lead;
        }
        rect(ML, y - 3, CW, 3, CODE_BG, null, 0);
        y -= 9;
    }

    void note(String text, float[] bg, float[] fg) {
        float size = 8.8f, lead = size * 1.35f;
        List<String> lines = wrap(Font.REG, size, text, CW - 20);
        float h = lines.size() * lead + 10;
        need(Math.min(h, 200));
        float top = y;
        rect(ML, top - h, CW, h, bg, null, 0);
        rect(ML, top - h, 3, h, fg, null, 0);
        float yy = top - 5;
        for (String l : lines) {
            text(ML + 12, yy - size, Font.REG, size, l, BLACK);
            yy -= lead;
        }
        y = top - h - 8;
    }

    /** Tabella con intestazione ripetuta a ogni cambio pagina. Celle che iniziano con "**" in grassetto. */
    void table(String[] head, float[] frac, List<String[]> rows, Font[] fonts) {
        float size = 8f, lead = size * 1.3f, pad = 3.5f;
        float[] w = new float[frac.length];
        for (int i = 0; i < frac.length; i++) w[i] = frac[i] * CW;
        float headH = head == null ? 0 : lead + 2 * pad;
        need(headH + lead * 2 + 2 * pad);
        if (head != null) tableHead(head, w, size, lead, pad);
        int r = 0;
        for (String[] row : rows) {
            List<List<String>> cells = new ArrayList<>();
            List<Font> cellFonts = new ArrayList<>();
            int max = 1;
            for (int i = 0; i < w.length; i++) {
                String v = i < row.length && row[i] != null ? row[i] : "";
                Font f = fonts != null && i < fonts.length && fonts[i] != null ? fonts[i] : Font.REG;
                if (v.startsWith("**")) { v = v.substring(2); f = f == Font.MONO ? Font.MONOB : Font.BOLD; }
                List<String> l = wrap(f, size, v, w[i] - 2 * pad);
                cells.add(l);
                cellFonts.add(f);
                max = Math.max(max, l.size());
            }
            int fit = (int) ((PH - MT - MB - headH - 2 * pad) / lead);
            if (max > fit) max = fit;
            float h = max * lead + 2 * pad;
            if (y - h < MB) {
                newPage();
                if (head != null) tableHead(head, w, size, lead, pad);
            }
            if (r % 2 == 1) rect(ML, y - h, CW, h, LIGHT, null, 0);
            line(ML, y - h, ML + CW, y - h, LINE, 0.5f);
            float x = ML;
            for (int i = 0; i < w.length; i++) {
                List<String> l = cells.get(i);
                for (int k = 0; k < Math.min(max, l.size()); k++) text(x + pad, y - pad - size - k * lead + 1, cellFonts.get(i), size, l.get(k), BLACK);
                x += w[i];
            }
            y -= h;
            r++;
        }
        y -= 10;
    }

    private void tableHead(String[] head, float[] w, float size, float lead, float pad) {
        float h = lead + 2 * pad;
        rect(ML, y - h, CW, h, ACCENT, null, 0);
        float x = ML;
        for (int i = 0; i < head.length; i++) {
            text(x + pad, y - pad - size + 1, Font.BOLD, size, head[i], WHITE);
            x += w[i];
        }
        y -= h;
    }

    /** Schema delle relazioni di una tabella: padri a sinistra (FK uscenti), figlie a destra (FK entranti). */
    void relations(String center, List<String> parents, List<String> children) {
        int maxSide = 8;
        List<String> p = limit(parents, maxSide), c = limit(children, maxSide);
        int rows = Math.max(1, Math.max(p.size(), c.size()));
        float bh = 17, gap = 6, bw = 145, h = rows * (bh + gap) + 26;
        need(h + 6);
        float top = y;
        float lx = ML, cx = ML + CW / 2 - bw / 2, rx = ML + CW - bw;
        text(lx, top - 9, Font.IT, 7.5f, "Tabelle collegate (FK uscenti)", GRAY);
        text(rx, top - 9, Font.IT, 7.5f, "Tabelle che la referenziano", GRAY);
        float areaTop = top - 18;
        float cy = areaTop - (rows * (bh + gap) - gap) / 2 - bh / 2;
        box(cx, cy, bw, bh, center, true);
        for (int i = 0; i < p.size(); i++) {
            float by = areaTop - i * (bh + gap) - bh;
            box(lx, by, bw, bh, p.get(i), false);
            arrow(cx, cy + bh / 2, lx + bw, by + bh / 2, ACCENT);
        }
        for (int i = 0; i < c.size(); i++) {
            float by = areaTop - i * (bh + gap) - bh;
            box(rx, by, bw, bh, c.get(i), false);
            arrow(rx, by + bh / 2, cx + bw, cy + bh / 2, GRAY);
        }
        y = top - h;
    }

    static float relationsHeight(int parents, int children) {
        int rows = Math.max(1, Math.min(8, Math.max(parents, children)));
        return rows * 23 + 26;
    }

    private static List<String> limit(List<String> l, int max) {
        if (l.size() <= max) return l;
        List<String> r = new ArrayList<>(l.subList(0, max - 1));
        r.add("... altre " + (l.size() - max + 1));
        return r;
    }

    private void box(float x, float yy, float w, float h, String label, boolean main) {
        rect(x, yy, w, h, main ? ACCENT : ACCENT2, main ? null : ACCENT, 0.6f);
        Font f = main ? Font.BOLD : Font.REG;
        float size = 8;
        String l = label;
        while (width(f, size, l) > w - 8 && l.length() > 3) l = l.substring(0, l.length() - 2) + "…";
        text(x + (w - width(f, size, l)) / 2, yy + 5.5f, f, size, l, main ? WHITE : BLACK);
    }

    /** Riga dell'indice con puntini e numero di pagina, cliccabile. */
    void tocLine(int level, String title, int targetPage, String pageLabel, float targetY) {
        float size = level == 1 ? 10 : 8.8f, lead = level == 1 ? 17 : 13;
        need(lead);
        Font f = level == 1 ? Font.BOLD : Font.REG;
        float x = ML + (level - 1) * 16;
        String t = title;
        float maxT = CW - (x - ML) - 40;
        while (width(f, size, t) > maxT && t.length() > 3) t = t.substring(0, t.length() - 2) + "…";
        if (level == 1) y -= 4;
        text(x, y - size, f, size, t, level == 1 ? ACCENT : BLACK);
        float tw = width(f, size, t), pw = width(f, size, pageLabel);
        float dotsFrom = x + tw + 4, dotsTo = ML + CW - pw - 4;
        StringBuilder dots = new StringBuilder();
        while (width(Font.REG, size, dots + ".") < dotsTo - dotsFrom) dots.append('.');
        text(dotsFrom, y - size, Font.REG, size, dots.toString(), LINE);
        text(ML + CW - pw, y - size, f, size, pageLabel, BLACK);
        Link lk = new Link();
        lk.x = x; lk.y = y - lead + 2; lk.w = ML + CW - x; lk.h = lead; lk.target = targetPage; lk.ty = targetY;
        cur.links.add(lk);
        y -= lead;
    }

    static int tocPages(List<Mark> marks) {
        float avail = PH - MT - MB - 52, used = 0;
        int pages = 1;
        for (Mark m : marks) {
            float lead = m.level == 1 ? 21 : 13;
            if (used + lead > avail) { pages++; used = 0; avail = PH - MT - MB; }
            used += lead;
        }
        return pages;
    }

    // ------------------------------------------------------------ serializzazione

    /** Unisce le pagine iniziali (copertina, indice) e il corpo e produce il PDF. */
    static byte[] build(PdfWriter front, PdfWriter body, String title) {
        int off = front.pages.size();
        List<Page> all = new ArrayList<>(front.pages);
        all.addAll(body.pages);
        int np = all.size();
        // piè di pagina
        for (int i = 1; i < np; i++) {
            PdfWriter tmp = new PdfWriter();
            tmp.cur = all.get(i);
            tmp.line(ML, MB - 18, ML + CW, MB - 18, LINE, 0.5f);
            tmp.text(ML, MB - 30, Font.REG, 7.5f, title, GRAY);
            String pg = "Pagina " + (i + 1) + " di " + np;
            tmp.text(ML + CW - width(Font.REG, 7.5f, pg), MB - 30, Font.REG, 7.5f, pg, GRAY);
        }
        List<Mark> marks = new ArrayList<>();
        for (Mark m : body.marks) {
            Mark c = new Mark();
            c.level = m.level; c.title = m.title; c.page = m.page + off; c.y = m.y;
            marks.add(c);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        int pageBase = 10;
        int outlineBase = pageBase + 2 * np;
        int objCount = outlineBase + marks.size() - 1;
        for (int i = 0; i <= objCount; i++) offsets.add(0);

        w(out, "%PDF-1.4\n%âãÏÓ\n");
        obj(out, offsets, 1, "<< /Type /Catalog /Pages 2 0 R" + (marks.isEmpty() ? "" : " /Outlines 3 0 R /PageMode /UseOutlines") + " >>");
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < np; i++) kids.append(pageBase + 2 * i).append(" 0 R ");
        obj(out, offsets, 2, "<< /Type /Pages /Count " + np + " /Kids [" + kids + "] >>");

        // segnalibri: livello 1 sotto la radice, livello 2 sotto l'ultimo livello 1
        List<Integer> top = new ArrayList<>();
        Map<Integer, List<Integer>> childrenOf = new LinkedHashMap<>();
        Integer lastTop = null;
        for (int i = 0; i < marks.size(); i++) {
            if (marks.get(i).level == 1 || lastTop == null) { top.add(i); lastTop = i; childrenOf.put(i, new ArrayList<>()); }
            else childrenOf.get(lastTop).add(i);
        }
        if (!marks.isEmpty())
            obj(out, offsets, 3, "<< /Type /Outlines /First " + (outlineBase + top.get(0)) + " 0 R /Last " + (outlineBase + top.get(top.size() - 1)) + " 0 R /Count " + top.size() + " >>");
        else obj(out, offsets, 3, "<< /Type /Outlines /Count 0 >>");
        int fo = 4;
        for (Font f : Font.values())
            obj(out, offsets, fo++, "<< /Type /Font /Subtype /Type1 /BaseFont /" + f.base + " /Encoding /WinAnsiEncoding >>");
        obj(out, offsets, 9, "<< /Title " + utf16(title) + " /Producer (OdinDoc) /Creator (OdinDoc) >>");

        StringBuilder fontRes = new StringBuilder();
        fo = 4;
        for (Font f : Font.values()) fontRes.append('/').append(f.id).append(' ').append(fo++).append(" 0 R ");
        for (int i = 0; i < np; i++) {
            Page p = all.get(i);
            StringBuilder annots = new StringBuilder();
            for (Link l : p.links) {
                int target = Math.min(np - 1, l.target + off);
                annots.append("<< /Type /Annot /Subtype /Link /Border [0 0 0] /Rect [").append(n(l.x)).append(' ').append(n(l.y)).append(' ')
                        .append(n(l.x + l.w)).append(' ').append(n(l.y + l.h)).append("] /Dest [").append(pageBase + 2 * target)
                        .append(" 0 R /XYZ null ").append(n(l.ty)).append(" null] >> ");
            }
            obj(out, offsets, pageBase + 2 * i, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + n(PW) + " " + n(PH) + "] /Resources << /Font << " + fontRes + ">> >> /Contents "
                    + (pageBase + 2 * i + 1) + " 0 R" + (annots.length() > 0 ? " /Annots [" + annots + "]" : "") + " >>");
            byte[] data = deflate(p.c.toString().getBytes(StandardCharsets.ISO_8859_1));
            offsets.set(pageBase + 2 * i + 1, out.size());
            w(out, (pageBase + 2 * i + 1) + " 0 obj\n<< /Length " + data.length + " /Filter /FlateDecode >>\nstream\n");
            out.write(data, 0, data.length);
            w(out, "\nendstream\nendobj\n");
        }
        for (int i = 0; i < marks.size(); i++) {
            Mark m = marks.get(i);
            boolean isTop = top.contains(i);
            List<Integer> siblings = isTop ? top : null;
            int parent = 3;
            if (!isTop) for (Map.Entry<Integer, List<Integer>> e : childrenOf.entrySet()) if (e.getValue().contains(i)) { siblings = e.getValue(); parent = outlineBase + e.getKey(); }
            int idx = siblings.indexOf(i);
            StringBuilder sb = new StringBuilder("<< /Title ").append(utf16(m.title)).append(" /Parent ").append(parent).append(" 0 R");
            if (idx > 0) sb.append(" /Prev ").append(outlineBase + siblings.get(idx - 1)).append(" 0 R");
            if (idx < siblings.size() - 1) sb.append(" /Next ").append(outlineBase + siblings.get(idx + 1)).append(" 0 R");
            List<Integer> ch = childrenOf.get(i);
            if (ch != null && !ch.isEmpty())
                sb.append(" /First ").append(outlineBase + ch.get(0)).append(" 0 R /Last ").append(outlineBase + ch.get(ch.size() - 1)).append(" 0 R /Count -").append(ch.size());
            sb.append(" /Dest [").append(pageBase + 2 * m.page).append(" 0 R /XYZ null ").append(n(m.y)).append(" null] >>");
            obj(out, offsets, outlineBase + i, sb.toString());
        }
        int xref = out.size();
        StringBuilder x = new StringBuilder("xref\n0 " + (objCount + 1) + "\n0000000000 65535 f \n");
        for (int i = 1; i <= objCount; i++) x.append(String.format("%010d 00000 n \n", offsets.get(i)));
        x.append("trailer\n<< /Size ").append(objCount + 1).append(" /Root 1 0 R /Info 9 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        w(out, x.toString());
        return out.toByteArray();
    }

    private static void obj(ByteArrayOutputStream out, List<Integer> offsets, int num, String body) {
        offsets.set(num, out.size());
        w(out, num + " 0 obj\n" + body + "\nendobj\n");
    }

    private static void w(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
        out.write(b, 0, b.length);
    }

    private static String utf16(String s) {
        StringBuilder sb = new StringBuilder("<FEFF");
        for (char c : s.toCharArray()) sb.append(String.format("%04X", (int) c));
        return sb.append('>').toString();
    }

    private static byte[] deflate(byte[] in) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        d.setInput(in);
        d.finish();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!d.finished()) { int k = d.deflate(buf); bo.write(buf, 0, k); }
        d.end();
        return bo.toByteArray();
    }
}
