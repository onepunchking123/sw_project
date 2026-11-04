package com.example.fall_detection_backend.api.setup;

import com.example.fall_detection_backend.api.camera.CameraPairingService;
import com.example.fall_detection_backend.api.camera.CameraPairingService.GuardianCameraResponse;
import com.example.fall_detection_backend.api.live.LiveSessionService;
import com.example.fall_detection_backend.domain.CameraDevice;
import com.example.fall_detection_backend.domain.CameraDeviceRepository;
import com.example.fall_detection_backend.domain.CaregiverLink;
import com.example.fall_detection_backend.domain.CaregiverLinkRepository;
import com.example.fall_detection_backend.domain.DomainEnums;
import com.example.fall_detection_backend.domain.Patient;
import com.example.fall_detection_backend.domain.PatientContact;
import com.example.fall_detection_backend.domain.PatientContactRepository;
import com.example.fall_detection_backend.domain.PatientRepository;
import com.example.fall_detection_backend.domain.User;
import com.example.fall_detection_backend.domain.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/users")
class SetupUserController {
    private final UserRepository users;

    SetupUserController(UserRepository users) {
        this.users = users;
    }

    @PostMapping
    @Transactional
    ResponseEntity<UserProfileResponse> create(@Valid @RequestBody UserProfileRequest request) {
        var user = new User();
        apply(request, user);
        user.setPasswordHash("!");

        var saved = users.save(user);
        return ResponseEntity.created(URI.create("/api/users/" + saved.getId())).body(UserProfileResponse.from(saved));
    }

    @GetMapping("/{userId}")
    UserProfileResponse get(@PathVariable UUID userId) {
        return UserProfileResponse.from(SetupCrudSupport.findUser(users, userId));
    }

    private static void apply(UserProfileRequest request, User user) {
        user.setName(request.name());
        user.setEmail(request.email());
        user.setPhone(request.phone());
    }
}

@RestController
@RequestMapping("/api/patients")
class SetupPatientController {
    private final EntityManager entityManager;
    private final PatientRepository patients;

    SetupPatientController(EntityManager entityManager, PatientRepository patients) {
        this.entityManager = entityManager;
        this.patients = patients;
    }

    @GetMapping
    List<PatientResponse> list(
        @RequestParam(defaultValue = "0") int offset,
        @RequestParam(defaultValue = "200") int limit
    ) {
        return entityManager
            .createQuery("select p from Patient p order by p.name, p.id", Patient.class)
            .setFirstResult(SetupCrudSupport.offset(offset))
            .setMaxResults(SetupCrudSupport.limit(limit))
            .getResultList()
            .stream()
            .map(PatientResponse::from)
            .toList();
    }

    @PostMapping
    @Transactional
    ResponseEntity<PatientResponse> create(@Valid @RequestBody PatientRequest request) {
        var patient = new Patient();
        apply(request, patient);

        var saved = patients.save(patient);
        return ResponseEntity.created(URI.create("/api/patients/" + saved.getId())).body(PatientResponse.from(saved));
    }

    @GetMapping("/{patientId}")
    PatientResponse get(@PathVariable UUID patientId) {
        return PatientResponse.from(SetupCrudSupport.findPatient(patients, patientId));
    }

    @PutMapping("/{patientId}")
    @Transactional
    PatientResponse update(@PathVariable UUID patientId, @Valid @RequestBody PatientRequest request) {
        var patient = SetupCrudSupport.findPatient(patients, patientId);
        apply(request, patient);
        return PatientResponse.from(patients.save(patient));
    }

    @DeleteMapping("/{patientId}")
    @Transactional
    ResponseEntity<Void> delete(@PathVariable UUID patientId) {
        var patient = SetupCrudSupport.findPatient(patients, patientId);
        patients.delete(patient);
        return ResponseEntity.noContent().build();
    }

    private static void apply(PatientRequest request, Patient patient) {
        patient.setName(request.name());
        patient.setAge(request.age());
        patient.setSex(request.sex());
        patient.setAddress(request.address());
        patient.setDementiaStage(request.dementiaStage());
    }
}

@RestController
@RequestMapping("/api/patients/{patientId}/contacts")
class SetupPatientContactController {
    private final EntityManager entityManager;
    private final PatientRepository patients;
    private final PatientContactRepository contacts;

    SetupPatientContactController(
        EntityManager entityManager,
        PatientRepository patients,
        PatientContactRepository contacts
    ) {
        this.entityManager = entityManager;
        this.patients = patients;
        this.contacts = contacts;
    }

    @GetMapping
    List<PatientContactResponse> list(
        @PathVariable UUID patientId,
        @RequestParam(defaultValue = "0") int offset,
        @RequestParam(defaultValue = "200") int limit
    ) {
        SetupCrudSupport.requirePatient(patients, patientId);
        return entityManager
            .createQuery("""
                select c
                from PatientContact c
                where c.patientId = :patientId
                order by c.sortOrder, c.id
                """, PatientContact.class)
            .setParameter("patientId", patientId)
            .setFirstResult(SetupCrudSupport.offset(offset))
            .setMaxResults(SetupCrudSupport.limit(limit))
            .getResultList()
            .stream()
            .map(PatientContactResponse::from)
            .toList();
    }

    @PostMapping
    @Transactional
    ResponseEntity<PatientContactResponse> create(
        @PathVariable UUID patientId,
        @Valid @RequestBody PatientContactRequest request
    ) {
        SetupCrudSupport.requirePatient(patients, patientId);
        var contact = new PatientContact();
        contact.setPatientId(patientId);
        apply(request, contact);

        var saved = contacts.save(contact);
        return ResponseEntity.created(URI.create("/api/patients/" + patientId + "/contacts/" + saved.getId()))
            .body(PatientContactResponse.from(saved));
    }

    @GetMapping("/{contactId}")
    PatientContactResponse get(@PathVariable UUID patientId, @PathVariable UUID contactId) {
        return PatientContactResponse.from(SetupCrudSupport.findContact(contacts, patientId, contactId));
    }

    @PutMapping("/{contactId}")
    @Transactional
    PatientContactResponse update(
        @PathVariable UUID patientId,
        @PathVariable UUID contactId,
        @Valid @RequestBody PatientContactRequest request
    ) {
        var contact = SetupCrudSupport.findContact(contacts, patientId, contactId);
        apply(request, contact);
        return PatientContactResponse.from(contacts.save(contact));
    }

    @DeleteMapping("/{contactId}")
    @Transactional
    ResponseEntity<Void> delete(@PathVariable UUID patientId, @PathVariable UUID contactId) {
        var contact = SetupCrudSupport.findContact(contacts, patientId, contactId);
        contacts.delete(contact);
        return ResponseEntity.noContent().build();
    }

    private static void apply(PatientContactRequest request, PatientContact contact) {
        contact.setName(request.name());
        contact.setRelation(request.relation());
        contact.setPhone(request.phone());
        contact.setSortOrder(request.sortOrder());
    }
}

@RestController
@RequestMapping("/api/patients/{patientId}/cameras")
class SetupCameraDeviceController {
    private final EntityManager entityManager;
    private final PatientRepository patients;
    private final CameraDeviceRepository cameras;
    private final CameraPairingService cameraPairing;
    private LiveSessionService liveSessions;

    SetupCameraDeviceController(
        EntityManager entityManager,
        PatientRepository patients,
        CameraDeviceRepository cameras,
        CameraPairingService cameraPairing
    ) {
        this.entityManager = entityManager;
        this.patients = patients;
        this.cameras = cameras;
        this.cameraPairing = cameraPairing;
    }

    @Autowired(required = false)
    void setLiveSessions(LiveSessionService liveSessions) {
        this.liveSessions = liveSessions;
    }

    @GetMapping
    List<GuardianCameraResponse> list(
        @PathVariable UUID patientId,
        @RequestParam(defaultValue = "0") int offset,
        @RequestParam(defaultValue = "200") int limit
    ) {
        SetupCrudSupport.requirePatient(patients, patientId);
        return entityManager
            .createQuery("""
                select c
                from CameraDevice c
                where c.patientId = :patientId
                order by c.name, c.id
                """, CameraDevice.class)
            .setParameter("patientId", patientId)
            .setFirstResult(SetupCrudSupport.offset(offset))
            .setMaxResults(SetupCrudSupport.limit(limit))
            .getResultList()
            .stream()
            .map(cameraPairing::guardianView)
            .toList();
    }

    @PostMapping
    @Transactional
    ResponseEntity<GuardianCameraResponse> create(
        @PathVariable UUID patientId,
        @Valid @RequestBody CameraDeviceRequest request
    ) {
        SetupCrudSupport.requirePatient(patients, patientId);
        var camera = new CameraDevice();
        camera.setPatientId(patientId);
        camera.setDeviceToken(UUID.randomUUID().toString());
        camera.setStatus(DomainEnums.DeviceStatus.UNPAIRED);
        apply(request, camera);

        var saved = cameras.save(camera);
        return ResponseEntity.created(URI.create("/api/patients/" + patientId + "/cameras/" + saved.getId()))
            .body(cameraPairing.guardianView(saved));
    }

    @GetMapping("/{cameraId}")
    GuardianCameraResponse get(@PathVariable UUID patientId, @PathVariable UUID cameraId) {
        return cameraPairing.guardianView(SetupCrudSupport.findCamera(cameras, patientId, cameraId));
    }

    @PutMapping("/{cameraId}")
    @Transactional
    GuardianCameraResponse update(
        @PathVariable UUID patientId,
        @PathVariable UUID cameraId,
        @Valid @RequestBody CameraDeviceRequest request
    ) {
        var camera = SetupCrudSupport.findCamera(cameras, patientId, cameraId);
        var privacyChanged = camera.getPrivacyMode() != request.privacyMode()
            || !java.util.Objects.equals(camera.getSensitivity(), request.sensitivity());
        apply(request, camera);
        if (privacyChanged) {
            camera.setPrivacyRevision((camera.getPrivacyRevision() == null ? 0L : camera.getPrivacyRevision()) + 1);
        }
        if (privacyChanged && liveSessions != null) {
            liveSessions.invalidateCamera(cameraId, "카메라 개인정보 설정이 변경되어 라이브 세션이 종료되었습니다.");
        }
        return cameraPairing.guardianView(cameras.save(camera));
    }

    @DeleteMapping("/{cameraId}")
    @Transactional
    ResponseEntity<Void> unlink(@PathVariable UUID patientId, @PathVariable UUID cameraId) {
        cameraPairing.unlink(patientId, cameraId);
        return ResponseEntity.noContent().build();
    }

    private static void apply(CameraDeviceRequest request, CameraDevice camera) {
        camera.setName(request.name());
        camera.setModel(request.model());
        camera.setPrivacyMode(request.privacyMode());
        camera.setSensitivity(request.sensitivity());
    }
}

@RestController
@RequestMapping("/api/patients/{patientId}/caregivers")
class SetupCaregiverLinkController {
    private final EntityManager entityManager;
    private final PatientRepository patients;
    private final UserRepository users;
    private final CaregiverLinkRepository caregiverLinks;

    SetupCaregiverLinkController(
        EntityManager entityManager,
        PatientRepository patients,
        UserRepository users,
        CaregiverLinkRepository caregiverLinks
    ) {
        this.entityManager = entityManager;
        this.patients = patients;
        this.users = users;
        this.caregiverLinks = caregiverLinks;
    }

    @GetMapping
    List<CaregiverLinkResponse> list(
        @PathVariable UUID patientId,
        @RequestParam(defaultValue = "0") int offset,
        @RequestParam(defaultValue = "200") int limit
    ) {
        SetupCrudSupport.requirePatient(patients, patientId);
        return entityManager
            .createQuery("""
                select c
                from CaregiverLink c
                where c.patientId = :patientId
                order by c.role, c.id
                """, CaregiverLink.class)
            .setParameter("patientId", patientId)
            .setFirstResult(SetupCrudSupport.offset(offset))
            .setMaxResults(SetupCrudSupport.limit(limit))
            .getResultList()
            .stream()
            .map(link -> CaregiverLinkResponse.from(link, SetupCrudSupport.findUser(users, link.getUserId())))
            .toList();
    }

    @PostMapping
    @Transactional
    ResponseEntity<CaregiverLinkResponse> create(
        @PathVariable UUID patientId,
        @Valid @RequestBody CaregiverLinkRequest request
    ) {
        SetupCrudSupport.requirePatient(patients, patientId);
        var user = SetupCrudSupport.findUser(users, request.userId());

        var link = new CaregiverLink();
        link.setPatientId(patientId);
        apply(request, link);

        var saved = caregiverLinks.save(link);
        return ResponseEntity.created(URI.create("/api/patients/" + patientId + "/caregivers/" + saved.getId()))
            .body(CaregiverLinkResponse.from(saved, user));
    }

    @GetMapping("/{caregiverLinkId}")
    CaregiverLinkResponse get(@PathVariable UUID patientId, @PathVariable UUID caregiverLinkId) {
        var link = SetupCrudSupport.findCaregiverLink(caregiverLinks, patientId, caregiverLinkId);
        return CaregiverLinkResponse.from(link, SetupCrudSupport.findUser(users, link.getUserId()));
    }

    @PutMapping("/{caregiverLinkId}")
    @Transactional
    CaregiverLinkResponse update(
        @PathVariable UUID patientId,
        @PathVariable UUID caregiverLinkId,
        @Valid @RequestBody CaregiverLinkRequest request
    ) {
        var user = SetupCrudSupport.findUser(users, request.userId());
        var link = SetupCrudSupport.findCaregiverLink(caregiverLinks, patientId, caregiverLinkId);
        apply(request, link);
        return CaregiverLinkResponse.from(caregiverLinks.save(link), user);
    }

    @DeleteMapping("/{caregiverLinkId}")
    @Transactional
    ResponseEntity<Void> delete(@PathVariable UUID patientId, @PathVariable UUID caregiverLinkId) {
        var link = SetupCrudSupport.findCaregiverLink(caregiverLinks, patientId, caregiverLinkId);
        caregiverLinks.delete(link);
        return ResponseEntity.noContent().build();
    }

    private static void apply(CaregiverLinkRequest request, CaregiverLink link) {
        link.setUserId(request.userId());
        link.setRelation(request.relation());
        link.setRole(request.role());
        link.setNotifyFall(request.notifyFall());
        link.setNotifyStillMin(request.notifyStillMin());
        link.setNotifyOffline(request.notifyOffline());
    }
}

record UserProfileRequest(
    @NotBlank @Size(max = 60) String name,
    @NotBlank @Email @Size(max = 255) String email,
    @Size(max = 32) String phone
) {
}

record UserProfileResponse(UUID id, String name, String email, String phone) {
    static UserProfileResponse from(User user) {
        return new UserProfileResponse(user.getId(), user.getName(), user.getEmail(), user.getPhone());
    }
}

record PatientRequest(
    @NotBlank @Size(max = 60) String name,
    @Min(0) @Max(130) Short age,
    @Pattern(regexp = "M|F", message = "M 또는 F만 입력할 수 있습니다.") String sex,
    @Size(max = 255) String address,
    @Size(max = 40) String dementiaStage
) {
}

record PatientResponse(
    UUID id,
    String name,
    Short age,
    String sex,
    String address,
    String dementiaStage
) {
    static PatientResponse from(Patient patient) {
        return new PatientResponse(
            patient.getId(),
            patient.getName(),
            patient.getAge(),
            patient.getSex(),
            patient.getAddress(),
            patient.getDementiaStage()
        );
    }
}

record PatientContactRequest(
    @NotBlank @Size(max = 60) String name,
    @NotNull DomainEnums.CaregiverRelation relation,
    @NotBlank @Size(max = 32) String phone,
    @NotNull @Min(0) Integer sortOrder
) {
}

record PatientContactResponse(
    UUID id,
    UUID patientId,
    String name,
    DomainEnums.CaregiverRelation relation,
    String phone,
    Integer sortOrder
) {
    static PatientContactResponse from(PatientContact contact) {
        return new PatientContactResponse(
            contact.getId(),
            contact.getPatientId(),
            contact.getName(),
            contact.getRelation(),
            contact.getPhone(),
            contact.getSortOrder()
        );
    }
}

record CameraDeviceRequest(
    @NotBlank @Size(max = 60) String name,
    @Size(max = 80) String model,
    @NotNull DomainEnums.PrivacyMode privacyMode,
    @NotNull @Min(1) @Max(3) Short sensitivity
) {
}

record CaregiverLinkRequest(
    @NotNull UUID userId,
    @NotNull DomainEnums.CaregiverRelation relation,
    @NotNull DomainEnums.CaregiverRole role,
    @NotNull Boolean notifyFall,
    @Min(1) @Max(1440) Short notifyStillMin,
    @NotNull Boolean notifyOffline
) {
}

record CaregiverLinkResponse(
    UUID id,
    UUID patientId,
    UserProfileResponse user,
    DomainEnums.CaregiverRelation relation,
    DomainEnums.CaregiverRole role,
    Boolean notifyFall,
    Short notifyStillMin,
    Boolean notifyOffline
) {
    static CaregiverLinkResponse from(CaregiverLink link, User user) {
        return new CaregiverLinkResponse(
            link.getId(),
            link.getPatientId(),
            UserProfileResponse.from(user),
            link.getRelation(),
            link.getRole(),
            link.getNotifyFall(),
            link.getNotifyStillMin(),
            link.getNotifyOffline()
        );
    }
}

final class SetupCrudSupport {
    // List endpoints accept offset and limit; limit defaults to 200 and is capped at 200.
    private static final int MAX_LIMIT = 200;

    private SetupCrudSupport() {
    }

    static int offset(int offset) {
        if (offset < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "offset은 0 이상이어야 합니다.");
        }
        return offset;
    }

    static int limit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit은 1부터 200 사이여야 합니다.");
        }
        return limit;
    }

    static Patient findPatient(PatientRepository patients, UUID patientId) {
        return patients.findById(patientId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "환자를 찾을 수 없습니다."));
    }

    static void requirePatient(PatientRepository patients, UUID patientId) {
        if (!patients.existsById(patientId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "환자를 찾을 수 없습니다.");
        }
    }

    static User findUser(UserRepository users, UUID userId) {
        return users.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
    }

    static PatientContact findContact(PatientContactRepository contacts, UUID patientId, UUID contactId) {
        var contact = contacts.findById(contactId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "연락처를 찾을 수 없습니다."));
        if (!patientId.equals(contact.getPatientId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "환자와 연락처가 일치하지 않습니다.");
        }
        return contact;
    }

    static CameraDevice findCamera(CameraDeviceRepository cameras, UUID patientId, UUID cameraId) {
        var camera = cameras.findById(cameraId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "카메라를 찾을 수 없습니다."));
        if (!patientId.equals(camera.getPatientId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "환자와 카메라가 일치하지 않습니다.");
        }
        return camera;
    }


    static CaregiverLink findCaregiverLink(
        CaregiverLinkRepository caregiverLinks,
        UUID patientId,
        UUID caregiverLinkId
    ) {
        var link = caregiverLinks.findById(caregiverLinkId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "보호자 연결을 찾을 수 없습니다."));
        if (!patientId.equals(link.getPatientId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "환자와 보호자 연결이 일치하지 않습니다.");
        }
        return link;
    }
}
