package local.watch7.guard;

import android.util.AtomicFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** A boot-only circuit breaker. No timer, polling, or persistent worker. */
final class BootGuard {
    private final GuardModule module;
    private final AtomicFile state;
    private final String label;
    private boolean completed;

    private BootGuard(GuardModule module, File path, String label) {
        this.module = module;
        this.state = new AtomicFile(path);
        this.label = label;
    }

    static BootGuard begin(GuardModule module, File path, String label) {
        BootGuard guard = new BootGuard(module, path, label);
        try {
            String previous;
            try {
                previous = new String(guard.state.readFully(), StandardCharsets.UTF_8).trim();
            } catch (FileNotFoundException firstUse) {
                previous = "";
            }
            if (!previous.isEmpty() && !previous.equals("ready")) {
                guard.write("disabled");
                module.info("BOOT GUARD DISABLED " + label
                        + ": previous startup incomplete or protection already disabled");
                return null;
            }
            guard.write("pending");
            module.info("BOOT GUARD ARMED " + label);
            return guard;
        } catch (Throwable error) {
            module.failure("boot guard " + label + " (hooks skipped)", error);
            return null;
        }
    }

    synchronized void complete() {
        if (completed) return;
        try {
            write("ready");
            completed = true;
            module.info("BOOT GUARD READY " + label);
        } catch (Throwable error) {
            module.failure("boot guard completion " + label, error);
        }
    }

    private void write(String value) throws IOException {
        FileOutputStream output = null;
        try {
            output = state.startWrite();
            output.write((value + "\n").getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
            state.finishWrite(output);
            output = null;
            String actual = new String(state.readFully(), StandardCharsets.UTF_8).trim();
            if (!actual.equals(value)) throw new IOException("boot marker did not persist");
        } finally {
            if (output != null) state.failWrite(output);
        }
    }
}