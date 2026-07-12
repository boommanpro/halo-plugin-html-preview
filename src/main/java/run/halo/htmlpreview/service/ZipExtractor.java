package run.halo.htmlpreview.service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebInputException;

/**
 * F-006 + F-007:解压时逐 entry 校验,边写边统计。
 * <p>任何 entry 含 .. \\ / \\ NUL 一律失败,磁盘不留半成品。</p>
 */
@Component
public class ZipExtractor {

    public static final long MAX_ENTRY_SIZE = 50L * 1024 * 1024;

    public record Result(long extractedSize, int fileCount) {}

    public Result extract(InputStream in, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        long extracted = 0;
        int count = 0;
        try (var zis = new ZipInputStream(new BufferedInputStream(in))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }
                String name = entry.getName();
                if (!PathGuard.isSafeEntryName(name)) {
                    throw new ServerWebInputException(
                        "illegal zip entry: " + name);
                }
                long entrySize = entry.getSize();
                if (entrySize > MAX_ENTRY_SIZE) {
                    throw new ServerWebInputException(
                        "zip entry too large: " + name);
                }
                Path target = targetDir.resolve(name).normalize();
                if (!target.startsWith(targetDir)) {
                    throw new ServerWebInputException(
                        "zip entry escapes project dir: " + name);
                }
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                long written = Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                extracted += written;
                count++;
                if (count > 5000) {
                    throw new ServerWebInputException(
                        "too many files (>5000)");
                }
                if (extracted > 200L * 1024 * 1024) {
                    throw new ServerWebInputException(
                        "extracted size exceeds 200MB");
                }
                zis.closeEntry();
            }
        }
        return new Result(extracted, count);
    }
}
