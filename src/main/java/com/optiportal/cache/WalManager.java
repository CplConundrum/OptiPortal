package com.optiportal.cache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.optiportal.config.PluginConfig;

/**
 * Write-Ahead Log manager for JSON backend crash protection.
 * Before any cache write: WAL entry written first.
 * On startup: incomplete WAL entries trigger .bak recovery.
 * SQLite/H2/MySQL have native WAL - this is mainly for JSON safety.
 */
public class WalManager {

    private final File walDir;
    private final File dataFolder;
    private static final DateTimeFormatter BACKUP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault());

    public WalManager(PluginConfig config) {
        this.dataFolder = config.getDataFolder();
        this.walDir = new File(dataFolder, "preload-cache/wal");
        walDir.mkdirs();
    }

    public void writeIntent(String key) {
        try {
            new File(walDir, sanitize(key) + ".wal").createNewFile();
        } catch (IOException e) {
            System.err.println("[OptiPortal] WAL write failed for " + key + ": " + e.getMessage());
        }
    }

    public void clearIntent(String key) {
        new File(walDir, sanitize(key) + ".wal").delete();
    }

    public boolean hasIncompleteIntent(String key) {
        return new File(walDir, sanitize(key) + ".wal").exists();
    }

    public File[] getAllIncompleteIntents() {
        File[] files = walDir.listFiles((d, name) -> name.endsWith(".wal"));
        return files != null ? files : new File[0];
    }

    private String sanitize(String key) {
        return key.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    /**
     * Write content to a file atomically using a temp file + rename pattern.
     * This prevents corruption from mid-write crashes.
     * @param target the target file to write
     * @param content the content to write
     * @throws IOException if write fails
     */
    public void writeAtomic(File target, String content) throws IOException {
        File tmp = new File(target.getParent(), target.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp);
             FileChannel channel = fos.getChannel();
             Writer w = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            w.write(content);
            w.flush();
            // fsync before rename so content is on disk before the file is visible
            channel.force(true);
        }
        // Atomic rename — on most OS this is atomic at filesystem level
        if (!tmp.renameTo(target)) {
            // Fallback: copy + delete
            Files.move(tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * List available backups (dated .bak files in the data folder).
     * Returns display strings like "2026-03-09_14-30-00 (portal-data.json.bak)".
     */
    public List<String> listBackups() {
        List<String> result = new ArrayList<>();
        File[] baks = dataFolder.listFiles((d, name) -> name.endsWith(".bak"));
        if (baks == null) return result;
        for (File bak : baks) {
            try {
                BasicFileAttributes attrs = Files.readAttributes(bak.toPath(), BasicFileAttributes.class);
                String date = BACKUP_FMT.format(attrs.lastModifiedTime().toInstant());
                result.add(date + "  (" + bak.getName() + ")");
            } catch (IOException e) {
                result.add(bak.getName());
            }
        }
        result.sort(Comparator.reverseOrder());
        return result;
    }

    /**
     * Restore a backup by matching the date prefix or filename.
     * Copies the matched .bak file over portal-data.json.
     * Returns true on success.
     */
    public boolean restoreBackup(String dateOrName) {
        File[] baks = dataFolder.listFiles((d, name) -> name.endsWith(".bak"));
        if (baks == null) return false;
        for (File bak : baks) {
            if (bak.getName().contains(dateOrName) || bak.getName().startsWith(dateOrName)) {
                File target = new File(dataFolder, bak.getName().replace(".bak", ""));
                try {
                    Files.copy(bak.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("[OptiPortal] Restored backup " + bak.getName() + " → " + target.getName());
                    return true;
                } catch (IOException e) {
                    System.err.println("[OptiPortal] Backup restore failed: " + e.getMessage());
                    return false;
                }
            }
        }
        return false;
    }
}
