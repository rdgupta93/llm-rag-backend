
package com.llmrag.llm_rag_backend.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RagService {

    private final SemanticSearchService semanticSearchService;
    private final OllamaChatService ollamaChatService;
    private final ClaimValidationService claimValidationService;

    private static final Pattern CITATION_PATTERN =
            Pattern.compile(
                    "\\[Source:\\s*([^,\\]]+),\\s*Chunk:\\s*(\\d+)\\]"
            );

    private static final Pattern CHUNK_PATTERN =
            Pattern.compile("(?i)\\bChunk\\s*[:#]?\\s*(\\d+)\\b");

    private static final String FALLBACK_ANSWER =
            "The retrieved evidence could not be verified sufficiently "
                    + "to provide a reliable answer.";

    public RagService(
            SemanticSearchService semanticSearchService,
            OllamaChatService ollamaChatService,
            ClaimValidationService claimValidationService) {

        this.semanticSearchService = semanticSearchService;
        this.ollamaChatService = ollamaChatService;
        this.claimValidationService = claimValidationService;
    }

    public Map<String, Object> askQuestion(
            String question, Long documentId, int topK)
            throws IOException, InterruptedException {

        List<Map<String, Object>> results =
                semanticSearchService.search(question, documentId, topK);

        if (results == null || results.isEmpty()) {
            throw new IllegalArgumentException(
                    "No processed document chunks found. "
                            + "Please process a document before asking questions."
            );
        }

        StringBuilder context = new StringBuilder();
        Set<Integer> allowedChunkLabels = new LinkedHashSet<>();

        for (Map<String, Object> source : results) {
            String filename = String.valueOf(source.get("filename"));
            int chunkLabel = getChunkLabel(source);

            allowedChunkLabels.add(chunkLabel);

            context.append("\n[Source: ")
                    .append(filename)
                    .append(", Chunk: ")
                    .append(chunkLabel)
                    .append("]\n")
                    .append(String.valueOf(source.get("chunk_text")))
                    .append("\n");
        }

        System.out.println("========== RAG QUESTION START ==========");
        System.out.println("Question: " + question);
        System.out.println("Document ID: " + documentId);
        System.out.println("Top K: " + topK);
        System.out.println("Retrieved sources: " + results.size());
        System.out.println("Context length: " + context.length());


        String rawAnswer =
                ollamaChatService.generateAnswer(question, context.toString());

        boolean notSpecified = rawAnswer != null
                && rawAnswer.trim().startsWith("NOT_SPECIFIED:");

        if (notSpecified) {
            rawAnswer = rawAnswer.trim()
                    .substring("NOT_SPECIFIED:".length())
                    .trim();

            if (rawAnswer.isBlank()) {
                rawAnswer =
                        "The retrieved evidence does not specify "
                                + "the requested information.";
            }
        }

        System.out.println("========== RAW GENERATED ANSWER ==========");
        System.out.println(rawAnswer);
        System.out.println("==========================================");

        List<String> paragraphs = normalizeParagraphs(rawAnswer);


        List<Map<String, Object>> evidenceChecks = new ArrayList<>();
        List<Map<String, Object>> paragraphChecks = new ArrayList<>();
        List<String> acceptedParagraphs = new ArrayList<>();

        for (int i = 0; i < paragraphs.size(); i++) {
            String paragraph = paragraphs.get(i);

            Map<String, Object> check = new LinkedHashMap<>();
            check.put("paragraphIndex", i);
            check.put("originalParagraph", paragraph);

            ClaimParts parts = extractClaimAndCitation(paragraph);

            String claim = removeSourceCommentary(parts.claim());
            Map<String, Object> source = null;
            String citation = null;
            String status;
            String reason;


            if (notSpecified) {
                source = findExplicitMissingSource(question, results);

                status = "NOT_SPECIFIED";
                reason = source != null
                        ? "Missing information verified against a retrieved source."
                        : "Missing information could not be verified against a specific source.";

            } else if (claim.isBlank() || claim.length() < 5) {
                status = "UNCERTAIN";
                reason = "Could not extract a meaningful claim.";

            } else {
                if (parts.filename() != null) {
                    source = findSource(
                            results, parts.filename(), parts.chunkLabel()
                    );

                    if (source == null) {
                        status = "UNSUPPORTED";
                        reason =
                                "Citation does not match a retrieved source.";
                    } else {
                        status = ollamaChatService.evaluateEvidence(
                                claim,
                                String.valueOf(source.get("chunk_text"))
                        );
                        reason =
                                "Evaluated against the cited retrieved source.";
                    }
                } else {
                    // Citation recovery is allowed only after evidence
                    // is verified against an actual retrieved passage.
                    source = findSupportingSource(claim, results);

                    if (source == null) {
                        status = "UNCERTAIN";
                        reason =
                                "No retrieved source could be verified as supporting this claim.";
                    } else {
                        status = "SUPPORTED";
                        reason =
                                "Citation recovered from a verified retrieved source.";
                    }
                }
            }


            if (("SUPPORTED".equalsIgnoreCase(status)
                    || "NOT_SPECIFIED".equalsIgnoreCase(status))
                    && source != null) {

                citation = buildCitation(source);

                String normalizedParagraph = claim + " " + citation;
                check.put("normalizedParagraph", normalizedParagraph);
                acceptedParagraphs.add(normalizedParagraph);
            }


            check.put("claim", claim);
            check.put("citation", citation);
            check.put("evidenceStatus", status);
            check.put("reason", reason);
            check.put(
                    "sourceChunk",
                    source == null ? null : getChunkLabel(source)
            );
            paragraphChecks.add(check);

            Map<String, Object> evidenceCheck = new LinkedHashMap<>();
            evidenceCheck.put("paragraphIndex", i);
            evidenceCheck.put("claim", claim);
            evidenceCheck.put("citation", citation);
            evidenceCheck.put("evidenceStatus", status);
            evidenceCheck.put("reason", reason);
            evidenceCheck.put(
                    "sourceChunk",
                    source == null ? null : getChunkLabel(source)
            );
            evidenceChecks.add(evidenceCheck);

            if (!"SUPPORTED".equalsIgnoreCase(status)) {
                System.out.println(
                        "FILTERED CLAIM: " + claim
                                + " | status=" + status
                                + " | reason=" + reason
                );
            }
        }

        // Remove exact duplicate claims while preserving their order.
        List<String> uniqueParagraphs = new ArrayList<>();
        Set<String> seenClaims = new LinkedHashSet<>();

        for (String paragraph : acceptedParagraphs) {
            String normalizedClaim = CITATION_PATTERN
                    .matcher(paragraph)
                    .replaceAll("")
                    .replaceAll("\\s+", " ")
                    .trim()
                    .toLowerCase(Locale.ROOT);

            if (seenClaims.add(normalizedClaim)) {
                uniqueParagraphs.add(paragraph);
            }
        }

        String validatedAnswer;


        if (notSpecified) {
            validatedAnswer = acceptedParagraphs.isEmpty()
                    ? "The retrieved evidence does not specify "
                    + "the requested information."
                    : String.join("\n\n", acceptedParagraphs);
        } else {
            validatedAnswer = uniqueParagraphs.isEmpty()
                    ? FALLBACK_ANSWER
                    : String.join("\n\n", uniqueParagraphs);
        }


        Map<String, Object> finalClaimValidation =
                claimValidationService.validateCitations(
                        validatedAnswer, results
                );

        Set<Integer> citedChunkLabels = new LinkedHashSet<>();
        List<String> invalidCitations = new ArrayList<>();

        Matcher matcher = CITATION_PATTERN.matcher(validatedAnswer);

        while (matcher.find()) {
            String filename = matcher.group(1).trim();
            int label = Integer.parseInt(matcher.group(2));

            citedChunkLabels.add(label);

            if (findSource(results, filename, label) == null) {
                invalidCitations.add(matcher.group());
            }
        }

        String citationStatus;

        if (citedChunkLabels.isEmpty()) {
            citationStatus = "MISSING";
        } else if (!invalidCitations.isEmpty()) {
            citationStatus = "INVALID_LABELS";
        } else {
            citationStatus = "VALID_LABELS";
        }

        String citationWarning;

        switch (citationStatus) {
            case "MISSING" ->
                    citationWarning =
                            "No recognizable source citations were found.";
            case "INVALID_LABELS" ->
                    citationWarning =
                            "One or more citations do not match retrieved sources.";
            default ->
                    citationWarning =
                            "Citation labels match retrieved sources. "
                                    + "Check evidenceChecks for claim-level verdicts.";
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("question", question);
        response.put("answer", validatedAnswer);
        response.put("documentId", documentId);
        response.put("totalSources", results.size());
        response.put("sources", results);
        response.put("claimValidation", finalClaimValidation);
        response.put("paragraphChecks", paragraphChecks);
        response.put("evidenceChecks", evidenceChecks);
        response.put("citationStatus", citationStatus);
        response.put("allowedChunkLabels", new ArrayList<>(allowedChunkLabels));
        response.put("citedChunkLabels", new ArrayList<>(citedChunkLabels));
        response.put("invalidCitations", invalidCitations);
        response.put("citationWarning", citationWarning);

        System.out.println("========== FINAL ANSWER ==========");
        System.out.println(validatedAnswer);
        System.out.println("Citation status: " + citationStatus);
        System.out.println("==================================");

        return response;
    }

    private List<String> normalizeParagraphs(String answer) {
        List<String> paragraphs = new ArrayList<>();

        if (answer == null || answer.isBlank()) {
            return paragraphs;
        }

        String[] blocks = answer.trim().split("\\R\\s*\\R+");

        for (String block : blocks) {
            String paragraph = block.trim();

            if (paragraph.isBlank()) {
                continue;
            }

            Matcher citationMatcher = CITATION_PATTERN.matcher(paragraph);
            String withoutCitations = citationMatcher.replaceAll("").trim();
            boolean citationOnly = withoutCitations.isBlank()
                    && CITATION_PATTERN.matcher(paragraph).find();

            if (citationOnly && !paragraphs.isEmpty()) {
                // Attach standalone citation block to the previous claim.
                // Its citation is then checked against retrieved sources.
                int last = paragraphs.size() - 1;
                paragraphs.set(last, paragraphs.get(last) + " " + paragraph);
            } else {
                paragraphs.add(paragraph);
            }
        }

        return paragraphs;
    }


    private ClaimParts extractClaimAndCitation(String paragraph) {
        Matcher matcher = CITATION_PATTERN.matcher(paragraph);

        int count = 0;
        String filename = null;
        int chunkLabel = -1;
        int start = -1;
        int end = -1;

        while (matcher.find()) {
            count++;
            filename = matcher.group(1).trim();
            chunkLabel = Integer.parseInt(matcher.group(2));
            start = matcher.start();
            end = matcher.end();
        }

        if (count == 0) {
            return new ClaimParts(paragraph.trim(), null, -1);
        }

        // Do not silently choose between multiple citations.
        if (count > 1) {
            return new ClaimParts("", null, -1);
        }

        // Accept a single citation at the beginning, middle, or end.
        String claim = (
                paragraph.substring(0, start)
                        + " "
                        + paragraph.substring(end)
        ).trim();

        // Remove explanatory wording surrounding the citation.
        claim = claim
                .replaceAll(
                        "(?i)\\b(?:supporting\\s+)?source\\s*:\\s*$",
                        ""
                )
                .replaceAll(
                        "(?i)^\\s*(?:supporting\\s+)?source\\s*:\\s*",
                        ""
                )
                .replaceAll("\\s+", " ")
                .trim();

        return new ClaimParts(claim, filename, chunkLabel);
    }



    private Map<String, Object> findExplicitMissingSource(
            String question,
            List<Map<String, Object>> results) {

        String normalizedQuestion = question
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();

        String[] topicTerms = normalizedQuestion.split("\\s+");

        for (Map<String, Object> source : results) {
            String sourceText = String.valueOf(source.get("chunk_text"))
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", " ")
                    .replaceAll("\\s+", " ")
                    .trim();

            boolean explicitAbsence =
                    sourceText.contains("does not specify")
                            || sourceText.contains("not specified")
                            || sourceText.contains("does not define")
                            || sourceText.contains("does not state");

            if (!explicitAbsence) {
                continue;
            }

            int matchingTerms = 0;

            for (String term : topicTerms) {
                if (term.length() > 2 && sourceText.contains(term)) {
                    matchingTerms++;
                }
            }

            if (matchingTerms >= 2) {
                return source;
            }
        }

        return null;
    }


    private Map<String, Object> findSupportingSource(
            String claim, List<Map<String, Object>> results)
            throws IOException, InterruptedException {

        String normalizedClaim = claim
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);

        // First: check for an exact claim in retrieved evidence.
        for (Map<String, Object> source : results) {
            String sourceText = String.valueOf(source.get("chunk_text"));

            String normalizedSource = sourceText
                    .replaceAll("\\s+", " ")
                    .trim()
                    .toLowerCase(Locale.ROOT);

            if (normalizedSource.contains(normalizedClaim)) {
                System.out.println(
                        "EVIDENCE MATCH: Exact claim found in chunk "
                                + getChunkLabel(source)
                );
                return source;
            }
        }

        // Otherwise, use the existing LLM judge for paraphrased claims.
        for (Map<String, Object> source : results) {
            String sourceText = String.valueOf(source.get("chunk_text"));

            String verdict =
                    ollamaChatService.evaluateEvidence(claim, sourceText);

            if ("SUPPORTED".equalsIgnoreCase(verdict)) {
                return source;
            }
        }

        return null;
    }


    private Map<String, Object> findSource(
            List<Map<String, Object>> results,
            String filename,
            int chunkLabel) {

        for (Map<String, Object> source : results) {
            String sourceFilename = String.valueOf(source.get("filename"));

            if (filename.equals(sourceFilename)
                    && chunkLabel == getChunkLabel(source)) {
                return source;
            }
        }

        return null;
    }

    private int getChunkLabel(Map<String, Object> source) {
        return Integer.parseInt(
                String.valueOf(source.get("chunk_index"))
        ) + 1;
    }

    private String buildCitation(Map<String, Object> source) {
        return "[Source: "
                + source.get("filename")
                + ", Chunk: " + getChunkLabel(source) + "]";
    }

    private record ClaimParts(
            String claim, String filename, int chunkLabel) {
    }

    private String removeSourceCommentary(String claim) {
        if (claim == null || claim.isBlank()) {
            return "";
        }

        return claim
                .replaceAll(
                        "(?i)\\s*This is stated in the .*",
                        ""
                )
                .replaceAll(
                        "(?i)\\s*According to the (?:source|document|handbook)[,:]?\\s*",
                        " "
                )
                .replaceAll("\\s+", " ")
                .trim();
    }
}
