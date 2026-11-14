package local.watch7.guard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {
    private final List<Button> buttons = new ArrayList<>();
    private TextView output;
    private PolicySettings settings;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new PolicySettings(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        scroll.addView(layout);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("三星手表通知保护 0.1");
        title.setTextSize(23);
        layout.addView(title);
        TextView explanation = new TextView(this);
        explanation.setText("\n适配：HyperOS " + Config.FIRMWARE + " / Android 17\n"
                + "当前：" + Build.VERSION.INCREMENTAL + " / SDK " + Build.VERSION.SDK_INT + "\n"
                + (Config.supported() ? "固件版本符合，Hook 是否命中需查看日志。" : "固件未适配，模块不会启用 Hook。")
                + "\n\n使用顺序：\n"
                + "1. 在 LSPosed 启用本模块，作用域为系统框架和电量和性能（PowerKeeper）。\n"
                + "2. 重启手机，再查看日志确认两个保护均已加载。\n"
                + "3. 点“一次性设置”，在 KernelSU 授权本应用 Root。\n\n"
                + "仅保护主用户的 Watch7 插件。含启动失败回退；无自动轮询、定时任务和唤醒锁。\n");
        explanation.setTextSize(16);
        layout.addView(explanation);

        button(layout, "一次性设置为不限制", () -> settings.initialize());
        button(layout, "检查当前策略和冻结状态", () -> {
            ApplicationInfo app = getPackageManager().getApplicationInfo(Config.TARGET, 0);
            return settings.status(app.uid);
        });
        button(layout, "查看保护日志", () -> {
            String logs = PolicySettings.logs();
            return logs.isBlank() ? "尚无保护日志。请确认模块已启用并重启；本页不能直接确认框架是否加载。" : logs;
        });
        Button restore = new Button(this);
        restore.setText("恢复原后台设置");
        restore.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("恢复前请先停用模块")
                .setMessage("请先在 LSPosed 停用本模块并重启，然后恢复。恢复完成后再卸载，避免丢失原设置备份。")
                .setPositiveButton("恢复原设置", (dialog, which) -> run(() -> settings.restore()))
                .setNegativeButton("取消", null).show());
        buttons.add(restore);
        layout.addView(restore);

        output = new TextView(this);
        output.setTextSize(14);
        output.setTextIsSelectable(true);
        output.setPadding(0, padding, 0, padding);
        layout.addView(output);
        if (Process.myUid() >= 100000) {
            for (Button button : buttons) button.setEnabled(false);
            output.setText("此版本仅支持主用户，请在手机主用户安装和使用。");
        }
    }

    private void button(LinearLayout layout, String label, Action action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> run(action));
        buttons.add(button);
        layout.addView(button);
    }

    private void run(Action action) {
        for (Button button : buttons) button.setEnabled(false);
        output.setText("执行一次检查或设置，等待 Root 授权…");
        new Thread(() -> {
            String result;
            try {
                result = action.perform();
            } catch (Exception error) {
                result = error.getClass().getSimpleName() + "：" + error.getMessage();
            }
            final String text = result;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                output.setText(text);
                for (Button button : buttons) button.setEnabled(true);
            });
        }, "Watch7Guard-manual").start();
    }

    private interface Action {
        String perform() throws Exception;
    }
}
