package local.watch7.guard;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PolicySettings {
    private static final String URI = "content://com.miui.powerkeeper.configure/userTable";
    private static final String WHERE = "\"pkgName='" + Config.TARGET + "' AND userId=0\"";
    private static final Pattern VALUE = Pattern.compile("\\bbgControl=([A-Za-z][A-Za-z0-9_]{0,63})(?:,|\\s|$)");
    private final SharedPreferences backup;

    PolicySettings(Context context) {
        backup = context.getSharedPreferences("original_policy", Context.MODE_PRIVATE);
    }

    String initialize() throws Exception {
        if (!Config.supported()) throw new IOException("此固件未适配，不写入配置。");
        String before = current();
        if (!backup.contains("value")
                && !backup.edit().putString("value", before).commit()) {
            throw new IOException("无法保存原设置，已取消修改。");
        }
        set("noRestrict");
        return "后台策略已设为 noRestrict。\n原值：" + backup.getString("value", "")
                + "\n请查看保护日志及当前状态，再验证锁屏通知。";
    }

    String restore() throws Exception {
        String original = backup.getString("value", null);
        if (original == null) return "没有本模块保存的原设置，无需恢复。";
        if (!original.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
            throw new IOException("备份值格式异常，未写入配置。");
        }
        String now = current();
        if (!now.equals("noRestrict") && !now.equals(original)) {
            throw new IOException("当前值已变为 " + now + "，为保留后续修改，未覆盖。备份：" + original);
        }
        set(original);
        if (!backup.edit().remove("value").commit()) {
            throw new IOException("设置已恢复，但未能清除备份；可再次执行恢复。");
        }
        return "后台策略已恢复为：" + original;
    }

    String current() throws Exception {
        String output = root("content query --uri " + URI
                + " --projection pkgName:bgControl:userId --where " + WHERE);
        Matcher rows = Pattern.compile("(?m)^Row:").matcher(output);
        int count = 0;
        while (rows.find()) count++;
        Matcher value = VALUE.matcher(output);
        if (count != 1 || !value.find()) {
            throw new IOException("未找到唯一的 Watch7 主用户配置，未修改。\n" + output);
        }
        return value.group(1);
    }

    private void set(String value) throws Exception {
        if (!value.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
            throw new IOException("配置值格式无效。");
        }
        root("content update --uri " + URI + " --bind bgControl:s:" + value + " --where " + WHERE);
        String after = current();
        if (!value.equals(after)) throw new IOException("写入后仍为 " + after + "，配置未保持。");
    }

    String status(int uid) throws Exception {
        String policy = current();
        String paths = "/sys/fs/cgroup/apps/uid_" + uid + "/pid_*/cgroup.freeze";
        String frozen = root("for p in " + paths + "; do "
                + "if [ -f \"$p\" ]; then printf '%s=' \"$p\"; cat \"$p\"; fi; done");
        return "后台策略：" + policy + "\nUID：" + uid
                + "\n当前进程冻结值（0=未冻结，1=冻结）：\n"
                + (frozen.isBlank() ? "没有运行中的目标进程或路径不可用。" : frozen);
    }

    static String logs() throws Exception {
        return root("logcat -d -v time -s Watch7Guard:I '*:S' | tail -n 40");
    }

    private static String root(String command) throws Exception {
        java.lang.Process process = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true).start();
        try {
            // Outputs are bounded: one database row, target process flags, or forty short log lines.
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                throw new IOException("Root 命令超时，请确认 KernelSU 已授权本应用。");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) throw new IOException("Root 命令失败：\n" + output);
            return output;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
