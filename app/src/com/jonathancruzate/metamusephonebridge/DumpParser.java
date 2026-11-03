package com.jonathancruzate.metamusephonebridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses the Modes and Routines app's "Device Status" dump notifications —
 * the exact text the server-side system already treats as ground truth
 * (field 1 = Current mode; the full dump also carries the running-routines
 * list). Fields are separated by '|' (older routine versions used
 * zero-width spaces). Parsing is defensive: anything unexpected is a LOUD
 * parse failure (parseOk=false), never a silent guess — a One UI format
 * change must surface as an alarm, not as wrong state.
 */
public final class DumpParser {

    public static final class Result {
        public boolean parseOk;
        public String mode;        // field 1, e.g. "Home", "Sleep", "None"
        public String running;     // running-routines list text, "" if not found
        public List<String> fields = new ArrayList<>();
    }

    private static final Set<String> KNOWN_MODES = new HashSet<>(Arrays.asList(
            "home", "sleep", "night", "morning", "away", "work", "relax",
            "driving", "exercise", "workout", "movie theater", "cinema",
            "theater", "none"));

    private DumpParser() {}

    public static Result parse(String text) {
        Result r = new Result();
        if (text == null) return r;
        // Strip zero-width characters some routine versions embed.
        String clean = text.replace("\u200B", "").replace("\uFEFF", "").trim();
        if (clean.isEmpty()) return r;

        String[] parts = clean.split("\\|");
        if (parts.length < 3) {
            // Fall back: some dumps arrive as lines.
            parts = clean.split("\\n");
        }
        if (parts.length < 3) return r;

        for (String p : parts) r.fields.add(stripLabel(p.trim()));
        if (r.fields.isEmpty()) return r;

        String mode = r.fields.get(0);
        if (!KNOWN_MODES.contains(mode.toLowerCase())) {
            // Field 1 is not a recognizable mode — refuse to guess.
            r.parseOk = false;
            r.mode = mode;
            return r;
        }
        r.mode = mode;

        // Running-routines list: the field that reads like a list of routine
        // names ("At Home, Switch Away mode, or 1 other"). It is never the
        // date field (contains a month or a colon time) and never a plain
        // percentage / on-off value.
        for (int i = 1; i < r.fields.size(); i++) {
            String f = r.fields.get(i);
            if (looksLikeRoutineList(f)) { r.running = f; break; }
        }
        r.parseOk = true;
        return r;
    }

    private static boolean looksLikeRoutineList(String f) {
        String low = f.toLowerCase();
        if (low.matches(".*\\b(january|february|march|april|may|june|july|august|september|october|november|december)\\b.*")) return false;
        if (low.contains("%") || low.equals("on") || low.equals("off")) return false;
        if (low.contains("charging")) return false;
        // "X, Y, or N other(s)" is the canonical Samsung list rendering.
        if (low.matches(".*,.*\\bor\\b.*\\bother.*")) return true;
        // A comma list of named routines also qualifies when it names a
        // known routine/mode word.
        if (f.contains(",") && (low.contains("mode") || low.contains("home")
                || low.contains("routine") || low.contains("device status"))) return true;
        return false;
    }

    /** Fields are positional values, but strip a leading "Label:" if a
     *  routine version ever renders one. */
    private static String stripLabel(String field) {
        int colon = field.indexOf(':');
        if (colon > 0 && colon < 24) {
            String head = field.substring(0, colon).toLowerCase();
            if (head.matches("[a-z ]+") && !head.contains("http")) {
                return field.substring(colon + 1).trim();
            }
        }
        return field;
    }
}
