package com.kalo.document.service;

import com.kalo.common.exception.InvalidOperationException;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;

/** Shared byte policy; filename and caller-supplied MIME never decide the type. */
final class DocumentFilePolicy {
    private DocumentFilePolicy() {}
    record Upload(byte[] bytes, String extension) {}

    static Upload upload(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > DocumentStorage.MAX_FILE_BYTES)
            throw new InvalidOperationException("Choose a PDF, JPG or PNG file of at most 5 MB");
        try (var input = file.getInputStream()) {
            byte[] bytes = input.readNBytes((int) DocumentStorage.MAX_FILE_BYTES + 1);
            if (bytes.length == 0 || bytes.length > DocumentStorage.MAX_FILE_BYTES)
                throw new InvalidOperationException("Document exceeds the 5 MB limit");
            String extension = extension(bytes);
            if (extension == null) throw new InvalidOperationException("Only PDF, JPG and PNG documents are accepted");
            return new Upload(bytes, extension);
        } catch (IOException exception) {
            throw new InvalidOperationException("Could not read uploaded document");
        }
    }

    static String extension(byte[] b) {
        if (b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') return "pdf";
        if (b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && (b[4] & 0xff) == 13 && (b[5] & 0xff) == 10 && (b[6] & 0xff) == 26 && (b[7] & 0xff) == 10) return "png";
        if (b.length >= 4 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) return "jpg";
        return null;
    }

    static String contentType(String extension) {
        return switch (extension) {
            case "pdf" -> "application/pdf";
            case "png" -> "image/png";
            default -> "image/jpeg";
        };
    }
}
