package ai.hypermemetic.voicevault;

public class PaseoHarness {
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    static PaseoSelection.Node node(String label, int x, int y, int right, int bottom) {
        PaseoSelection.Node n = new PaseoSelection.Node();
        n.label = label; n.path = "/" + label; n.left = x; n.top = y;
        n.right = right; n.bottom = bottom; n.visible = n.enabled = n.clickable = true;
        n.text = "";
        return n;
    }
    static void hintAndExistingText() {
        String hint = PaseoSelection.COMPOSER;
        check(PaseoSelection.draftText(true, true, hint).isEmpty());
        check(PaseoSelection.draftText(true, true, "other placeholder").isEmpty());
        check(PaseoSelection.draftText(true, false, "").isEmpty());
        check(PaseoSelection.draftText(true, false, null).isEmpty());
        check(PaseoSelection.draftText(true, false, "real draft").equals("real draft"));
        check(PaseoSelection.draftText(true, false, hint).equals(hint));
        check(PaseoSelection.draftText(true, false, "  real draft  ").equals("  real draft  "));
        check(PaseoSelection.draftText(false, true, hint).equals(hint));

        PaseoSelection.Node root = node("root", 0, 0, 1000, 2000);
        PaseoSelection.Node editor = node(hint, 50, 1750, 800, 1840);
        editor.editable = true; root.children.add(editor);
        PaseoSelection.Node send = node("Send message", 820, 1755, 950, 1835);
        root.children.add(send);
        // The entry and prewrite snapshots may contain a real draft: SET_TEXT replaces it.
        editor.text = PaseoSelection.draftText(true, false, "real draft");
        check(PaseoSelection.composer(root) == editor);
        check(!editor.text.isEmpty());
        PaseoSelection.Gate gate = new PaseoSelection.Gate();
        check(gate.write());
        // Before the write is echoed the UI may show the hint in getText().
        editor.text = PaseoSelection.draftText(true, true, hint);
        check(gate.check(root, editor, "replacement", true, 0) == PaseoSelection.Status.WAIT);
        editor.text = PaseoSelection.draftText(true, false, "replacement");
        check(gate.check(root, editor, "replacement", true, 1) == PaseoSelection.Status.SEND);
        check(gate.dispatch()); check(!gate.dispatch());

        // Even after a successful echo, edits before send remain unsafe.
        PaseoSelection.Gate changed = new PaseoSelection.Gate();
        changed.write();
        check(changed.check(root, editor, "replacement", true, 0) == PaseoSelection.Status.SEND);
        editor.text = PaseoSelection.draftText(true, false, "user edited after insert");
        check(changed.check(root, editor, "replacement", true, 1) == PaseoSelection.Status.ABORT);
        changed.cancel(); // service clears the flow on ABORT
        check(!changed.dispatch());
        PaseoSelection.Gate intervened = new PaseoSelection.Gate();
        intervened.write();
        check(intervened.check(root, editor, "replacement", true, 0) == PaseoSelection.Status.ABORT);

        // Identical user content to the hint must remain real text and can be echoed.
        editor.text = PaseoSelection.draftText(true, false, hint);
        check(PaseoSelection.composer(root) == editor && editor.text.equals(hint));
        PaseoSelection.Gate literalHint = new PaseoSelection.Gate();
        literalHint.write();
        editor.text = PaseoSelection.draftText(true, true, hint);
        check(literalHint.check(root, editor, hint, true, 0) == PaseoSelection.Status.WAIT);
        editor.text = PaseoSelection.draftText(true, false, hint);
        check(literalHint.check(root, editor, hint, true, 1) == PaseoSelection.Status.SEND);
        check(literalHint.dispatch());
    }
    public static void main(String[] args) {
        hintAndExistingText();
        check(PaseoSelection.isPaseo("sh.paseo") && PaseoSelection.isPaseo("sh.paseo.debug"));
        check(!PaseoSelection.isPaseo("com.example.chat"));
        PaseoSelection.Node root = node("root", 0, 0, 1000, 2000);
        PaseoSelection.Node editor = node("Message agent...", 50, 1750, 800, 1840);
        editor.editable = true; root.children.add(editor);
        check(PaseoSelection.composer(root) == editor);
        PaseoSelection.Node meter = node("Context window 50% used", 830, 1630, 940, 1700);
        root.children.add(meter);
        for (String label : new String[] {"Send message", "Queue message", "Send and interrupt", "Send and steer"}) {
            PaseoSelection.Node send = node(label, 820, 1755, 950, 1835);
            root.children.add(send);
            check(PaseoSelection.submit(root, editor) == send);
            check(PaseoSelection.readiness(root, editor, "hello", true, 0) == PaseoSelection.Status.WAIT);
            editor.text = "hello";
            check(PaseoSelection.readiness(root, editor, "hello", true, 0) == PaseoSelection.Status.SEND);
            check(PaseoSelection.readiness(root, editor, "hello", false, 0) == PaseoSelection.Status.INSERTED);
            send.enabled = false; check(PaseoSelection.submit(root, editor) == null); send.enabled = true;
            send.visible = false; check(PaseoSelection.submit(root, editor) == null); send.visible = true;
            send.clickable = false; check(PaseoSelection.submit(root, editor) == null); send.clickable = true;
            PaseoSelection.Node duplicate = node(label, 820, 1755, 950, 1835);
            root.children.add(duplicate); check(PaseoSelection.submit(root, editor) == null);
            root.children.remove(duplicate); root.children.remove(send);
            editor.text = "";
        }
        for (String label : new String[] {"Stop", "Voice", "Submit", "Send message tooltip", "Send message now"}) {
            PaseoSelection.Node other = node(label, 820, 1755, 950, 1835);
            root.children.add(other); check(PaseoSelection.submit(root, editor) == null); root.children.remove(other);
        }
        editor.text = "user edit";
        check(PaseoSelection.readiness(root, editor, "hello", true, 1) == PaseoSelection.Status.ABORT);
        editor.text = "";
        check(PaseoSelection.readiness(root, editor, "hello", true, 7) == PaseoSelection.Status.ABORT);
        PaseoSelection.Node copy = node("Message agent...", 50, 1750, 800, 1840);
        copy.path = editor.path;
        check(PaseoSelection.same(editor, copy)); copy.path = "/other"; check(!PaseoSelection.same(editor, copy));
        root.children.add(copy); copy.editable = true; check(PaseoSelection.composer(root) == null);
        root.children.remove(copy); editor.visible = false; check(PaseoSelection.composer(root) == null);
        editor.visible = true; editor.enabled = false; check(PaseoSelection.composer(root) == null);
        editor.enabled = true;
        PaseoSelection.Gate gate = new PaseoSelection.Gate();
        check(!gate.dispatch()); check(gate.write()); check(!gate.write());
        check(gate.check(root, editor, "hello", true, 0) == PaseoSelection.Status.WAIT);
        editor.text = "hello";
        check(gate.check(root, editor, "hello", false, 1) == PaseoSelection.Status.INSERTED);
        editor.text = "";
        check(gate.check(root, editor, "hello", true, 2) == PaseoSelection.Status.ABORT);
        editor.text = "hello";
        check(gate.dispatch()); check(!gate.dispatch());
        check(gate.check(root, editor, "hello", true, 3) == PaseoSelection.Status.ABORT);
        PaseoSelection.Gate canceled = new PaseoSelection.Gate();
        canceled.write(); canceled.cancel();
        check(canceled.check(root, editor, "hello", true, 0) == PaseoSelection.Status.ABORT);
        check(!canceled.dispatch());
    }
}
