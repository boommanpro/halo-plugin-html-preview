package run.halo.htmlpreview.service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class ProjectStorage {

    @Value("${halo.plugin.html-preview.storage-root:#{'${user.home}/.halo2/html-projects'}}")
    private String storageRoot;

    private Path root;

    @PostConstruct
    void init() throws IOException {
        root = Paths.get(storageRoot);
        Files.createDirectories(root);
        log.info("[html-preview] storage root = {}", root.toAbsolutePath());
    }

    public Mono<Path> projectDir(String id) {
        if (id == null || id.contains("/") || id.contains("\\") || id.contains("..")) {
            return Mono.error(new IllegalArgumentException("invalid project id: " + id));
        }
        return Mono.just(root.resolve(id));
    }

    public Path root() {
        return root;
    }
}
