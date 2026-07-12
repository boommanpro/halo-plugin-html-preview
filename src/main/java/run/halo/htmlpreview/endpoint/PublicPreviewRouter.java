package run.halo.htmlpreview.endpoint;

import java.nio.file.Files;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.htmlpreview.service.HtmlProjectService;
import run.halo.htmlpreview.service.PathGuard;

/**
 * F-005 / F-008 / F-009 / F-011:公开预览路由。/preview/{id}/ 与 /preview/{id}/** 匿名访问。
 * <p>实现 {@link RouterFunction<ServerResponse>} 接口(而非继承 CustomEndpoint),因为公开路由不走 group 前缀。</p>
 * <p>在 2.25.0 中,RouterFunction.route() 返回 Mono&lt;HandlerFunction&lt;T&gt;&gt;。</p>
 * <p>匿名访问通过 role-template-anonymous.yaml 中的 aggregate-to-anonymous 标签放行。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublicPreviewRouter implements RouterFunction<ServerResponse> {

    private static final String PREFIX = "/preview/";

    private final HtmlProjectService projectService;
    private final RouterFunction<ServerResponse> delegate = RouterFunctions.route()
        .GET("/preview/{id}", req -> serveIndex(req))
        .GET("/preview/{id}/", req -> serveIndex(req))
        .GET("/preview/{id}/**", req -> serveFile(req))
        .build();

    @Override
    public Mono<HandlerFunction<ServerResponse>> route(ServerRequest request) {
        return delegate.route(request);
    }

    private Mono<ServerResponse> serveIndex(ServerRequest request) {
        String id = request.pathVariable("id");
        Path dir = path(id);
        if (!Files.exists(dir)) {
            return ServerResponse.notFound().build();
        }
        Path index = dir.resolve("index.html");
        if (!Files.exists(index)) {
            return ServerResponse.notFound().build();
        }
        return writeFile(index);
    }

    private Mono<ServerResponse> serveFile(ServerRequest request) {
        String fullPath = request.path();
        if (!fullPath.startsWith(PREFIX)) {
            return ServerResponse.notFound().build();
        }
        String after = fullPath.substring(PREFIX.length());
        int slash = after.indexOf('/');
        if (slash < 0) {
            return serveIndex(request);
        }
        String id = after.substring(0, slash);
        String rest = after.substring(slash + 1);
        String safe = PathGuard.sanitizeForUrlPath(rest);
        if (safe == null) {
            return ServerResponse.badRequest().bodyValue("invalid path");
        }
        Path dir = path(id);
        if (!Files.exists(dir)) {
            return ServerResponse.notFound().build();
        }
        Path target = dir.resolve(safe).normalize();
        if (!target.startsWith(dir) || !Files.exists(target) || Files.isDirectory(target)) {
            return ServerResponse.notFound().build();
        }
        return writeFile(target);
    }

    private Path path(String id) {
        return projectService.rootDir().resolve(id);
    }

    private Mono<ServerResponse> writeFile(Path file) {
        Resource resource = new FileSystemResource(file);
        MediaType mediaType = MediaType.parseMediaType(
            PathGuard.guessContentType(file.getFileName().toString()));
        return ServerResponse.ok()
            .contentType(mediaType)
            .bodyValue(resource);
    }
}
