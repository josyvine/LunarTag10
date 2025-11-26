package com.lunartag.app.ui.contact;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.lunartag.app.R;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ContactFragment extends Fragment {

    private EditText editName, editEmail, editMessage;
    private Button btnSend;
    private ExecutorService executorService;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // YOUR API URL
    private static final String FORMSPREE_URL = "https://formspree.io/f/xyzenlao";

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_contact, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        editName = view.findViewById(R.id.edit_contact_name);
        editEmail = view.findViewById(R.id.edit_contact_email);
        editMessage = view.findViewById(R.id.edit_contact_message);
        btnSend = view.findViewById(R.id.button_send_contact);
        
        executorService = Executors.newSingleThreadExecutor();

        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                attemptSend();
            }
        });
    }

    private void attemptSend() {
        final String name = editName.getText().toString().trim();
        final String email = editEmail.getText().toString().trim();
        final String message = editMessage.getText().toString().trim();

        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(email) || TextUtils.isEmpty(message)) {
            Toast.makeText(getContext(), "Please fill all fields", Toast.LENGTH_SHORT).show();
            return;
        }

        // Disable button to prevent double clicking
        btnSend.setEnabled(false);
        btnSend.setText("Sending...");

        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL(FORMSPREE_URL);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    conn.setRequestProperty("Accept", "application/json");
                    conn.setDoOutput(true);

                    // Create JSON Payload
                    JSONObject jsonParam = new JSONObject();
                    jsonParam.put("name", name);
                    jsonParam.put("email", email);
                    jsonParam.put("message", message);

                    // Send Data
                    try (OutputStream os = conn.getOutputStream()) {
                        byte[] input = jsonParam.toString().getBytes("UTF-8");
                        os.write(input, 0, input.length);
                    }

                    final int responseCode = conn.getResponseCode();

                    // Update UI on Main Thread
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (responseCode >= 200 && responseCode < 300) {
                                Toast.makeText(getContext(), "Message Sent Successfully!", Toast.LENGTH_LONG).show();
                                // Clear fields
                                editName.setText("");
                                editEmail.setText("");
                                editMessage.setText("");
                            } else {
                                Toast.makeText(getContext(), "Failed to send. Error code: " + responseCode, Toast.LENGTH_LONG).show();
                            }
                            // Reset button
                            btnSend.setEnabled(true);
                            btnSend.setText("Send Message");
                        }
                    });

                } catch (final Exception e) {
                    e.printStackTrace();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(getContext(), "Connection Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            btnSend.setEnabled(true);
                            btnSend.setText("Send Message");
                        }
                    });
                }
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executorService != null) {
            executorService.shutdown();
        }
    }
            }
