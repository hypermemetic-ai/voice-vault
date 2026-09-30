package ai.hypermemetic.voicevault;

import java.util.ArrayList;
import java.util.List;

/** Conservative, source-label-based selection. No substring or ancestor click matching. */
final class PaseoSelection {
    static final String COMPOSER = "Message agent...";
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
        // Paseo's primary control sits beside the input in the bottom composer toolbar.
        // Reject controls outside the editor's local band, including the context meter above it.
        collect(root, n -> !n.editable && n.visible && n.enabled && n.clickable
                && primary(n.label) && n.top >= editor.top - (editor.bottom - editor.top)
                && n.bottom <= editor.bottom + (editor.bottom - editor.top)
                && n.left >= editor.right && n.right <= root.right, matches);
        return matches.size() == 1 ? matches.get(0) : null;
    }
    interface Filter { boolean accept(Node n); }
    private static void collect(Node n, Filter f, List<Node> matches) {
        if (n == null) return;
        if (f.accept(n)) matches.add(n);
        for (Node child : n.children) collect(child, f, matches);
    }
    /** One request; neither a failed action nor delayed UI rendering authorizes a second dispatch. */
    static final class Gate {
        private boolean written, echoed, dispatched, canceled;
        boolean write() { if (written || canceled) return false; written = true; return true; }
        Status check(Node root, Node editor, String expected, boolean autoSend, int attempt) {
            if (!written || dispatched || canceled) return Status.ABORT;
            if (editor != null && expected.equals(editor.text)) echoed = true;
            if (echoed && (editor == null || !expected.equals(editor.text))) return Status.ABORT;
            return readiness(root, editor, expected, autoSend, attempt);
        }
        boolean dispatch() {
            if (!written || dispatched || canceled || !echoed) return false;
            dispatched = true;
            return true;
        }
        void cancel() { canceled = true; }
    }
    enum Status { WAIT, ABORT, INSERTED, SEND }
    static Status readiness(Node root, Node editor, String expected, boolean autoSend, int attempt) {
        if (editor == null) return Status.ABORT;
        if (!expected.equals(editor.text)) return editor.text.isEmpty() && attempt < 7 ? Status.WAIT : Status.ABORT;
        if (!autoSend) return Status.INSERTED;
        return submit(root, editor) != null ? Status.SEND : attempt < 7 ? Status.WAIT : Status.ABORT;
    }
    static boolean same(Node a, Node b) {
        return a != null && b != null && a.path.equals(b.path)
                && a.left == b.left && a.top == b.top && a.right == b.right && a.bottom == b.bottom;
    }
}
