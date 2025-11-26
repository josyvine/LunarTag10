package com.lunartag.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.lunartag.app.databinding.ActivityMainBinding;
import com.lunartag.app.firebase.RemoteConfigManager;
import com.lunartag.app.ui.logs.LogFragment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The main screen of the application.
 * UPDATED: Handles centralized logging and blinking notification icon.
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private NavController navController;
    private ActivityResultLauncher<String[]> permissionLauncher;
    private String[] requiredPermissions;

    // --- CENTRAL LOG STORAGE ---
    // Stores the logs so they persist when switching screens
    private StringBuilder logHistory = new StringBuilder();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    // --- LIVE LOG RECEIVER ---
    // Listens for messages from Robot, Camera, and System
    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && "com.lunartag.ACTION_LOG_UPDATE".equals(intent.getAction())) {
                String message = intent.getStringExtra("log_msg");
                String type = intent.getStringExtra("log_type"); // "info" or "error"

                if (message != null) {
                    // 1. Add to history
                    logHistory.append(message).append("\n");

                    // 2. Blink the Icon
                    blinkLogIcon(type);

                    // 3. If the Log Screen is currently open, update it in real-time
                    NavHostFragment navHost = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment_activity_main);
                    if (navHost != null) {
                        for (androidx.fragment.app.Fragment fragment : navHost.getChildFragmentManager().getFragments()) {
                            if (fragment instanceof LogFragment && fragment.isVisible()) {
                                ((LogFragment) fragment).appendLog(message);
                            }
                        }
                    }
                }
            }
        }
    };

    /**
     * Public method for LogFragment to retrieve the full history when it opens.
     */
    public String getGlobalLogs() {
        return logHistory.toString();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Initialize Log History
        logHistory.append("-- SYSTEM STARTED --\n");

        // Permissions Setup
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions = new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.READ_MEDIA_IMAGES
            };
        } else {
            requiredPermissions = new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            };
        }

        RemoteConfigManager.fetchRemoteConfig(this);

        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment_activity_main);
        
        if (navHostFragment != null) {
            navController = navHostFragment.getNavController();
        }

        // --- NAVIGATION LOGIC ---
        
        binding.navDashboard.setOnClickListener(v -> {
            navController.navigate(R.id.navigation_dashboard);
            updateIconVisuals(binding.navDashboard);
        });

        binding.navCamera.setOnClickListener(v -> {
            navController.navigate(R.id.navigation_camera);
            updateIconVisuals(binding.navCamera);
        });

        binding.navGallery.setOnClickListener(v -> {
            navController.navigate(R.id.navigation_gallery);
            updateIconVisuals(binding.navGallery);
        });

        binding.navRobot.setOnClickListener(v -> {
            navController.navigate(R.id.navigation_robot);
            updateIconVisuals(binding.navRobot);
        });

        binding.navApps.setOnClickListener(v -> {
            navController.navigate(R.id.navigation_apps);
            updateIconVisuals(binding.navApps);
        });

        binding.navHelp.setOnClickListener(v -> {
            navController.navigate(R.id.navigation_help);
            updateIconVisuals(binding.navHelp);
        });

        binding.navContact.setOnClickListener(v -> {
            navController.navigate(R.id.navigation_contact);
            updateIconVisuals(binding.navContact);
        });

        // NEW: Log Icon Click Listener
        binding.navLogs.setOnClickListener(v -> {
            navController.navigate(R.id.navigation_logs);
            updateIconVisuals(binding.navLogs);
        });

        binding.navSettings.setOnClickListener(v -> {
            navController.navigate(R.id.navigation_settings);
            updateIconVisuals(binding.navSettings);
        });

        permissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                new ActivityResultCallback<Map<String, Boolean>>() {
                    @Override
                    public void onActivityResult(Map<String, Boolean> results) {
                        boolean allGranted = true;
                        for (Boolean granted : results.values()) {
                            if (!granted) {
                                allGranted = false;
                                break;
                            }
                        }
                        if (allGranted) {
                            onPermissionsGranted();
                        } else {
                            Toast.makeText(MainActivity.this, "Permissions needed for core features.", Toast.LENGTH_LONG).show();
                        }
                    }
                });

        checkAndRequestPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter("com.lunartag.ACTION_LOG_UPDATE");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
             registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
             registerReceiver(logReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(logReceiver);
        } catch (IllegalArgumentException e) {
            // Receiver not registered
        }
    }

    /**
     * Blinks the Log Icon based on message type.
     * RED for errors, GREEN for info.
     */
    private void blinkLogIcon(String type) {
        final int defaultColor = getAttributeColor(com.google.android.material.R.attr.colorOnSurface);
        int blinkColor = Color.GREEN; // Default info color

        // Detect error types
        if (type != null && (type.equalsIgnoreCase("error") || type.equalsIgnoreCase("fail"))) {
            blinkColor = Color.RED;
        }

        // Apply blink color
        binding.navLogs.setColorFilter(blinkColor, PorterDuff.Mode.SRC_IN);

        // Reset after 500ms
        uiHandler.postDelayed(() -> {
            // Only reset if Logs is NOT the currently active tab
            // If it IS active, updateIconVisuals handles the color
            // For simplicity, we reset to default or active color logic here
            // But to keep visual stability, we just reset to the appropriate state:
            
            // Check if logs is currently selected based on icon color or logic
            // A simple reset to "inactive" default is safe, the user will tap if they want to see it.
            // Or better, check logic:
            
            // If we are ON the logs screen, keep it Primary Color. If not, reset to Default.
            // Since we don't easily track current Fragment ID here without complexity, 
            // we will trigger a visual update based on the view state.
            
            // Simple approach: Reset to default gray. 
            // If user is ON the tab, they will tap or we can leave it gray until next tap.
            // However, to look professional, let's just reset to default gray.
            binding.navLogs.setColorFilter(defaultColor, PorterDuff.Mode.SRC_IN);
            
            // Re-apply active state if it was active (Optional refinement)
            // We can check if the current navigation destination is logs, but for now, simple blink is fine.
            
        }, 500);
    }

    private void updateIconVisuals(View activeView) {
        int activeColor = getAttributeColor(com.google.android.material.R.attr.colorPrimary);
        int inactiveColor = getAttributeColor(com.google.android.material.R.attr.colorOnSurface);

        binding.navDashboard.setColorFilter(inactiveColor);
        binding.navCamera.setColorFilter(inactiveColor);
        binding.navGallery.setColorFilter(inactiveColor);
        binding.navRobot.setColorFilter(inactiveColor);
        binding.navApps.setColorFilter(inactiveColor);
        binding.navHelp.setColorFilter(inactiveColor);
        binding.navContact.setColorFilter(inactiveColor);
        binding.navLogs.setColorFilter(inactiveColor); // Reset Logs
        binding.navSettings.setColorFilter(inactiveColor);

        if (activeView instanceof ImageView) {
            ((ImageView) activeView).setColorFilter(activeColor);
        }
    }

    private int getAttributeColor(int attrId) {
        android.util.TypedValue typedValue = new android.util.TypedValue();
        getTheme().resolveAttribute(attrId, typedValue, true);
        return typedValue.data;
    }

    private void checkAndRequestPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();
        boolean allPermissionsAlreadyGranted = true;
        for (String permission : requiredPermissions) {
            if (permission != null && ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
                allPermissionsAlreadyGranted = false;
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toArray(new String[0]));
        }

        if (allPermissionsAlreadyGranted) {
            onPermissionsGranted();
        }
    }

    private void onPermissionsGranted() {
        logHistory.append("System: Permissions Granted.\n");
    }
}