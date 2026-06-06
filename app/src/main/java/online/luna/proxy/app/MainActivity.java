package online.luna.proxy.app;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {

    private TextView tvStatus, tvTimer;
    private EditText etToken;
    private Button btnSync, btnDownloadShizuku, btnLogout, btnStart, btnStop;
    private LinearLayout layoutShizukuGuide, layoutAuthGateway, layoutAutomation;
    private ProgressBar progressBar;

    private SharedPreferences prefs;
    private static final String PREF_NAME = "AccessLoginPrefs";
    private static final String KEY_TOKEN = "auth_token";
    private static final String KEY_JWT_TOKEN = "jwt_token";
    private static final String KEY_USER_NAME = "user_name";
    private static final String KEY_TARGET_PATH = "selected_game_path";

    private final int SHIZUKU_REQ_CODE = 9005;
    private boolean isVerifyingHandshake = false;

    private String calculatedPrivilegeMode = "UNKNOWN";
    private final String PATH_FF_MAX = "/storage/emulated/0/Android/data/com.dts.freefiremax/files/localconfig.json";
    private final String PATH_FF_NORMAL = "/storage/emulated/0/Android/data/com.dts.freefireth/files/localconfig.json";

    // =============== YOUR API CONFIG ================
    private static final String API_CHECK_URL = "https://host-ggclient.vercel.app/check?token=";
    private static final String CONFIG_VER_ADDR = "https://version-ggbluellama.vercel.app/live/";
    private static final String CONFIG_SERVER_BASE = "http://203.175.125.151:10136/";

    private final BroadcastReceiver serviceWorkflowReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && intent.getAction() != null) {
                String action = intent.getAction();
                if ("online.luna.TIMER_UPDATED".equals(action)) {
                    String timeStr = intent.getStringExtra("time_string");
                    if (tvTimer != null && timeStr != null) tvTimer.setText("Automation: " + timeStr);
                } else if ("online.luna.TIMER_STOPPED".equals(action)) {
                    isVerifyingHandshake = false;
                    setLoadingDisplayState(false);
                    if (tvTimer != null) tvTimer.setText("Automation: 00:00:00");
                    synchronizeActiveServiceButtonStates();
                } else if ("online.luna.START_SUCCESSFUL".equals(action)) {
                    isVerifyingHandshake = false;
                    setLoadingDisplayState(false);
                    synchronizeActiveServiceButtonStates();
                } else if ("online.luna.START_FAILED".equals(action)) {
                    isVerifyingHandshake = false;
                    setLoadingDisplayState(false);
                    synchronizeActiveServiceButtonStates();
                    
                    String errorMsg = intent.getStringExtra("error_msg");
                    int errorCode = intent.getIntExtra("error_code", 0);
                    
                    if (errorCode == 410) {
                        if (tvStatus != null) tvStatus.setText("Set-up Your Access Token");
                        if (btnStart != null) {
                            btnStart.setVisibility(View.VISIBLE);
                            btnStart.setText("OPEN ACCESS PANEL");
                            btnStart.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff3f51b5));
                            btnStart.setOnClickListener(v -> {
                                Intent webIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://access.luna.online"));
                                startActivity(webIntent);
                            });
                        }
                    } else {
                        Toast.makeText(MainActivity.this, "Error: " + errorMsg, Toast.LENGTH_LONG).show();
                    }
                }
            }
        }
    };

    private final Shizuku.OnRequestPermissionResultListener shizukuListener = 
        (requestCode, grantResult) -> {
            if (requestCode == SHIZUKU_REQ_CODE && grantResult == PackageManager.PERMISSION_GRANTED) {
                evaluateApplicationWorkflowState();
            } else {
                displayStatusMessage("Shizuku interface connection rejected.");
            }
        };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        hideMobileSystemHeaderBars();
        executeBootPurgeSequence();

        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        tvStatus = findViewById(R.id.tv_status);
        tvTimer = findViewById(R.id.tv_timer);
        etToken = findViewById(R.id.et_token);
        btnSync = findViewById(R.id.btn_sync);
        btnDownloadShizuku = findViewById(R.id.btn_download_shizuku);
        btnLogout = findViewById(R.id.btn_logout);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);

        layoutShizukuGuide = findViewById(R.id.layout_shizuku_guide);
        layoutAuthGateway = findViewById(R.id.layout_auth_gateway);
        layoutAutomation = findViewById(R.id.layout_automation);
        progressBar = findViewById(R.id.progress_bar);

        Shizuku.addRequestPermissionResultListener(shizukuListener);
        setupInteractiveActionListeners();
        runIntelligentGameDiscoverySequence();
    }

    private void hideMobileSystemHeaderBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            final WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }
    }

    private void executeBootPurgeSequence() {
        try {
            File maxFile = new File(PATH_FF_MAX);
            if (maxFile.exists()) maxFile.delete();
            File normalFile = new File(PATH_FF_NORMAL);
            if (normalFile.exists()) normalFile.delete();
        } catch (Exception e) {}
    }

    private void setupInteractiveActionListeners() {
        btnDownloadShizuku.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))));
        btnSync.setOnClickListener(v -> executeNetworkValidationSequence());
        btnLogout.setOnClickListener(v -> {
            stopAutomationServiceEngine();
            prefs.edit().clear().apply();
            runIntelligentGameDiscoverySequence();
        });
        resetStartButtonToDefaultState();
        btnStop.setOnClickListener(v -> stopAutomationServiceEngine());
    }

    private void runIntelligentGameDiscoverySequence() {
        boolean hasNormal = isGamePackageInstalled("com.dts.freefireth");
        boolean hasMax = isGamePackageInstalled("com.dts.freefiremax");

        if (!hasNormal && !hasMax) {
            displayStatusMessage("Execution Failed: Target game configuration parameters not found.");
            toggleLayoutVisibilityBlocks(layoutShizukuGuide, false);
            toggleLayoutVisibilityBlocks(layoutAuthGateway, false);
            toggleLayoutVisibilityBlocks(layoutAutomation, false);
            return;
        }

        if (hasNormal && hasMax) {
            if (prefs.contains(KEY_TARGET_PATH)) calculateSystemPrivilegeEcosystem();
            else showGameSelectionDialog();
        } else if (hasMax) {
            prefs.edit().putString(KEY_TARGET_PATH, PATH_FF_MAX).apply();
            calculateSystemPrivilegeEcosystem();
        } else {
            prefs.edit().putString(KEY_TARGET_PATH, PATH_FF_NORMAL).apply();
            calculateSystemPrivilegeEcosystem();
        }
    }

    private void showGameSelectionDialog() {
        String[] options = {"Free Fire Standard", "Free Fire MAX"};
        new AlertDialog.Builder(this)
            .setTitle("Select Target Environment")
            .setCancelable(false)
            .setItems(options, (dialog, which) -> {
                prefs.edit().putString(KEY_TARGET_PATH, which == 0 ? PATH_FF_NORMAL : PATH_FF_MAX).apply();
                calculateSystemPrivilegeEcosystem();
            }).show();
    }

    private boolean isGamePackageInstalled(String targetPackage) {
        try { getPackageManager().getPackageInfo(targetPackage, 0); return true; } 
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private void calculateSystemPrivilegeEcosystem() {
        File parentFolder = new File(prefs.getString(KEY_TARGET_PATH, PATH_FF_MAX)).getParentFile();
        if (parentFolder != null && parentFolder.exists() && parentFolder.canWrite()) {
            calculatedPrivilegeMode = "DIRECT";
            evaluateApplicationWorkflowState();
            return;
        }
        if (isDeviceRooted()) {
            calculatedPrivilegeMode = "ROOT";
            evaluateApplicationWorkflowState();
            return;
        }
        calculatedPrivilegeMode = "SHIZUKU";
        evaluateApplicationWorkflowState();
    }

    private boolean isDeviceRooted() {
        String[] paths = { "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su", "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su" };
        for (String path : paths) { if (new File(path).exists()) return true; }
        return false;
    }

    private void evaluateApplicationWorkflowState() {
        runOnUiThread(() -> {
            if ("DIRECT".equals(calculatedPrivilegeMode) || "ROOT".equals(calculatedPrivilegeMode)) {
                proceedToTokenValidationInterface("Privilege Mode: " + calculatedPrivilegeMode + " Active.");
                return;
            }
            if (!Shizuku.pingBinder()) {
                displayStatusMessage("Shizuku engine configuration not found.");
                toggleLayoutVisibilityBlocks(layoutShizukuGuide, true);
                toggleLayoutVisibilityBlocks(layoutAuthGateway, false);
                toggleLayoutVisibilityBlocks(layoutAutomation, false);
                btnLogout.setVisibility(View.GONE);
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                displayStatusMessage("Awaiting Shizuku interface confirmation channel...");
                toggleLayoutVisibilityBlocks(layoutShizukuGuide, false);
                toggleLayoutVisibilityBlocks(layoutAuthGateway, false);
                toggleLayoutVisibilityBlocks(layoutAutomation, false);
                btnLogout.setVisibility(View.GONE);
                Shizuku.requestPermission(SHIZUKU_REQ_CODE);
                return;
            }
            proceedToTokenValidationInterface("Shizuku Privilege Verification Complete.");
        });
    }

    private void proceedToTokenValidationInterface(String statusPrefix) {
        String savedToken = prefs.getString(KEY_TOKEN, null);
        if (TextUtils.isEmpty(savedToken)) {
            displayStatusMessage(statusPrefix + "\nAuthenticate workspace console configuration.");
            toggleLayoutVisibilityBlocks(layoutShizukuGuide, false);
            toggleLayoutVisibilityBlocks(layoutAuthGateway, true);
            toggleLayoutVisibilityBlocks(layoutAutomation, false);
            btnLogout.setVisibility(View.GONE);
        } else {
            displayStatusMessage("Welcome back!\n" + statusPrefix + " Engine Active.");
            toggleLayoutVisibilityBlocks(layoutShizukuGuide, false);
            toggleLayoutVisibilityBlocks(layoutAuthGateway, false);
            toggleLayoutVisibilityBlocks(layoutAutomation, true);
            btnLogout.setVisibility(View.VISIBLE);
            if (!isVerifyingHandshake) {
                resetStartButtonToDefaultState();
                synchronizeActiveServiceButtonStates();
            }
        }
    }

    private void resetStartButtonToDefaultState() {
        if (btnStart != null) {
            btnStart.setText("START ENVIRONMENT SERVICE");
            btnStart.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff2e7d32));
            btnStart.setOnClickListener(v -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 101);
                }
                startAutomationServiceEngine();
            });
        }
    }

    private void executeNetworkValidationSequence() {
        String token = etToken.getText().toString().trim();
        if (TextUtils.isEmpty(token)) {
            Toast.makeText(this, "Please enter your authentication key.", Toast.LENGTH_SHORT).show();
            return;
        }

        setLoadingDisplayState(true);
        
        String apiUrl = API_CHECK_URL + token;
        Request request = new Request.Builder()
                .url(apiUrl)
                .get()
                .build();

        new OkHttpClient().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) { 
                handleNetworkFailureState("Gateway failure: " + e.getMessage()); 
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response r = response) {
                    if (!r.isSuccessful()) { 
                        handleNetworkFailureState("Validation rejected: HTTP " + r.code()); 
                        return; 
                    }
                    
                    String responseBody = r.body().string();
                    JSONObject json = new JSONObject(responseBody);
                    
                    String jwtToken = json.optString("JwtToken", "");
                    
                    if (jwtToken.isEmpty()) {
                        handleNetworkFailureState("Invalid token! No JWT received.");
                        return;
                    }
                    
                    prefs.edit()
                        .putString(KEY_TOKEN, token)
                        .putString(KEY_JWT_TOKEN, jwtToken)
                        .apply();

                    runOnUiThread(() -> {
                        setLoadingDisplayState(false);
                        displayStatusMessage("Token Verified Successfully!");
                        Toast.makeText(MainActivity.this, "Authentication Successful!", Toast.LENGTH_SHORT).show();
                        runIntelligentGameDiscoverySequence();
                    });
                    
                } catch (Exception e) { 
                    handleNetworkFailureState("Payload configuration error: " + e.getMessage()); 
                }
            }
        });
    }

    private void createLocalConfigFile() {
        String jwtToken = prefs.getString(KEY_JWT_TOKEN, "");
        String targetPath = prefs.getString(KEY_TARGET_PATH, PATH_FF_NORMAL);
        
        if (jwtToken.isEmpty()) {
            Toast.makeText(this, "No JWT token found! Please verify again.", Toast.LENGTH_LONG).show();
            return;
        }
        
        try {
            File configFile = new File(targetPath);
            File parentDir = configFile.getParentFile();
            
            JSONObject config = new JSONObject();
            config.put("verAddr", CONFIG_VER_ADDR);
            config.put("serverLoginUrl", CONFIG_SERVER_BASE + jwtToken + "/");
            String configContent = config.toString(2);
            
            boolean success = false;
            
            if ("DIRECT".equals(calculatedPrivilegeMode)) {
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }
                try (FileWriter writer = new FileWriter(configFile)) {
                    writer.write(configContent);
                    writer.flush();
                    success = true;
                }
            } 
            else if ("ROOT".equals(calculatedPrivilegeMode)) {
                success = writeFileWithRoot(targetPath, configContent);
            } 
            else if ("SHIZUKU".equals(calculatedPrivilegeMode)) {
                success = writeFileWithShizuku(targetPath, configContent);
            }
            
            if (success) {
                Toast.makeText(this, "Config Created Successfully!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Failed to create config! Using Shizuku or Root.", Toast.LENGTH_LONG).show();
            }
            
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }

    private boolean writeFileWithRoot(String filePath, String content) {
        try {
            Process process = Runtime.getRuntime().exec("su");
            OutputStream os = process.getOutputStream();
            
            String escapedContent = content.replace("'", "'\\''");
            String cmd = "mkdir -p " + new File(filePath).getParent() + "\n";
            cmd += "echo '" + escapedContent + "' > " + filePath + "\n";
            cmd += "chmod 666 " + filePath + "\n";
            cmd += "exit\n";
            
            os.write(cmd.getBytes());
            os.flush();
            os.close();
            
            return process.waitFor() == 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private boolean writeFileWithShizuku(String filePath, String content) {
        try {
            Class<?> shizukuClass = Class.forName("rikka.shizuku.Shizuku");
            Method newProcessMethod = shizukuClass.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            newProcessMethod.setAccessible(true);
            
            Process process = (Process) newProcessMethod.invoke(null, (Object) new String[]{"/system/bin/sh"}, null, null);
            OutputStream os = process.getOutputStream();
            
            String escapedContent = content.replace("'", "'\\''");
            String cmd = "mkdir -p " + new File(filePath).getParent() + "\n";
            cmd += "echo '" + escapedContent + "' > " + filePath + "\n";
            cmd += "chmod 666 " + filePath + "\n";
            cmd += "exit\n";
            
            os.write(cmd.getBytes());
            os.flush();
            os.close();
            
            return process.waitFor() == 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private void deleteLocalConfigFile() {
        String targetPath = prefs.getString(KEY_TARGET_PATH, PATH_FF_NORMAL);
        
        boolean success = false;
        
        if ("DIRECT".equals(calculatedPrivilegeMode)) {
            File configFile = new File(targetPath);
            if (configFile.exists()) {
                success = configFile.delete();
            }
        } 
        else if ("ROOT".equals(calculatedPrivilegeMode)) {
            success = deleteFileWithRoot(targetPath);
        } 
        else if ("SHIZUKU".equals(calculatedPrivilegeMode)) {
            success = deleteFileWithShizuku(targetPath);
        }
        
        if (success) {
            Toast.makeText(this, "Config Deleted!", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean deleteFileWithRoot(String filePath) {
        try {
            Process process = Runtime.getRuntime().exec("su");
            OutputStream os = process.getOutputStream();
            os.write(("rm -f " + filePath + "\nexit\n").getBytes());
            os.flush();
            os.close();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean deleteFileWithShizuku(String filePath) {
        try {
            Class<?> shizukuClass = Class.forName("rikka.shizuku.Shizuku");
            Method newProcessMethod = shizukuClass.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            newProcessMethod.setAccessible(true);
            Process process = (Process) newProcessMethod.invoke(null, (Object) new String[]{"/system/bin/sh"}, null, null);
            OutputStream os = process.getOutputStream();
            os.write(("rm -f " + filePath + "\nexit\n").getBytes());
            os.flush();
            os.close();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void startAutomationServiceEngine() {
        isVerifyingHandshake = true;
        setLoadingDisplayState(true);
        
        if (btnStart != null) { 
            btnStart.setText("STARTING ENGINE..."); 
            btnStart.setEnabled(false); 
        }
        if (btnStop != null) btnStop.setVisibility(View.GONE);

        createLocalConfigFile();
        
        Intent intent = new Intent(this, AutomationService.class);
        intent.putExtra("token", prefs.getString(KEY_TOKEN, ""));
        intent.putExtra("jwt_token", prefs.getString(KEY_JWT_TOKEN, ""));
        intent.putExtra("mode", calculatedPrivilegeMode);
        intent.putExtra("target_path", prefs.getString(KEY_TARGET_PATH, PATH_FF_MAX));
        
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        
        isVerifyingHandshake = false;
        setLoadingDisplayState(false);
        synchronizeActiveServiceButtonStates();
    }

    private void stopAutomationServiceEngine() { 
        setLoadingDisplayState(true); 
        stopService(new Intent(this, AutomationService.class));
        deleteLocalConfigFile();
        setLoadingDisplayState(false);
        synchronizeActiveServiceButtonStates();
    }

    private void synchronizeActiveServiceButtonStates() {
        if (isVerifyingHandshake) return;
        boolean isRunning = AutomationService.isRunning;
        if (btnStart != null) { btnStart.setVisibility(isRunning ? View.GONE : View.VISIBLE); btnStart.setEnabled(true); }
        if (btnStop != null) { btnStop.setVisibility(isRunning ? View.VISIBLE : View.GONE); btnStop.setEnabled(true); }
    }

    private void displayStatusMessage(String msg) { if (tvStatus != null) tvStatus.setText(msg); }
    
    private void toggleLayoutVisibilityBlocks(LinearLayout layout, boolean show) { 
        if (layout != null) layout.setVisibility(show ? View.VISIBLE : View.GONE); 
    }

    private void setLoadingDisplayState(boolean loading) {
        runOnUiThread(() -> {
            if (progressBar != null) progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
            if (!loading) {
                if (btnStart != null) btnStart.setEnabled(true);
                if (btnStop != null) btnStop.setEnabled(true);
                if (btnSync != null) btnSync.setEnabled(true);
            }
        });
    }

    private void handleNetworkFailureState(String error) {
        runOnUiThread(() -> {
            isVerifyingHandshake = false;
            setLoadingDisplayState(false);
            resetStartButtonToDefaultState();
            synchronizeActiveServiceButtonStates();
            Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction("online.luna.TIMER_UPDATED");
        filter.addAction("online.luna.TIMER_STOPPED");
        filter.addAction("online.luna.START_SUCCESSFUL");
        filter.addAction("online.luna.START_FAILED");
        if (Build.VERSION.SDK_INT >= 34) registerReceiver(serviceWorkflowReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(serviceWorkflowReceiver, filter);
    }

    @Override
    protected void onStop() { try { unregisterReceiver(serviceWorkflowReceiver); } catch (Exception e) {} super.onStop(); }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideMobileSystemHeaderBars();
    }

    @Override
    protected void onResume() { 
        super.onResume(); 
        if (prefs.contains(KEY_TARGET_PATH)) calculateSystemPrivilegeEcosystem(); 
    }

    @Override
    protected void onDestroy() { 
        Shizuku.removeRequestPermissionResultListener(shizukuListener); 
        super.onDestroy(); 
    }
}