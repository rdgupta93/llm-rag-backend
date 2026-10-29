
package com.llmrag.llm_rag_backend.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ClaimValidationService {

    private static final Pattern CITATION_PATTERN =
            Pattern.compile(
                    "\\[Source:\\s*([^,\\]]+),\\s*Chunk:\\s*(\\d+)\\]"
            );

    public Map<String, Object> validateCitations(
            String answer,
            List<Map<String, Object>> sources) {

        Set<String> allowedSources = new LinkedHashSet<>();

        for (Map<String, Object> source : sources) {
            String filename = String.valueOf(source.get("filename"));

            int chunkLabel = Integer.parseInt(
                    String.valueOf(source.get("chunk_index"))
            ) + 1;

            allowedSources.add(filename + "|"+ chunkLabel);
        }

        Matcher matcher = CITATION_PATTERN.matcher(answer);

        List<String> citedSources = new ArrayList<>();
        List<String> invalidCitations = new ArrayList<>();

        while (matcher.find()) {
            String filename = matcher.group(1).trim();
            String chunkLabel = matcher.group(2);

            String citation = filename + "|" + chunkLabel;
            citedSources.add(citation);

            if (!allowedSources.contains(citation)) {
                invalidCitations.add(
                        "[Source: " + filename
                                + ", Chunk: " + chunkLabel + "]"
                );
            }
        }

        String status;

        if (citedSources.isEmpty()) {
            status = "MISSING";
        } else if (!invalidCitations.isEmpty()) {
            status = "INVALID_LABELS";
        } else {
            status = "VALID_LABELS";
        }

        return Map.of(
                "citationStatus", status,
                "citedSources", citedSources,
                "invalidCitations", invalidCitations
        );
    }
}
