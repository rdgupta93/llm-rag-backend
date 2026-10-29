
package com.llmrag.llm_rag_backend.controller;
import com.llmrag.llm_rag_backend.service.DocumentTextExtractor;
import com.llmrag.llm_rag_backend.model.Document;
import com.llmrag.llm_rag_backend.repository.DocumentRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.llmrag.llm_rag_backend.service.DocumentProcessingService;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.stream.Collectors;
import com.llmrag.llm_rag_backend.service.RagService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.llmrag.llm_rag_backend.service.SemanticSearchService;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    @Value("${app.upload.dir}")
    private String uploadDir;

    private final DocumentRepository documentRepository;
    private final DocumentTextExtractor documentTextExtractor;
    private final DocumentProcessingService documentProcessingService;
    private final SemanticSearchService semanticSearchService;
    private final RagService ragService;
    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("pdf", "txt");

    public DocumentController(DocumentRepository documentRepository, DocumentTextExtractor documentTextExtractor,
                              DocumentProcessingService documentProcessingService,
                              SemanticSearchService semanticSearchService,
                              RagService ragService) {
        this.documentRepository = documentRepository;
        this.documentTextExtractor = documentTextExtractor;
        this.documentProcessingService = documentProcessingService;
        this.semanticSearchService = semanticSearchService;
        this.ragService = ragService;
    }

    @PostMapping(
            value = "/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<Map<String, Object>> uploadDocument(
            @RequestParam("file") MultipartFile file) {

        Path destination = null;

        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(
                        Map.of("message", "Please select a file")
                );
            }

            String originalFilename = file.getOriginalFilename();

            if (originalFilename == null
                    || originalFilename.isBlank()
                    || !originalFilename.contains(".")) {
                return ResponseEntity.badRequest().body(
                        Map.of("message", "Invalid filename")
                );
            }

            String extension = originalFilename
                    .substring(originalFilename.lastIndexOf('.') + 1)
                    .toLowerCase();

            if (!ALLOWED_EXTENSIONS.contains(extension)) {
                return ResponseEntity.badRequest().body(
                        Map.of(
                                "message",
                                "Only PDF and TXT files are allowed"
                        )
                );
            }

            // Calculate SHA-256 from the actual file content.
            byte[] fileBytes = file.getBytes();
            String contentHash = calculateSha256(fileBytes);

            // Check PostgreSQL before saving the file.
            if (documentRepository.existsByContentHash(contentHash)) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(
                        Map.of(
                                "message",
                                "Duplicate document! This file has already been uploaded."
                        )
                );
            }

            Path uploadPath = Paths.get(uploadDir)
                    .toAbsolutePath()
                    .normalize();

            Files.createDirectories(uploadPath);

            String storedFilename = UUID.randomUUID() + "." + extension;
            destination = uploadPath.resolve(storedFilename).normalize();

            if (!destination.startsWith(uploadPath)) {
                return ResponseEntity.badRequest().body(
                        Map.of("message", "Invalid file path")
                );
            }

            // Save the file locally.
            Files.copy(
                    new java.io.ByteArrayInputStream(fileBytes),
                    destination,
                    StandardCopyOption.REPLACE_EXISTING
            );

            // Save metadata in PostgreSQL.
            Document document = new Document();
            document.setOriginalFilename(originalFilename);
            document.setStoredFilename(storedFilename);
            document.setFileSize(file.getSize());
            document.setUploadTimestamp(LocalDateTime.now());
            document.setContentHash(contentHash);

            Document savedDocument = documentRepository.save(document);

            return ResponseEntity.status(HttpStatus.CREATED).body(
                    Map.of(
                            "message", "Document uploaded successfully!",
                            "documentId", savedDocument.getId(),
                            "filename", savedDocument.getOriginalFilename(),
                            "storedFilename", savedDocument.getStoredFilename(),
                            "sizeBytes", savedDocument.getFileSize(),
                            "uploadTimestamp",
                            savedDocument.getUploadTimestamp().toString()
                    )
            );

        } catch (DataIntegrityViolationException exception) {
            deleteFileQuietly(destination);

            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    Map.of(
                            "message",
                            "Duplicate document! This file may already exist."
                    )
            );

        } catch (IOException | NoSuchAlgorithmException exception) {
            deleteFileQuietly(destination);
            exception.printStackTrace();

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    Map.of("message", "Unable to process the uploaded document.")
            );
        }
    }

    private String calculateSha256(byte[] fileBytes)
            throws NoSuchAlgorithmException {

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(fileBytes);

        return HexFormat.of().formatHex(hashBytes);
    }

    private void deleteFileQuietly(Path path) {
        if (path == null) {
            return;
        }

        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            exception.printStackTrace();
        }
    }


    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getAllDocuments() {

        List<Map<String, Object>> documents =
                documentRepository.findAllByOrderByUploadTimestampDesc()
                        .stream()
                        .map(document -> Map.<String, Object>of(
                                "id", document.getId(),
                                "filename", document.getOriginalFilename(),
                                "storedFilename", document.getStoredFilename(),
                                "sizeBytes", document.getFileSize(),
                                "uploadTimestamp",
                                document.getUploadTimestamp().toString()
                        ))
                        .collect(Collectors.toList());

        return ResponseEntity.ok(documents);
    }

    @GetMapping("/{id}/extract-text")
    public ResponseEntity<Map<String, Object>> extractDocumentText(
            @PathVariable Long id) {

        Document document = documentRepository.findById(id).orElse(null);

        if (document == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    Map.of("message", "Document not found.")
            );
        }

        try {
            Path filePath = Paths.get(uploadDir)
                    .toAbsolutePath()
                    .normalize()
                    .resolve(document.getStoredFilename())
                    .normalize();

            if (!filePath.startsWith(
                    Paths.get(uploadDir).toAbsolutePath().normalize())) {
                return ResponseEntity.badRequest().body(
                        Map.of("message", "Invalid document path.")
                );
            }

            if (!Files.exists(filePath)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        Map.of("message", "Document file not found on disk.")
                );
            }

            String extractedText = documentTextExtractor.extractText(filePath);

            return ResponseEntity.ok(
                    Map.of(
                            "documentId", document.getId(),
                            "filename", document.getOriginalFilename(),
                            "textLength", extractedText.length(),
                            "extractedText", extractedText
                    )
            );

        } catch (IOException exception) {
            exception.printStackTrace();

            return ResponseEntity.status(
                    HttpStatus.INTERNAL_SERVER_ERROR
            ).body(
                    Map.of("message", "Unable to extract document text.")
            );
        }
    }




    @PostMapping("/{id}/process")
    public ResponseEntity<Map<String, Object>> processDocument(
            @PathVariable Long id) {

        try {
            int totalChunks = documentProcessingService.processDocument(id);

            return ResponseEntity.ok(
                    Map.of(
                            "message", "Document processed successfully!",
                            "documentId", id,
                            "totalChunks", totalChunks
                    )
            );

        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    Map.of("message", exception.getMessage())
            );

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            return ResponseEntity.status(
                    HttpStatus.INTERNAL_SERVER_ERROR
            ).body(
                    Map.of("message", "Document processing was interrupted.")
            );

        } catch (IOException exception) {
            return ResponseEntity.status(
                    HttpStatus.INTERNAL_SERVER_ERROR
            ).body(
                    Map.of("message", "Unable to process document.")
            );
        }
    }

    public record SearchRequest(
            String question,
            Long documentId,
            Integer topK) {
    }

    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> searchDocuments(
            @RequestBody SearchRequest request) {

        try {
            if (request.question() == null
                    || request.question().isBlank()) {
                return ResponseEntity.badRequest().body(
                        Map.of("message", "Question must not be empty."));
            }

            int topK = request.topK() == null ? 5 : request.topK();

            List<Map<String, Object>> results =
                    semanticSearchService.search(
                            request.question(),
                            request.documentId(),
                            topK);

            return ResponseEntity.ok(Map.of(
                    "question", request.question(),
                    "totalResults", results.size(),
                    "results", results
            ));

        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(
                    Map.of("message", exception.getMessage()));

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(
                    HttpStatus.INTERNAL_SERVER_ERROR).body(
                    Map.of("message", "Search was interrupted."));

        } catch (IOException exception) {
            return ResponseEntity.status(
                    HttpStatus.INTERNAL_SERVER_ERROR).body(
                    Map.of("message", "Unable to search documents."));
        }
    }


    public record RagRequest(
            String question,
            Long documentId,
            Integer topK) {
    }

    @PostMapping("/ask")
    public ResponseEntity<Map<String, Object>> askQuestion(
            @RequestBody RagRequest request) {

        try {
            if (request.question() == null
                    || request.question().isBlank()) {
                return ResponseEntity.badRequest().body(
                        Map.of("message", "Question must not be empty."));
            }

            int topK = request.topK() == null ? 5 : request.topK();

            Map<String, Object> result = ragService.askQuestion(
                    request.question(),
                    request.documentId(),
                    topK);

            return ResponseEntity.ok(result);

        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(
                    Map.of("message", exception.getMessage()));

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(
                    HttpStatus.INTERNAL_SERVER_ERROR).body(
                    Map.of("message", "Question processing was interrupted."));

        } catch (IOException exception) {
            return ResponseEntity.status(
                    HttpStatus.INTERNAL_SERVER_ERROR).body(
                    Map.of("message", "Unable to generate the answer."));
        }
    }
}
