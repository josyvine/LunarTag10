package com.lunartag.app.utils;

import android.location.Location;
import androidx.exifinterface.media.ExifInterface;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * A utility class to handle writing custom EXIF data to image files.
 */
public class ExifUtils {

    // Private constructor to prevent instantiation
    private ExifUtils() {}

    /**
     * Writes all required location and timestamp metadata to the image's EXIF tags.
     * @param filePath The absolute path to the saved JPEG image.
     * @param realLocation The real GPS location of the capture.
     * @param realCaptureTimestamp The real system time of the capture (in milliseconds).
     * @param assignedTimestamp The assigned timestamp (in milliseconds).
     */
    public static void writeExifData(String filePath, Location realLocation, long realCaptureTimestamp, long assignedTimestamp) {
        try {
            ExifInterface exifInterface = new ExifInterface(filePath);

            // 1. Write standard GPS tags with the real location data
            if (realLocation != null) {
                exifInterface.setGpsInfo(realLocation);
            }

            // 2. Write the standard DateTimeOriginal tag with the REAL capture time
            SimpleDateFormat exifSdf = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);
            exifSdf.setTimeZone(TimeZone.getDefault());
            exifInterface.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifSdf.format(new Date(realCaptureTimestamp)));
            exifInterface.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, exifSdf.format(new Date(realCaptureTimestamp)));

            // 3. Write a custom ImageDescription tag containing a JSON string for auditing
            // This stores both the real and assigned timestamps clearly for verification.
            String jsonPayload = "{\"assignedTimestamp\":" + assignedTimestamp + ",\"captureTimestampReal\":" + realCaptureTimestamp + "}";
            exifInterface.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, jsonPayload);

            // Save the changes to the file
            exifInterface.saveAttributes();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
