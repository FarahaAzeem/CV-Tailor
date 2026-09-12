package com.cvtailor.service;

import com.cvtailor.model.User;
import com.cvtailor.model.UserCv;
import com.cvtailor.repository.UserCvRepository;
import com.cvtailor.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class CvService {

    private static final Logger log = LoggerFactory.getLogger(CvService.class);

    private final UserCvRepository cvRepository;
    private final UserRepository userRepository;
    private final RestTemplate restTemplate;

    @Value("${gemini.api.key:your_gemini_api_key_here}")
    private String geminiApiKey;

    @Value("${gemini.api.url:https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent}")
    private String geminiApiUrl;

    public CvService(UserCvRepository cvRepository, UserRepository userRepository, RestTemplate restTemplate) {
        this.cvRepository = cvRepository;
        this.userRepository = userRepository;
        this.restTemplate = restTemplate;
    }

    public User getUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found with email: " + email));
    }

    public List<UserCv> getCvsForUser(User user) {
        return cvRepository.findByUser(user);
    }

    public Optional<UserCv> getCvByIdAndUser(Long id, User user) {
        return cvRepository.findByIdAndUser(id, user);
    }

    public UserCv createCvForUser(UserCv cv, User user) {
        log.info("Saving new CV for user '{}' target job: {}", user.getEmail(), cv.getTargetJobTitle());
        cv.setUser(user);
        return cvRepository.save(cv);
    }

    public UserCv tailorCvWithGeminiForUser(Long cvId, String jobDescription, User user) {
        UserCv cv = cvRepository.findByIdAndUser(cvId, user)
                .orElseThrow(() -> new RuntimeException("CV not found with id: " + cvId + " for user: " + user.getEmail()));

        log.info("Tailoring CV ID {} for user {} using Google Gemini API...", cvId, user.getEmail());

        String prompt = buildGeminiPrompt(cv.getRawContent(), jobDescription);
        String tailoredResult = callGeminiApi(prompt);

        cv.setTailoredContent(tailoredResult);
        if (jobDescription != null && !jobDescription.isBlank()) {
            cv.setTargetJobTitle(extractTargetTitleFromDescription(jobDescription));
        }

        return cvRepository.save(cv);
    }

    private String buildGeminiPrompt(String rawCvContent, String targetJobDescription) {
        return "You are an expert ATS (Applicant Tracking System) resume writer and senior technical recruiter.\n" +
                "Please rewrite and tailor the provided CV content to perfectly align with the target job description.\n\n" +
                "SECTION STRUCTURE INSTRUCTIONS:\n" +
                "Always structure the output CV using the following exact section order and clear uppercase section headings:\n" +
                "1. CONTACT INFORMATION (Name, email, phone, location, LinkedIn if present in raw CV)\n" +
                "2. PROFESSIONAL SUMMARY (Compelling summary tailored to target job)\n" +
                "3. EDUCATION (Degrees, institution, dates)\n" +
                "4. SKILLS (Core technical skills, competencies, and ATS keywords from job description)\n" +
                "5. PROJECTS (Key projects, tech stack, and achievements - skip if no project info exists in raw CV)\n" +
                "6. WORK EXPERIENCE (Positions, companies, dates, ATS-optimized bullet points with strong action verbs)\n" +
                "7. ACHIEVEMENTS (Certifications, awards, honors - skip if no achievement info exists in raw CV)\n\n" +
                "RULES:\n" +
                "- Use clear section headings in uppercase (e.g., 'CONTACT INFORMATION', 'PROFESSIONAL SUMMARY', 'EDUCATION', 'SKILLS', 'PROJECTS', 'WORK EXPERIENCE', 'ACHIEVEMENTS').\n" +
                "- Only include sections that have actual content from the original CV — skip empty sections rather than inventing fake data.\n" +
                "- Highlight relevant technical skills and keywords from the target job description.\n" +
                "- Use clean bullet points (- or •) for achievements and job duties.\n" +
                "- Maintain clean ATS-friendly formatting.\n\n" +
                "--- RAW CV CONTENT ---\n" +
                (rawCvContent != null ? rawCvContent : "N/A") + "\n\n" +
                "--- TARGET JOB DESCRIPTION ---\n" +
                (targetJobDescription != null ? targetJobDescription : "N/A") + "\n\n" +
                "--- TAILORED ATS-OPTIMIZED CV ---";
    }

    private String callGeminiApi(String prompt) {
        if (isPlaceholderApiKey(geminiApiKey)) {
            log.warn("Gemini API key is set to placeholder ('{}'). Returning simulated tailored CV.", geminiApiKey);
            return "CONTACT INFORMATION\n" +
                   "John Doe | john.doe@example.com | (555) 123-4567 | linkedin.com/in/johndoe\n\n" +
                   "PROFESSIONAL SUMMARY\n" +
                   "Highly qualified software engineer with extensive experience developing scale-ready backend applications and RESTful microservices tailored to target job requirements.\n\n" +
                   "EDUCATION\n" +
                   "B.S. in Computer Science - State University (2018 - 2022)\n\n" +
                   "SKILLS\n" +
                   "• Core Technical: Java 21, Spring Boot, PostgreSQL, REST APIs, Microservices, Docker, Git\n" +
                   "• Security & Cloud: JWT Security, Spring Security, AWS, CI/CD Pipelines\n\n" +
                   "PROJECTS\n" +
                   "• CV Tailor AI Platform: Developed an automated ATS resume tailoring platform using Spring Boot and Google Gemini AI.\n\n" +
                   "WORK EXPERIENCE\n" +
                   "Senior Software Engineer | Tech Solutions Inc. (2022 - Present)\n" +
                   "• Designed and deployed scalable REST APIs processing high-concurrency user requests.\n" +
                   "• Integrated secure JWT authentication and optimized database queries for performance.\n\n" +
                   "ACHIEVEMENTS\n" +
                   "• Certified Spring Professional & AWS Certified Solutions Architect\n\n" +
                   "(Note: Configure your real GEMINI_API_KEY in application.properties to enable live AI responses)";
        }

        try {
            String url = geminiApiUrl + "?key=" + geminiApiKey;

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> part = Map.of("text", prompt);
            Map<String, Object> content = Map.of("parts", List.of(part));
            Map<String, Object> requestBody = Map.of("contents", List.of(content));

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            JsonNode response = restTemplate.postForObject(url, entity, JsonNode.class);

            if (response != null && response.has("candidates") && response.path("candidates").size() > 0) {
                JsonNode partsNode = response.path("candidates").get(0).path("content").path("parts");
                if (partsNode.isArray() && partsNode.size() > 0) {
                    return partsNode.get(0).path("text").asText();
                }
            }

            log.error("Unexpected response structure from Gemini API: {}", response);
            throw new RuntimeException("Failed to parse Gemini API response");
        } catch (Exception e) {
            log.error("Error calling Gemini API: {}", e.getMessage(), e);
            throw new RuntimeException("Error communicating with Gemini API: " + e.getMessage(), e);
        }
    }

    private boolean isPlaceholderApiKey(String key) {
        return key == null || key.isBlank() || "your_gemini_api_key_here".equalsIgnoreCase(key.trim());
    }

    private String extractTargetTitleFromDescription(String jobDescription) {
        if (jobDescription.length() > 50) {
            return jobDescription.substring(0, 47) + "...";
        }
        return jobDescription;
    }

    public void deleteCvForUser(Long id, User user) {
        UserCv cv = cvRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new RuntimeException("CV not found with id: " + id));
        cvRepository.delete(cv);
    }
}
