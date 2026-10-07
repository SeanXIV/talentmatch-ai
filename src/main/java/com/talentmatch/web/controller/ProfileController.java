package com.talentmatch.web.controller;

import com.talentmatch.profile.ProfileService;
import com.talentmatch.profile.ResumeRepository;
import com.talentmatch.profile.ResumeService;
import com.talentmatch.web.dto.ProfileRequest;
import com.talentmatch.web.dto.ProfileResponse;
import com.talentmatch.web.dto.ResumeResponse;
import com.talentmatch.web.dto.ResumeSummaryResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The owner's master profile: upload a CV, review the AI draft, save the confirmed profile.
 * Every endpoint here returns personal data (PII) and there is no authentication yet; the server
 * binds to 127.0.0.1 by default (PRODUCTION_READINESS §6).
 */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ResumeService resumeService;
    private final ProfileService profileService;

    public ProfileController(ResumeService resumeService, ProfileService profileService) {
        this.resumeService = resumeService;
        this.profileService = profileService;
    }

    /** 202 when a new CV was queued for reading; 200 when the same file was already uploaded. */
    @PostMapping(path = "/resume", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ResumeResponse> upload(@RequestParam(name = "file", required = false) MultipartFile file) {
        ResumeService.UploadResult result = resumeService.upload(file);
        ResumeResponse body = ResumeResponse.of(result.resume());
        URI location = URI.create("/api/profile/resume/" + body.id());
        return ResponseEntity.status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .location(location).body(body);
    }

    /** Every uploaded CV, newest first (status and metadata; no draft). */
    @GetMapping("/resumes")
    public List<ResumeSummaryResponse> resumes() {
        return resumeService.list().stream().map(ResumeSummaryResponse::of).toList();
    }

    /** Deletes an uploaded CV (file, text and draft); 409 while it is being read. */
    @DeleteMapping("/resume/{id}")
    public ResponseEntity<Void> deleteResume(@PathVariable("id") UUID id) {
        resumeService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/resume/{id}")
    public ResumeResponse resume(@PathVariable("id") UUID id) {
        return ResumeResponse.of(resumeService.get(id));
    }

    /** The original PDF, as a download. */
    @GetMapping("/resume/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable("id") UUID id) {
        ResumeRepository.StoredFile f = resumeService.file(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(f.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(f.content());
    }

    /** Reads the CV again (after a failure, or to replace the draft). */
    @PostMapping("/resume/{id}/extract")
    public ResponseEntity<ResumeResponse> extract(@PathVariable("id") UUID id) {
        return ResponseEntity.accepted().body(ResumeResponse.of(resumeService.retry(id)));
    }

    @GetMapping
    public ProfileResponse get() {
        return profileService.get();
    }

    @PutMapping
    public ProfileResponse save(@RequestBody ProfileRequest request) {
        return profileService.save(request);
    }
}
