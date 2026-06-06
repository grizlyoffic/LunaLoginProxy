package online.luna.proxy.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Method;

public class AutomationService extends Service {

    public static boolean isRunning = false;
    private final String CHANNEL_ID = "AutoChannel";
    private final int NOTIF_ID = 8771;

    private Handler timerHandler;
    private long startTime = 0L;
    private String runtimeMode = "SHIZUKU";
    private String targetFilePath = "";
    
    // Timer duration in milliseconds (default 1 hour = 3600000ms)
    // Change this as per your requirement
    private static final long TIMER_DURATION = 3600000; // 1 hour
    // private static final long TIMER_DURATION = 60000; // 1 minute for testing

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            
            long millis = System.currentTimeMillis() - startTime;
            
            // CHECK IF TIMER IS COMPLETED
            if (millis >= TIMER_DURATION) {
                // Timer complete - auto stop service
                stopSelf();
                return;
            }
            
            int seconds = (int) (millis / 1000);
            int minutes = seconds / 60;
            int hours = minutes / 60;
            seconds = seconds % 60;
            minutes = minutes % 60;
            String timeString = String.format("%02d:%02d:%02d", hours, minutes, seconds);
            
            updateNotification("Active", "Running: " + timeString);
            
            Intent broadcast = new Intent("online.luna.TIMER_UPDATED");
            broadcast.putExtra("time_string", timeString);
            broadcast.setPackage("online.luna.proxy.app");
            sendBroadcast(broadcast);
            
            timerHandler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        timerHandler = new Handler(Looper.getMainLooper());
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP_SERVICE".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null) {
            runtimeMode = intent.getStringExtra("mode") != null ? intent.getStringExtra("mode") : "SHIZUKU";
            targetFilePath = intent.getStringExtra("target_path") != null ? intent.getStringExtra("target_path") : "";
        }
        
        startSuccess();
        return START_STICKY;
    }

    private void startSuccess() {
        isRunning = true;
        startTime = System.currentTimeMillis();
        
        new Handler(Looper.getMainLooper()).post(() -> {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, buildNotification("Active", "Running..."), 
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, buildNotification("Active", "Running..."));
            }
            
            timerHandler.post(timerRunnable);
            
            Intent successIntent = new Intent("online.luna.START_SUCCESSFUL");
            successIntent.setPackage("online.luna.proxy.app");
            sendBroadcast(successIntent);
            Toast.makeText(getApplicationContext(), "Started", Toast.LENGTH_SHORT).show();
        });
    }

    private void deleteConfigFile() {
        if (targetFilePath == null || targetFilePath.isEmpty()) return;
        
        if ("DIRECT".equals(runtimeMode)) {
            File file = new File(targetFilePath);
            if (file.exists()) file.delete();
        } else {
            try {
                Process process;
                if ("ROOT".equals(runtimeMode)) {
                    process = Runtime.getRuntime().exec("su");
                } else {
                    Class<?> c = Class.forName("rikka.shizuku.Shizuku");
                    Method m = c.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
                    m.setAccessible(true);
                    process = (Process) m.invoke(null, (Object) new String[]{"/system/bin/sh"}, null, null);
                }
                OutputStream os = process.getOutputStream();
                os.write(("rm -f " + targetFilePath + "\nexit\n").getBytes());
                os.flush();
                os.close();
                process.waitFor();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String title, String content) {
        Intent stopIntent = new Intent(this, AutomationService.class);
        stopIntent.setAction("STOP_SERVICE");
        
        int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pStop = PendingIntent.getService(this, 0, stopIntent, flags);
        PendingIntent pApp = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), flags);
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentIntent(pApp)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP", pStop)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String title, String content) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIF_ID, buildNotification(title, content));
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
            
            // FILE DELETE HOGI JAB SERVICE STOP HOGA
            Thread cleanupThread = new Thread(this::deleteConfigFile);
            cleanupThread.start();
            try { cleanupThread.join(); } catch (InterruptedException e) {}
            
            Toast.makeText(getApplicationContext(), "Service Stopped & Config Deleted", Toast.LENGTH_LONG).show();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
