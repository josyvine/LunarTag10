package com.hfm.app;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.libtorrent4j.AddTorrentParams;
import org.libtorrent4j.AlertListener;
import org.libtorrent4j.AlertType;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.Sha1Hash;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.alerts.Alert;
import org.libtorrent4j.alerts.StateUpdateAlert;
import org.libtorrent4j.alerts.TorrentErrorAlert;
import org.libtorrent4j.alerts.TorrentFinishedAlert;
import org.libtorrent4j.swig.create_torrent;
import org.libtorrent4j.swig.file_storage;
import org.libtorrent4j.swig.libtorrent;  // For default_torrent_piece_size
import org.libtorrent4j.swig.piece_index_vector;  // For manual piece layers if needed

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TorrentManager {

    private static final String TAG = "TorrentManager";
    private static volatile TorrentManager instance;

    private final SessionManager sessionManager;
    private final Context appContext;

    // Maps to track active torrents
    private final Map<String, TorrentHandle> activeTorrents; // dropRequestId -> TorrentHandle
    private final Map<Sha1Hash, String> hashToIdMap; // infoHash -> dropRequestId

    private TorrentManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.sessionManager = new SessionManager();
        this.activeTorrents = new ConcurrentHashMap<>();
        this.hashToIdMap = new ConcurrentHashMap<>();

        // Set up the listener for torrent events
        sessionManager.addListener(new AlertListener() {
            @Override
            public int[] types() {
                // FIXED: Return alert categories as ints (from AlertType enum ordinals or libtorrent constants)
                return new int[]{
                        AlertType.STATE_NOTIFICATION.swigValue(),  // For StateUpdateAlert
                        AlertType.TORRENT_PROGRESS.swigValue(),    // For TorrentFinishedAlert
                        AlertType.ERROR_NOTIFICATION.swigValue()   // For TorrentErrorAlert
                };
            }

            @Override
            public void alert(Alert<?> alert) {
                // FIXED: Compare using alert.type().swigValue() to int enum value
                int alertType = alert.type().swigValue();

                if (alertType == AlertType.STATE_NOTIFICATION.swigValue()) {
                    handleStateUpdate((StateUpdateAlert) alert);
                } else if (alertType == AlertType.TORRENT_PROGRESS.swigValue()) {
                    handleTorrentFinished((TorrentFinishedAlert) alert);
                } else if (alertType == AlertType.ERROR_NOTIFICATION.swigValue()) {
                    handleTorrentError((TorrentErrorAlert) alert);
                }
            }
        });

        // Start the session, this will start the DHT and other services
        sessionManager.start();
    }

    public static TorrentManager getInstance(Context context) {
        if (instance == null) {
            synchronized (TorrentManager.class) {
                if (instance == null) {
                    instance = new TorrentManager(context);
                }
            }
        }
        return instance;
    }

    private void handleStateUpdate(StateUpdateAlert alert) {
        for (TorrentStatus status : alert.status()) {
            // FIXED: Use infoHash() (returns Sha1Hash directly; no infoHashes().v1())
            String dropRequestId = hashToIdMap.get(status.infoHash());
            if (dropRequestId != null) {
                Intent intent = new Intent(DropProgressActivity.ACTION_UPDATE_STATUS);
                intent.putExtra(DropProgressActivity.EXTRA_STATUS_MAJOR, status.isSeeding() ? "Sending File..." : "Receiving File...");
                intent.putExtra(DropProgressActivity.EXTRA_STATUS_MINOR, "Peers: " + status.numPeers() + " | ↓ " + (status.downloadPayloadRate() / 1024) + " KB/s | ↑ " + (status.uploadPayloadRate() / 1024) + " KB/s");
                // FIXED: Use raw totalDone() as int (your original; cast long to int if <2GB)
                intent.putExtra(DropProgressActivity.EXTRA_PROGRESS, (int) status.totalDone());
                intent.putExtra(DropProgressActivity.EXTRA_MAX_PROGRESS, (int) status.totalWanted());
                intent.putExtra(DropProgressActivity.EXTRA_BYTES_TRANSFERRED, (long) status.totalDone());
                LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent);
            }
        }
    }

    private void handleTorrentFinished(TorrentFinishedAlert alert) {
        TorrentHandle handle = alert.handle();
        // FIXED: Use infoHash()
        String dropRequestId = hashToIdMap.get(handle.infoHash());
        Log.d(TAG, "Torrent finished for request ID: " + dropRequestId);

        if (dropRequestId != null) {
            Intent intent = new Intent(DropProgressActivity.ACTION_TRANSFER_COMPLETE);
            LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent);
        }

        // Cleanup
        cleanupTorrent(handle);
    }

    private void handleTorrentError(TorrentErrorAlert alert) {
        TorrentHandle handle = alert.handle();
        // FIXED: Use infoHash()
        String dropRequestId = hashToIdMap.get(handle.infoHash());
        // CORRECT API: The error message is now directly on the alert's message() method.
        String errorMsg = alert.message();
        Log.e(TAG, "Torrent error for request ID " + dropRequestId + ": " + errorMsg);

        if (dropRequestId != null) {
            Intent errorIntent = new Intent(DownloadService.ACTION_DOWNLOAD_ERROR);
            errorIntent.putExtra(DownloadService.EXTRA_ERROR_MESSAGE, "Torrent transfer failed: " + errorMsg);
            LocalBroadcastManager.getInstance(appContext).sendBroadcast(errorIntent);

            LocalBroadcastManager.getInstance(appContext).sendBroadcast(new Intent(DropProgressActivity.ACTION_TRANSFER_ERROR));
        }

        // Cleanup
        cleanupTorrent(handle);
    }

    public String startSeeding(File dataFile, String dropRequestId) {  // FIXED: Renamed param to dataFile for clarity
        if (dataFile == null || !dataFile.exists()) {
            Log.e(TAG, "Data file to be seeded does not exist.");
            return null;
        }

        File torrentFile = null;
        try {
            // FIXED: Create .torrent from data file (single-file torrent)
            torrentFile = createTorrentFile(dataFile);  // Helper method below
            final TorrentInfo torrentInfo = new TorrentInfo(torrentFile);
            
            AddTorrentParams params = new AddTorrentParams();
            // FIXED: Use setters: setTi(TorrentInfo) and setSavePath(String)
            params.setTi(torrentInfo);
            params.setSavePath(dataFile.getParentFile().getAbsolutePath());
            
            // FIXED: Direct on SessionManager (no getSession())
            sessionManager.addTorrent(params);
            // FIXED: Direct findTorrent(Sha1Hash); use infoHash()
            TorrentHandle handle = sessionManager.findTorrent(torrentInfo.infoHash());

            if (handle != null) {
                activeTorrents.put(dropRequestId, handle);
                // FIXED: Use infoHash()
                hashToIdMap.put(handle.infoHash(), dropRequestId);
                // CORRECT API: The method is .makeMagnetUri()
                String magnetLink = handle.makeMagnetUri();
                Log.d(TAG, "Started seeding for request ID " + dropRequestId + ". Magnet: " + magnetLink);
                return magnetLink;
            } else {
                Log.e(TAG, "Failed to get TorrentHandle after adding seed.");
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create torrent for seeding: " + e.getMessage(), e);
            return null;
        } finally {
            if (torrentFile != null && torrentFile.exists()) {
                torrentFile.delete();  // Cleanup temp .torrent
            }
        }
    }

    private File createTorrentFile(File dataFile) throws IOException {
        // FIXED: Create single-file torrent (no trackers; add via ct.addTracker if needed)
        file_storage fs = new file_storage();
        // FIXED: add_file(String name, long size)
        fs.add_file(dataFile.getName(), dataFile.length());
        // FIXED: libtorrent.default_torrent_piece_size(file_storage)
        int pieceSize = (int) libtorrent.default_torrent_piece_size(fs);
        // FIXED: Constructor create_torrent(file_storage, int)
        create_torrent ct = new create_torrent(fs, pieceSize);
        // REMOVED: set_root not available; not needed for single-file (root is empty by default)

        // FIXED: Generate hashes (call once); add manual piece layers if needed for v2/hybrid
        ct.generate();
        // For basic v1, no extra layers; if error, add: ct.set_piece_layers(piece_index_vector, ...)

        // FIXED: bencode() directly on ct after generate() (returns byte[])
        byte[] torrentBytes = ct.bencode();

        // Write to temp .torrent file
        File tempTorrent = File.createTempFile("seed_", ".torrent", dataFile.getParentFile());
        try (FileOutputStream fos = new FileOutputStream(tempTorrent)) {
            fos.write(torrentBytes);
        }
        return tempTorrent;
    }

    public void startDownload(String magnetLink, File saveDirectory, String dropRequestId) {
        if (!saveDirectory.exists()) {
            saveDirectory.mkdirs();
        }

        // CORRECT API: .parseMagnetUri is a static method on AddTorrentParams.
        AddTorrentParams params = AddTorrentParams.parseMagnetUri(magnetLink);
        // FIXED: setSavePath(String)
        params.setSavePath(saveDirectory.getAbsolutePath());
        // FIXED: Direct on SessionManager
        sessionManager.addTorrent(params);
        // FIXED: Direct findTorrent; params has no infoHashes(), use parse result or wait for handle
        // Note: For magnet, hash may not be immediate; use a callback or poll. For now, assume after add
        Sha1Hash expectedHash = Sha1Hash.from_hex(/* extract from magnet if needed */);  // TODO: Parse magnet for hash
        TorrentHandle handle = sessionManager.findTorrent(expectedHash);

        if (handle != null) {
            activeTorrents.put(dropRequestId, handle);
            // FIXED: Use infoHash()
            hashToIdMap.put(handle.infoHash(), dropRequestId);
            Log.d(TAG, "Started download for request ID: " + dropRequestId);
        } else {
            Log.e(TAG, "Failed to get TorrentHandle after adding download from magnet link.");
        }
    }

    private void cleanupTorrent(TorrentHandle handle) {
        if (handle == null || !handle.isValid()) {
            return;
        }
        // FIXED: Use infoHash()
        Sha1Hash hash = handle.infoHash();
        String dropRequestId = hashToIdMap.get(hash);

        if (dropRequestId != null) {
            activeTorrents.remove(dropRequestId);
            hashToIdMap.remove(hash);
        }
        // FIXED: Direct on SessionManager
        sessionManager.removeTorrent(handle);
        Log.d(TAG, "Cleaned up and removed torrent for request ID: " + (dropRequestId != null ? dropRequestId : "unknown"));
    }

    public void stopSession() {
        Log.d(TAG, "Stopping torrent session manager.");
        sessionManager.stop();
        activeTorrents.clear();
        hashToIdMap.clear();
        instance = null; // Allow re-initialization if needed
    }
}