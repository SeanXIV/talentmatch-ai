package com.talentmatch.profile;

import com.talentmatch.service.FieldErrors;
import com.talentmatch.service.exception.ApiException;
import com.talentmatch.service.exception.ConflictException;
import com.talentmatch.service.exception.NotFoundException;
import com.talentmatch.web.error.ErrorCode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/** CV upload, status, listing, download, deletion and re-extraction. */
@Service
public class ResumeService {

    public static final String PDF = "application/pdf";

    /** Control and format characters (bidi overrides such as U+202E can disguise "cv.exe"), quotes, slashes. */
    private static final Pattern UNSAFE_NAME = Pattern.compile("[\\p{Cntrl}\\p{Cf}\"\\\\/]");

    private final ResumeRepository repository;
    private final ResumeTextExtractor textExtractor;
    private final ResumeExtractionService extraction;
    private final ProfileProperties properties;
    private final TransactionTemplate tx;

    public ResumeService(ResumeRepository repository, ResumeTextExtractor textExtractor,
                         ResumeExtractionService extraction, ProfileProperties properties,
                         PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.textExtractor = textExtractor;
        this.extraction = extraction;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Result of an upload: the CV, and whether it is new (false = the same file was already uploaded). */
    public record UploadResult(ResumeRecord resume, boolean created) {
    }

    public UploadResult upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            new FieldErrors().add("file", "Choose a PDF file to upload (form field 'file').").throwIfAny();
        }
        if (file.getSize() > properties.maxResumeBytes()) {
            throw tooLarge(file.getSize());
        }
        byte[] bytes = read(file);
        if (!ResumeTextExtractor.looksLikePdf(bytes)) {
            throw new ApiException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Only PDF CVs are supported, and this file is not a PDF. Export your CV as a PDF and upload that.");
        }
        String sha = sha256(bytes);
        var reusable = repository.findReusable(sha);
        if (reusable.isPresent()) {
            return new UploadResult(reusable.get(), false);
        }
        ResumeTextExtractor.ExtractedPdf pdf;
        try {
            pdf = textExtractor.extract(bytes);
        } catch (ResumeTextExtractor.UnreadableResumeException e) {
            throw new ApiException(ErrorCode.RESUME_UNREADABLE, HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (ResumeTextExtractor.ParserBusyException e) {
            throw new ApiException(ErrorCode.UPLOAD_BUSY, HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
        String name = fileName(file.getOriginalFilename());
        // Same file uploaded twice at once (double click): the advisory lock lets one insert, the
        // other then finds that row (N1).
        UploadResult result = tx.execute(status -> {
            repository.lockSha(sha);
            var existing = repository.findReusable(sha);
            if (existing.isPresent()) {
                return new UploadResult(existing.get(), false);
            }
            UUID id = repository.insert(name, PDF, bytes, sha, pdf.pageCount(), pdf.text());
            return new UploadResult(get(id), true);
        });
        if (result.created()) {
            extraction.enqueue(result.resume().id()); // after commit, so the worker sees the row
        }
        return result;
    }

    /** Every uploaded CV, newest first (metadata and status only). */
    public List<ResumeSummary> list() {
        return repository.list();
    }

    /**
     * Deletes an uploaded CV and its draft. A confirmed profile is kept (its {@code resumeId}
     * becomes null). Refused while the model is reading it.
     */
    public void delete(UUID id) {
        if (repository.deleteUnlessRunning(id)) {
            return;
        }
        ResumeRecord current = get(id); // 404 when it does not exist
        throw new ConflictException(ErrorCode.RESUME_EXTRACTION_IN_PROGRESS, "This CV is being read right now ("
                + current.status() + "). Wait for it to finish, then delete it.");
    }

    public ResumeRecord get(UUID id) {
        return repository.find(id).orElseThrow(() -> NotFoundException.resume(id));
    }

    public ResumeRepository.StoredFile file(UUID id) {
        return repository.findFile(id).orElseThrow(() -> NotFoundException.resume(id));
    }

    /** Re-runs extraction for a FAILED or SUCCEEDED CV (the old draft is discarded). */
    public ResumeRecord retry(UUID id) {
        ResumeRecord current = get(id);
        if (!repository.requeue(id)) {
            throw new ConflictException(ErrorCode.RESUME_EXTRACTION_IN_PROGRESS, "This CV is already being read ("
                    + current.status() + "). Wait for it to finish, then check GET /api/profile/resume/" + id + ".");
        }
        extraction.enqueue(id);
        return get(id);
    }

    private ApiException tooLarge(long size) {
        return new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, HttpStatus.PAYLOAD_TOO_LARGE,
                "This file is " + megabytes(size) + "; a CV can be at most " + megabytes(properties.maxResumeBytes())
                        + ". Export it again with smaller images, or remove pages that aren't your CV.");
    }

    static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded file", e);
        }
    }

    /** Last path segment, unsafe characters removed, at most 255 characters; default {@code cv.pdf}. */
    static String fileName(String original) {
        String name = original == null ? "" : original;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = UNSAFE_NAME.matcher(name.substring(slash + 1)).replaceAll("").strip();
        if (name.isEmpty()) {
            return "cv.pdf";
        }
        if (name.length() <= 255) {
            return name;
        }
        int start = name.length() - 255;
        if (Character.isLowSurrogate(name.charAt(start))) {
            start++; // never keep half of a surrogate pair
        }
        return name.substring(start);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
