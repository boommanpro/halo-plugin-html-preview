package run.halo.htmlpreview.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;
import run.halo.app.extension.Metadata;

@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@GVK(group = "html-preview.halo.run",
     version = "v1alpha1",
     kind = "HtmlProject",
     plural = "htmlprojects",
     singular = "htmlproject")
public class HtmlProject extends AbstractExtension {

    public HtmlProject() {
        setMetadata(new Metadata());
    }

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    @Data
    public static class Spec {
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 100)
        private String displayName;

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String originalFilename;

        @Schema(description = "Original zip file size in bytes")
        private long originalSize;

        @Schema(description = "Total extracted size in bytes (F-007 limit)")
        private long extractedSize;

        @Schema(description = "Number of files in the project (F-007 limit)")
        private int fileCount;

        @Schema(description = "Upload time (UTC)")
        private Instant createdAt;
    }
}
