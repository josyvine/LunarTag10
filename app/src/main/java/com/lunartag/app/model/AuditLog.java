package com.lunartag.app.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * A data model class that represents an audit log record in the local Room database.
 * An entry is created for every critical action performed in the app.
 */
@Entity(tableName = "audit_logs")
public class AuditLog {

    @PrimaryKey(autoGenerate = true)
    public long id;

    private long photoId; // The ID of the photo this log is related to
    private String action; // e.g., "CAPTURE", "ASSIGN", "SEND_ATTEMPT", "SEND_SUCCESS", "SEND_FAILED"
    private String details; // A string to store extra details, potentially as JSON
    private long timestamp; // Stored as long (milliseconds) for Room

    // --- Getters and Setters for all fields ---

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getPhotoId() {
        return photoId;
    }

    public void setPhotoId(long photoId) {
        this.photoId = photoId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}