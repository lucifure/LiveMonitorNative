package com.livemonitor.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MonitorService extends Service {

    private static final String TAG = "MonitorService";
    private static final String CHANNEL_ID = "LiveMonitorChannel";
    private static final String CHANNEL_LIVE_ID = "LiveDetectedChannel";
    private static final int NOTIF_ID = 1;
    private static final int POLL_SECONDS = 60;
    private static final String YT_API_KEY = "AIzaSyDnAsBrxe_aFkUSpqkrFDczUw-PpLoEhuY";

    private PowerManager.WakeLock wakeLock;
    private ExecutorService executor;
    private volatile boolean running = false;
    private volatile boolean recording = false;
    private String channelUrl = "";
    private Process recordingProcess;

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newCachedThreadPool();
        createNotificationChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if ("START".equals(action)) {
            channelUrl = intent.getStringExtra("url");
            startForeground(NOTIF_ID, buildNotification("Monitoring: " + shortUrl(channelUrl)));
            acquireWakeLock();
            running = true;
            executor.execute(this::monitorLoop);
        } else if ("STOP".equals(action)) {
            stopAll();
        }
        return START_NOT_STICKY;
    }

    // ── Monitor loop ──────────────────────────────────────────────────────────
    private void monitorLoop() {
        sendLog("Monitor started. Wake lock ON.", "success");
        sendLog("Checking: " + channelUrl, "info");

        while (running) {
            sendLog("Checking live status...", "dim");
            try {
                String channelId = getChannelId(channelUrl);
                if (channelId == null) {
                    sendLog("Could not resolve channel ID. Retrying in 60s...", "error");
                    sleep(POLL_SECONDS);
                    continue;
                }

                String[] liveInfo = checkLive(channelId);
                if (liveInfo != null) {
                    String videoId = liveInfo[0];
                    String title = liveInfo[1];
                    sendLog("LIVE DETECTED: " + title, "live");
                    updateNotification("LIVE: " + title);
                    sendLiveNotification(title, videoId);

                    // Start recording
                    if (!recording) {
                        recording = true;
                        String watchUrl = "https://youtube.com/watch?v=" + videoId;
                        executor.execute(() -> startRecording(watchUrl, title));
                    }

                    // Keep checking while live
                    while (running && recording) {
                        sleep(POLL_SECONDS);
                        if (!running) break;
                        sendLog("Re-checking stream...", "dim");
                        String[] stillLive = checkLive(channelId);
                        if (stillLive == null) {
                            sendLog("Stream ended. Stopping recording...", "warning");
                            stopRecording();
                            recording = false;
                            updateNotification("Monitoring: " + shortUrl(channelUrl));
                            sendLog("Resuming monitor in 60s...", "info");
                            break;
                        }
                    }
                } else {
                    sendLog("Not live. Next check in " + POLL_SECONDS + "s...", "dim");
                    sleep(POLL_SECONDS);
                }
            } catch (Exception e) {
                sendLog("Error: " + e.getMessage(), "error");
                sleep(POLL_SECONDS);
            }
        }
        sendLog("Monitor stopped.", "info");
    }

    // ── YouTube API: Get Channel ID ───────────────────────────────────────────
    private String getChannelId(String url) {
        try {
            // Extract @handle
            String handle = null;
            if (url.contains("/@")) {
                handle = url.substring(url.indexOf("/@") + 2).replaceAll("[/?#].*", "");
            } else if (url.contains("/channel/")) {
                return url.substring(url.indexOf("/channel/") + 9).replaceAll("[/?#].*", "");
            }

            if (handle == null) return null;

            // Use YouTube API forHandle
            String apiUrl = "https://www.googleapis.com/youtube/v3/channels?part=id&forHandle="
                + handle + "&key=" + YT_API_KEY;
            String response = httpGet(apiUrl);
            if (response == null) return null;

            JSONObject json = new JSONObject(response);
            JSONArray items = json.optJSONArray("items");
            if (items != null && items.length() > 0) {
                return items.getJSONObject(0).getString("id");
            }
        } catch (Exception e) {
            sendLog("getChannelId error: " + e.getMessage(), "error");
        }
        return null;
    }

    // ── YouTube API: Check Live ───────────────────────────────────────────────
    private String[] checkLive(String channelId) {
        try {
            String apiUrl = "https://www.googleapis.com/youtube/v3/search?part=snippet"
                + "&channelId=" + channelId
                + "&eventType=live&type=video&maxResults=1&key=" + YT_API_KEY;
            String response = httpGet(apiUrl);
            if (response == null) return null;

            JSONObject json = new JSONObject(response);
            JSONArray items = json.optJSONArray("items");
            if (items != null && items.length() > 0) {
                JSONObject item = items.getJSONObject(0);
                String videoId = item.getJSONObject("id").getString("videoId");
                String title = item.getJSONObject("snippet").getString("title");
                return new String[]{videoId, title};
            }
        } catch (Exception e) {
            sendLog("checkLive error: " + e.getMessage(), "error");
        }
        return null;
    }

    // ── Recording using yt-dlp ────────────────────────────────────────────────
    private void startRecording(String watchUrl, String title) {
        try {
            File ytdlp = getOrDownloadYtdlp();
            if (ytdlp == null) {
                sendLog("yt-dlp not available. Cannot record.", "error");
                recording = false;
                return;
            }

            String date = new SimpleDateFormat("ddMMyyyy_HHmm", Locale.getDefault()).format(new Date());
            String safeTitle = title.replaceAll("[^a-zA-Z0-9._-]", "_").substring(0,
                Math.min(title.length(), 50));
            String outFile = "/storage/emulated/0/Download/YouTubeMonitor/"
                + safeTitle + "_" + date + ".mp4";

            // Create output dir
            new File("/storage/emulated/0/Download/YouTubeMonitor/").mkdirs();

            sendLog("Recording to: " + outFile, "info");
            sendLog("Starting yt-dlp...", "info");

            ProcessBuilder pb = new ProcessBuilder(
                ytdlp.getAbsolutePath(),
                "--no-part",
                "--restrict-filenames",
                "--merge-output-format", "mp4",
                "-f", "bestvideo[height<=720]+bestaudio/best[height<=720]",
                "-o", outFile,
                "--socket-timeout", "90",
                "--retries", "50",
                "--fragment-retries", "50",
                watchUrl
            );
            pb.redirectErrorStream(true);
            pb.environment().put("HOME", getFilesDir().getAbsolutePath());

            recordingProcess = pb.start();

            // Read output
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(recordingProcess.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null && running) {
                if (line.contains("[download]") || line.contains("[info]") ||
                    line.contains("Destination") || line.contains("Merging")) {
                    sendLog(line, "info");
                }
            }

            int exitCode = recordingProcess.waitFor();
            sendLog("yt-dlp exited with code: " + exitCode, exitCode == 0 ? "success" : "error");

        } catch (Exception e) {
            sendLog("Recording error: " + e.getMessage(), "error");
        }
        recording = false;
    }

    private void stopRecording() {
        if (recordingProcess != null) {
            recordingProcess.destroy();
            recordingProcess = null;
        }
    }

    // ── Download yt-dlp binary ────────────────────────────────────────────────
    private File getOrDownloadYtdlp() {
        // Use app's own files dir with exec permission via chmod
        // Use OBB directory — allows execution on Android
        File execDir = getObbDir();
        if (execDir == null) execDir = getFilesDir();
        execDir.mkdirs();
        File ytdlp = new File(execDir, "yt-dlp");

        if (ytdlp.exists()) {
            // Force executable permission using chmod via shell
            try {
                new ProcessBuilder("chmod", "777", ytdlp.getAbsolutePath()).start().waitFor();
            } catch (Exception ignored) {}
            sendLog("yt-dlp ready.", "success");
            return ytdlp; // Return regardless of canExecute
        }

        sendLog("Downloading yt-dlp binary (~10MB)...", "warning");
        String downloadUrl = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux_aarch64";

        try {
            URL url = new URL(downloadUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();

            InputStream input = conn.getInputStream();
            FileOutputStream output = new FileOutputStream(ytdlp);
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
            output.close();
            input.close();

            // Set executable using multiple methods
            ytdlp.setExecutable(true, false);
            try {
                new ProcessBuilder("chmod", "777", ytdlp.getAbsolutePath()).start().waitFor();
            } catch (Exception ignored) {}

            sendLog("yt-dlp downloaded! Size: " + ytdlp.length() + " bytes", "success");
            return ytdlp; // Return regardless — chmod should work
        } catch (Exception e) {
            sendLog("Failed to download yt-dlp: " + e.getMessage(), "error");
            return null;
        }
    }

    // ── HTTP GET ──────────────────────────────────────────────────────────────
    private String httpGet(String urlString) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");

            int code = conn.getResponseCode();
            if (code != 200) return null;

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ── Wake lock ─────────────────────────────────────────────────────────────
    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LiveMonitor::WakeLock");
        wakeLock.acquire();
        sendLog("CPU Wake lock acquired.", "success");
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────────
    private void createNotificationChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);

        NotificationChannel monitorCh = new NotificationChannel(
            CHANNEL_ID, "Monitor Status", NotificationManager.IMPORTANCE_LOW);
        monitorCh.setDescription("Shows monitoring status");
        nm.createNotificationChannel(monitorCh);

        NotificationChannel liveCh = new NotificationChannel(
            CHANNEL_LIVE_ID, "Live Detected", NotificationManager.IMPORTANCE_HIGH);
        liveCh.setDescription("Alerts when stream goes live");
        nm.createNotificationChannel(liveCh);
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Live Monitor")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    private void sendLiveNotification(String title, String videoId) {
        Intent intent = new Intent(Intent.ACTION_VIEW,
            android.net.Uri.parse("https://youtube.com/watch?v=" + videoId));
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new NotificationCompat.Builder(this, CHANNEL_LIVE_ID)
            .setContentTitle("🔴 Stream is LIVE!")
            .setContentText(title)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(new long[]{0, 500, 200, 500, 200, 500})
            .build();

        getSystemService(NotificationManager.class).notify(2, notif);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private void sendLog(String message, String type) {
        Log.d(TAG, message);
        Intent intent = new Intent("MONITOR_LOG");
        intent.putExtra("message", message);
        intent.putExtra("type", type);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private String shortUrl(String url) {
        return url.replace("https://www.youtube.com/", "")
                  .replace("https://youtube.com/", "");
    }

    private void sleep(int seconds) {
        try { Thread.sleep(seconds * 1000L); } catch (InterruptedException ignored) {}
    }

    private void stopAll() {
        running = false;
        recording = false;
        stopRecording();
        releaseWakeLock();
        stopForeground(true);
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        stopAll();
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }
}
