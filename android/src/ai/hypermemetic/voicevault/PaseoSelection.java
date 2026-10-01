package ai.hypermemetic.voicevault;

import java.util.ArrayList;
import java.util.List;

/** Conservative, source-label-based selection. No substring or ancestor click matching. */
final class PaseoSelection {
    static final String COMPOSER = "Message agent...";
    /** An editable node showing its platform hint has no user draft, even if getText returns the hint. */
    static String draftText(boolean editable, boolean showingHintText, CharSequence text) {
        return (editable && showingHintText) || text == null ? "" : text.toString();
    }
    static boolean isPaseo(String pkg) {
        return "sh.paseo".equals(pkg) || "sh.paseo.debug".equals(pkg);
    }
    static boolean primary(String label) {
        return "Send message".equals(label) || "Queue message".equals(label)
                || "Send and interrupt".equals(label) || "Send and steer".equals(label);
    }
    static final class Node {
        String label, text;
        boolean editable, visible, enabled, clickable;
        int left, top, right, bottom;
        String path;
        List<Node> children = new ArrayList<>();
        // Actual click target is always the labeled node, never a parent.
        Object handle;
    }
    static Node composer(Node root) {
        List<Node> matches = new ArrayList<>();
        collect(root, n -> n.editable && n.visible && n.enabled && COMPOSER.equals(n.label), matches);
        return matches.size() == 1 ? matches.get(0) : null;
    }
    static Node submit(Node root, Node editor) {
        if (editor == null) return null;
        List<Node> matches = new ArrayList<>();
        // input.tsx: full-width text surface, then a separate row (12dp gap,
        // 28dp controls, row marginHorizontal -6). Scale locality by the control,
        // not by the variable input height; never search arbitrary global labels.
        collect(root, n -> !n.editable && n.visible && n.enabled && n.clickable
                && primary(n.label) && lowerToolbar(editor, n), matches);
        return matches.size() == 1 ? matches.get(0) : null;
    }
    private static boolean lowerToolbar(Node editor, Node button) {
        int height = button.bottom - button.top;
        // Native export can omit any subset of wrappers and optional controls.
        // Exact labels, uniqueness and local geometry identify the primary action.
        return height > 0 && button.right > button.left && button.top >= editor.bottom
                && button.top - editor.bottom <= 2 * height
                && button.left >= (editor.left + editor.right) / 2
                && Math.abs(button.right - editor.right) <= height;
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
            if (expected.equals(editor.text)) echoed = true;
            else if (echoed || (!editor.text.isEmpty() && !before.equals(editor.text))) cancel();
        }
        Status check(Node root, Node editor, String expected, boolean autoSend, boolean expired) {
            ready = false;
            observe(editor, expected);
            if (!written || dispatched || canceled) return Status.ABORT;
            if (!expected.equals(editor.text)) return expired ? Status.UNCONFIRMED : Status.WAIT;
            if (!autoSend) return Status.INSERTED;
            if (submit(root, editor) != null) { ready = true; return Status.SEND; }
            return expired ? Status.MANUAL : Status.WAIT;
        }
        boolean dispatch() {
            if (!written || dispatched || canceled || !echoed || !ready) return false;
            dispatched = true;
            return true;
        }
        boolean isCanceled() { return canceled; }
        boolean hasEcho() { return echoed; }
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
