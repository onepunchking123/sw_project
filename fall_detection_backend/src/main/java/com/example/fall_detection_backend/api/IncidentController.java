package com.example.fall_detection_backend.api;

import com.example.fall_detection_backend.domain.DetectionEvent;
import com.example.fall_detection_backend.domain.DomainEnums;
import com.example.fall_detection_backend.domain.DomainEnums.EventKind;
import com.example.fall_detection_backend.domain.DomainEnums.EventRule;
import com.example.fall_detection_backend.domain.DomainEnums.IncidentResolution;
import com.example.fall_detection_backend.domain.Incident;
import com.example.fall_detection_backend.domain.IncidentResponse;
import com.example.fall_detection_backend.domain.Patient;
import com.example.fall_detection_backend.domain.User;
import com.example.fall_detection_backend.domain.WeeklyReport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
@Transactional
public class IncidentController {
    private static final List<DomainEnums.ResponseStatus> RESPONSE_STATUS_ORDER = List.of(
        DomainEnums.ResponseStatus.NOTIFIED,
        DomainEnums.ResponseStatus.ACKNOWLEDGED,
        DomainEnums.ResponseStatus.RESPONDING
    );

    private final EntityManager em;

    public IncidentController(EntityManager em) {
        this.em = em;
    }

    @GetMapping("/patients/{patientId}/incidents")
    public List<IncidentResponseBody> listIncidents(@PathVariable UUID patientId,
        @RequestParam(defaultValue = "0") int offset,
        @RequestParam(defaultValue = "200") int limit) {
        validatePage(offset, limit);
        patient(patientId);
        return em.createQuery(
            "select i from Incident i where i.patientId = :patientId order by i.startedAt desc, i.id",
            Incident.class
        )
            .setParameter("patientId", patientId)
            .setFirstResult(offset)
            .setMaxResults(limit)
            .getResultList()
            .stream()
            .map(IncidentResponseBody::from)
            .toList();
    }

    @PostMapping("/patients/{patientId}/incidents")
    @ResponseStatus(HttpStatus.CREATED)
    public IncidentResponseBody createIncident(
        @PathVariable UUID patientId,
        @Valid @RequestBody IncidentInput input
    ) {
        patient(patientId);
        var event = validIncidentEvent(input.detectionEventId(), patientId);
        if (incidentExistsForEvent(event.getId(), null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 긴급 상황으로 등록된 감지 이벤트입니다.");
        }

        var incident = new Incident();
        incident.setPatientId(patientId);
        incident.setDetectionEventId(event.getId());
        applyIncidentInput(incident, input);
        persistAndFlush(incident);
        return IncidentResponseBody.from(incident);
    }

    @GetMapping("/incidents/{incidentId}")
    public IncidentResponseBody getIncident(@PathVariable UUID incidentId) {
        return IncidentResponseBody.from(incident(incidentId));
    }

    @PutMapping("/incidents/{incidentId}")
    public IncidentResponseBody updateIncident(
        @PathVariable UUID incidentId,
        @Valid @RequestBody IncidentInput input
    ) {
        var incident = incident(incidentId);
        var event = validIncidentEvent(input.detectionEventId(), incident.getPatientId());
        if (incidentExistsForEvent(event.getId(), incident.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 긴급 상황으로 등록된 감지 이벤트입니다.");
        }
        incident.setDetectionEventId(event.getId());
        applyIncidentInput(incident, input);
        em.flush();
        return IncidentResponseBody.from(incident);
    }

    @DeleteMapping("/incidents/{incidentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteIncident(@PathVariable UUID incidentId) {
        em.remove(incident(incidentId));
        em.flush();
    }

    @PatchMapping("/incidents/{incidentId}/resolution")
    public IncidentResponseBody resolveIncident(
        @PathVariable UUID incidentId,
        @Valid @RequestBody ResolutionInput input
    ) {
        var incident = incident(incidentId);
        user(input.resolvedByUserId());
        incident.setResolution(input.resolution());
        incident.setResolvedByUserId(input.resolvedByUserId());
        incident.setResolutionNote(trim(input.resolutionNote()));
        incident.setResolvedAt(Instant.now());
        em.flush();
        return IncidentResponseBody.from(incident);
    }

    @GetMapping("/incidents/{incidentId}/responses")
    public List<IncidentResponseBodyItem> listResponses(@PathVariable UUID incidentId,
        @RequestParam(defaultValue = "0") int offset,
        @RequestParam(defaultValue = "200") int limit) {
        validatePage(offset, limit);
        incident(incidentId);
        return em.createQuery(
            "select r from IncidentResponse r where r.incidentId = :incidentId order by r.updatedAt desc, r.id",
            IncidentResponse.class
        )
            .setParameter("incidentId", incidentId)
            .setFirstResult(offset)
            .setMaxResults(limit)
            .getResultList()
            .stream()
            .map(IncidentResponseBodyItem::from)
            .toList();
    }

    @PostMapping("/incidents/{incidentId}/responses")
    @ResponseStatus(HttpStatus.CREATED)
    public IncidentResponseBodyItem createResponse(
        @PathVariable UUID incidentId,
        @Valid @RequestBody ResponseCreateInput input
    ) {
        var incident = incident(incidentId);
        requireCaregiver(incident.getPatientId(), input.userId());
        if (responseExists(incidentId, input.userId(), null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 등록된 보호자 대응 상태입니다.");
        }

        var response = new IncidentResponse();
        response.setIncidentId(incidentId);
        response.setUserId(input.userId());
        response.setNote(trim(input.note()));
        applyResponseStatus(response, parseStatus(input.status()));
        persistAndFlush(response);
        return IncidentResponseBodyItem.from(response);
    }

    @PutMapping("/incidents/{incidentId}/responses/{responseId}")
    public IncidentResponseBodyItem updateResponse(
        @PathVariable UUID incidentId,
        @PathVariable UUID responseId,
        @Valid @RequestBody ResponseUpdateInput input
    ) {
        incident(incidentId);
        var response = incidentResponse(responseId);
        if (!incidentId.equals(response.getIncidentId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "해당 긴급 상황의 보호자 대응 상태가 아닙니다.");
        }
        return updateResponse(response, input);
    }

    private IncidentResponseBodyItem updateResponse(IncidentResponse response, ResponseUpdateInput input) {
        applyResponseStatus(response, parseStatus(input.status()));
        response.setNote(trim(input.note()));
        em.flush();
        return IncidentResponseBodyItem.from(response);
    }

    @GetMapping("/patients/{patientId}/weekly-reports")
    public List<WeeklyReportResponseBody> listWeeklyReports(@PathVariable UUID patientId,
        @RequestParam(defaultValue = "0") int offset,
        @RequestParam(defaultValue = "200") int limit) {
        validatePage(offset, limit);
        patient(patientId);
        return em.createQuery(
            "select r from WeeklyReport r where r.patientId = :patientId order by r.weekStart desc, r.id",
            WeeklyReport.class
        )
            .setParameter("patientId", patientId)
            .setFirstResult(offset)
            .setMaxResults(limit)
            .getResultList()
            .stream()
            .map(WeeklyReportResponseBody::from)
            .toList();
    }

    private void validatePage(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "offset은 0 이상, limit은 1~200이어야 합니다.");
        }
    }

    private void applyIncidentInput(Incident incident, IncidentInput input) {
        validateIncidentTimes(input);
        incident.setStartedAt(input.startedAt());
        incident.setCountdownEndsAt(input.countdownEndsAt());
        incident.setPatientResponded(input.patientResponded() != null ? input.patientResponded() : false);
        incident.setPatientRespondedAt(input.patientRespondedAt());
        incident.setAutoNotifiedAt(input.autoNotifiedAt());
    }

    private void validateIncidentTimes(IncidentInput input) {
        if (!input.countdownEndsAt().isAfter(input.startedAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "카운트다운 종료 시각은 시작 시각 이후여야 합니다.");
        }
        if (Boolean.TRUE.equals(input.patientResponded()) && input.patientRespondedAt() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "환자 응답 시각을 입력해주세요.");
        }
        if (!Boolean.TRUE.equals(input.patientResponded()) && input.patientRespondedAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "환자가 응답하지 않은 경우 응답 시각을 입력할 수 없습니다.");
        }
        if (input.patientRespondedAt() != null && input.patientRespondedAt().isBefore(input.startedAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "환자 응답 시각은 시작 시각 이후여야 합니다.");
        }
        if (input.autoNotifiedAt() != null && input.autoNotifiedAt().isBefore(input.countdownEndsAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "자동 알림 시각은 카운트다운 종료 이후여야 합니다.");
        }
    }

    private DetectionEvent validIncidentEvent(UUID detectionEventId, UUID patientId) {
        var event = required(DetectionEvent.class, detectionEventId, "감지 이벤트를 찾을 수 없습니다.");
        if (!patientId.equals(event.getPatientId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "해당 환자의 감지 이벤트가 아닙니다.");
        }
        if (event.getKind() != EventKind.FALLEN_IMMOBILE || event.getRule() != EventRule.R1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "R1 미동 낙상 감지만 긴급 상황으로 등록할 수 있습니다.");
        }
        return event;
    }

    private void applyResponseStatus(IncidentResponse response, DomainEnums.ResponseStatus nextStatus) {
        var currentStatus = response.getStatus();
        if (statusRank(nextStatus) < statusRank(currentStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "보호자 대응 상태는 이전 단계로 되돌릴 수 없습니다.");
        }

        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (statusRank(nextStatus) >= statusRank(DomainEnums.ResponseStatus.ACKNOWLEDGED)
            && response.getAcknowledgedAt() == null) {
            response.setAcknowledgedAt(now);
        }
        if (statusRank(nextStatus) >= statusRank(DomainEnums.ResponseStatus.RESPONDING)
            && response.getRespondingAt() == null) {
            response.setRespondingAt(now);
        }
        response.setStatus(nextStatus);
        response.setUpdatedAt(now);
    }

    private int statusRank(DomainEnums.ResponseStatus status) {
        var rank = RESPONSE_STATUS_ORDER.indexOf(status);
        if (rank < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "보호자 대응 상태가 올바르지 않습니다.");
        }
        return rank;
    }

    private DomainEnums.ResponseStatus parseStatus(String status) {
        var normalized = status == null ? "NOTIFIED" : status.trim().toUpperCase();
        try {
            var parsed = DomainEnums.ResponseStatus.valueOf(normalized);
            statusRank(parsed);
            return parsed;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "보호자 대응 상태가 올바르지 않습니다.");
        }
    }

    private boolean incidentExistsForEvent(UUID detectionEventId, UUID exceptIncidentId) {
        var query = em.createQuery(
            "select count(i) from Incident i where i.detectionEventId = :detectionEventId"
                + (exceptIncidentId == null ? "" : " and i.id <> :exceptIncidentId"),
            Long.class
        )
            .setParameter("detectionEventId", detectionEventId);
        if (exceptIncidentId != null) {
            query.setParameter("exceptIncidentId", exceptIncidentId);
        }
        return query.getSingleResult() > 0;
    }

    private boolean responseExists(UUID incidentId, UUID userId, UUID exceptResponseId) {
        var query = em.createQuery(
            "select count(r) from IncidentResponse r where r.incidentId = :incidentId and r.userId = :userId"
                + (exceptResponseId == null ? "" : " and r.id <> :exceptResponseId"),
            Long.class
        )
            .setParameter("incidentId", incidentId)
            .setParameter("userId", userId);
        if (exceptResponseId != null) {
            query.setParameter("exceptResponseId", exceptResponseId);
        }
        return query.getSingleResult() > 0;
    }

    private void requireCaregiver(UUID patientId, UUID userId) {
        user(userId);
        var count = em.createQuery(
            "select count(c) from CaregiverLink c where c.patientId = :patientId and c.userId = :userId",
            Long.class
        )
            .setParameter("patientId", patientId)
            .setParameter("userId", userId)
            .getSingleResult();
        if (count == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "해당 환자의 보호자만 대응 상태를 등록할 수 있습니다.");
        }
    }

    private void patient(UUID patientId) {
        required(Patient.class, patientId, "환자를 찾을 수 없습니다.");
    }

    private void user(UUID userId) {
        required(User.class, userId, "사용자를 찾을 수 없습니다.");
    }

    private Incident incident(UUID incidentId) {
        return required(Incident.class, incidentId, "긴급 상황을 찾을 수 없습니다.");
    }

    private IncidentResponse incidentResponse(UUID responseId) {
        return required(IncidentResponse.class, responseId, "보호자 대응 상태를 찾을 수 없습니다.");
    }

    private <T> T required(Class<T> type, UUID id, String message) {
        T row = id == null ? null : em.find(type, id);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
        }
        return row;
    }

    private void persistAndFlush(Object row) {
        try {
            em.persist(row);
            em.flush();
        } catch (PersistenceException e) {
            throw new DataIntegrityViolationException("constraint violation", e);
        }
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    public record IncidentInput(
        @NotNull UUID detectionEventId,
        @NotNull Instant startedAt,
        @NotNull Instant countdownEndsAt,
        Boolean patientResponded,
        Instant patientRespondedAt,
        Instant autoNotifiedAt
    ) {
    }

    public record ResolutionInput(
        @NotNull IncidentResolution resolution,
        @NotNull UUID resolvedByUserId,
        @Size(max = 10000) String resolutionNote
    ) {
    }

    public record ResponseCreateInput(
        @NotNull UUID userId,
        String status,
        @Size(max = 10000) String note
    ) {
    }

    public record ResponseUpdateInput(
        @NotBlank String status,
        @Size(max = 10000) String note
    ) {
    }

    public record IncidentResponseBody(
        UUID id,
        UUID detectionEventId,
        UUID patientId,
        Instant startedAt,
        Instant countdownEndsAt,
        Boolean patientResponded,
        Instant patientRespondedAt,
        Instant autoNotifiedAt,
        Instant resolvedAt,
        IncidentResolution resolution,
        UUID resolvedByUserId,
        String resolutionNote,
        Instant createdAt
    ) {
        static IncidentResponseBody from(Incident incident) {
            return new IncidentResponseBody(
                incident.getId(),
                incident.getDetectionEventId(),
                incident.getPatientId(),
                incident.getStartedAt(),
                incident.getCountdownEndsAt(),
                incident.getPatientResponded(),
                incident.getPatientRespondedAt(),
                incident.getAutoNotifiedAt(),
                incident.getResolvedAt(),
                incident.getResolution(),
                incident.getResolvedByUserId(),
                incident.getResolutionNote(),
                incident.getCreatedAt()
            );
        }
    }

    public record IncidentResponseBodyItem(
        UUID id,
        UUID incidentId,
        UUID userId,
        String status,
        Instant notifiedAt,
        Instant acknowledgedAt,
        Instant respondingAt,
        Instant updatedAt,
        String note
    ) {
        static IncidentResponseBodyItem from(IncidentResponse response) {
            return new IncidentResponseBodyItem(
                response.getId(),
                response.getIncidentId(),
                response.getUserId(),
                response.getStatus().name(),
                response.getNotifiedAt(),
                response.getAcknowledgedAt(),
                response.getRespondingAt(),
                response.getUpdatedAt(),
                response.getNote()
            );
        }
    }

    public record WeeklyReportResponseBody(
        UUID id,
        UUID patientId,
        java.time.LocalDate weekStart,
        Integer abnormalCount,
        Integer fallCount,
        Float uptimePercent,
        Float avgActiveHours,
        Float avgLyingNightHours,
        String summaryText,
        String pdfKey,
        Instant generatedAt
    ) {
        static WeeklyReportResponseBody from(WeeklyReport report) {
            return new WeeklyReportResponseBody(
                report.getId(),
                report.getPatientId(),
                report.getWeekStart(),
                report.getAbnormalCount(),
                report.getFallCount(),
                report.getUptimePercent(),
                report.getAvgActiveHours(),
                report.getAvgLyingNightHours(),
                report.getSummaryText(),
                report.getPdfKey(),
                report.getGeneratedAt()
            );
        }
    }
}
