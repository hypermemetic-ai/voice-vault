package ai.hypermemetic.voicevault;

import java.util.ArrayList;
import java.util.List;

/**
 * Screen semantics and native input identity for any app's chat composer
 * (T3 Code in Chrome, Paseo, messaging apps); no application view IDs or layout rules.
 * The class name is historical: it began as the Paseo-only path.
 */
final class PaseoSelection {
    /** An editable node showing its platform hint has no user draft, even if getText returns the hint. */
    static String draftText(boolean editable, boolean showingHintText, CharSequence text) {
        return draftText(editable, showingHintText, text, null);
    }
    /** Web editors may report their placeholder as text without flagging it as a hint. */
    static String draftText(boolean editable, boolean showingHintText, CharSequence text, CharSequence hint) {
        if ((editable && showingHintText) || text == null) return "";
        String value = text.toString();
        if (editable && hint != null && hint.length() > 0 && sameDraft(value, hint.toString())) return "";
        return value;
    }
    static boolean isPaseo(String pkg) {
        return "sh.paseo".equals(pkg) || "sh.paseo.debug".equals(pkg);
    }
    /** Accessibility text from web/native editors differs in NBSP, line breaks and trailing newlines. */
    static String normalize(String text) {
        if (text == null) return "";
        return text.replace('\u00A0', ' ').replace("\u200B", "").replaceAll("\\s+", " ").trim();
    }
    static boolean sameDraft(String a, String b) { return normalize(a).equals(normalize(b)); }
    static boolean blank(String text) { return normalize(text).isEmpty(); }
    /** Dictation adds to the user's draft; it never replaces it. */
    static String append(String draft, String addition) {
        String add = addition == null ? "" : addition.trim();
        if (blank(draft)) return add;
        String kept = draft.replaceAll("[\\s\u00A0\u200B]+$", "");
        return add.isEmpty() ? kept : kept + " " + add;
    }
    static boolean primary(String label) {
        return "Send".equals(label) || "Submit".equals(label) || "Queue".equals(label)
                || "Send message".equals(label) || "Queue message".equals(label)
                || "Send and interrupt".equals(label) || "Send and steer".equals(label)
                // T3 Code composer (aria-label of its submit control by mode).
                || "Submit message".equals(label) || "Steer message".equals(label)
                || "Compact and send".equals(label) || "Update queued message".equals(label)
                // T3 Code 2.0 Android composer: the label follows the agent's state
                // (idle Send/Queue, running Steer/Queue/Restart, new thread Start/Queue task).
                || "Steer".equals(label) || "Restart".equals(label)
                || "Start task".equals(label) || "Queue task".equals(label);
    }
    static final class Node {
        String label, text;
        boolean editable, visible, enabled, clickable, focused, scoped;
        int left, top, right, bottom;
        String path;
        List<Node> children = new ArrayList<>();
        // Actual click target is always the labeled node, never a parent.
        Object handle;
    }
    static Node composer(Node root) {
        List<Node> matches = new ArrayList<>();
        collect(root, n -> n.editable && n.visible && n.enabled && n.focused, matches);
        if (!matches.isEmpty()) return matches.size() == 1 ? matches.get(0) : null;
        collect(root, n -> n.editable && n.visible && n.enabled, matches);
        return matches.size() == 1 ? matches.get(0) : null;
    }
    static Node submit(Node root, Node editor) {
        Node owner = owner(root, editor);
        if (owner == null) return null;
        List<Node> matches = new ArrayList<>();
        collect(owner, n -> !n.editable && n.visible && n.enabled && n.clickable
                && primary(n.label), matches);
        return matches.size() == 1 ? matches.get(0) : null;
    }
    private static Node owner(Node root, Node editor) {
        if (editor == null) return null;
        List<Node> owners = new ArrayList<>();
        collect(root, n -> n.scoped && contains(n, editor), owners);
        return owners.size() == 1 ? owners.get(0) : null;
    }
    private static boolean contains(Node node, Node editor) {
        if (node == editor || (node.handle != null && node.handle.equals(editor.handle))) return true;
        for (Node child : node.children) if (contains(child, editor)) return true;
        return false;
    }
    static String unavailable(Node root, Node editor) {
        Node owner = owner(root, editor);
        if (owner == null) return "Inserted — controls unavailable";
        List<Node> buttons = new ArrayList<>();
        collect(owner, n -> !n.editable && n.visible && n.clickable && primary(n.label), buttons);
        int enabled = 0;
        for (Node button : buttons) if (button.enabled) enabled++;
        if (enabled > 1) return "Inserted — multiple Send controls";
        if (!buttons.isEmpty() && enabled == 0) return "Inserted — Send disabled";
        return "Inserted — Send unavailable";
    }
    interface Filter { boolean accept(Node n); }
    private static void collect(Node n, Filter f, List<Node> matches) {
        if (n == null) return;
        if (f.accept(n)) matches.add(n);
        for (Node child : n.children) collect(child, f, matches);
    }
    /** One request; neither a failed action nor delayed UI rendering authorizes a second dispatch. */
    static final class Gate {
        private boolean written, echoed, dispatched, canceled, ready;
        private String before = "";
        private String unavailable = "Inserted — Send unavailable";
        boolean write(String previousText) {
            if (written || canceled) return false;
            before = previousText; written = true; return true;
        }
        // Also called on native content/text events: echo then manual clear/edit
        // cancels even if both events arrive between readiness polls.
        void observe(Node editor, String expected) {
            if (canceled || dispatched) return;
            if (editor == null) { cancel(); return; }
            if (!written) return;
            if (sameDraft(expected, editor.text)) echoed = true;
            else if (echoed || (!blank(editor.text) && !sameDraft(before, editor.text))) cancel();
        }
        Status check(Node root, Node editor, String expected, boolean autoSend, boolean expired) {
            ready = false;
            observe(editor, expected);
            if (!written || dispatched || canceled) return Status.ABORT;
            if (!sameDraft(expected, editor.text)) return expired ? Status.UNCONFIRMED : Status.WAIT;
            if (!autoSend) return Status.INSERTED;
            if (submit(root, editor) != null) { ready = true; return Status.SEND; }
            unavailable = unavailable(root, editor);
            return expired ? Status.MANUAL : Status.WAIT;
        }
        boolean dispatch() {
            if (!written || dispatched || canceled || !echoed || !ready) return false;
            dispatched = true;
            return true;
        }
        boolean isCanceled() { return canceled; }
        boolean hasEcho() { return echoed; }
        String unavailableFeedback() { return unavailable; }
        void cancel() { canceled = true; ready = false; }
    }
    enum Status { WAIT, ABORT, INSERTED, SEND, MANUAL, UNCONFIRMED }
    static String feedback(Status status) {
        if (status == Status.MANUAL) return "Inserted — send manually";
        if (status == Status.UNCONFIRMED) return "Copied — insertion unconfirmed; paste manually";
        return null; // cancellation/optimistic clearing is not server acceptance or a failure
    }
    static boolean same(Node a, Node b) {
        return a != null && b != null && a.path.equals(b.path)
                && a.left == b.left && a.top == b.top && a.right == b.right && a.bottom == b.bottom;
    }
}
