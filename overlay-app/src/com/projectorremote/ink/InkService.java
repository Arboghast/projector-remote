package com.projectorremote.ink;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws the phone's ink live over any app. The relay reaches it through `adb forward` to a
 * loopback-only port and sends one JSON message per line (the same ink messages the stage page
 * takes, plus overlay-on / overlay-off).
 */
public final class InkService extends Service {
    private static final int PORT = 4790;
    private static final String CHANNEL = "ink";

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private InkView ink;
    private boolean shown;
    private volatile boolean running = true;
    private ServerSocket server;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Drawing", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Projector Ink")
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .build();
        startForeground(1, n);

        windows = getSystemService(WindowManager.class);
        ink = new InkView(this);
        new Thread(this::serve, "ink-server").start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void show(boolean on) {
        if (on == shown) return;
        if (on) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    // Never steal input: the remote keeps driving the app underneath.
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            windows.addView(ink, lp);
        } else {
            ink.clear();
            windows.removeView(ink);
        }
        shown = on;
    }

    private void serve() {
        try {
            server = new ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1"));
            while (running) {
                try (Socket s = server.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = in.readLine()) != null) handle(line);
                } catch (Exception ignored) {
                    // client went away; wait for the next one
                }
                main.post(() -> show(false));
            }
        } catch (Exception e) {
            stopSelf();
        }
    }

    private void handle(String line) {
        try {
            JSONObject m = new JSONObject(line);
            String type = m.optString("type");
            switch (type) {
                case "overlay-on": main.post(() -> show(true)); break;
                case "overlay-off": main.post(() -> show(false)); break;
                case "ink-clear": main.post(ink::clear); break;
                case "ink-undo": main.post(ink::undo); break;
                case "ink": {
                    String id = m.getString("id");
                    String color = m.optString("color", "#f5a524");
                    float size = (float) m.optDouble("size", 0.006);
                    String tool = m.optString("tool", "pen");
                    JSONArray pts = m.getJSONArray("points");
                    List<float[]> points = new ArrayList<>(pts.length());
                    for (int i = 0; i < pts.length(); i++) {
                        JSONArray p = pts.getJSONArray(i);
                        points.add(new float[]{(float) p.getDouble(0), (float) p.getDouble(1)});
                    }
                    main.post(() -> ink.add(id, color, size, tool, points));
                    break;
                }
                default: break;
            }
        } catch (Exception ignored) {
            // one bad message shouldn't end the session
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        main.post(() -> show(false));
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
