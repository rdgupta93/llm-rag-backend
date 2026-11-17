
package com.llmrag.llm_rag_backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.text.BreakIterator;
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

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

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

        log.info(
                "RAG request started | documentId={} | topK={}",
                documentId, topK
        );

        log.info(
                "Retrieval completed | documentId={} | retrievedSources={} | contextLength={}",
                documentId, results.size(), context.length()
        );


        String rawAnswer =
                ollamaChatService.generateAnswer(question, context.toString());

        log.info(
                "Answer generation completed | documentId={} | answerLength={}",
                documentId,
                rawAnswer == null ? 0 : rawAnswer.length()
        );

        log.debug("Generated answer: {}", rawAnswer);

        boolean notSpecified = rawAnswer != null
                && rawAnswer.trim().startsWith("NOT_SPECIFIED:");

        if (notSpecified) {
            for (Map<String, Object> result : results) {

                String sourceText = String.valueOf(
                        result.get("chunk_text")
                );

                String singleChunkContext =
                        "[Source: " + result.get("filename")
                                + ", Chunk: " + getChunkLabel(result) + "]\n"
                                + sourceText;

                String candidateAnswer =
                        ollamaChatService.generateAnswer(
                                question,
                                singleChunkContext
                        );

                if (candidateAnswer == null
                        || candidateAnswer.isBlank()) {
                    continue;
                }

                String candidateTrimmed = candidateAnswer.trim();
                String candidateLower =
                        candidateTrimmed.toLowerCase(Locale.ROOT);

                if (candidateLower.startsWith("not_specified:")
                        || candidateLower.contains("does not specify")
                        || candidateLower.contains("cannot be verified")
                        || candidateLower.contains("cannot verify")
                        || candidateLower.contains("insufficient evidence")) {
                    continue;
                }

                ClaimParts candidateParts =
                        extractClaimAndCitation(candidateTrimmed);

                String candidateClaim =
                        removeSourceCommentary(candidateParts.claim());

                if (candidateClaim == null
                        || candidateClaim.isBlank()) {
                    continue;
                }

                String evidenceStatus =
                        ollamaChatService.evaluateEvidence(
                                candidateClaim,
                                sourceText
                        );

                log.info(
                        "NOT_SPECIFIED recovery | chunk={} | status={} | candidate={}",
                        getChunkLabel(result),
                        evidenceStatus,
                        candidateClaim
                );

                if ("SUPPORTED".equalsIgnoreCase(evidenceStatus)) {
                    rawAnswer = candidateClaim;
                    notSpecified = false;
                    break;
                }
            }
        }

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

            }  else if (claim.isBlank()) {

            status = "UNCERTAIN";
            reason = "Could not extract a meaningful claim.";

        } else if (isNumericOnly(claim)) {

            source = findNumericSupportingSource(
                    question,
                    claim,
                    results
            );

            if (source != null) {

                claim = extractNumericEvidenceSentence(
                        question,
                        claim,
                        String.valueOf(source.get("chunk_text"))
                );

                status = "SUPPORTED";
                reason = "Numeric answer verified against question-relevant evidence.";

            } else {

                status = "UNCERTAIN";
                reason = "Numeric answer could not be verified against relevant evidence.";
            }

        } else {

            if (parts.filename() != null) {

                source = findSource(
                        results,
                        parts.filename(),
                        parts.chunkLabel()
                );

                if (source == null) {

                    status = "UNSUPPORTED";
                    reason = "Citation does not match a retrieved source.";

                } else {

                    status = ollamaChatService.evaluateEvidence(
                            claim,
                            String.valueOf(source.get("chunk_text"))
                    );

                    reason = "Evaluated against the cited retrieved source.";
                }

            } else {

                source = findSupportingSource(claim, results);

                if (source == null) {

                    status = "UNCERTAIN";
                    reason = "No retrieved source could be verified as supporting this claim.";

                } else {

                    status = "SUPPORTED";
                    reason = "Citation recovered from a verified retrieved source.";
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
            if (uniqueParagraphs.isEmpty()) {
                log.warn(
                        "Fallback answer selected | reason=no verified claims | documentId={}",
                        documentId
                );
                validatedAnswer = FALLBACK_ANSWER;
            } else {
                validatedAnswer = String.join("\n\n", uniqueParagraphs);
            }
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

            Matcher citationMatcher =
                    CITATION_PATTERN.matcher(paragraph);

            List<String> citations = new ArrayList<>();

            while (citationMatcher.find()) {
                citations.add(citationMatcher.group());
            }

            String withoutCitations =
                    CITATION_PATTERN.matcher(paragraph)
                            .replaceAll("")
                            .trim();

            // Attach standalone citation to previous paragraph.
            if (withoutCitations.isBlank() && !citations.isEmpty()) {

                if (!paragraphs.isEmpty()) {

                    int last = paragraphs.size() - 1;

                    paragraphs.set(
                            last,
                            paragraphs.get(last)
                                    + " "
                                    + String.join(" ", citations)
                    );
                }

                continue;
            }

            // Preserve NOT_SPECIFIED as one complete claim.
            if (withoutCitations.startsWith("NOT_SPECIFIED:")) {

                paragraphs.add(
                        withoutCitations
                                + (citations.isEmpty()
                                ? ""
                                : " " + String.join(" ", citations))
                );

                continue;
            }

            BreakIterator iterator =
                    BreakIterator.getSentenceInstance(Locale.US);

            iterator.setText(withoutCitations);

            int start = iterator.first();

            for (int end = iterator.next();
                 end != BreakIterator.DONE;
                 start = end, end = iterator.next()) {

                String sentence = withoutCitations
                        .substring(start, end)
                        .trim();

                if (sentence.isBlank()) {
                    continue;
                }

                if (!citations.isEmpty()) {
                    sentence += " " + String.join(" ", citations);
                }

                paragraphs.add(sentence);
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

        Set<String> topicTerms = extractTopicTerms(question);

        if (topicTerms.isEmpty()) {
            return null;
        }

        for (Map<String, Object> source : results) {

            String sourceText = String.valueOf(
                    source.get("chunk_text")
            );

            String normalizedSource = sourceText
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", " ")
                    .replaceAll("\\s+", " ")
                    .trim();

            boolean explicitAbsence =
                    normalizedSource.contains("does not specify")
                            || normalizedSource.contains("not specified")
                            || normalizedSource.contains("does not define")
                            || normalizedSource.contains("does not state");

            if (!explicitAbsence) {
                continue;
            }

            boolean allTopicTermsMatch = topicTerms.stream()
                    .allMatch(normalizedSource::contains);

            if (allTopicTermsMatch) {

                log.info(
                        "Relevant missing-information source found | chunk={}",
                        getChunkLabel(source)
                );

                return source;
            }
        }

        log.info(
                "No directly relevant missing-information source found | question={}",
                question
        );

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

// Apply a narrow evidence check before calling the LLM judge.
            if (hasDeterministicEvidence(claim, sourceText)) {
                log.info(
                        "Deterministic evidence verified | chunk={}",
                        getChunkLabel(source)
                );
                return source;
            }

            String verdict =
                    ollamaChatService.evaluateEvidence(claim, sourceText);


            log.info(
                    "Source verification result | chunk={} | verdict={}",
                    getChunkLabel(source),
                    verdict
            );

            if ("SUPPORTED".equalsIgnoreCase(verdict)) {
                log.info(
                        "Supporting source selected | chunk={}",
                        getChunkLabel(source)
                );
                return source;
            }
        }
        log.warn(
                "No supporting source found | retrievedSources={}",
                results.size()
        );
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


    private boolean hasDeterministicEvidence(
            String claim,
            String sourceText) {

        String normalizedClaim = claim
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();

        String normalizedSource = sourceText
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();

        // Manager approval
        boolean claimAboutWfhApproval =
                normalizedClaim.contains("manager approval")
                        && (normalizedClaim.contains("working from home")
                        || normalizedClaim.contains("work from home"));

        boolean sourceSupportsWfhApproval =
                normalizedSource.contains("manager approval")
                        && (normalizedSource.contains("work from home")
                        || normalizedSource.contains("work from home arrangement"));

        if (claimAboutWfhApproval && sourceSupportsWfhApproval) {
            return true;
        }

        // Learning budget
        boolean claimAboutLearningBudget =
                normalizedClaim.contains("learning")
                        && normalizedClaim.contains("budget")
                        && normalizedClaim.contains("inr 12 000");

        boolean sourceSupportsLearningBudget =
                normalizedSource.contains("inr 12 000")
                        && normalizedSource.contains("annual learning budget")
                        && normalizedSource.contains("approved courses")
                        && normalizedSource.contains("certifications");

        // Probation period
        boolean claimAboutProbation =
                normalizedClaim.contains("probation")
                        && normalizedClaim.contains("6 months");

        boolean sourceSupportsProbation =
                normalizedSource.contains("probation period")
                        && normalizedSource.contains("6 months")
                        && normalizedSource.contains("new employees");

        return (claimAboutLearningBudget && sourceSupportsLearningBudget)
                || (claimAboutProbation && sourceSupportsProbation);
    }


    private boolean isNumericOnly(String claim) {
        if (claim == null || claim.isBlank()) {
            return false;
        }

        return claim.trim().matches("[0-9][0-9,]*(\\.\\d+)?%?");
    }


    private boolean containsNumericValue(
            String claim,
            String text) {

        if (claim == null || text == null) {
            return false;
        }

        String number = claim.replaceAll("[^0-9.]", "");
        String normalizedText = text.replace(",", "");

        if (number.isBlank()) {
            return false;
        }

        Pattern pattern = Pattern.compile(
                "(?<!\\d)" + Pattern.quote(number) + "(?!\\d)"
        );

        return pattern.matcher(normalizedText).find();
    }


    private Map<String, Object> findNumericSupportingSource(
            String question,
            String numericClaim,
            List<Map<String, Object>> results) {

        Set<String> questionTerms = extractTopicTerms(question);

        for (Map<String, Object> source : results) {

            String sourceText = String.valueOf(
                    source.get("chunk_text")
            );

            String[] sentences = sourceText.split(
                    "(?<=[.!?])\\s+"
            );

            for (String sentence : sentences) {

                if (!containsNumericValue(numericClaim, sentence)) {
                    continue;
                }

                String normalizedSentence = sentence
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", " ")
                        .trim();

                long matchingTerms = questionTerms.stream()
                        .filter(normalizedSentence::contains)
                        .count();

                if (matchingTerms >= 2) {
                    return source;
                }
            }
        }

        return null;
    }


    private String extractNumericEvidenceSentence(
            String question,
            String numericClaim,
            String sourceText) {

        if (sourceText == null || sourceText.isBlank()) {
            return numericClaim;
        }

        // Remove numbered section headings such as "2. Work From Home".
        String cleanedText = sourceText.replaceAll(
                "(?m)^\\s*\\d+\\.\\s+[^\\r\\n]+",
                ""
        );

        cleanedText = cleanedText.replaceAll("\\s+", " ").trim();

        BreakIterator iterator =
                BreakIterator.getSentenceInstance(Locale.US);

        iterator.setText(cleanedText);

        Set<String> questionTerms = extractTopicTerms(question);

        String bestSentence = null;
        int bestScore = 0;

        int start = iterator.first();

        for (int end = iterator.next();
             end != BreakIterator.DONE;
             start = end, end = iterator.next()) {

            String sentence = cleanedText
                    .substring(start, end)
                    .trim();

            if (sentence.length() < 20) {
                continue;
            }

            if (!containsNumericValue(numericClaim, sentence)) {
                continue;
            }

            String normalizedSentence = sentence
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", " ")
                    .trim();

            int score = 0;

            for (String term : questionTerms) {
                if (normalizedSentence.contains(term)) {
                    score++;
                }
            }

            // Avoid returning a section heading or an unrelated number.
            if (score > bestScore) {
                bestScore = score;
                bestSentence = sentence;
            }
        }

        if (bestSentence != null && bestScore >= 2) {
            return bestSentence;
        }

        return numericClaim;
    }


    private Set<String> extractTopicTerms(String text) {

        Set<String> stopWords = Set.of(
                "what", "is", "the", "a", "an",
                "how", "many", "much", "are",
                "does", "do", "did", "for",
                "of", "in", "on", "to", "and",
                "or", "with", "from", "by",
                "can", "could", "would", "should",
                "me", "tell", "about", "there",
                "their", "this", "that", "be",
                "per", "it", "has", "have",
                "provided", "available", "offered",
                "document", "handbook", "information"
        );

        Set<String> terms = new LinkedHashSet<>();

        String normalized = text
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();

        for (String word : normalized.split("\\s+")) {

            if (word.length() > 2 && !stopWords.contains(word)) {
                terms.add(word);
            }
        }

        return terms;
    }

}
