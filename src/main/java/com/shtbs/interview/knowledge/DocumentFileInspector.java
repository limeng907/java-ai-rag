package com.shtbs.interview.knowledge;

import com.shtbs.interview.common.ErrorCode;
import com.shtbs.interview.exception.BusinessException;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

@Component
public class DocumentFileInspector {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final Set<String> TYPES = Set.of("pdf", "docx", "txt", "md");
    private final Tika tika = new Tika();

    public record Upload(String name, String type, String mime, byte[] bytes, String hash) {
        public long size() { return bytes.length; }
    }

    //校验上传的文件是否合法
    public Upload inspect(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw new BusinessException(ErrorCode.INVALID_DOCUMENT_FILE);
        }
        String rawName = file.getOriginalFilename();
        String name = rawName == null ? "" : rawName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isBlank() || name.length() > 255 || name.indexOf('\0') >= 0) {
            throw new BusinessException(ErrorCode.INVALID_DOCUMENT_FILE);
        }
        int dot = name.lastIndexOf('.');
        String type = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw new BusinessException(ErrorCode.INVALID_DOCUMENT_FILE);
        }
        byte[] bytes = file.getBytes();
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new BusinessException(ErrorCode.INVALID_DOCUMENT_FILE);
        }
        //tika:结合文件内容和文件名称检测实际MIME类型
        //MIME是一种描述数据内容类型的标准，让程序知道收到的数据是什么格式
        String mime = tika.detect(bytes, name);
        boolean matching = switch (type) {
            case "pdf" -> "application/pdf".equals(mime);
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mime);
            case "txt", "md" -> mime.startsWith("text/") && validUtf8(bytes);
            default -> false;
        };
        if (!matching) {
            throw new BusinessException(ErrorCode.INVALID_DOCUMENT_FILE);
        }
        //这里的sha256是用来检测问及那是否重复上传
        return new Upload(name, type, mime, bytes, sha256(bytes));
    }

    private boolean validUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException ex) {
            return false;
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
