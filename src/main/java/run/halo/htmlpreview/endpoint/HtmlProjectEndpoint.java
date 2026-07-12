package run.halo.htmlpreview.endpoint;

import lombok.RequiredArgsConstructor;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.htmlpreview.dto.ProjectVo;
import run.halo.htmlpreview.service.HtmlProjectService;

/**
 * F-001 ~ F-004: 管理 API. Super-admin 限定, 不需要 role-template.
 * <p>实现 {@link RouterFunction<ServerResponse>} 接口(而非 {@code CustomEndpoint}),
 * 绕过 Halo 的 AggregatedRouterFunction race condition, 路由在插件启动即被注册.</p>
 * <p>URL 前缀: {@code /apis/console.api.html-preview.halo.run/v1alpha1/}.</p>
 */
@Component
@RequiredArgsConstructor
public class HtmlProjectEndpoint implements RouterFunction<ServerResponse> {

    private static final String PREFIX = "/apis/console.api.html-preview.halo.run/v1alpha1";

    private final HtmlProjectService service;
    private final RouterFunction<ServerResponse> delegate = RouterFunctions.route()
        .POST(PREFIX + "/projects", this::upload)
        .GET(PREFIX + "/projects", this::list)
        .GET(PREFIX + "/projects/{name}", this::get)
        .PUT(PREFIX + "/projects/{name}/replace", this::replace)
        .DELETE(PREFIX + "/projects/{name}", this::delete)
        .build();

    @Override
    public Mono<HandlerFunction<ServerResponse>> route(ServerRequest request) {
        return delegate.route(request);
    }

    private Mono<ServerResponse> upload(ServerRequest request) {
        return request.multipartData().flatMap(parts -> {
            FilePart filePart = null;
            String displayName = null;
            String slug = null;
            for (var values : parts.values()) {
                for (Part p : values) {
                    if (p instanceof FilePart fp) {
                        filePart = fp;
                    } else if (p instanceof FormFieldPart ffp) {
                        switch (ffp.name()) {
                            case "displayName" -> displayName = ffp.value();
                            case "slug" -> slug = ffp.value();
                        }
                    }
                }
            }
            if (filePart == null) {
                return ServerResponse.badRequest().bodyValue("missing file part");
            }
            return service.upload(filePart, displayName, slug)
                .flatMap(p -> ServerResponse.ok().bodyValue(p));
        });
    }

    private Mono<ServerResponse> replace(ServerRequest request) {
        String name = request.pathVariable("name");
        return request.multipartData().flatMap(parts -> {
            FilePart filePart = null;
            String displayName = null;
            for (var values : parts.values()) {
                for (Part p : values) {
                    if (p instanceof FilePart fp) {
                        filePart = fp;
                    } else if (p instanceof FormFieldPart ffp
                        && "displayName".equals(ffp.name())) {
                        displayName = ffp.value();
                    }
                }
            }
            if (filePart == null) {
                return ServerResponse.badRequest().bodyValue("missing file part");
            }
            return service.replace(name, filePart, displayName)
                .flatMap(p -> ServerResponse.ok().bodyValue(p));
        });
    }

    private Mono<ServerResponse> list(ServerRequest request) {
        return ServerResponse.ok().body(service.listAll(), ProjectVo.class);
    }

    private Mono<ServerResponse> get(ServerRequest request) {
        String name = request.pathVariable("name");
        return service.getVo(name)
            .flatMap(vo -> ServerResponse.ok().bodyValue(vo))
            .switchIfEmpty(ServerResponse.notFound().build());
    }

    private Mono<ServerResponse> delete(ServerRequest request) {
        String name = request.pathVariable("name");
        return service.deleteById(name)
            .then(ServerResponse.ok().build())
            .onErrorResume(e -> ServerResponse.notFound().build());
    }
}
