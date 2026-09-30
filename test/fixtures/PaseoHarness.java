package ai.hypermemetic.voicevault;

/** Source-faithful COLUMN: full-width input, 12-unit gap, lower 28-unit toolbar. */
public class PaseoHarness {
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    static PaseoSelection.Node node(String label, int x, int y, int right, int bottom) {
        PaseoSelection.Node n = new PaseoSelection.Node();
        n.label = label; n.path = "/" + label; n.left = x; n.top = y;
        n.right = right; n.bottom = bottom; n.visible = n.enabled = n.clickable = true;
        n.text = "";
        return n;
    }
    static class Fixture {
        PaseoSelection.Node root, column, editor, toolbar, send;
        Fixture(int y, int inputHeight, int scale) {
            root = node("screen", 0, 0, 400 * scale, 900 * scale);
            column = node("inputWrapper", 12 * scale, y * scale, 388 * scale,
                    (y + inputHeight + 12 + 28 + 8) * scale);
            editor = node(PaseoSelection.COMPOSER, 24 * scale, (y + 8) * scale,
                    376 * scale, (y + 8 + inputHeight) * scale);
            editor.editable = true;
            toolbar = node("buttonRow", 18 * scale, editor.bottom + 12 * scale,
                    382 * scale, editor.bottom + 40 * scale);
            // The row's negative horizontal margin may put the control 6 units
            // beyond the input edge, never to the right OF the entire input.
            send = node("Send message", 354 * scale, toolbar.top, 382 * scale, toolbar.bottom);
            root.children.add(column); column.children.add(editor); column.children.add(toolbar);
            toolbar.children.add(node("Context window 50% used", 18 * scale, toolbar.top, 90 * scale, toolbar.bottom));
            toolbar.children.add(node("Voice", 320 * scale, toolbar.top, 348 * scale, toolbar.bottom));
            toolbar.children.add(send);
        }
    }
    static PaseoSelection.Gate written(String before) {
        PaseoSelection.Gate g = new PaseoSelection.Gate();
        check(!g.dispatch()); check(g.write(before)); check(!g.write(before)); return g;
    }
    static void selection() {
        check(PaseoSelection.isPaseo("sh.paseo") && PaseoSelection.isPaseo("sh.paseo.debug"));
        check(!PaseoSelection.isPaseo("com.example.chat"));
        for (int scale : new int[] {1, 3}) for (int y : new int[] {420, 700})
            for (int height : new int[] {20, 120}) {
                Fixture f = new Fixture(y, height, scale);
                check(PaseoSelection.composer(f.root) == f.editor);
                for (String label : new String[] {"Send message", "Queue message", "Send and interrupt", "Send and steer"}) {
                    f.send.label = label;
                    check(PaseoSelection.submit(f.root, f.editor) == f.send);
                    f.send.enabled = false; check(PaseoSelection.submit(f.root, f.editor) == null); f.send.enabled = true;
                    f.send.visible = false; check(PaseoSelection.submit(f.root, f.editor) == null); f.send.visible = true;
                    f.send.clickable = false; check(PaseoSelection.submit(f.root, f.editor) == null); f.send.clickable = true;
                    PaseoSelection.Node duplicate = node(label, f.send.left, f.send.top, f.send.right, f.send.bottom);
                    f.toolbar.children.add(duplicate); check(PaseoSelection.submit(f.root, f.editor) == null);
                    f.toolbar.children.remove(duplicate);
                }
                for (String label : new String[] {"Context window 50% used", "Stop", "Voice", "Submit", "Send", "Send message tooltip"}) {
                    f.send.label = label; check(PaseoSelection.submit(f.root, f.editor) == null);
                }
                f.send.label = "Send message";
                f.toolbar.children.remove(f.send); f.root.children.add(f.send);
                check(PaseoSelection.submit(f.root, f.editor) == null); // global match at same bounds
                f.root.children.remove(f.send); f.toolbar.children.add(f.send);
                f.send.top -= 100 * scale; f.send.bottom -= 100 * scale;
                check(PaseoSelection.submit(f.root, f.editor) == null); // above input, context area
                f.send.top += 100 * scale; f.send.bottom += 100 * scale;
                PaseoSelection.Node copy = node(PaseoSelection.COMPOSER, f.editor.left, f.editor.top, f.editor.right, f.editor.bottom);
                copy.editable = true; f.column.children.add(copy); check(PaseoSelection.composer(f.root) == null);
                f.column.children.remove(copy);
                f.editor.enabled = false; check(PaseoSelection.composer(f.root) == null);
            }
    }
    static void flattenedNativeTree() {
        Fixture f = new Fixture(420, 24, 1);
        // Observed Android export: non-important column/row disappear, while
        // input and toolbar controls become direct siblings of the window root.
        f.root.children.clear(); f.root.children.add(f.editor);
        f.root.children.addAll(f.toolbar.children);
        check(PaseoSelection.submit(f.root, f.editor) == f.send);
        PaseoSelection.Node other = node("Queue message", f.send.left, f.send.top, f.send.right, f.send.bottom);
        f.root.children.add(other); check(PaseoSelection.submit(f.root, f.editor) == null);
        f.root.children.remove(other);
        f.root.children.removeAll(f.toolbar.children); f.root.children.add(f.send);
        check(PaseoSelection.submit(f.root, f.editor) == null); // isolated global match is not a toolbar
    }
    static void flow() {
        Fixture f = new Fixture(420, 20, 1);
        String hint = PaseoSelection.COMPOSER, expected = "replacement";
        check(PaseoSelection.draftText(true, true, hint).isEmpty());
        check(PaseoSelection.draftText(true, true, "other placeholder").isEmpty());
        check(PaseoSelection.draftText(true, false, null).isEmpty());
        check(PaseoSelection.draftText(true, false, hint).equals(hint));
        check(PaseoSelection.draftText(true, false, "  real draft  ").equals("  real draft  "));
        check(PaseoSelection.draftText(false, true, hint).equals(hint));
        PaseoSelection.Gate g = written("existing draft");
        f.editor.text = "existing draft"; // no unsolicited existing-draft prohibition
        check(g.check(f.root, f.editor, expected, true, 0) == PaseoSelection.Status.WAIT);
        f.editor.text = PaseoSelection.draftText(true, true, hint);
        check(g.check(f.root, f.editor, expected, true, 2) == PaseoSelection.Status.WAIT);
        check(!g.dispatch());
        f.editor.text = expected;
        check(g.check(f.root, f.editor, expected, true, 5) == PaseoSelection.Status.SEND);
        check(g.dispatch()); check(!g.dispatch());
        check(g.check(f.root, f.editor, expected, true, 6) == PaseoSelection.Status.ABORT);

        f.send.enabled = false;
        g = written("");
        check(g.check(f.root, f.editor, expected, true, 0) == PaseoSelection.Status.WAIT);
        check(g.check(f.root, f.editor, expected, true, 7) == PaseoSelection.Status.MANUAL);
        check(!g.dispatch());
        check("Inserted — send manually".equals(PaseoSelection.feedback(PaseoSelection.Status.MANUAL)));
        check(PaseoSelection.feedback(PaseoSelection.Status.ABORT) == null);
        check(PaseoSelection.feedback(PaseoSelection.Status.INSERTED) == null);
        f.send.enabled = true;
        for (boolean reset : new boolean[] {false, true}) {
            g = written("");
            check(g.check(f.root, f.editor, expected, true, 0) == PaseoSelection.Status.SEND);
            f.editor.text = ""; // manual send/clear, including input identity lost on remount
            g.observe(reset ? null : f.editor, expected);
            check(g.check(f.root, reset ? null : f.editor, expected, true, 1) == PaseoSelection.Status.ABORT);
            check(!g.dispatch());
            f.editor.text = expected; check(!g.dispatch()); // no resurrection
        }
        g = written(""); g.observe(f.editor, expected); f.editor.text = "user edit";
        check(g.check(f.root, f.editor, expected, true, 1) == PaseoSelection.Status.ABORT);
        check(!g.dispatch());
        g = written(""); f.editor.text = "";
        check(g.check(f.root, f.editor, expected, true, 7) == PaseoSelection.Status.UNCONFIRMED);
        check(PaseoSelection.feedback(PaseoSelection.Status.UNCONFIRMED).contains("insertion unconfirmed"));
        g = written(""); g.cancel(); f.editor.text = expected;
        check(g.check(f.root, f.editor, expected, true, 0) == PaseoSelection.Status.ABORT); check(!g.dispatch());
        g = written("");
        check(g.check(f.root, f.editor, expected, false, 0) == PaseoSelection.Status.INSERTED); check(!g.dispatch());
        g = written(""); f.editor.text = PaseoSelection.draftText(true, true, hint);
        check(g.check(f.root, f.editor, hint, true, 0) == PaseoSelection.Status.WAIT);
        f.editor.text = PaseoSelection.draftText(true, false, hint);
        check(g.check(f.root, f.editor, hint, true, 1) == PaseoSelection.Status.SEND); check(g.dispatch());
    }
    public static void main(String[] args) { selection(); flattenedNativeTree(); flow(); }
}
