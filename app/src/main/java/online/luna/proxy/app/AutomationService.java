package online.luna.proxy.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import org.json.JSONObject;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class AutomationService extends Service {

    public static boolean isRunning = false;
    private final String NOTIFICATION_CHANNEL_ID = "AutomationEngineChannel";
    private final int NOTIFICATION_ID = 8771;

    private Handler timerHandler;
    private long startTimestamp = 0L;
    private String runtimeMode = "SHIZUKU";
    private String targetFilePath = "";
    private String jwtToken = "";

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            long millis = System.currentTimeMillis() - startTimestamp;
            int seconds = (int) (millis / 1000);
            int minutes = seconds / 60;
            int hours = minutes / 60;
            seconds = seconds % 60;
            minutes = minutes % 60;

            String timeString = String.format("%02d:%02d:%02d", hours, minutes, seconds);
            updateForegroundSystemNotification("Server Active", "Running: " + timeString);

            Intent broadcastIntent = new Intent("online.luna.TIMER_UPDATED");
            broadcastIntent.putExtra("time_string", timeString);
            broadcastIntent.setPackage("online.luna.proxy.app");
            sendBroadcast(broadcastIntent);
            
            timerHandler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        timerHandler = new Handler(Looper.getMainLooper());
        createNotificationLifecycleChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP_SERVICE_ACTION".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String token = (intent != null) ? intent.getStringExtra("token") : "";
        jwtToken = (intent != null) ? intent.getStringExtra("jwt_token") : "";
        runtimeMode = (intent != null) ? intent.getStringExtra("mode") : "SHIZUKU";
        targetFilePath = (intent != null) ? intent.getStringExtra("target_path") : "";
        
        executeLocalConfigBasedWorkflow();
        return START_STICKY;
    }

    private void executeLocalConfigBasedWorkflow() {
        try {
            File configFile = new File(targetFilePath);
            if (!configFile.exists()) {
                sendFailureBroadcastSignal("Configuration file not found", 0);
                stopSelf();
                return;
            }

            String content = readFileContent(configFile);
            if (content.isEmpty()) {
                sendFailureBroadcastSignal("Configuration file is empty", 0);
                stopSelf();
                return;
            }

            JSONObject config = new JSONObject(content);
            String serverLoginUrl = config.optString("serverLoginUrl", "");
            
            if (serverLoginUrl.isEmpty()) {
                sendFailureBroadcastSignal("Invalid server URL in configuration", 0);
                stopSelf();
                return;
            }

            startServiceSuccess();
            
        } catch (Exception e) {
            sendFailureBroadcastSignal("Config read error: " + e.getMessage(), 0);
            stopSelf();
        }
    }

    private String readFileContent(File file) {
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file));
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
            reader.close();
            return content.toString();
        } catch (IOException e) {
            return "";
        }
    }

    private void startServiceSuccess() {
        isRunning = true;
        startTimestamp = System.currentTimeMillis();
        
        new Handler(Looper.getMainLooper()).post(() -> {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, buildSystemNotification("Server Active", "Service running..."), 
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, buildSystemNotification("Server Active", "Service running..."));
            }
            
            timerHandler.post(timerRunnable);
            
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                Intent successIntent = new Intent("online.luna.START_SUCCESSFUL");
                successIntent.setPackage("online.luna.proxy.app");
                sendBroadcast(successIntent);
                Toast.makeText(getApplicationContext(), "Server Started Successfully", Toast.LENGTH_LONG).show();
            }, 250);
        });
    }

    private void executeFilePurgeSequence() {
        if ("DIRECT".equals(runtimeMode)) {
            File file = new File(targetFilePath);
            if (file.exists()) file.delete();
        } else {
            String shellBinary = "ROOT".equals(runtimeMode) ? "su" : null;
            try {
                Process process;
                if (shellBinary != null) {
                    process = Runtime.getRuntime().exec(shellBinary);
                } else {
                    Class<?> shizukuClass = Class.forName("rikka.shizuku.Shizuku");
                    Method newProcessMethod = shizukuClass.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
                    newProcessMethod.setAccessible(true);
                    process = (Process) newProcessMethod.invoke(null, (Object) new String[]{"/system/bin/sh"}, null, null);
                }
                OutputStream os = process.getOutputStream();
                String cmd = "rm -f " + targetFilePath + "\n" + "exit\n";
                os.write(cmd.getBytes());
                os.flush();
                os.close();
                process.waitFor();
            } catch (Exception e) {
                Log.e("AutomationService", "Purge Exception: " + e.getMessage());
            }
        }
        new Handler(Looper.getMainLooper()).post(() -> 
            Toast.makeText(getApplicationContext(), "Server Stopped Successfully", Toast.LENGTH_LONG).show()
        );
    }

    private void sendFailureBroadcastSignal(String reasonMessage, int errorCode) {
        Intent failureBroadcast = new Intent("online.luna.START_FAILED");
        failureBroadcast.putExtra("error_msg", reasonMessage);
        failureBroadcast.putExtra("error_code", errorCode);
        failureBroadcast.setPackage("online.luna.proxy.app");
        sendBroadcast(failureBroadcast);
    }

    private void createNotificationLifecycleChannel() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID, "Environment Monitor", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildSystemNotification(String title, String content) {
        Intent stopIntent = new Intent(this, AutomationService.class);
        stopIntent.setAction("STOP_SERVICE_ACTION");
        
        int pFlags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pStopIntent = PendingIntent.getService(this, 0, stopIntent, pFlags);

        Intent appIntent = new Intent(this, MainActivity.class);
        PendingIntent pAppIntent = PendingIntent.getActivity(this, 0, appIntent, pFlags);

        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentIntent(pAppIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP", pStopIntent)
                .setOngoing(true)
                .build();
    }

    private void updateForegroundSystemNotification(String title, String content) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildSystemNotification(title, content));
        }
    }

    @Override
    public void onDestroy() {
        if (isRunning) {
            isRunning = false;
            timerHandler.removeCallbacks(timerRunnable);
            
            Intent stopIntent = new Intent("online.luna.TIMER_STOPPED");
            stopIntent.setPackage("online.luna.proxy.app");
            sendBroadcast(stopIntent);
            
            Thread cleanupThread = new Thread(this::executeFilePurgeSequence);
            cleanupThread.start();
            try { cleanupThread.join(); } catch (InterruptedException e) { e.printStackTrace(); }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}