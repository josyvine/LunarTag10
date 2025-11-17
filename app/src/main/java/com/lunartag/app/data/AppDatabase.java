package com.lunartag.app.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.lunartag.app.model.AuditLog;
import com.lunartag.app.model.Photo;

/**
 * The main database class for the application.
 * This class defines the database configuration and serves as the main access point
 * to the persisted data. It follows a singleton pattern to prevent having multiple
 * instances of the database opened at the same time.
 */
@Database(entities = {Photo.class, AuditLog.class}, version = 1, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    public abstract PhotoDao photoDao();
    public abstract AuditLogDao auditLogDao();

    private static volatile AppDatabase INSTANCE;

    public static AppDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                                    AppDatabase.class, "lunartag_database")
                            // NOTE: In a production app, you would need a proper migration strategy
                            // instead of destructive migration.
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return INSTANCE;
    }
          }
