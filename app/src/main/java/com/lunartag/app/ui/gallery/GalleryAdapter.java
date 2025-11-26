package com.lunartag.app.ui.gallery;

import android.content.Context; 
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.lunartag.app.R;
import com.lunartag.app.model.Photo;
import com.lunartag.app.ui.viewer.ImageViewerActivity;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class GalleryAdapter extends RecyclerView.Adapter<GalleryAdapter.PhotoViewHolder> {

    private final Context context;
    private final List<Photo> photoList;
    private final SimpleDateFormat timeFormat;

    // --- Selection Mode Variables ---
    private boolean isSelectionMode = false;
    private final Set<Long> selectedIds = new HashSet<>();
    private OnSelectionChangeListener selectionListener;

    public interface OnSelectionChangeListener {
        void onSelectionChanged(int count);
    }

    public GalleryAdapter(Context context, List<Photo> photoList) {
        this.context = context;
        this.photoList = photoList;
        this.timeFormat = new SimpleDateFormat("hh:mm a", Locale.US);
    }

    public void setSelectionListener(OnSelectionChangeListener listener) {
        this.selectionListener = listener;
    }

    @NonNull
    @Override
    public PhotoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_photo_thumbnail, parent, false);
        return new PhotoViewHolder(itemView);
    }

    @Override
    public void onBindViewHolder(@NonNull PhotoViewHolder holder, int position) {
        Photo currentPhoto = photoList.get(position);

        // 1. Set Text Data
        holder.timestampTextView.setText(timeFormat.format(currentPhoto.getAssignedTimestamp()));
        holder.statusTextView.setText(currentPhoto.getStatus());

        // 2. Load Image Efficiently (Thumbnail size)
        String filePath = currentPhoto.getFilePath();

        // FIXED: Check for Content URI (Custom Folder) vs File Path (Internal)
        if (filePath != null && filePath.startsWith("content://")) {
            // It is a Custom Folder URI - Load directly via Glide
            Glide.with(context)
                    .load(Uri.parse(filePath))
                    .override(320, 320) // Render small for grid performance
                    .centerCrop()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(holder.thumbnailImageView);
        } else {
            // It is a Default Internal File - Check existence first
            File imageFile = new File(filePath);
            if (imageFile.exists()) {
                Glide.with(context)
                        .load(Uri.fromFile(imageFile))
                        .override(320, 320) // Render small for grid performance
                        .centerCrop()
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .into(holder.thumbnailImageView);
            } else {
                // Clear image if file missing
                holder.thumbnailImageView.setImageDrawable(null);
            }
        }

        // 3. Handle Selection Mode UI
        if (isSelectionMode) {
            holder.checkBox.setVisibility(View.VISIBLE);
            holder.checkBox.setChecked(selectedIds.contains(currentPhoto.getId()));
        } else {
            holder.checkBox.setVisibility(View.GONE);
        }

        // 4. Handle Click Logic
        holder.itemView.setOnClickListener(v -> {
            if (isSelectionMode) {
                toggleSelection(currentPhoto.getId());
            } else {
                openImageViewer(position);
            }
        });

        // 5. Handle Long Click (Start Selection Mode)
        holder.itemView.setOnLongClickListener(v -> {
            if (!isSelectionMode) {
                isSelectionMode = true;
                toggleSelection(currentPhoto.getId());
                notifyDataSetChanged(); // Refresh all items to show checkboxes
                return true;
            }
            return false;
        });
    }

    private void toggleSelection(long photoId) {
        if (selectedIds.contains(photoId)) {
            selectedIds.remove(photoId);
        } else {
            selectedIds.add(photoId);
        }

        // Notify listener (Fragment) to update title or delete button
        if (selectionListener != null) {
            selectionListener.onSelectionChanged(selectedIds.size());
        }
        
        // Auto-exit selection mode if nothing is left selected
        if (selectedIds.isEmpty()) {
            isSelectionMode = false;
            notifyDataSetChanged();
        } else {
            notifyDataSetChanged();
        }
    }

    private void openImageViewer(int position) {
        // Create Intent to open the full-screen viewer
        Intent intent = new Intent(context, ImageViewerActivity.class);
        
        // We pass the ID of the clicked photo and the current list order
        // For simplicity in large lists, we might just pass the position and reload query,
        // but passing the ID list is robust for filtering.
        
        ArrayList<String> pathList = new ArrayList<>();
        ArrayList<Long> idList = new ArrayList<>();
        
        for (Photo p : photoList) {
            pathList.add(p.getFilePath());
            idList.add(p.getId());
        }

        intent.putStringArrayListExtra("paths", pathList);
        intent.putExtra("start_position", position);
        
        context.startActivity(intent);
    }

    // --- Selection Helpers for Fragment ---

    public void selectAll() {
        isSelectionMode = true;
        selectedIds.clear();
        for (Photo p : photoList) {
            selectedIds.add(p.getId());
        }
        notifyDataSetChanged();
        if (selectionListener != null) selectionListener.onSelectionChanged(selectedIds.size());
    }

    public void clearSelection() {
        isSelectionMode = false;
        selectedIds.clear();
        notifyDataSetChanged();
        if (selectionListener != null) selectionListener.onSelectionChanged(0);
    }

    public List<Long> getSelectedIds() {
        return new ArrayList<>(selectedIds);
    }
    
    public boolean isSelectionMode() {
        return isSelectionMode;
    }

    @Override
    public int getItemCount() {
        return photoList.size();
    }

    static class PhotoViewHolder extends RecyclerView.ViewHolder {
        final ImageView thumbnailImageView;
        final TextView timestampTextView;
        final TextView statusTextView;
        final CheckBox checkBox;

        PhotoViewHolder(@NonNull View itemView) {
            super(itemView);
            thumbnailImageView = itemView.findViewById(R.id.image_thumbnail);
            timestampTextView = itemView.findViewById(R.id.text_thumbnail_timestamp);
            statusTextView = itemView.findViewById(R.id.text_thumbnail_status);
            checkBox = itemView.findViewById(R.id.checkbox_select);
        }
    }
}