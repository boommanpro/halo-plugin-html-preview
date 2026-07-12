package run.halo.htmlpreview.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import org.springframework.web.server.ServerWebInputException;
import run.halo.app.extension.ReactiveExtensionClient;
import org.springframework.data.domain.Sort;
import run.halo.htmlpreview.dto.ProjectVo;
import run.halo.htmlpreview.extension.HtmlProject;

@Service
@RequiredArgsConstructor
@Slf4j
public class HtmlProjectService {

    private static final long MAX_ZIP_BYTES = 50L * 1024 * 1024;
    private static final String ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RNG = new SecureRandom();
    private static final int ID_LENGTH = 8;
    /**
     * Slug 规则:2-63 字符,小写字母/数字/连字符,首尾必须是字母或数字。
     */
    private static final Pattern SLUG_PATTERN =
        Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");

    private final ReactiveExtensionClient client;
    private final ProjectStorage storage;
    private final ZipExtractor zipExtractor;

    public Mono<ProjectVo> upload(FilePart file, String displayName, String slug) {
        String normalizedSlug = normalizeSlug(slug);
        return DataBufferUtils.join(file.content())
            .flatMap(dataBuffer -> {
                byte[] bytes;
                try (var is = dataBuffer.asInputStream()) {
                    bytes = is.readAllBytes();
                } catch (IOException e) {
                    DataBufferUtils.release(dataBuffer);
                    return Mono.error(e);
                } finally {
                    DataBufferUtils.release(dataBuffer);
                }
                if (bytes.length > MAX_ZIP_BYTES) {
                    return Mono.error(new ServerWebInputException(
                        "zip cannot exceed 50MB"));
                }
                // 用户提供了 slug → 先检查 extension 是否已存在,避免误覆盖。
                if (normalizedSlug != null) {
                    return client.fetch(HtmlProject.class, normalizedSlug)
                        .flatMap(existing -> Mono.<ProjectVo>error(
                            new ServerWebInputException(
                                "slug already exists: " + normalizedSlug
                                + " (use replace API instead)")))
                        .switchIfEmpty(Mono.fromCallable(() -> prepare(bytes,
                                displayName, file.filename(), normalizedSlug))
                            .flatMap(this::save));
                }
                // 未提供 slug → 随机生成;冲突概率极低(8 chars @ 36 alphabet ≈ 2.8T)。
                // 万一发生冲突,client.create 会失败,save() 会清理磁盘文件。
                String randomSlug = generateId();
                return Mono.fromCallable(() -> prepare(bytes, displayName,
                        file.filename(), randomSlug))
                    .flatMap(this::save);
            });
    }

    /**
     * 替换已存在项目的文件:删除旧文件 → 解压新 zip → 更新 spec。
     * slug 保持不变,extension 资源的 metadata.name 也不变。
     */
    public Mono<ProjectVo> replace(String name, FilePart file, String displayName) {
        if (!isValidSlug(name)) {
            return Mono.error(new ServerWebInputException(
                "invalid project slug: " + name));
        }
        return client.fetch(HtmlProject.class, name)
            .switchIfEmpty(Mono.error(new ServerWebInputException(
                "project not found: " + name)))
            .flatMap(existing -> DataBufferUtils.join(file.content())
                .flatMap(dataBuffer -> {
                    byte[] bytes;
                    try (var is = dataBuffer.asInputStream()) {
                        bytes = is.readAllBytes();
                    } catch (IOException e) {
                        DataBufferUtils.release(dataBuffer);
                        return Mono.error(e);
                    } finally {
                        DataBufferUtils.release(dataBuffer);
                    }
                    if (bytes.length > MAX_ZIP_BYTES) {
                        return Mono.error(new ServerWebInputException(
                            "zip cannot exceed 50MB"));
                    }
                    return Mono.fromCallable(() -> prepareReplace(bytes,
                            displayName, file.filename(), name))
                        .flatMap(prepared -> applyReplace(existing, prepared));
                }));
    }

    private record PreparedProject(HtmlProject project, Path projectDir) {}

    private PreparedProject prepare(byte[] bytes, String displayName,
                                   String originalFilename, String slug) {
        Path projectDir = storage.root().resolve(slug);
        if (Files.exists(projectDir)) {
            throw new ServerWebInputException(
                "slug already exists: " + slug + " (use replace API instead)");
        }
        try {
            Files.createDirectories(projectDir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        ZipExtractor.Result result;
        try (var bais = new java.io.ByteArrayInputStream(bytes)) {
            result = zipExtractor.extract(bais, projectDir);
        } catch (Exception e) {
            deleteQuietly(projectDir);
            throw new ServerWebInputException(
                "extract failed: " + e.getMessage());
        }
        if (result.fileCount() == 0) {
            deleteQuietly(projectDir);
            throw new ServerWebInputException("empty zip");
        }
        if (!Files.exists(projectDir.resolve("index.html"))) {
            deleteQuietly(projectDir);
            throw new ServerWebInputException(
                "missing index.html in project root");
        }
        HtmlProject project = new HtmlProject();
        project.getMetadata().setName(slug);
        project.setSpec(new HtmlProject.Spec());
        project.getSpec().setDisplayName(
            (displayName == null || displayName.isBlank())
                ? stripExt(originalFilename)
                : displayName);
        project.getSpec().setOriginalFilename(originalFilename);
        project.getSpec().setOriginalSize(bytes.length);
        project.getSpec().setExtractedSize(result.extractedSize());
        project.getSpec().setFileCount(result.fileCount());
        project.getSpec().setCreatedAt(Instant.now());
        return new PreparedProject(project, projectDir);
    }

    private PreparedProject prepareReplace(byte[] bytes, String displayName,
                                          String originalFilename, String slug) {
        Path projectDir = storage.root().resolve(slug);
        // 先清理旧文件再解压新文件,确保目录干净。
        deleteQuietly(projectDir);
        try {
            Files.createDirectories(projectDir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        ZipExtractor.Result result;
        try (var bais = new java.io.ByteArrayInputStream(bytes)) {
            result = zipExtractor.extract(bais, projectDir);
        } catch (Exception e) {
            deleteQuietly(projectDir);
            throw new ServerWebInputException(
                "extract failed: " + e.getMessage());
        }
        if (result.fileCount() == 0) {
            deleteQuietly(projectDir);
            throw new ServerWebInputException("empty zip");
        }
        if (!Files.exists(projectDir.resolve("index.html"))) {
            deleteQuietly(projectDir);
            throw new ServerWebInputException(
                "missing index.html in project root");
        }
        HtmlProject project = new HtmlProject();
        project.getMetadata().setName(slug);
        project.setSpec(new HtmlProject.Spec());
        project.getSpec().setDisplayName(
            (displayName == null || displayName.isBlank())
                ? stripExt(originalFilename)
                : displayName);
        project.getSpec().setOriginalFilename(originalFilename);
        project.getSpec().setOriginalSize(bytes.length);
        project.getSpec().setExtractedSize(result.extractedSize());
        project.getSpec().setFileCount(result.fileCount());
        project.getSpec().setCreatedAt(Instant.now());
        return new PreparedProject(project, projectDir);
    }

    private Mono<ProjectVo> save(PreparedProject pp) {
        return client.create(pp.project())
            .map(this::toVo)
            .onErrorResume(e -> {
                deleteQuietly(pp.projectDir());
                return Mono.error(new RuntimeException(
                    "create extension failed: " + e.getMessage(), e));
            });
    }

    private Mono<ProjectVo> applyReplace(HtmlProject existing, PreparedProject pp) {
        // 复用 existing 的 metadata.version 以避免并发覆盖冲突。
        HtmlProject updated = pp.project();
        updated.getMetadata().setVersion(existing.getMetadata().getVersion());
        return client.update(updated)
            .map(this::toVo)
            .onErrorResume(e -> Mono.error(new RuntimeException(
                "update extension failed: " + e.getMessage(), e)));
    }

    public Mono<HtmlProject> findById(String id) {
        return client.fetch(HtmlProject.class, id);
    }

    public Path rootDir() {
        return storage.root();
    }

    public Mono<java.util.List<ProjectVo>> listAll() {
        return client.listAll(HtmlProject.class, new ListOptions(),
                Sort.by(Sort.Order.desc("metadata.creationTimestamp")))
            .map(this::toVo)
            .collectList();
    }

    public Mono<ProjectVo> getVo(String name) {
        return client.fetch(HtmlProject.class, name).map(this::toVo);
    }

    public Mono<Void> deleteById(String id) {
        return client.fetch(HtmlProject.class, id)
            .flatMap(p -> client.delete(p)
                .doOnSuccess(v -> deleteQuietly(storage.root().resolve(id)))
                .then()
            );
    }

    private ProjectVo toVo(HtmlProject p) {
        return new ProjectVo(
            p.getMetadata().getName(),
            p.getSpec().getDisplayName(),
            p.getSpec().getOriginalFilename(),
            p.getSpec().getOriginalSize(),
            p.getSpec().getExtractedSize(),
            p.getSpec().getFileCount(),
            p.getSpec().getCreatedAt(),
            "/preview/" + p.getMetadata().getName() + "/"
        );
    }

    private static String normalizeSlug(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) return null;
        if (!isValidSlug(trimmed)) {
            throw new ServerWebInputException(
                "invalid slug: must be 2-63 chars, lowercase letters/digits/hyphens, "
                + "start and end with letter or digit");
        }
        return trimmed;
    }

    public static boolean isValidSlug(String slug) {
        return slug != null && SLUG_PATTERN.matcher(slug).matches();
    }

    private static String generateId() {
        var sb = new StringBuilder(ID_LENGTH);
        for (int i = 0; i < ID_LENGTH; i++) {
            sb.append(ID_ALPHABET.charAt(RNG.nextInt(ID_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String stripExt(String name) {
        if (name == null) return "未命名项目";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            if (!Files.exists(p)) return;
            try (var paths = Files.walk(p)) {
                paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (IOException ignored) {}
                    });
            }
        } catch (IOException e) {
            log.warn("[html-preview] failed to delete {}: {}", p, e.getMessage());
        }
    }

    public static MediaType ALLOWED_MEDIA_TYPE = MediaType.APPLICATION_OCTET_STREAM;
}
