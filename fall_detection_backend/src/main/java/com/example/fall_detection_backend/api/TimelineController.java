package com.example.fall_detection_backend.api;

import com.example.fall_detection_backend.domain.PatientRepository;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/patients/{patientId}")
public class TimelineController {
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;
    private final PatientRepository patients;
    public TimelineController(JdbcTemplate jdbc,PatientRepository patients) { this.jdbc=jdbc;this.patients=patients; }
    private void patient(UUID id) { if (!patients.existsById(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"환자를 찾을 수 없습니다."); }
    public record Entry(UUID id,String source,Instant occurredAt,String title,String memo) {}
    public record Section(String period,int count,List<Entry> entries) {}
    public record Day(LocalDate date,int count,String level) {}
    private List<Entry> entries(UUID patientId,LocalDate start,LocalDate end) {
        patient(patientId);
        var from=java.sql.Timestamp.from(start.atStartOfDay(ZONE).toInstant());
        var to=java.sql.Timestamp.from(end.atStartOfDay(ZONE).toInstant());
        return jdbc.query("""
            select id, 'AI' as source, occurred_at, kind::text as title, null::text as memo
            from detection_events where patient_id=? and occurred_at>=? and occurred_at<?
            union all
            select id, 'MANUAL' as source, recorded_at, title, memo
            from care_records where patient_id=? and recorded_at>=? and recorded_at<?
            order by occurred_at desc, id
            """, (rs,n)->new Entry(rs.getObject("id",UUID.class),rs.getString("source"),rs.getTimestamp("occurred_at").toInstant(),rs.getString("title"),rs.getString("memo")),patientId,from,to,patientId,from,to);
    }
    private String period(Instant time) {
        int hour=time.atZone(ZONE).getHour();
        return hour<6?"DAWN":hour<12?"MORNING":hour<18?"AFTERNOON":"EVENING";
    }
    @GetMapping("/timeline")
    public List<Section> timeline(@PathVariable UUID patientId,@RequestParam LocalDate date) {
        var rows=entries(patientId,date,date.plusDays(1));
        return List.of("EVENING","AFTERNOON","MORNING","DAWN").stream().map(p->{
            var group=rows.stream().filter(r->period(r.occurredAt()).equals(p)).toList();
            return new Section(p,group.size(),group);
        }).toList();
    }
    @GetMapping("/calendar")
    public List<Day> calendar(@PathVariable UUID patientId,@RequestParam String month) {
        final YearMonth m;
        try { m=YearMonth.parse(month); } catch (DateTimeException e) {throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"월은 yyyy-MM 형식으로 입력해주세요.");}
        var counts=new HashMap<LocalDate,Integer>();
        for(var row:entries(patientId,m.atDay(1),m.plusMonths(1).atDay(1))) counts.merge(row.occurredAt().atZone(ZONE).toLocalDate(),1,Integer::sum);
        var result=new ArrayList<Day>();
        for(int day=1;day<=m.lengthOfMonth();day++) {
            var date=m.atDay(day);int count=counts.getOrDefault(date,0);
            result.add(new Day(date,count,count==0?"NONE":count<=2?"LOW":count<=5?"MEDIUM":"HIGH"));
        }
        return result;
    }
}
