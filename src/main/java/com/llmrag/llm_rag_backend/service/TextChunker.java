
package com.llmrag.llm_rag_backend.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TextChunker {

    private static final int CHUNK_SIZE = 800;
    private static final int CHUNK_OVERLAP = 100;

//    private static final Pattern SENTENCE_PATTERN =
//            Pattern.compile(
//                    "[^.!?]+(?:[.!?]+[\\\"')\\]]*)?(?:\\s+|$)"
//            );

    public List<String> splitText(String text) {

        List<String> chunks = new ArrayList<>();

        if (text == null || text.isBlank()) {
            return chunks;
        }

        String cleanedText = text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();


        System.out.println("=== TEXTCHUNKER INPUT CHECK ===");

        int abstractIndex = text.indexOf("Experimental");

        if (abstractIndex >= 0) {
            int end = Math.min(text.length(), abstractIndex + 350);
            System.out.println(text.substring(abstractIndex, end));
        }


        // Normalize excessive spaces while preserving paragraph boundaries.
        cleanedText = cleanedText
                .replaceAll("[\\t ]+", " ")
                .replaceAll("\\n{3,}", "\n\n");

        String[] paragraphs = cleanedText.split("\\n\\s*\\n+");

        List<String> sentences = new ArrayList<>();

        // Split paragraphs into sentences without breaking decimal numbers.
        for (String paragraph : paragraphs) {

            String trimmedParagraph = paragraph.trim();

            if (trimmedParagraph.isEmpty()) {
                continue;
            }

            String[] paragraphSentences = trimmedParagraph.split(
                    "(?<=[.!?])\\s+(?=[A-Z0-9\"'(])"
            );

            for (String sentence : paragraphSentences) {
                String cleanSentence = sentence.trim();

                if (!cleanSentence.isEmpty()) {
                    sentences.add(cleanSentence);

                    // Temporary diagnostic
                    if (cleanSentence.contains("speedups")) {
                        System.out.println("SENTENCE CHECK: " + cleanSentence);
                        System.out.println(
                                "Has 1.59×: " + cleanSentence.contains("1.59×")
                        );
                        System.out.println(
                                "Has 1.36×: " + cleanSentence.contains("1.36×")
                        );
                    }
                }
            }
        }



        StringBuilder currentChunk = new StringBuilder();

        for (String sentence : sentences) {

            // Handle a single sentence longer than the chunk limit.
            if (sentence.length() > CHUNK_SIZE) {

                addChunk(chunks, currentChunk.toString());
                currentChunk.setLength(0);

                splitLongSentence(sentence, chunks);
                continue;
            }

            int additionalLength = sentence.length();

            if (!currentChunk.isEmpty()) {
                additionalLength += 1;
            }

            if (currentChunk.length() + additionalLength
                    <= CHUNK_SIZE) {

                if (!currentChunk.isEmpty()) {
                    currentChunk.append(' ');
                }

                currentChunk.append(sentence);

            } else {

                addChunk(chunks, currentChunk.toString());

                String overlap = getOverlap(
                        currentChunk.toString(),
                        CHUNK_OVERLAP
                );

                currentChunk.setLength(0);

                if (!overlap.isEmpty()
                        && overlap.length() + sentence.length() + 1
                        <= CHUNK_SIZE) {
                    currentChunk.append(overlap).append(' ');
                }

                currentChunk.append(sentence);
            }
        }

        addChunk(chunks, currentChunk.toString());

        return chunks;
    }

    private void splitLongSentence(
            String sentence,
            List<String> chunks) {

        int start = 0;

        while (start < sentence.length()) {

            int end = Math.min(
                    start + CHUNK_SIZE,
                    sentence.length()
            );

            // Prefer a nearby word boundary.
            if (end < sentence.length()) {
                int space = sentence.lastIndexOf(' ', end);

                if (space > start) {
                    end = space;
                }
            }

            String chunk = sentence.substring(start, end).trim();

            addChunk(chunks, chunk);

            if (end == sentence.length()) {
                break;
            }

            int nextStart = Math.max(
                    start + 1,
                    end - CHUNK_OVERLAP
            );

            start = nextStart;
        }
    }

    private String getOverlap(String text, int overlapSize) {

        if (text == null || text.isBlank()) {
            return "";
        }

        if (text.length() <= overlapSize) {
            return text.trim();
        }

        int start = text.length() - overlapSize;

        // Prefer starting at a word boundary.
        int space = text.indexOf(' ', start);

        if (space >= 0 && space < text.length() - 1) {
            start = space + 1;
        }

        return text.substring(start).trim();
    }

    private void addChunk(
            List<String> chunks,
            String chunkText) {

        if (chunkText != null && !chunkText.isBlank()) {
            chunks.add(chunkText.trim());
        }
    }
}
