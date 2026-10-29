
package com.llmrag.llm_rag_backend.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SemanticSearchService {

    private final OllamaEmbeddingService ollamaEmbeddingService;
    private final JdbcTemplate jdbcTemplate;

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "is", "are", "was", "were",
            "what", "which", "who", "whom", "where", "when",
            "why", "how", "do", "does", "did", "can", "could",
            "would", "should", "of", "in", "on", "at", "to",
            "for", "from", "by", "with", "and", "or", "this",
            "that", "these", "those", "it", "its", "their",
            "my", "your", "me", "please", "explain", "tell",
            "give", "show", "paper", "document"
    );

    public SemanticSearchService(
            OllamaEmbeddingService ollamaEmbeddingService,
            JdbcTemplate jdbcTemplate) {
        this.ollamaEmbeddingService = ollamaEmbeddingService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> search(
            String question,
            Long documentId,
            int topK) throws IOException, InterruptedException {

        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException(
                    "Question must not be empty.");
        }

        if (topK < 1 || topK > 20) {
            throw new IllegalArgumentException(
                    "topK must be between 1 and 20.");
        }

        // 1. Generate the question embedding.
        List<Double> embedding =
                ollamaEmbeddingService.generateEmbedding(question);

        if (embedding == null || embedding.size() != 768) {
            throw new IllegalStateException(
                    "Expected a 768-dimensional question embedding.");
        }

        String vectorValue = embedding.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));

        // 2. Retrieve more candidates than the requested final result count.
        int candidateLimit = Math.min(
                100,
                Math.max(20, topK * 4));

        String sql = """
                SELECT
                    dc.id AS chunk_id,
                    dc.document_id,
                    d.original_filename AS filename,
                    dc.chunk_index,
                    dc.chunk_text,
                    (1 - (dc.embedding <=> CAST(? AS vector)))
                        AS similarity_score
                FROM document_chunks dc
                JOIN documents d ON d.id = dc.document_id
                WHERE dc.embedding IS NOT NULL
                  AND (? IS NULL OR dc.document_id = ?)
                ORDER BY dc.embedding <=> CAST(? AS vector)
                LIMIT ?
                """;

        List<Map<String, Object>> candidates =
                jdbcTemplate.queryForList(
                        sql,
                        vectorValue,
                        documentId,
                        documentId,
                        vectorValue,
                        candidateLimit);

        if (candidates.isEmpty()) {
            return candidates;
        }

        // 3. Extract meaningful question keywords.
        Set<String> questionTerms = extractTerms(question);

        // 4. Rerank candidates using semantic and lexical relevance.
        for (Map<String, Object> candidate : candidates) {
            String chunkText = String.valueOf(
                    candidate.getOrDefault("chunk_text", ""));

            double semanticScore = toDouble(
                    candidate.get("similarity_score"));

            double lexicalScore =
                    calculateLexicalScore(chunkText, questionTerms);

            double rerankScore =
                    (0.75 * semanticScore) + (0.25 * lexicalScore);

            String lowerText = chunkText.toLowerCase(Locale.ROOT);


            String normalizedText = lowerText.stripLeading();

            // Stronger penalty when a chunk appears to belong to
            // the References or Bibliography section.
            if (normalizedText.startsWith("references")
                    || normalizedText.startsWith("bibliography")
                    || lowerText.contains("\nreferences\n")
                    || lowerText.contains("\nbibliography\n")) {
                rerankScore -= 0.25;
            }

            // Smaller penalty for appendix content.
            if (lowerText.contains("appendix")) {
                rerankScore -= 0.08;
            }


            candidate.put("lexical_score", lexicalScore);
            candidate.put("rerank_score", rerankScore);

            System.out.printf(
                    Locale.ROOT,
                    "RAG RERANK | chunk=%s | similarity=%.3f"
                            + " | lexical=%.3f | rerank=%.3f"
                            + " | preview=%s%n",
                    candidate.get("chunk_index"),
                    semanticScore,
                    lexicalScore,
                    rerankScore,
                    chunkText.substring(0, Math.min(160, chunkText.length()))
                            .replaceAll("\\s+", " ")
            );

        }


        // 5. Return the highest reranked candidates.

        candidates.sort(
                Comparator.comparingDouble(
                        (Map<String, Object> row) ->
                                toDouble(row.get("rerank_score"))
                ).reversed()
        );


        // Return top-K candidates
        return new ArrayList<>(
                candidates.subList(
                        0,
                        Math.min(topK, candidates.size())
                )
        );

    }

    private Set<String> extractTerms(String text) {
        Set<String> terms = new HashSet<>();

        for (String word : text.toLowerCase(Locale.ROOT)
                .split("[^a-z0-9]+")) {

            if (word.length() >= 3 && !STOP_WORDS.contains(word)) {
                terms.add(word);
            }
        }

        return terms;
    }

    private double calculateLexicalScore(
            String chunkText,
            Set<String> questionTerms) {

        if (questionTerms.isEmpty()) {
            return 0.0;
        }

        Set<String> chunkTerms = extractTerms(chunkText);

        long matchedTerms = questionTerms.stream()
                .filter(chunkTerms::contains)
                .count();

        return (double) matchedTerms / questionTerms.size();
    }

    private double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }

        if (value == null) {
            return 0.0;
        }

        return Double.parseDouble(value.toString());
    }
}
