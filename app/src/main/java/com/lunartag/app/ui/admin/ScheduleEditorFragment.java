package com.lunartag.app.ui.admin;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.lunartag.app.databinding.FragmentScheduleEditorBinding;

public class ScheduleEditorFragment extends Fragment {

    private static final String PREFS_NAME = "LunarTagFeatureToggles";
    private static final String KEY_CUSTOM_TIMESTAMP_ENABLED = "customTimestampEnabled";

    private FragmentScheduleEditorBinding binding;
    private SharedPreferences featureTogglePrefs;
    private boolean isFeatureEnabled = false;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        featureTogglePrefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        // Check if the feature is enabled. The default is false.
        isFeatureEnabled = featureTogglePrefs.getBoolean(KEY_CUSTOM_TIMESTAMP_ENABLED, false);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentScheduleEditorBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // This is the critical UI logic based on the remote toggle.
        if (isFeatureEnabled) {
            // If the feature is enabled, make the editor UI visible.
            view.setVisibility(View.VISIBLE);
            setupClickListeners();
            loadScheduledTimestamps();
        } else {
            // If the feature is disabled, hide this entire UI.
            view.setVisibility(View.GONE);
            // Optionally, navigate away or show a message.
        }
    }

    private void setupClickListeners() {
        binding.buttonAddTimestamp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Logic to open a Date and Time picker to add a new timestamp.
                Toast.makeText(getContext(), "Add Timestamp Clicked (Placeholder)", Toast.LENGTH_SHORT).show();
            }
        });

        binding.buttonAutoGenerate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Logic to open a dialog to auto-generate timestamps by interval.
                Toast.makeText(getContext()
