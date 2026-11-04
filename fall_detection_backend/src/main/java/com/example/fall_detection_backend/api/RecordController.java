package com.example.fall_detection_backend.api;

import com.example.fall_detection_backend.domain.*;
import com.example.fall_detection_backend.domain.DomainEnums.*;
import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/patients/{patientId}")
@Transactional
public class RecordController {
    private final EntityManager em;
    public RecordController(EntityManager em) { this.em = em; }
    private <T> T required(Class<T> type, UUID id) {
        T row = id == null ? null : em.find(type, id);
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "데이터를 찾을 수 없습니다.");
        return row;
    }
    private void patient(UUID id) { required(Patient.class, id); }
    private void samePatient(UUID expected, UUID actual) {
        if (!expected.equals(actual)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "해당 환자의 데이터가 아닙니다.");
    }
    private void author(UUID userId) { required(User.class, userId); }
    private <T> List<T> list(Class<T> type, UUID patientId, String order, int offset, int limit) {
        patient(patientId);
        page(offset,limit);
        return em.createQuery("select r from " + type.getSimpleName() + " r where r.patientId = :patient order by " + order, type)
            .setParameter("patient", patientId).setFirstResult(offset).setMaxResults(limit).getResultList();
    }

    private void page(int offset,int limit) {
        if(offset<0 || limit<1 || limit>200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"offset은 0 이상, limit은 1~200이어야 합니다.");
    }
    public record RecordInput(@NotNull UUID authorUserId, @NotNull Instant recordedAt,
        @NotNull RecordCategory category, ConditionLevel condition, @NotBlank @Size(max=80) String title,
        @Size(max=10000) String memo) {}

    @GetMapping("/records") public List<CareRecord> records(@PathVariable UUID patientId,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="200") int limit) {
        return list(CareRecord.class, patientId, "r.recordedAt desc, r.id",offset,limit);
    }
    @GetMapping("/records/{id}") public CareRecord record(@PathVariable UUID patientId, @PathVariable UUID id) {
        var row = required(CareRecord.class, id); samePatient(patientId, row.getPatientId()); return row;
    }
    private void fill(CareRecord row, RecordInput in) {
        author(in.authorUserId()); row.setAuthorUserId(in.authorUserId()); row.setRecordedAt(in.recordedAt());
        row.setCategory(in.category()); row.setCondition(in.condition()); row.setTitle(in.title().trim()); row.setMemo(in.memo());
    }
    @PostMapping("/records") @ResponseStatus(HttpStatus.CREATED)
    public CareRecord createRecord(@PathVariable UUID patientId, @Valid @RequestBody RecordInput in) {
        patient(patientId); var row = new CareRecord(); row.setPatientId(patientId); fill(row,in); em.persist(row); em.flush(); return row;
    }
    @PutMapping("/records/{id}") public CareRecord updateRecord(@PathVariable UUID patientId, @PathVariable UUID id, @Valid @RequestBody RecordInput in) {
        var row = record(patientId,id); fill(row,in); row.setUpdatedAt(Instant.now()); em.flush(); return row;
    }
    @DeleteMapping("/records/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRecord(@PathVariable UUID patientId, @PathVariable UUID id) { em.remove(record(patientId,id)); em.flush(); }

    public record EventInput(@NotNull UUID clientEventId, @NotNull UUID cameraDeviceId, @NotNull Instant occurredAt,
        @NotNull EventKind kind, @NotNull EventRule rule, @NotNull PoseState state,
        @NotNull @DecimalMin("0") @DecimalMax("1") Float probNormal,
        @NotNull @DecimalMin("0") @DecimalMax("1") Float probFall,
        @NotNull @DecimalMin("0") @DecimalMax("1") Float probFallen,
        @PositiveOrZero Float durationSec) {}
    @GetMapping("/events") public List<DetectionEvent> events(@PathVariable UUID patientId,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="200") int limit) { return list(DetectionEvent.class,patientId,"r.occurredAt desc, r.id",offset,limit); }
    @GetMapping("/events/{id}") public DetectionEvent event(@PathVariable UUID patientId, @PathVariable UUID id) {
        var row = required(DetectionEvent.class,id); samePatient(patientId,row.getPatientId()); return row;
    }
    private void fill(DetectionEvent row, UUID patientId, EventInput in) {
        var camera = required(CameraDevice.class,in.cameraDeviceId()); samePatient(patientId,camera.getPatientId());
        row.setClientEventId(in.clientEventId()); row.setCameraDeviceId(in.cameraDeviceId()); row.setOccurredAt(in.occurredAt());
        row.setKind(in.kind()); row.setRule(in.rule()); row.setState(in.state()); row.setProbNormal(in.probNormal());
        row.setProbFall(in.probFall()); row.setProbFallen(in.probFallen()); row.setDurationSec(in.durationSec());
    }
    @PostMapping("/events") @ResponseStatus(HttpStatus.CREATED)
    public DetectionEvent createEvent(@PathVariable UUID patientId,@Valid @RequestBody EventInput in) {
        patient(patientId); var row = new DetectionEvent(); row.setPatientId(patientId); fill(row,patientId,in); em.persist(row); em.flush(); return row;
    }
    @PutMapping("/events/{id}") public DetectionEvent updateEvent(@PathVariable UUID patientId,@PathVariable UUID id,@Valid @RequestBody EventInput in) {
        var row = event(patientId,id); fill(row,patientId,in); em.flush(); return row;
    }
    @DeleteMapping("/events/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEvent(@PathVariable UUID patientId,@PathVariable UUID id) { em.remove(event(patientId,id)); em.flush(); }

    public record NoteInput(UUID detectionEventId, UUID careRecordId, @NotNull UUID authorUserId,
        @NotNull NoteKind kind, PatientState patientState, @NotBlank @Size(max=10000) String memo) {}
    private UUID notePatient(EventNote row) {
        return row.getDetectionEventId() != null ? required(DetectionEvent.class,row.getDetectionEventId()).getPatientId()
            : required(CareRecord.class,row.getCareRecordId()).getPatientId();
    }
    @GetMapping("/notes") public List<EventNote> notes(@PathVariable UUID patientId,
        @RequestParam(required=false) UUID detectionEventId, @RequestParam(required=false) UUID careRecordId, @RequestParam(defaultValue="0") int offset, @RequestParam(defaultValue="200") int limit) {
        page(offset,limit);
        patient(patientId);
        if (detectionEventId != null && careRecordId != null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"대상은 하나만 지정해주세요.");
        if (detectionEventId != null) event(patientId,detectionEventId);
        if (careRecordId != null) record(patientId,careRecordId);
        var query = em.createQuery("select n from EventNote n where (n.detectionEventId in (select e.id from DetectionEvent e where e.patientId = :p) or n.careRecordId in (select r.id from CareRecord r where r.patientId = :p))"
            + (detectionEventId != null ? " and n.detectionEventId = :target" : careRecordId != null ? " and n.careRecordId = :target" : "") + " order by n.createdAt, n.id", EventNote.class)
            .setParameter("p",patientId);
        if (detectionEventId != null || careRecordId != null) query.setParameter("target", detectionEventId != null ? detectionEventId : careRecordId);
        return query.setFirstResult(offset).setMaxResults(limit).getResultList();
    }
    @GetMapping("/notes/{id}") public EventNote note(@PathVariable UUID patientId,@PathVariable UUID id) {
        var row=required(EventNote.class,id); samePatient(patientId,notePatient(row)); return row;
    }
    private void fill(EventNote row,UUID patientId,NoteInput in) {
        if ((in.detectionEventId()==null)==(in.careRecordId()==null)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"AI 감지 또는 수동 기록 중 하나를 지정해주세요.");
        if(in.detectionEventId()!=null) event(patientId,in.detectionEventId()); else record(patientId,in.careRecordId());
        author(in.authorUserId()); row.setDetectionEventId(in.detectionEventId()); row.setCareRecordId(in.careRecordId());
        row.setAuthorUserId(in.authorUserId()); row.setKind(in.kind()); row.setPatientState(in.patientState()); row.setMemo(in.memo().trim());
    }
    @PostMapping("/notes") @ResponseStatus(HttpStatus.CREATED)
    public EventNote createNote(@PathVariable UUID patientId,@Valid @RequestBody NoteInput in) {
        patient(patientId);var row=new EventNote();fill(row,patientId,in);em.persist(row);em.flush();return row;
    }
    @PutMapping("/notes/{id}") public EventNote updateNote(@PathVariable UUID patientId,@PathVariable UUID id,@Valid @RequestBody NoteInput in) {
        var row=note(patientId,id);fill(row,patientId,in);row.setUpdatedAt(Instant.now());em.flush();return row;
    }
    @DeleteMapping("/notes/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNote(@PathVariable UUID patientId,@PathVariable UUID id) { em.remove(note(patientId,id));em.flush(); }
}
