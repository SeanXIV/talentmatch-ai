package com.talentmatch.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.talentmatch.application.ApplicationRepository.ApplicationRow;
import com.talentmatch.application.DocumentRepository.DocumentRef;
import com.talentmatch.application.DocumentRepository.DocumentRow;
import com.talentmatch.application.DocumentRepository.NewVersion;
import com.talentmatch.application.DocumentRepository.StoredFile;
import com.talentmatch.application.content.CoverLetterContent;
import com.talentmatch.application.content.DocumentKind;
import com.talentmatch.application.content.Finding;
import com.talentmatch.application.content.FindingCode;
import com.talentmatch.application.content.Header;
import com.talentmatch.application.content.Severity;
import com.talentmatch.application.content.TailoredCvContent;
import com.talentmatch.application.render.DocumentRenderer;
import com.talentmatch.application.render.ExportFormat;
import com.talentmatch.application.render.RenderedFile;
import com.talentmatch.application.tailoring.ApplicationProperties;
import com.talentmatch.application.tailoring.EditValidator;
import com.talentmatch.application.tailoring.FactCheck;
import com.talentmatch.application.tailoring.ProfileFacts;
import com.talentmatch.feed.skills.SkillDictionary;
import com.talentmatch.profile.OwnerProfileRepository;
import com.talentmatch.profile.ProfileDocument;
import com.talentmatch.profile.ProfileVersionReader;
import com.talentmatch.service.FieldErrors;
import com.talentmatch.service.exception.ApiException;
import com.talentmatch.service.exception.ConflictException;
import com.talentmatch.service.exception.NotFoundException;
import com.talentmatch.service.exception.RequestValidationException;
import com.talentmatch.web.error.ErrorCode;
import com.talentmatch.web.error.FieldErrorDto;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Document versions of an application (Phase 7 §4.7, decisions i–l): list, read, owner edit, approval and
 * downloads.
 * <ul>
 *   <li><b>Read</b> adds the computed BLOCKING {@code PROFILE_CHANGED} when the version was built from an
 *       older profile version than the current one, and {@code jobChanged} when the job was updated since.</li>
 *   <li><b>Edit</b> creates a new immutable {@code EDIT} version of the base's kind, validated by
 *       {@link EditValidator} against the base's profile version (read-only facts, refs, limits; free text
 *       re-checked, findings WARNING only), inserted under the application row lock.</li>
 *   <li><b>Approve</b> records the approval once. BLOCKING findings (including {@code PROFILE_CHANGED}) need
 *       {@code acknowledgeFindings=true}. The PDF and DOCX are rendered before the transaction, then the
 *       approval and both files are stored together under the application row lock, so a version is never
 *       approved without its files. Approving an approved version changes nothing.</li>
 *   <li><b>Download</b> returns the stored bytes of an approved version; an unapproved version is rendered on
 *       the request thread with the DRAFT footer and a {@code -draft} file name (decision l).</li>
 * </ul>
 * Logs carry ids, kinds, versions and counts only: never content, findings' values or file names (they
 * contain the owner's name).
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    static final String BASED_ON_REQUIRED = "basedOn is required.";
    static final String CONTENT_REQUIRED = "content is required.";

    private final ApplicationRepository applications;
    private final DocumentRepository documents;
    private final OwnerProfileRepository profiles;
    private final ProfileVersionReader profileVersions;
    private final SkillDictionary dictionary;
    private final DocumentRenderer renderer;
    private final ApplicationProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;

    public DocumentService(ApplicationRepository applications, DocumentRepository documents,
                           OwnerProfileRepository profiles, ProfileVersionReader profileVersions,
                           SkillDictionary dictionary, DocumentRenderer renderer, ApplicationProperties properties,
                           Clock clock, PlatformTransactionManager transactionManager) {
        this.applications = applications;
        this.documents = documents;
        this.profiles = profiles;
        this.profileVersions = profileVersions;
        this.dictionary = dictionary;
        this.renderer = renderer;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    // ------------------------------------------------------------------ read

    /**
     * The application's versions, newest first.
     *
     * @param kind null = both kinds
     * @throws NotFoundException 404 APPLICATION_NOT_FOUND
     */
    @Transactional(readOnly = true)
    public List<DocumentSummaryView> list(UUID applicationId, DocumentKind kind) {
        requireApplication(applicationId);
        return documents.list(applicationId, kind, currentProfileVersion());
    }

    /** @throws NotFoundException 404 APPLICATION_NOT_FOUND or DOCUMENT_NOT_FOUND */
    @Transactional(readOnly = true)
    public DocumentView get(UUID applicationId, UUID documentId) {
        requireApplication(applicationId);
        return view(requireDocument(applicationId, documentId));
    }

    // ------------------------------------------------------------------ edit

    /**
     * Stores the owner's edit of {@code basedOn} as a new version (§4.7 edit).
     *
     * @param content the edited content: the §2 record shape of the base's kind
     * @throws com.talentmatch.service.exception.RequestValidationException 400 VALIDATION_FAILED (§5.5 messages)
     * @throws ApiException      400 MALFORMED_REQUEST for an unknown field or a value of the wrong type in
     *                           {@code content}
     * @throws NotFoundException 404 APPLICATION_NOT_FOUND
     */
    @Transactional
    public DocumentView edit(UUID applicationId, UUID basedOn, JsonNode content) {
        FieldErrors errors = new FieldErrors();
        if (basedOn == null) {
            errors.add("basedOn", BASED_ON_REQUIRED);
        }
        if (content == null || content.isNull() || content.isMissingNode()) {
            errors.add("content", CONTENT_REQUIRED);
        }
        errors.throwIfAny();

        applications.lockForUpdate(applicationId).orElseThrow(() -> NotFoundException.application(applicationId));
        DocumentRow base = documents.find(applicationId, basedOn).orElseThrow(() -> new RequestValidationException(
                List.of(new FieldErrorDto("basedOn", "No version " + basedOn + " in this application."))));
        DocumentKind kind = base.kind();
        Object edited = parseEdit(kind, content);

        // Refs and read-only facts resolve against the base's profile version (append-only, an FK target).
        ProfileDocument profile = profileVersions.find(base.profileVersion()).orElseThrow(() ->
                new IllegalStateException("Profile version " + base.profileVersion() + " is missing"));
        FactCheck check = FactCheck.load(dictionary::refresh);
        ProfileFacts facts = ProfileFacts.of(profile, properties.generation().maxProfileChars(), check, today());
        EditValidator.Result<?> result = switch (kind) {
            case CV -> EditValidator.validateCv((TailoredCvContent) edited, facts, base.profileVersion(), check);
            case COVER_LETTER -> EditValidator.validateCoverLetter((CoverLetterContent) edited,
                    (CoverLetterContent) DocumentJson.content(kind, base.contentJson()), facts, base.profileVersion(),
                    check);
        };
        if (!result.valid()) {
            result.errors().forEach(e -> errors.add(e.field(), e.message()));
            errors.throwIfAny();
        }

        Instant jobUpdatedAt = applications.jobUpdatedAt(applicationId).orElse(null);
        DocumentRef ref = documents.insertVersion(NewVersion.edit(applicationId, kind, base.id(),
                base.profileVersion(), jobUpdatedAt, result.content(), result.findings()));
        log.info("Document edited application={} document={} kind={} version={} basedOn={} warnings={}",
                applicationId, ref.id(), kind, ref.version(), base.id(), count(result.findings(), Severity.WARNING));
        return view(requireDocument(applicationId, ref.id()));
    }

    /** The {@code content} object as the base's record type; shape errors are 400 MALFORMED_REQUEST. */
    private static Object parseEdit(DocumentKind kind, JsonNode content) {
        if (!content.isObject()) {
            throw malformed("Field 'content' has the wrong type.", "content", "Expected an object.");
        }
        try {
            return DocumentJson.edited(kind, content);
        } catch (UnrecognizedPropertyException e) {
            String path = contentPath(e);
            throw malformed("Unknown field '" + path + "'.", path,
                    "Unknown field. Check the spelling (field names are camelCase).");
        } catch (MismatchedInputException e) {
            String path = contentPath(e);
            throw malformed("Field '" + path + "' has the wrong type.", path, "Expected a different type.");
        } catch (JsonMappingException e) {
            String path = contentPath(e);
            throw malformed("Field '" + path + "' has an invalid value.", path, "Invalid value.");
        } catch (JsonProcessingException e) {
            throw malformed("Field 'content' could not be read.", "content", "Invalid value.");
        }
    }

    // ------------------------------------------------------------------ approve

    /** What approval reads before rendering. */
    private record ToApprove(ApplicationRow application, DocumentRow document, Integer currentProfileVersion) {
    }

    /**
     * Approves a version (§4.7 approve); idempotent once approved.
     *
     * @param acknowledgeFindings null means false; required when the version has a BLOCKING finding
     * @throws NotFoundException 404 APPLICATION_NOT_FOUND or DOCUMENT_NOT_FOUND
     * @throws ConflictException 409 FINDINGS_NOT_ACKNOWLEDGED
     */
    public DocumentView approve(UUID applicationId, UUID documentId, Boolean acknowledgeFindings) {
        boolean acknowledged = Boolean.TRUE.equals(acknowledgeFindings);
        ToApprove in = readTx.execute(s -> new ToApprove(requireApplication(applicationId),
                requireDocument(applicationId, documentId), currentProfileVersion()));
        DocumentRow row = in.document();
        if (row.approved()) {
            return get(applicationId, documentId);      // already approved: nothing changes
        }
        List<Finding> blocking = findings(row, in.currentProfileVersion()).stream()
                .filter(f -> f.severity() == Severity.BLOCKING).toList();
        if (!blocking.isEmpty() && !acknowledged) {
            String codes = blocking.stream().map(f -> f.code().name()).distinct().collect(Collectors.joining(", "));
            throw new ConflictException(ErrorCode.FINDINGS_NOT_ACKNOWLEDGED, "This version has " + blocking.size()
                    + " finding(s) that need your review (" + codes + "). Read them, then approve again with "
                    + "acknowledgeFindings=true.");
        }

        // Render outside the transaction: a rendering failure approves nothing, and no row lock is held meanwhile.
        Object content = DocumentJson.content(row.kind(), row.contentJson());
        String company = in.application().jobCompany();
        RenderedFile pdf = renderer.render(content, company, row.createdAt(), ExportFormat.PDF, false);
        RenderedFile docx = renderer.render(content, company, row.createdAt(), ExportFormat.DOCX, false);

        return tx.execute(s -> {
            applications.lockForUpdate(applicationId).orElseThrow(() -> NotFoundException.application(applicationId));
            if (documents.approve(documentId, acknowledged)) {
                documents.insertFile(documentId, ExportFormat.PDF, pdf.bytes(), sha256(pdf.bytes()));
                documents.insertFile(documentId, ExportFormat.DOCX, docx.bytes(), sha256(docx.bytes()));
                log.info("Document approved application={} document={} kind={} version={} acknowledged={} "
                                + "blocking={} pdfBytes={} docxBytes={}", applicationId, documentId, row.kind(),
                        row.version(), acknowledged, blocking.size(), pdf.bytes().length, docx.bytes().length);
            } else {
                // Approved concurrently (or deleted: requireDocument answers 404); the first approval's files stay.
                log.info("Document already approved application={} document={}", applicationId, documentId);
            }
            return view(requireDocument(applicationId, documentId));
        });
    }

    // ------------------------------------------------------------------ download

    /** What a download reads. @param stored the stored export (approved versions only) */
    private record ToDownload(ApplicationRow application, DocumentRow document, StoredFile stored) {
    }

    /**
     * One export of a version: the stored bytes once approved, otherwise a draft rendered now.
     *
     * @throws NotFoundException 404 APPLICATION_NOT_FOUND or DOCUMENT_NOT_FOUND
     */
    public RenderedFile file(UUID applicationId, UUID documentId, ExportFormat format) {
        Objects.requireNonNull(format, "format");
        ToDownload in = readTx.execute(s -> {
            ApplicationRow application = requireApplication(applicationId);
            DocumentRow row = requireDocument(applicationId, documentId);
            StoredFile stored = row.approved()
                    ? documents.findFile(documentId, format).orElseThrow(() -> new IllegalStateException(
                            "Approved document " + documentId + " has no stored " + format))
                    : null;
            return new ToDownload(application, row, stored);
        });
        DocumentRow row = in.document();
        Object content = DocumentJson.content(row.kind(), row.contentJson());
        String company = in.application().jobCompany();
        if (in.stored() != null) {
            return new RenderedFile(in.stored().content(), format.contentType(),
                    DocumentRenderer.fileName(row.kind(), fullName(content), company, false, format));
        }
        return renderer.render(content, company, row.createdAt(), format, true);
    }

    // ------------------------------------------------------------------ helpers

    private ApplicationRow requireApplication(UUID applicationId) {
        return applications.find(applicationId).orElseThrow(() -> NotFoundException.application(applicationId));
    }

    private DocumentRow requireDocument(UUID applicationId, UUID documentId) {
        return documents.find(applicationId, documentId)
                .orElseThrow(() -> NotFoundException.document(applicationId, documentId));
    }

    private Integer currentProfileVersion() {
        return profiles.findRef().map(OwnerProfileRepository.OwnerRef::version).orElse(null);
    }

    private DocumentView view(DocumentRow row) {
        Integer current = currentProfileVersion();
        boolean profileCurrent = DocumentRepository.profileCurrent(row.profileVersion(), current);
        List<Finding> findings = findings(row, current);
        DocumentSummaryView.FindingCounts counts = new DocumentSummaryView.FindingCounts(
                (int) count(findings, Severity.BLOCKING), (int) count(findings, Severity.WARNING),
                (int) count(findings, Severity.INFO));
        return new DocumentView(
                row.id(),
                row.kind(),
                row.version(),
                row.source(),
                row.basedOnId(),
                row.profileVersion(),
                profileCurrent,
                row.model(),
                row.createdAt(),
                row.approvedAt(),
                counts,
                DocumentJson.content(row.kind(), row.contentJson()),
                findings,
                row.approvalAcknowledged(),
                row.jobChanged(),
                row.approved() ? documents.fileFormats(row.id()) : List.of(),
                DocumentView.Downloads.of(row.applicationId(), row.id()));
    }

    /** The stored findings, preceded by the computed {@code PROFILE_CHANGED} when the profile was saved since. */
    static List<Finding> findings(DocumentRow row, Integer currentProfileVersion) {
        List<Finding> stored = DocumentJson.findings(row.findingsJson());
        if (DocumentRepository.profileCurrent(row.profileVersion(), currentProfileVersion)) {
            return stored;
        }
        List<Finding> all = new ArrayList<>(stored.size() + 1);
        all.add(profileChanged(row.profileVersion(), currentProfileVersion));
        all.addAll(stored);
        return List.copyOf(all);
    }

    static Finding profileChanged(int profileVersion, int currentProfileVersion) {
        return Finding.blocking(FindingCode.PROFILE_CHANGED, null, null, "Your profile changed after this version "
                + "was made (it uses profile version " + profileVersion + "; the current one is "
                + currentProfileVersion + "), so it may not match your profile any more. Generate again, or approve "
                + "it with acknowledgeFindings=true if it still fits.");
    }

    private static String fullName(Object content) {
        Header header = content instanceof TailoredCvContent cv ? cv.header()
                : content instanceof CoverLetterContent letter ? letter.header() : null;
        return header == null || header.fullName() == null ? "" : header.fullName();
    }

    /** Today in {@code talentmatch.applications.zone} (the career span end for the edit's fact sets). */
    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), properties.zoneId());
    }

    private static long count(List<Finding> findings, Severity severity) {
        return findings.stream().filter(f -> f.severity() == severity).count();
    }

    /** {@code content.experience[0].title}: the JSON path below the request's {@code content} object. */
    private static String contentPath(JsonMappingException e) {
        StringBuilder sb = new StringBuilder("content");
        for (JsonMappingException.Reference ref : e.getPath()) {
            if (ref.getFieldName() != null) {
                sb.append('.').append(ref.getFieldName());
            } else if (ref.getIndex() >= 0) {
                sb.append('[').append(ref.getIndex()).append(']');
            }
        }
        return sb.toString();
    }

    private static ApiException malformed(String message, String field, String fieldMessage) {
        return new ApiException(ErrorCode.MALFORMED_REQUEST, HttpStatus.BAD_REQUEST, message,
                List.of(new FieldErrorDto(field, fieldMessage)));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
