
package com.llmrag.llm_rag_backend.service;

import com.llmrag.llm_rag_backend.model.Document;
import java.util.Locale;
import com.llmrag.llm_rag_backend.model.DocumentChunk;
import com.llmrag.llm_rag_backend.repository.DocumentRepository;
import com.llmrag.llm_rag_backend.repository.DocumentChunkRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class DocumentProcessingService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final DocumentTextExtractor documentTextExtractor;
    private final TextChunker textChunker;
    private final OllamaEmbeddingService ollamaEmbeddingService;
    private final JdbcTemplate jdbcTemplate;

    @Value("${app.upload.dir}")
    private String uploadDir;

    public DocumentProcessingService(
            DocumentRepository documentRepository,
            DocumentChunkRepository documentChunkRepository,
            DocumentTextExtractor documentTextExtractor,
            TextChunker textChunker,
            OllamaEmbeddingService ollamaEmbeddingService,
            JdbcTemplate jdbcTemplate) {

        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.documentTextExtractor = documentTextExtractor;
        this.textChunker = textChunker;
        this.ollamaEmbeddingService = ollamaEmbeddingService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(rollbackFor = Exception.class)
    public int processDocument(Long documentId)
            throws IOException, InterruptedException {

        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Document not found: " + documentId));

        Path uploadPath = Paths.get(uploadDir)
                .toAbsolutePath()
                .normalize();

        Path filePath = uploadPath
                .resolve(document.getStoredFilename())
                .normalize();

        if (!filePath.startsWith(uploadPath)) {
            throw new IllegalArgumentException("Invalid document path.");
        }

        if (!Files.isRegularFile(filePath)) {
            throw new IOException("Document file not found.");
        }

        String extractedText = documentTextExtractor.extractText(filePath);


        System.out.println("=== ABSTRACT TEXT CHECK ===");

        int abstractIndex = extractedText.indexOf("Experimental");

        if (abstractIndex >= 0) {
            int end = Math.min(
                    extractedText.length(),
                    abstractIndex + 700
            );

            System.out.println(
                    extractedText.substring(abstractIndex, end)
            );
        } else {
            System.out.println("Experimental not found in extracted text.");
        }


        System.out.println("=== PDF EXTRACTION DIAGNOSTIC ===");

        String[] terms = {
                "50×", "59×", "36×", "23×",
                "1.50×", "1.59×", "1.36×", "1.23×",
                "1.50", "1.59", "1.36", "1.23"
        };

        for (String term : terms) {
            int index = extractedText.indexOf(term);

            System.out.println("\nTerm: " + term
                    + " | Found: " + (index >= 0));

            if (index >= 0) {
                int start = Math.max(0, index - 150);
                int end = Math.min(
                        extractedText.length(),
                        index + 250
                );

                System.out.println(
                        extractedText.substring(start, end)
                );
            }
        }


        // YAHAN NAYA EXACT WHOLE-NUMBER CHECK ADD KARO
        System.out.println("=== EXACT WHOLE-NUMBER CHECK ===");


        Pattern speedupPattern = Pattern.compile(
                "(?<![\\d.])(50|59|36|23)×"
        );

        Matcher speedupMatcher = speedupPattern.matcher(extractedText);



        while (speedupMatcher.find()) {
            int start = Math.max(0, speedupMatcher.start() - 100);
            int end = Math.min(
                    extractedText.length(),
                    speedupMatcher.end() + 150
            );

            System.out.println("Match: " + speedupMatcher.group());
            System.out.println(
                    extractedText.substring(start, end)
            );
        }



        // Exclude the References section for PDF documents.
        String textForChunking = extractedText;

        if (document.getOriginalFilename() != null
                && document.getOriginalFilename()
                .toLowerCase(Locale.ROOT)
                .endsWith(".pdf")) {

            Pattern referencesPattern = Pattern.compile(
                    "(?im)^\\s*References\\s*$"
            );

            Matcher matcher = referencesPattern.matcher(extractedText);

            if (matcher.find()) {
                textForChunking = extractedText
                        .substring(0, matcher.start())
                        .trim();

                System.out.println(
                        "References section excluded. Start index: "
                                + matcher.start()
                );
            } else {
                System.out.println(
                        "References heading not found. Keeping full document."
                );
            }
        }


        System.out.println("=== BEFORE CHUNKING CHECK ===");

        String[] checkTerms = {
                "1.50×", "1.59×", "1.36×", "1.23×"
        };

        for (String term : checkTerms) {
            System.out.println(
                    term + " -> " + textForChunking.contains(term)
            );
        }


        List<String> chunks = textChunker.splitText(textForChunking);


        System.out.println("=== CHUNK SPEEDUP DIAGNOSTIC ===");

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);

            if (chunk.contains("50×")
                    || chunk.contains("59×")
                    || chunk.contains("36×")
                    || chunk.contains("23×")) {

                System.out.println("Chunk index: " + i);
                System.out.println(chunk);
                System.out.println("-----------------------------");
            }
        }


        if (chunks.isEmpty()) {
            throw new IllegalArgumentException(
                    "No extractable text found in document.");
        }

        // Generate all embeddings before replacing existing chunks.
        List<List<Double>> embeddings = new ArrayList<>();

        for (String chunkText : chunks) {
            embeddings.add(
                    ollamaEmbeddingService.generateEmbedding(chunkText));
        }

        // Replace old chunks and their embeddings in one transaction.
        documentChunkRepository.deleteByDocumentId(documentId);
        documentChunkRepository.flush();

        List<DocumentChunk> documentChunks = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocumentId(documentId);
            chunk.setChunkIndex(i);
            chunk.setChunkText(chunks.get(i));
            chunk.setCreatedAt(LocalDateTime.now());
            documentChunks.add(chunk);
        }

        List<DocumentChunk> savedChunks =
                documentChunkRepository.saveAllAndFlush(documentChunks);

        String insertVectorSql = """
                UPDATE document_chunks
                SET embedding = CAST(? AS vector)
                WHERE id = ?
                """;


        for (int i = 0; i < savedChunks.size(); i++) {
            String vectorValue = embeddings.get(i).stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(",", "[", "]"));

            Long chunkId = savedChunks.get(i).getId();

            jdbcTemplate.update(
                    insertVectorSql,
                    ps -> {
                        ps.setString(1, vectorValue);
                        ps.setLong(2, chunkId);
                    });
        }


        return savedChunks.size();
    }
}
