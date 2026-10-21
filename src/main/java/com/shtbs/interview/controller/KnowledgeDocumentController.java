package com.shtbs.interview.controller;

import com.shtbs.interview.common.PageResult;
import com.shtbs.interview.common.Result;
import com.shtbs.interview.context.UserContext;
import com.shtbs.interview.dto.KnowledgeQueryDTO;
import com.shtbs.interview.knowledge.KnowledgeDocumentService;
import com.shtbs.interview.knowledge.KnowledgeRepository;
import com.shtbs.interview.vo.RagAnswerVO;
import jakarta.validation.Valid;
import org.springframework.ai.document.Document;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
public class KnowledgeDocumentController {
    private final KnowledgeDocumentService documents;

    public KnowledgeDocumentController(KnowledgeDocumentService documents) {
        this.documents = documents;
    }

    @PostMapping(value = "/knowledge-bases/{baseId}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<KnowledgeDocumentService.DocumentView> upload(
            @PathVariable long baseId, @RequestPart("file") MultipartFile file) throws IOException {
        return Result.success(documents.upload(UserContext.getUserId(), baseId, file));
    }

    @GetMapping("/knowledge/documents")
    public Result<PageResult<KnowledgeDocumentService.DocumentView>> list(
            @RequestParam(required = false) Long knowledgeBaseId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(documents.list(UserContext.getUserId(), knowledgeBaseId, page, size));
    }

    @GetMapping("/knowledge/documents/{id}")
    public Result<KnowledgeDocumentService.DocumentView> get(@PathVariable long id) {
        return Result.success(documents.get(UserContext.getUserId(), id));
    }

    @GetMapping("/knowledge/documents/{id}/versions")
    public Result<List<KnowledgeDocumentService.VersionView>> versions(@PathVariable long id) {
        return Result.success(documents.versions(UserContext.getUserId(), id));
    }

    @GetMapping("/knowledge/documents/{id}/chunks")
    public Result<List<KnowledgeRepository.ChunkRow>> chunks(
            @PathVariable long id, @RequestParam(required = false) Integer version,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(documents.chunks(UserContext.getUserId(), id, version, page, size));
    }

    @GetMapping("/knowledge/documents/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable long id) {
        KnowledgeDocumentService.Download download = documents.download(UserContext.getUserId(), id);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.name(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(download.mimeType()))
                .contentLength(download.bytes().length)
                .body(download.bytes());
    }

    @PutMapping(value = "/knowledge/documents/{id}/file",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<KnowledgeDocumentService.DocumentView> replace(
            @PathVariable long id, @RequestPart("file") MultipartFile file) throws IOException {
        return Result.success(documents.replaceFile(UserContext.getUserId(), id, file));
    }

    @PostMapping("/knowledge/documents/{id}/reindex")
    public Result<KnowledgeDocumentService.DocumentView> reindex(@PathVariable long id) {
        return Result.success(documents.reindex(UserContext.getUserId(), id));
    }

    @PostMapping("/knowledge/documents/{id}/retry")
    public Result<KnowledgeDocumentService.DocumentView> retry(@PathVariable long id) {
        return Result.success(documents.retry(UserContext.getUserId(), id));
    }

    @DeleteMapping("/knowledge/documents/{id}")
    public Result<KnowledgeDocumentService.DocumentView> delete(@PathVariable long id) {
        return Result.success(documents.delete(UserContext.getUserId(), id));
    }

    @PostMapping("/knowledge/search")
    public Result<List<Document>> search(@Valid @RequestBody KnowledgeQueryDTO request) {
        return Result.success(documents.search(UserContext.getUserId(),
                request.knowledgeBaseId(), request.question()));
    }

    @PostMapping("/knowledge/chat")
    public Result<RagAnswerVO> chat(@Valid @RequestBody KnowledgeQueryDTO request) {
        return Result.success(documents.chat(UserContext.getUserId(),
                request.knowledgeBaseId(), request.question()));
    }
}
