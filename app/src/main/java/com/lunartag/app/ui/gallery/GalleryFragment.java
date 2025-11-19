package com.lunartag.app.ui.gallery;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;

import com.lunartag.app.data.AppDatabase;
import com.lunartag.app.data.PhotoDao;
import com.lunartag.app.databinding.FragmentGalleryBinding;
import com.lunartag.app.model.Photo;

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
        
        // Initialize adapter with empty list first
        adapter = new GalleryAdapter(getContext(), photoList);
        binding.recyclerViewGallery.setAdapter(adapter);
    }

    @Override
    public void onResume() {
        super.onResume();
        // When the screen becomes visible (e.g., coming back from Camera), reload the photos.
        loadPhotos();
    }

    private void loadPhotos() {
        // Show a loading indicator
        binding.progressBarGallery.setVisibility(View.VISIBLE);
        binding.textNoPhotos.setVisibility(View.GONE);

        // Execute database query in background thread
        databaseExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AppDatabase db = AppDatabase.getDatabase(getContext());
                PhotoDao dao = db.photoDao();
                
                // Get all photos, ordered by capture time (newest first)
                final List<Photo> loadedPhotos = dao.getAllPhotos();

                // Update UI on Main Thread
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        if (binding == null) return; // Safety check if fragment was destroyed

                        binding.progressBarGallery.setVisibility(View.GONE);

                        if (loadedPhotos != null && !loadedPhotos.isEmpty()) {
                            // Update the list and notify adapter
                            photoList.clear();
                            photoList.addAll(loadedPhotos);
                            adapter.notifyDataSetChanged();
                            
                            binding.recyclerViewGallery.setVisibility(View.VISIBLE);
                            binding.textNoPhotos.setVisibility(View.GONE);
                        } else {
                            // Show "No Photos" state
                            binding.recyclerViewGallery.setVisibility(View.GONE);
                            binding.textNoPhotos.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null; // Important to prevent memory leaks
        if (databaseExecutor != null) {
            databaseExecutor.shutdown();
        }
    }
}