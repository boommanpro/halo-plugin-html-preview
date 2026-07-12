package run.halo.htmlpreview.dto;

import java.time.Instant;

public record ProjectVo(
        String name,
        String displayName,
        String originalFilename,
        long originalSize,
        long extractedSize,
        int fileCount,
        Instant createdAt,
        String previewUrl
) {}
