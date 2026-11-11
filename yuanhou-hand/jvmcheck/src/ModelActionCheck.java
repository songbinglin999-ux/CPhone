import com.yuanhou.hand.model.BatchPlan;
import com.yuanhou.hand.model.ModelAction;
import com.yuanhou.hand.policy.UntilCheck;
import com.yuanhou.hand.screen.ScreenSnapshot;
import android.graphics.Rect;

public class ModelActionCheck {
    static int pass, fail;
    static void eq(String name, Object a, Object b) {
        if (String.valueOf(a).equals(String.valueOf(b))) { pass++; }
        else { fail++; System.out.println("FAIL " + name + ": " + a + " != " + b); }
    }

    public static void main(String[] args) {
        ModelAction tap = ModelAction.parse("{\"action\":\"tap\",\"ref\":3,\"say\":\"点搜索\"}");
        eq("single type", tap.type, "tap");
        eq("single ref", tap.ref, 3);
        eq("single not batch", tap.steps.size(), 0);

        ModelAction batch = ModelAction.parse("{\"action\":\"do_steps\",\"say\":\"打开美团再等\",\"steps\":["
                + "{\"action\":\"open_app\",\"app\":\"meituan\",\"expect_app\":\"meituan\",\"timeout_s\":10},"
                + "{\"action\":\"tap\",\"ref\":5,\"expect\":\"搜索\",\"wait_stable\":true}"
                + "]}");
        eq("batch type", batch.type, "do_steps");
        eq("batch n", batch.steps.size(), 2);
        eq("batch0 app", batch.steps.get(0).app, "meituan");
        eq("batch0 expect_app", batch.steps.get(0).expectApp, "meituan");
        eq("batch0 timeout", batch.steps.get(0).timeoutSec, 10);
        eq("batch1 expect", batch.steps.get(1).expect, "搜索");
        eq("batch1 stable", batch.steps.get(1).waitStable, true);
        eq("validate ok", BatchPlan.validate(batch), null);

        ModelAction empty = ModelAction.parse("{\"action\":\"do_steps\",\"steps\":[]}");
        eq("empty invalid", BatchPlan.validate(empty) != null, true);

        ModelAction tooMany = new ModelAction();
        tooMany.type = "do_steps";
        java.util.List<ModelAction> many = new java.util.ArrayList<>();
        for (int i = 0; i < 21; i++) { ModelAction s = new ModelAction(); s.type = "back"; many.add(s); }
        tooMany.steps = many;
        eq("21 invalid", BatchPlan.validate(tooMany).contains("最多 20"), true);

        ModelAction nested = ModelAction.parse("{\"action\":\"do_steps\",\"steps\":[{\"action\":\"do_steps\",\"steps\":[{\"action\":\"back\"}]}]}");
        // nested fill(top=false) does not parse inner steps, type is still do_steps
        eq("nested invalid", BatchPlan.validate(nested).contains("不能再套"), true);

        ModelAction pay = ModelAction.parse("{\"action\":\"do_steps\",\"steps\":[{\"action\":\"tap\",\"ref\":1,\"gate\":\"pay\"}]}");
        eq("pay invalid", BatchPlan.validate(pay).contains("付款"), true);

        eq("timeout default", UntilCheck.timeoutMs(new ModelAction()), 8000);
        ModelAction ts = new ModelAction(); ts.timeoutSec = 99;
        eq("timeout clamp", UntilCheck.timeoutMs(ts), 20000);

        ScreenSnapshot snap = new ScreenSnapshot();
        snap.packageName = "com.sankuai.meituan";
        ScreenSnapshot.Node n = new ScreenSnapshot.Node();
        n.text = "搜索商家";
        n.bounds = new Rect(10, 10, 200, 80);
        snap.nodes.add(n);
        ModelAction u = new ModelAction();
        u.expect = "搜索";
        u.expectApp = "meituan";
        eq("holds expect+app", UntilCheck.holds(u, snap), true);
        u.expectGone = "搜索商家";
        eq("holds gone fails", UntilCheck.holds(u, snap), false);
        eq("app alias", UntilCheck.packageOf("美团"), "com.sankuai.meituan");

        ModelAction junk = ModelAction.parse("not json at all");
        eq("bad json stop", junk.type, "stop");

        System.out.println("ModelActionCheck: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
