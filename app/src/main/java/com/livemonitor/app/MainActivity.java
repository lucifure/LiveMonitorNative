package com.livemonitor.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.livemonitor.app.databinding.ActivityMainBinding;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private boolean isMonitoring = false;
    private BroadcastReceiver logReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.logText.setMovementMethod(new ScrollingMovementMethod());

        requestPermissions();

        binding.btnStart.setOnClickListener(v -> {
            String url = binding.urlInput.getText().toString().trim();
            if (url.isEmpty()) {
                Toast.makeText(this, "Please enter a YouTube URL", Toast.LENGTH_SHORT).show();
                return;
            }
            startMonitoring(url);
        });

        binding.btnStop.setOnClickListener(v -> stopMonitoring());

        // Register log receiver
        logReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String msg = intent.getStringExtra("message");
                String type = intent.getStringExtra("type");
                if (msg != null) appendLog(msg, type);
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(
            logReceiver, new IntentFilter("MONITOR_LOG"));

        appendLog("Ready. Paste a YouTube channel URL and tap Start.", "info");
    }

    private void startMonitoring(String url) {
        isMonitoring = true;
        binding.btnStart.setEnabled(false);
        binding.btnStop.setEnabled(true);
        binding.urlInput.setEnabled(false);
        binding.statusText.setText("Monitoring...");
        binding.statusText.setTextColor(getColor(R.color.green));

        Intent intent = new Intent(this, MonitorService.class);
        intent.setAction("START");
        intent.putExtra("url", url);
        startForegroundService(intent);

        appendLog("Started monitoring: " + url, "success");
    }

    private void stopMonitoring() {
        isMonitoring = false;
        binding.btnStart.setEnabled(true);
        binding.btnStop.setEnabled(false);
        binding.urlInput.setEnabled(true);
        binding.statusText.setText("Stopped");
        binding.statusText.setTextColor(getColor(R.color.text_dim));

        Intent intent = new Intent(this, MonitorService.class);
        intent.setAction("STOP");
        startService(intent);

        appendLog("Monitoring stopped.", "info");
    }

    private void appendLog(String message, String type) {
        runOnUiThread(() -> {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            String current = binding.logText.getText().toString();
            String newText = current + "\n[" + time + "] " + message;
            // Keep last 100 lines
            String[] lines = newText.split("\n");
            if (lines.length > 100) {
                StringBuilder sb = new StringBuilder();
                for (int i = lines.length - 100; i < lines.length; i++) {
                    sb.append(lines[i]).append("\n");
                }
                newText = sb.toString();
            }
            binding.logText.setText(newText.trim());
            // Auto scroll to bottom
            final int scrollAmount = binding.logText.getLayout() != null ?
                binding.logText.getLayout().getLineTop(binding.logText.getLineCount()) -
                binding.logText.getHeight() : 0;
            if (scrollAmount > 0) binding.logText.scrollTo(0, scrollAmount);
        });
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(logReceiver);
    }
}
