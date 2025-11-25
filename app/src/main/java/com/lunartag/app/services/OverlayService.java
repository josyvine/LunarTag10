package com.lunartag.app.services;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.lunartag.app.R;

public class OverlayService extends Service {

    private static OverlayService instance;
    private WindowManager windowManager;
    
    // Views
    private View overlayView; // The Red Blink Box
    private View markerBox;
    
    private View trainingView; // The Draggable Crosshair
    
    // Params
    private WindowManager.LayoutParams blinkParams;
    private WindowManager.LayoutParams trainingParams;
    
    private boolean isBlinkAttached = false;
    private boolean isTrainingAttached = false;
    
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Prefs to save coordinates
    private static final String PREFS_ACCESSIBILITY = "LunarTagAccessPrefs";
    private static final String KEY_ICON_X = "share_icon_x";
    private static final String KEY_ICON_Y = "share_icon_y";

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        // 1. INFLATE RED BLINK LAYOUT (Original)
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_marker_view, null);
        markerBox = overlayView.findViewById(R.id.marker_box);

        // 2. CONFIGURE BLINK PARAMS
        int layoutFlag;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
        }

        blinkParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | 
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | 
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, // Allow clicks to pass through
                PixelFormat.TRANSLUCENT);

        blinkParams.gravity = Gravity.TOP | Gravity.START;
        blinkParams.x = 0;
        blinkParams.y = 0;
    }

    public static OverlayService getInstance() {
        return instance;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "ACTION_START_TRAINING".equals(intent.getAction())) {
            showTrainingTarget();
        }
        return START_STICKY;
    }

    // =============================================================
    // PART 1: ORIGINAL RED BLINK LOGIC (UNTOUCHED)
    // =============================================================

    public void showMarkerAt(Rect bounds) {
        if (overlayView == null || windowManager == null) return;

        handler.post(() -> {
            try {
                if (isTrainingAttached) removeTrainingView(); // Clear training if active

                // Update Position
                blinkParams.x = bounds.left;
                blinkParams.y = bounds.top;
                
                // Resize box
                FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) markerBox.getLayoutParams();
                layoutParams.width = bounds.width();
                layoutParams.height = bounds.height();
                markerBox.setLayoutParams(layoutParams);

                if (!isBlinkAttached) {
                    windowManager.addView(overlayView, blinkParams);
                    isBlinkAttached = true;
                } else {
                    windowManager.updateViewLayout(overlayView, blinkParams);
                }

                startBlinking();

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    // NEW: Helper to Blink at specific X,Y (For Share Sheet)
    // Creates a fake 100x100 box at the saved coordinate to simulate the blink
    public void showMarkerAtCoordinate(int x, int y) {
        Rect fakeRect = new Rect(x, y, x + 150, y + 150);
        showMarkerAt(fakeRect);
    }

    private void startBlinking() {
        markerBox.setVisibility(View.VISIBLE);
        handler.postDelayed(() -> markerBox.setVisibility(View.INVISIBLE), 200);
        handler.postDelayed(() -> markerBox.setVisibility(View.VISIBLE), 400);
        handler.postDelayed(() -> markerBox.setVisibility(View.INVISIBLE), 600);
        handler.postDelayed(this::hideMarker, 800);
    }

    public void hideMarker() {
        handler.post(() -> {
            if (isBlinkAttached && overlayView != null) {
                try {
                    windowManager.removeView(overlayView);
                    isBlinkAttached = false;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    // =============================================================
    // PART 2: NEW TRAINING LOGIC (DRAGGABLE TARGET)
    // =============================================================

    private void showTrainingTarget() {
        if (isTrainingAttached) return; // Already showing

        handler.post(() -> {
            try {
                // 1. Inflate the Crosshair Layout
                trainingView = LayoutInflater.from(this).inflate(R.layout.overlay_training_view, null);
                
                // 2. Configure Params (Clickable this time!)
                int layoutFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? 
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;

                trainingParams = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        layoutFlag,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, // Let interaction happen
                        PixelFormat.TRANSLUCENT);

                trainingParams.gravity = Gravity.TOP | Gravity.START;
                trainingParams.x = 500; // Default start pos
                trainingParams.y = 1000;

                // 3. Add Touch Listener for Dragging
                setupDragListener(trainingView);

                // 4. Setup Save Button
                Button btnSave = trainingView.findViewById(R.id.btn_save_position);
                btnSave.setOnClickListener(v -> saveCoordinates());

                windowManager.addView(trainingView, trainingParams);
                isTrainingAttached = true;

            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, "Error showing overlay: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupDragListener(View view) {
        view.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = trainingParams.x;
                        initialY = trainingParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        trainingParams.x = initialX + (int) (event.getRawX() - initialTouchX);
                        trainingParams.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(trainingView, trainingParams);
                        return true;
                }
                return false;
            }
        });
    }

    private void saveCoordinates() {
        // Calculate center of the crosshair (approx offset)
        // The View is wrap_content, we want the click to be where the image is.
        // Simple approach: Save top-left + padding.
        // Or just save x,y and let the robot click there.
        
        // Adding offset (e.g. 50px) to click the center of the crosshair, not the top-left corner
        int targetX = trainingParams.x + 75; 
        int targetY = trainingParams.y + 75; 

        SharedPreferences prefs = getSharedPreferences(PREFS_ACCESSIBILITY, Context.MODE_PRIVATE);
        prefs.edit()
                .putInt(KEY_ICON_X, targetX)
                .putInt(KEY_ICON_Y, targetY)
                .apply();

        Toast.makeText(this, "Position Saved: " + targetX + "," + targetY, Toast.LENGTH_LONG).show();
        removeTrainingView();
    }

    private void removeTrainingView() {
        if (isTrainingAttached && trainingView != null) {
            try {
                windowManager.removeView(trainingView);
                isTrainingAttached = false;
            } catch (Exception e) {}
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        hideMarker();
        removeTrainingView();
        instance = null;
    }
}