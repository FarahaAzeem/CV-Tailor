package com.cvtailor.controller;

import com.cvtailor.dto.TailorRequest;
import com.cvtailor.model.User;
import com.cvtailor.model.UserCv;
import com.cvtailor.service.CvService;
import com.cvtailor.service.DocumentService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/cv")
public class CvController {

    private final CvService cvService;
    private final DocumentService documentService;

    public CvController(CvService cvService, DocumentService documentService) {
        this.cvService = cvService;
        this.documentService = documentService;
    }

    private User getAuthenticatedUser(Authentication authentication) {
        String email = authentication.getName();
        return cvService.getUserByEmail(email);
    }

    @GetMapping
    public ResponseEntity<List<UserCv>> getAllCvs(Authentication authentication) {
        User user = getAuthenticatedUser(authentication);
        return ResponseEntity.ok(cvService.getCvsForUser(user));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserCv> getCvById(@PathVariable Long id, Authentication authentication) {
        User user = getAuthenticatedUser(authentication);
        return cvService.getCvByIdAndUser(id, user)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<UserCv> createCv(@RequestBody UserCv cv, Authentication authentication) {
        User user = getAuthenticatedUser(authentication);
        UserCv created = cvService.createCvForUser(cv, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/{id}/tailor")
    public ResponseEntity<UserCv> tailorCv(
            @PathVariable Long id,
            @RequestBody TailorRequest request,
            Authentication authentication) {
        User user = getAuthenticatedUser(authentication);
        String jobDescription = request != null ? request.getJobDescription() : "";
        UserCv tailored = cvService.tailorCvWithGeminiForUser(id, jobDescription, user);
        return ResponseEntity.ok(tailored);
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadCvFile(@RequestParam("file") MultipartFile file) {
        try {
            String extractedText = documentService.extractText(file);
            return ResponseEntity.ok(Map.of(
                    "extractedText", extractedText,
                    "fileName", file.getOriginalFilename() != null ? file.getOriginalFilename() : "Uploaded File"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error parsing uploaded file: " + e.getMessage());
        }
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> downloadCv(
            @PathVariable Long id,
            @RequestParam(value = "format", defaultValue = "pdf") String format,
            Authentication authentication) {
        User user = getAuthenticatedUser(authentication);
        Optional<UserCv> optionalCv = cvService.getCvByIdAndUser(id, user);

        if (optionalCv.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        UserCv cv = optionalCv.get();
        String content = cv.getTailoredContent() != null && !cv.getTailoredContent().isBlank()
                ? cv.getTailoredContent()
                : cv.getRawContent();

        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        String safeTitle = cv.getTitle() != null ? cv.getTitle().replaceAll("[^a-zA-Z0-9_-]", "_") : "cv_" + id;

        try {
            if ("docx".equalsIgnoreCase(format)) {
                byte[] docxBytes = documentService.generateDocx(content, cv.getTitle());
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeTitle + ".docx\"")
                        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                        .body(docxBytes);
            } else {
                byte[] pdfBytes = documentService.generatePdf(content, cv.getTitle());
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeTitle + ".pdf\"")
                        .contentType(MediaType.APPLICATION_PDF)
                        .body(pdfBytes);
            }
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCv(@PathVariable Long id, Authentication authentication) {
        User user = getAuthenticatedUser(authentication);
        cvService.deleteCvForUser(id, user);
        return ResponseEntity.noContent().build();
    }
}

