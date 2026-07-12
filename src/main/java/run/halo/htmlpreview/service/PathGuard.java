package run.halo.htmlpreview.service;

import java.util.Locale;

/**
 * F-006 / F-009 / F-010 路径与安全守卫:拒绝 ../ 绝对路径 反斜杠 NUL 等危险输入。
 */
public final class PathGuard {

    private PathGuard() {}

    public static boolean isSafeEntryName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (name.indexOf('\0') >= 0) {
            return false;
        }
        if (name.contains("..")) {
            return false;
        }
        if (name.startsWith("/") || name.startsWith("\\")) {
            return false;
        }
        if (name.contains("\\")) {
            return false;
        }
        return true;
    }

    public static String sanitizeForUrlPath(String rest) {
        if (rest == null) {
            return "";
        }
        if (rest.indexOf('\0') >= 0 || rest.contains("..") || rest.contains("\\")) {
            return null;
        }
        if (rest.startsWith("/")) {
            rest = rest.substring(1);
        }
        return rest;
    }

    public static String guessContentType(String filename) {
        if (filename == null) {
            return "application/octet-stream";
        }
        String ext = "";
        int dot = filename.lastIndexOf('.');
        if (dot >= 0 && dot < filename.length() - 1) {
            ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        }
        return switch (ext) {
            case "html", "htm" -> "text/html;charset=utf-8";
            case "css" -> "text/css;charset=utf-8";
            case "js", "mjs" -> "application/javascript;charset=utf-8";
            case "json" -> "application/json;charset=utf-8";
            case "xml" -> "application/xml;charset=utf-8";
            case "txt", "md" -> "text/plain;charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "ico" -> "image/x-icon";
            case "woff" -> "font/woff";
            case "woff2" -> "font/woff2";
            case "ttf" -> "font/ttf";
            case "otf" -> "font/otf";
            case "eot" -> "application/vnd.ms-fontobject";
            case "mp3" -> "audio/mpeg";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            case "pdf" -> "application/pdf";
            case "zip" -> "application/zip";
            default -> "application/octet-stream";
        };
    }
}
