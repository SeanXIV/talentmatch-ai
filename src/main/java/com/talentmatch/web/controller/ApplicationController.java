package com.talentmatch.web.controller;

import com.talentmatch.application.ApplicationService;
import com.talentmatch.application.ApplicationSort;
import com.talentmatch.application.ApplicationStatus;
import com.talentmatch.application.ApplicationSummaryView;
import com.talentmatch.application.ApplicationView;
import com.talentmatch.application.DocumentService;
import com.talentmatch.application.DocumentSummaryView;
import com.talentmatch.application.DocumentView;
import com.talentmatch.application.GenerationService;
import com.talentmatch.application.GenerationView;
import com.talentmatch.application.content.DocumentKind;
import com.talentmatch.application.render.ExportFormat;
import com.talentmatch.application.render.RenderedFile;
import com.talentmatch.service.Paging;
import com.talentmatch.service.exception.InvalidParameterException;
import com.talentmatch.web.dto.ApplicationCreateRequest;
import com.talentmatch.web.dto.ApplicationUpdateRequest;
import com.talentmatch.web.dto.ApproveRequest;
import com.talentmatch.web.dto.DocumentEditRequest;
import com.talentmatch.web.dto.GenerationRequest;
import com.talentmatch.web.dto.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The application tracker, document generation and document versions (Phase 7 §5.2, §5.4, §5.5). PII:
 * notes and tailored documents; no auth, loopback only, like {@code /api/profile}. Downloads are
 * {@code attachment} with {@code nosniff}, as {@code /api/profile/resume/{id}/file}.
 */
@RestController
@RequestMapping("/api/applications")
@Validated
public class ApplicationController {

    static final String STATUS_FILTER_MESSAGE = "status must be a comma-separated list of "
            + ApplicationStatus.NAMES + ".";
    static final String SORT_MESSAGE = "sort must be updated or created.";
    static final String KIND_MESSAGE = "kind must be CV or COVER_LETTER.";
    static final String FORMAT_MESSAGE = "format must be pdf or docx.";

    private final ApplicationService applicationService;
    private final GenerationService generationService;
    private final DocumentService documentService;

    public ApplicationController(ApplicationService applicationService, GenerationService generationService,
                                 DocumentService documentService) {
        this.applicationService = applicationService;
        this.generationService = generationService;
        this.documentService = documentService;
    }

    @GetMapping
    public PageResponse<ApplicationSummaryView> list(
            @RequestParam(name = "page", defaultValue = "0")
            @Min(value = 0, message = ApiParams.PAGE_MESSAGE) int page,
            @RequestParam(name = "size", defaultValue = "" + Paging.DEFAULT_SIZE)
            @Min(value = 1, message = ApiParams.SIZE_MESSAGE)
            @Max(value = Paging.MAX_SIZE, message = ApiParams.SIZE_MESSAGE) int size,
            @RequestParam(name = "status", required = false) List<String> status,
            @RequestParam(name = "sort", required = false) String sort) {
        return PageResponse.of(applicationService.list(statuses(status), order(sort), page, size), s -> s);
    }

    @GetMapping("/{id}")
    public ApplicationView get(@PathVariable("id") UUID id) {
        return applicationService.get(id);
    }

    @PostMapping
    public ResponseEntity<ApplicationView> create(@RequestBody ApplicationCreateRequest request) {
        ApplicationView created = applicationService.create(request == null ? null : request.toNewApplication());
        return ResponseEntity.created(URI.create("/api/applications/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public ApplicationView update(@PathVariable("id") UUID id, @RequestBody ApplicationUpdateRequest request) {
        return applicationService.update(id, request == null ? null : request.toChanges());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
        applicationService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Queues an AI generation of the application's documents; poll the returned generation. */
    @PostMapping("/{id}/generations")
    public ResponseEntity<GenerationView> generate(@PathVariable("id") UUID id,
                                                   @RequestBody(required = false) GenerationRequest request) {
        GenerationView queued = generationService.request(id, request == null ? null : request.kinds(),
                request == null ? null : request.mentionNoticePeriod());
        return ResponseEntity.accepted()
                .location(URI.create("/api/applications/" + id + "/generations/" + queued.id()))
                .body(queued);
    }

    @GetMapping("/{id}/generations/{genId}")
    public GenerationView generation(@PathVariable("id") UUID id, @PathVariable("genId") UUID genId) {
        return generationService.get(id, genId);
    }

    /** The application's document versions, newest first, optionally of one kind. */
    @GetMapping("/{id}/documents")
    public List<DocumentSummaryView> documents(@PathVariable("id") UUID id,
                                               @RequestParam(name = "kind", required = false) String kind) {
        return documentService.list(id, kind(kind));
    }

    @GetMapping("/{id}/documents/{docId}")
    public DocumentView document(@PathVariable("id") UUID id, @PathVariable("docId") UUID docId) {
        return documentService.get(id, docId);
    }

    /** Stores an owner edit as a new version. */
    @PostMapping("/{id}/documents")
    public ResponseEntity<DocumentView> edit(@PathVariable("id") UUID id, @RequestBody DocumentEditRequest request) {
        DocumentView created = documentService.edit(id, request == null ? null : request.basedOn(),
                request == null ? null : request.content());
        return ResponseEntity.created(URI.create("/api/applications/" + id + "/documents/" + created.id()))
                .body(created);
    }

    @PostMapping("/{id}/documents/{docId}/approve")
    public DocumentView approve(@PathVariable("id") UUID id, @PathVariable("docId") UUID docId,
                                @RequestBody(required = false) ApproveRequest request) {
        return documentService.approve(id, docId, request == null ? null : request.acknowledgeFindings());
    }

    /** A version as PDF or DOCX: the stored file once approved, otherwise a draft rendered now. */
    @GetMapping("/{id}/documents/{docId}/file")
    public ResponseEntity<byte[]> file(@PathVariable("id") UUID id, @PathVariable("docId") UUID docId,
                                       @RequestParam(name = "format", required = false) String format) {
        RenderedFile f = documentService.file(id, docId, format(format));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(f.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(f.bytes());
    }

    /** {@code CV} or {@code COVER_LETTER} (case-insensitive); no value means both. */
    static DocumentKind kind(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return DocumentKind.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidParameterException("kind", KIND_MESSAGE);
        }
    }

    /** {@code pdf} (default) or {@code docx}, case-insensitive. */
    static ExportFormat format(String raw) {
        if (raw == null || raw.isBlank()) {
            return ExportFormat.PDF;
        }
        return ExportFormat.parse(raw).orElseThrow(() -> new InvalidParameterException("format", FORMAT_MESSAGE));
    }

    /**
     * {@code status=NEW,APPLIED} or {@code status=NEW&status=APPLIED} (case-insensitive); blank entries
     * are ignored, and no value means every status.
     */
    static Set<ApplicationStatus> statuses(List<String> raw) {
        Set<ApplicationStatus> statuses = EnumSet.noneOf(ApplicationStatus.class);
        if (raw == null) {
            return statuses;
        }
        for (String value : raw) {
            if (value == null) {
                continue;
            }
            for (String token : value.split(",")) {
                if (token.isBlank()) {
                    continue;
                }
                ApplicationStatus parsed = ApplicationStatus.parse(token);
                if (parsed == null) {
                    throw new InvalidParameterException("status", STATUS_FILTER_MESSAGE);
                }
                statuses.add(parsed);
            }
        }
        return statuses;
    }

    /** {@code updated} (default) or {@code created}, case-insensitive. */
    static ApplicationSort order(String raw) {
        if (raw == null || raw.isBlank()) {
            return ApplicationSort.UPDATED;
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "updated" -> ApplicationSort.UPDATED;
            case "created" -> ApplicationSort.CREATED;
            default -> throw new InvalidParameterException("sort", SORT_MESSAGE);
        };
    }
}
