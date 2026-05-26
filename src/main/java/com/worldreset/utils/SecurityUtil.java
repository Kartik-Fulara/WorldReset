package com.worldreset.utils;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Logger;

/**
 * Security and health utilities for disk space and data integrity.
 */
public class SecurityUtil {

    /**
     * Calculates the recursive size of a directory in bytes.
     */
    public static long getDirectorySize(File dir) {
        if (!dir.exists() || !dir.isDirectory()) return 0;
        final long[] size = {0};
        try {
            Files.walkFileTree(dir.toPath(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    size[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {}
        return size[0];
    }

    /**
     * Returns the free space available on the partition containing {@code file} in bytes.
     */
    public static long getFreeSpace(File file) {
        // Ensure parent exists to get partition info
        File target = file;
        while (target != null && !target.exists()) {
            target = target.getParentFile();
        }
        return (target != null) ? target.getFreeSpace() : 0;
    }

    /**
     * Generates a SHA-256 checksum for a file and writes it to a .sha256 file next to it.
     */
    public static String createChecksum(File file) throws IOException, NoSuchAlgorithmException {
        String hash = getFileChecksum(file);
        File hashFile = new File(file.getAbsolutePath() + ".sha256");
        try (PrintWriter out = new PrintWriter(hashFile)) {
            out.print(hash);
        }
        return hash;
    }

    /**
     * Verifies a file against its .sha256 checksum file.
     * @return true if matches, false if mismatched or checksum file missing.
     */
    public static boolean verifyChecksum(File file) {
        File hashFile = new File(file.getAbsolutePath() + ".sha256");
        if (!hashFile.exists()) return false;

        try {
            String expected = Files.readString(hashFile.toPath()).trim();
            String actual = getFileChecksum(file);
            return expected.equalsIgnoreCase(actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static String getFileChecksum(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream fis = new FileInputStream(file)) {
            byte[] byteArray = new byte[8192];
            int bytesCount;
            while ((bytesCount = fis.read(byteArray)) != -1) {
                digest.update(byteArray, 0, bytesCount);
            }
        }
        byte[] bytes = digest.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(Integer.toString((b & 0xff) + 0x100, 16).substring(1));
        }
        return sb.toString();
    }
}
