package io.freemytv;

import android.content.ClipData;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * The phone remote's hands: a plain Java program the TV's ADB starts as the shell user from our own APK
 * (CLASSPATH=base.apk app_process, like scrcpy and Shizuku), since `input keyevent` takes 1.3 s a key on a 2021 Xiaomi.
 * It injects keys through InputManager, pastes text through the clipboard (Cyrillic too) and opens apps with `am`.
 * It listens only on 127.0.0.1, and each connection must first send the token PhoneRemote started it with: the shell
 * user can press any key, so no other app on the TV may drive it.
 *
 * Lines: "auth TOKEN" (answer "ok VERSION"), "key CODE", "long CODE", "paste BASE64", "start PKG/CLASS",
 * "view URL", "quit".
 */
public final class Injector {
    static final int PORT = 8643;
    private static final Pattern COMPONENT = Pattern.compile("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+");
    private static final Pattern URL = Pattern.compile("https?://[^\\s\"'`\\\\]{1,2000}");

    private static Object input;
    private static Method inject;

    private Injector() {}

    public static void main(String[] args) throws Exception {
        final String token = args.length > 0 ? args[0] : "";
        final String version = args.length > 1 ? args[1] : "";
        if (token.length() < 16) return;
        Looper.prepareMainLooper();
        Class<?> im = Class.forName("android.hardware.input.InputManager");
        input = im.getMethod("getInstance").invoke(null);
        inject = im.getMethod("injectInputEvent", InputEvent.class, int.class);
        try (ServerSocket server = new ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"))) {
            while (true) {
                final Socket s = server.accept();
                new Thread(() -> serve(s, token, version)).start();
            }
        }
    }

    private static void serve(Socket s, String token, String version) {
        try (Socket socket = s) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();
            String first = in.readLine();
            if (first == null || !constantEquals(first, "auth " + token)) return;
            out.write(("ok " + version + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                int space = line.indexOf(' ');
                String cmd = space < 0 ? line : line.substring(0, space), arg = space < 0 ? "" : line.substring(space + 1);
                try {
                    switch (cmd) {
                        case "key": key(Integer.parseInt(arg), false); break;
                        case "long": key(Integer.parseInt(arg), true); break;
                        case "paste": paste(new String(Base64.decode(arg, Base64.NO_WRAP), StandardCharsets.UTF_8)); break;
                        case "start":
                            if (COMPONENT.matcher(arg).matches()) exec("am", "start", "-n", arg);
                            break;
                        case "view":
                            if (URL.matcher(arg).matches()) exec("am", "start", "-a", "android.intent.action.VIEW", "-d", arg);
                            break;
                        case "quit": System.exit(0); break;
                        default: break;
                    }
                } catch (Exception e) {
                    System.err.println("injector: " + line + ": " + e);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void key(int code, boolean hold) throws Exception {
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD | InputDevice.SOURCE_DPAD);
        inject.invoke(input, down, 0);
        if (hold) {
            SystemClock.sleep(600);
            long at = SystemClock.uptimeMillis();
            inject.invoke(input, KeyEvent.changeTimeRepeat(down, at, 1, down.getFlags() | KeyEvent.FLAG_LONG_PRESS), 0);
            SystemClock.sleep(100);
        }
        inject.invoke(input, KeyEvent.changeTimeRepeat(KeyEvent.changeAction(down, KeyEvent.ACTION_UP), SystemClock.uptimeMillis(), 0), 0);
    }

    /** Into the clipboard, then the Paste key: what the TV's own keyboard takes, in any language. */
    private static void paste(String text) throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "clipboard");
        Object clipboard = Class.forName("android.content.IClipboard$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
        ClipData clip = ClipData.newPlainText("Free My TV", text);
        for (Method m : clipboard.getClass().getMethods()) {
            if (!m.getName().equals("setPrimaryClip")) continue;
            // (clip, package, user) on Android 11; Android 12 adds an attribution tag, 14 a device id.
            Class<?>[] types = m.getParameterTypes();
            Object[] values = new Object[types.length];
            int strings = 0;
            for (int i = 0; i < types.length; i++) {
                if (types[i] == ClipData.class) values[i] = clip;
                else if (types[i] == String.class) values[i] = strings++ == 0 ? "com.android.shell" : null;
                else if (types[i] == int.class) values[i] = 0;
            }
            m.invoke(clipboard, values);
            break;
        }
        key(KeyEvent.KEYCODE_PASTE, false);
    }

    private static void exec(String... command) throws Exception {
        new ProcessBuilder(command).redirectErrorStream(true).start().waitFor();
    }

    private static boolean constantEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8), y = b.getBytes(StandardCharsets.UTF_8);
        int diff = x.length ^ y.length;
        for (int i = 0; i < Math.min(x.length, y.length); i++) diff |= x[i] ^ y[i];
        return diff == 0;
    }
}
