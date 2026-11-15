package samm.bridge;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Properties;

/**
 * Этот класс внедряется в игру (копируется из APK менеджера в отдельный dex).
 * Можно использовать только API Android — ни Kotlin, ни androidx здесь нет.
 *
 * {@link #afterGameLibs()} вызывается сразу после System.loadLibrary("GTASA").
 */
public final class Bridge {
    public static final String TAG = "SAMM";
    public static final String DIR = "samm";
    public static final String STATUS = "status.properties";
    public static final String HEARTBEAT = "heartbeat";
    public static final String CRASH = "crash.txt";

    static volatile Context sContext;
    static volatile Properties sConfig;
    private static volatile boolean sStarted;

    private Bridge() {
    }

    public static void afterGameLibs() {
        if (sStarted) return;
        sStarted = true;
        Properties status = new Properties();
        status.setProperty("startedAt", String.valueOf(System.currentTimeMillis()));
        status.setProperty("pid", String.valueOf(android.os.Process.myPid()));
        try {
            Context ctx = context();
            Properties cfg = config(ctx);
            status.setProperty("mode", cfg.getProperty("mode", "?"));
            if ("AML".equals(cfg.getProperty("mode"))) {
                try {
                    System.loadLibrary("AML");
                    status.setProperty("aml", "loaded");
                } catch (Throwable t) {
                    status.setProperty("aml", "error");
                    status.setProperty("amlError", String.valueOf(t));
                    Log.e(TAG, "AML не загрузился", t);
                }
            } else {
                status.setProperty("aml", "off");
            }
            installCrashHandler(ctx);
            writeStatus(ctx, status);
            startHeartbeat(ctx);
        } catch (Throwable t) {
            Log.e(TAG, "Bridge.afterGameLibs", t);
        }
    }

    static Context context() {
        Context c = sContext;
        if (c != null) return c;
        try {
            Object app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                sContext = (Context) app;
                return sContext;
            }
        } catch (Throwable ignored) {
        }
        throw new IllegalStateException("Нет контекста игры");
    }

    static Properties config(Context ctx) {
        Properties p = sConfig;
        if (p != null) return p;
        p = new Properties();
        try (InputStream in = ctx.getAssets().open("samm/bridge.properties")) {
            p.load(in);
        } catch (Throwable t) {
            Log.e(TAG, "Нет samm/bridge.properties", t);
        }
        sConfig = p;
        return p;
    }

    /** Корень данных игры: /sdcard/Android/data/<пакет>. */
    static File dataRoot(Context ctx) {
        File files = ctx.getExternalFilesDir(null);
        if (files == null) files = new File(ctx.getFilesDir(), "external");
        File root = files.getParentFile();
        return root != null ? root : files;
    }

    static File sammDir(Context ctx) {
        File d = new File(dataRoot(ctx), DIR);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static void writeStatus(Context ctx, Properties status) throws Exception {
        try (FileOutputStream out = new FileOutputStream(new File(sammDir(ctx), STATUS))) {
            status.store(out, "SAMM bridge");
        }
        //noinspection ResultOfMethodCallIgnored
        new File(sammDir(ctx), CRASH).delete();
    }

    private static void startHeartbeat(final Context ctx) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                File hb = new File(sammDir(ctx), HEARTBEAT);
                while (true) {
                    try {
                        try (FileOutputStream out = new FileOutputStream(hb)) {
                            out.write(String.valueOf(System.currentTimeMillis()).getBytes());
                        }
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "samm-heartbeat");
        t.setDaemon(true);
        t.start();
    }

    private static void installCrashHandler(final Context ctx) {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    try (FileOutputStream out = new FileOutputStream(new File(sammDir(ctx), CRASH))) {
                        out.write(("time=" + System.currentTimeMillis() + "\nthread=" + thread.getName() + "\n" + sw).getBytes("UTF-8"));
                    }
                } catch (Throwable ignored) {
                }
                if (prev != null) prev.uncaughtException(thread, e);
            }
        });
    }
}
