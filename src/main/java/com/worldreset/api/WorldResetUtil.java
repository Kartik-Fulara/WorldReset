package com.worldreset.api;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Public utility class for world and file management.
 * Optimized for Minecraft server environments.
 */
public class WorldResetUtil {

    /**
     * Extracts a ZIP archive to a destination directory with Zip Slip protection.
     * 
     * @param zipFile the ZIP file to extract
     * @param destDir the directory to extract into
     * @throws IOException if extraction fails or a Zip Slip attempt is detected
     */
    public static void unzip(File zipFile, File destDir) throws IOException {
        String canonicalDest = destDir.getCanonicalPath();
        
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File file = new File(destDir, entry.getName());
                String canonicalEntry = file.getCanonicalPath();
                
                if (!canonicalEntry.startsWith(canonicalDest + File.separator) && !canonicalEntry.equals(canonicalDest)) {
                    throw new IOException("Blocked Zip Slip attempt: entry '" + entry.getName() + "' is outside destination.");
                }

                if (entry.isDirectory()) {
                    file.mkdirs();
                } else {
                    file.getParentFile().mkdirs();
                    try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(file))) {
                        byte[] buffer = new byte[4096];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            bos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    /**
     * Recursively ZIP a directory.
     * 
     * @param sourceDir the directory to ZIP
     * @param zipFile   the destination ZIP file
     * @throws IOException if ZIPPING fails
     */
    public static void zipDirectory(File sourceDir, File zipFile) throws IOException {
        final Path sourcePath = sourceDir.toPath().toAbsolutePath();
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)))) {
            Files.walkFileTree(sourcePath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path absDir = dir.toAbsolutePath();
                    if (!absDir.equals(sourcePath)) {
                        String entry = sourcePath.relativize(absDir).toString().replace('\\', '/') + "/";
                        zos.putNextEntry(new ZipEntry(entry));
                        zos.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path absFile = file.toAbsolutePath();
                    String entry = sourcePath.relativize(absFile).toString().replace('\\', '/');
                    zos.putNextEntry(new ZipEntry(entry));
                    Files.copy(absFile, zos);
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    /**
     * Recursively delete a directory using NIO walkFileTree.
     * 
     * @param path          directory to delete
     * @param preservePaths list of relative paths to keep
     * @param rootPath      absolute root for relativization
     * @return true if the directory itself was deleted
     * @throws IOException if deletion fails
     */
    public static boolean deleteDirectoryNio(Path path, List<String> preservePaths, Path rootPath) throws IOException {
        if (!Files.exists(path)) return true;

        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String rel = rootPath.relativize(file.toAbsolutePath()).toString().replace('\\', '/');
                if (preservePaths.contains(rel)) return FileVisitResult.CONTINUE;
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                String rel = rootPath.relativize(dir.toAbsolutePath()).toString().replace('\\', '/');
                if (preservePaths.contains(rel)) return FileVisitResult.CONTINUE;

                try {
                    Files.delete(dir);
                } catch (DirectoryNotEmptyException e) {
                    // Silently ignore - directory contains preserved files
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return !Files.exists(path);
    }
}