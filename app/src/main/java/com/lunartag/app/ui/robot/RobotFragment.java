package com.lunartag.app.ui.robot;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.lunartag.app.R;

/**
 * The Robot Fragment.
 * Allows the user to select between "Semi-Automatic" and "Full-Automatic" modes.
 * Saves the preference to the shared file used by the Accessibility Service.
 */
public class RobotFragment extends Fragment {

    // Must match LunarTagAccessibilityService constants
    private static final String PREFS_ACCESSIBILITY = "LunarTagAccessPrefs";
    private static final String KEY_AUTO_MODE = "automation_mode"; // Values: "semi" or "full"

    private RadioGroup radioGroup;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_robot, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        radioGroup = view.findViewById(R.id.radio_group_automation);

        // 1. Load saved state
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_ACCESSIBILITY, Context.MODE_PRIVATE);
        String currentMode = prefs.getString(KEY_AUTO_MODE, "semi");

        // 2. Update UI based on saved state
        if (currentMode.equals("full")) {
            radioGroup.check(R.id.radio_full);
        } else {
            radioGroup.check(R.id.radio_semi);
        }

        // 3. Listen for changes
        radioGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                String newMode = "semi";
                String message = "Mode set to Semi-Automatic";

                if (checkedId == R.id.radio_full) {
                    newMode = "full";
                    message = "Mode set to Full-Automatic (Zero Click)";
                }

                // Save immediately
                prefs.edit().putString(KEY_AUTO_MODE, newMode).apply();
                
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
    }
          }
