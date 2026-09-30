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
    public static void main(String[] args) {
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
