package it.robyjava.odindoc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Funzioni di servizio: lettura file, impronte, identificatori. */
final class Util {
    private Util() {}

    /** Segnaposto interno per le parti di SQL calcolate a runtime. */
    static final char DYN = '\u0001';

    /** Legge un file provando UTF-8 e ripiegando su Windows-1252 (tipico dei progetti italiani). */
    static String read(Path p) throws IOException {
        byte[] b = Files.readAllBytes(p);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b)).toString().replace("﻿", "");
        } catch (CharacterCodingException e) {
            return new String(b, Charset.forName("windows-1252"));
        }
    }

    static List<Path> files(List<Path> roots, Set<String> exts, boolean includeTests) throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.exists(root)) throw new IOException("Cartella inesistente: " + root);
            try (Stream<Path> s = Files.walk(root)) {
                out.addAll(s.filter(Files::isRegularFile)
                        .filter(p -> exts.contains(ext(p)))
                        .filter(p -> {
                            String n = p.toString().replace('\\', '/');
                            if (n.contains("/target/") || n.contains("/build/") || n.contains("/.git/")) return false;
                            return includeTests || !n.contains("/test/");
                        })
                        .sorted().collect(Collectors.toList()));
            }
        }
        return out;
    }

    static String ext(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i + 1);
    }

    static String hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Normalizza un identificatore Oracle: toglie lo schema, toglie le virgolette, maiuscolo se non quotato. */
    static String ident(String s) {
        if (s == null) return null;
        s = s.trim();
        // prende l'ultima parte dopo il punto (fuori dalle virgolette)
        int dot = -1;
        boolean q = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') q = !q;
            else if (c == '.' && !q) dot = i;
        }
        if (dot >= 0) s = s.substring(dot + 1);
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) return s.substring(1, s.length() - 1);
        return s.toUpperCase(Locale.ROOT);
    }

    static String collapse(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /** Prima frase di un testo, per le tabelle riassuntive. */
    static String firstSentence(String s) {
        if (s == null) return "";
        s = collapse(s);
        int i = s.indexOf(". ");
        if (i > 0 && i < 220) return s.substring(0, i + 1);
        return s.length() > 220 ? s.substring(0, 217) + "..." : s;
    }

    /** Toglie i tag inline del Javadoc ({@link X} -> X) e l'HTML. */
    static String javadoc(String s) {
        if (s == null) return null;
        s = s.replaceAll("\\{@(?:link|linkplain|code|literal|value)\\s+([^}]*)}", "$1").replaceAll("<[^>]+>", " ");
        s = s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&nbsp;", " ").trim();
        return s.isEmpty() ? null : s;
    }

    static String ops(Set<Character> ops) {
        StringBuilder sb = new StringBuilder();
        for (char c : new char[]{'C', 'R', 'U', 'D'}) if (ops.contains(c)) sb.append(c);
        return sb.toString();
    }

    static String showSql(String s) {
        return s.replace(String.valueOf(DYN), "<...>");
    }
}
