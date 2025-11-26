package com.lunartag.app.ui.gallery;

import android.app.AlertDialog;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;

import com.lunartag.app.data.AppDatabase;
import com.lunartag.app.data.PhotoDao;
import com.lunartag.app.databinding.FragmentGalleryBinding;
import com.lunartag.app.model.Photo;
import com.lunartag.app.utils.Scheduler;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GalleryFragment extends Fragment {

    private FragmentGalleryBinding binding;
    private GalleryAdapter adapter;
    private ExecutorService databaseExecutor;
    private List<Photo> photoList;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentGalleryBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Initialize Executor for background DB operations
        databaseExecutor = Executors.newSingleThreadExecutor();
        photoList = new ArrayList<>();

        // Setup the RecyclerView with a GridLayoutManager to show 3 columns
        GridLayoutManager layoutManager = new GridLayoutManager(getContext(), 3);
        binding.recyclerViewGallery.setLayoutManager(layoutManager);
        
        // Initialize adapter
        adapter = new GalleryAdapter(getContext(), photoList);
        binding.recyclerViewGallery.setAdapter(adapter);

        // --- Setup Selection Logic ---
        setupSelectionListeners();
    }

    private void setupSelectionListeners() {
        // 1. Listen for updates from the Adapter (when user clicks photos)
        adapter.setSelectionListener(count -> {
            if (count > 0) {
                showSelectionToolbar(count);
            } else {
                hideSelectionToolbar();
            }
        });

        // 2. Close Button (X)
        binding.btnCloseSelection.setOnClickListener(v -> {
            adapter.clearSelection();
            hideSelectionToolbar();
        });

        // 3. Select All Button
        binding.btnSelectAll.setOnClickListener(v -> {
            adapter.selectAll();
        });

        // 4. Delete Button (Trash Icon)
        binding.btnDeleteSelection.setOnClickListener(v -> {
            confirmDeletion();
        });
    }

    private void showSelectionToolbar(int count) {
        binding.cardSelectionToolbar.setVisibility(View.VISIBLE);
        binding.textSelectionCount.setText(count + " Selected");
    }

    private void hideSelectionToolbar() {
        binding.cardSelectionToolbar.setVisibility(View.GONE);
    }

    private void confirmDeletion() {
        int count = adapter.getSelectedIds().size();
        new AlertDialog.Builder(getContext())
                .setTitle("Delete Photos?")
                .setMessage("Are you sure you want to delete " + count + " photo(s)?")
                .setPositiveButton("Delete", (dialog, which) -> deleteSelectedPhotos())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteSelectedPhotos() {
        List<Long> idsToDelete = adapter.getSelectedIds();
        adapter.clearSelection(); 
        hideSelectionToolbar();

        databaseExecutor.execute(() -> {
            AppDatabase db = AppDatabase.getDatabase(getContext());
            PhotoDao dao = db.photoDao();

            for (Long id : idsToDelete) {
                Photo photo = dao.getPhotoById(id);
                if (photo != null) {
                    // 1. Cancel Alarm
                    Scheduler.cancelPhotoSend(getContext(), photo.getId());

                    // 2. Delete Physical File
                    try {
                        String filePath = photo.getFilePath();

                        // FIXED: Handle Deletion for Custom Folder (SAF) vs Standard File
                        if (filePath != null && filePath.startsWith("content://")) {
                            // Delete using ContentResolver
                            getContext().getContentResolver().delete(Uri.parse(filePath), null, null);
                        } else {
                            // Delete standard file
                            File file = new File(filePath);
                            if (file.exists()) {
                                file.delete();
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }

            // 3. Delete from DB
            dao.deletePhotos(idsToDelete);

            // 4. Reload
            new Handler(Looper.getMainLooper()).post(() -> {
                Toast.makeText(getContext(), "Photos Deleted", Toast.LENGTH_SHORT).show();
                loadPhotos();
            });
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // Clear any previous selection when returning to this screen
        if (adapter != null) {
            adapter.clearSelection();
            hideSelectionToolbar();
        }
        loadPhotos();
    }

    private void loadPhotos() {
        binding.progressBarGallery.setVisibility(View.VISIBLE);
        binding.textNoPhotos.setVisibility(View.GONE);

        databaseExecutor.execute(() -> {
            AppDatabase db = AppDatabase.getDatabase(getContext());
            PhotoDao dao = db.photoDao();
            
            final List<Photo> loadedPhotos = dao.getAllPhotos();

            new Handler(Looper.getMainLooper()).post(() -> {
                if (binding == null) return;

                binding.progressBarGallery.setVisibility(View.GONE);

                if (loadedPhotos != null && !loadedPhotos.isEmpty()) {
                    photoList.clear();
                    photoList.addAll(loadedPhotos);
                    adapter.notifyDataSetChanged();
                    
                    binding.recyclerViewGallery.setVisibility(View.VISIBLE);
                    binding.textNoPhotos.setVisibility(View.GONE);
                } else {
                    binding.recyclerViewGallery.setVisibility(View.GONE);
                    binding.textNoPhotos.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null; 
        if (databaseExecutor != null) {
            databaseExecutor.shutdown();
        }
    }
}