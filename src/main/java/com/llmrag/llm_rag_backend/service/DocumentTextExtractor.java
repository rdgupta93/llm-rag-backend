
package com.llmrag.llm_rag_backend.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

@Service
public class DocumentTextExtractor {

    public String extractText(Path filePath) throws IOException {

        String filename = filePath.getFileName()
                .toString()
                .toLowerCase(Locale.ROOT);

        if (filename.endsWith(".pdf")) {
            try (PDDocument document =
                         Loader.loadPDF(filePath.toFile())) {

                PDFTextStripper stripper = new PDFTextStripper();
                String extractedText = stripper.getText(document).trim();

                System.out.println("=== PDFBOX EXTRACTION CHECK ===");

                String[] terms = {
                        "1.50×", "1.59×", "1.36×", "1.23×",
                        "50×", "59×", "36×", "23×"
                };

                for (String term : terms) {
                    System.out.println(
                            term + " -> "
                                    + extractedText.contains(term)
                    );
                }

                return extractedText;
            }
        }

        if (filename.endsWith(".txt")) {
            return Files.readString(
                    filePath,
                    StandardCharsets.UTF_8
            ).trim();
        }

        throw new IllegalArgumentException(
                "Only PDF and TXT files are supported."
        );
    }
}
