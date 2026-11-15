package samm.bridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Properties;

/**
 * Мост в игре: даёт менеджеру модов (и только ему) доступ к папке игры
 * /sdcard/Android/data/<пакет>/, куда на Android 11+ другие приложения не пускают.
 *
 * Вызовы: call("status"|"list"|"delete"|"mkdirs"|"rename"), openFile("content://…/f/<путь>").
 */
public final class BridgeProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        Bridge.sContext = getContext();
        return true;
    }

    // ---- доступ ----

    private void checkCaller() {
        Context ctx = getContext();
        if (ctx == null) throw new SecurityException("no context");
        Properties cfg = Bridge.config(ctx);
        String expectedPkg = cfg.getProperty("manager");
        String expectedCert = cfg.getProperty("managerCert");
        String caller = getCallingPackage();
        if (caller == null || expectedPkg == null || !caller.equals(expectedPkg)) {
            throw new SecurityException("Доступ только для менеджера модов");
        }
        if (expectedCert != null && !expectedCert.isEmpty() && !expectedCert.equalsIgnoreCase(certSha256(ctx, caller))) {
            throw new SecurityException("Подпись менеджера не совпадает");
        }
    }

    @SuppressWarnings("deprecation")
    static String certSha256(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
                sigs = pi.signingInfo.getApkContentsSigners();
            } else {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
                sigs = pi.signatures;
            }
            if (sigs == null || sigs.length == 0) return "";
            byte[] d = MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray());
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private File resolve(String rel) throws IOException {
        Context ctx = getContext();
        File root = Bridge.dataRoot(ctx).getCanonicalFile();
        File f = new File(root, rel == null ? "" : rel).getCanonicalFile();
        if (!f.getPath().equals(root.getPath()) && !f.getPath().startsWith(root.getPath() + File.separator)) {
            throw new SecurityException("Путь вне папки игры: " + rel);
        }
        return f;
    }

    // ---- команды ----

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        checkCaller();
        Bundle out = new Bundle();
        try {
            switch (method) {
                case "status": {
                    Context ctx = getContext();
                    File dir = new File(Bridge.dataRoot(ctx), Bridge.DIR);
                    out.putString("root", Bridge.dataRoot(ctx).getAbsolutePath());
                    out.putString("status", readText(new File(dir, Bridge.STATUS)));
                    out.putString("heartbeat", readText(new File(dir, Bridge.HEARTBEAT)));
                    out.putString("crash", readText(new File(dir, Bridge.CRASH)));
                    out.putLong("now", System.currentTimeMillis());
                    out.putString("mode", Bridge.config(ctx).getProperty("mode", "?"));
                    break;
                }
                case "list": {
                    File d = resolve(arg);
                    File[] files = d.listFiles();
                    ArrayList<String> names = new ArrayList<>();
                    int n = files == null ? 0 : files.length;
                    long[] sizes = new long[n];
                    boolean[] dirs = new boolean[n];
                    long[] times = new long[n];
                    for (int i = 0; i < n; i++) {
                        names.add(files[i].getName());
                        sizes[i] = files[i].length();
                        dirs[i] = files[i].isDirectory();
                        times[i] = files[i].lastModified();
                    }
                    out.putStringArrayList("names", names);
                    out.putLongArray("sizes", sizes);
                    out.putBooleanArray("dirs", dirs);
                    out.putLongArray("times", times);
                    out.putBoolean("exists", d.exists());
                    break;
                }
                case "delete":
                    out.putBoolean("ok", deleteRecursive(resolve(arg)));
                    break;
                case "mkdirs": {
                    File d = resolve(arg);
                    out.putBoolean("ok", d.isDirectory() || d.mkdirs());
                    break;
                }
                case "rename": {
                    File from = resolve(arg);
                    File to = resolve(extras == null ? null : extras.getString("to"));
                    File parent = to.getParentFile();
                    if (parent != null) //noinspection ResultOfMethodCallIgnored
                        parent.mkdirs();
                    out.putBoolean("ok", from.renameTo(to));
                    break;
                }
                default:
                    out.putString("error", "Неизвестная команда " + method);
            }
        } catch (Exception e) {
            out.putString("error", String.valueOf(e));
        }
        return out;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        checkCaller();
        String path = uri.getPath();
        if (path == null || !path.startsWith("/f/")) throw new FileNotFoundException("Неверный путь " + uri);
        try {
            File f = resolve(path.substring(3));
            int m = ParcelFileDescriptor.parseMode(mode);
            if ((m & ParcelFileDescriptor.MODE_WRITE_ONLY) != 0 || (m & ParcelFileDescriptor.MODE_READ_WRITE) != 0) {
                File parent = f.getParentFile();
                if (parent != null) //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
            }
            return ParcelFileDescriptor.open(f, m);
        } catch (IOException e) {
            throw new FileNotFoundException(String.valueOf(e));
        }
    }

    private static boolean deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        return !f.exists() || f.delete();
    }

    private static String readText(File f) {
        if (!f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 64 * 1024)];
            int n = in.read(b);
            return n <= 0 ? "" : new String(b, 0, n, "UTF-8");
        } catch (IOException e) {
            return null;
        }
    }

    // ---- не используется ----

    @Override
    public Cursor query(Uri uri, String[] p, String s, String[] a, String o) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String s, String[] a) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) {
        return 0;
    }
}
