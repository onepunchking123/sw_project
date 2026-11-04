package com.example.fall_detection_backend;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.URI;
import java.net.http.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named="CAREGUARD_DISPOSABLE_DB",matches="true")
class IncidentApiIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    private final HttpClient client=HttpClient.newHttpClient();
    private HttpResponse<String> call(String method,String path,String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
            .header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode json(HttpResponse<String> response) throws Exception { return JsonMapper.builder().build().readTree(response.body()); }
    @Test void responseTransitionsPreserveTimesAndResolutionPersists() throws Exception {
        var patient=UUID.randomUUID();var user=UUID.randomUUID();var camera=UUID.randomUUID();var event=UUID.randomUUID();
        jdbc.update("insert into patients(id,name,age) values (?,'환자',80)",patient);
        jdbc.update("insert into users(id,email,password_hash,name) values (?,?,'!','보호자')",user,user+"@example.com");
        jdbc.update("insert into caregiver_links(user_id,patient_id,relation) values (?,?,'CHILD')",user,patient);
        jdbc.update("insert into camera_devices(id,patient_id,device_token) values (?,?,?)",camera,patient,camera.toString());
        jdbc.update("insert into detection_events(id,client_event_id,patient_id,camera_device_id,occurred_at,kind,rule,state,prob_normal,prob_fall,prob_fallen) values (?,?,?,?,now(),'FALLEN_IMMOBILE','R1','FALLEN',0,0.2,0.8)",event,UUID.randomUUID(),patient,camera);
        String input="""
            {"detectionEventId":"%s","startedAt":"2026-10-02T09:00:00Z","countdownEndsAt":"2026-10-02T09:00:10Z"}
            """.formatted(event);
        var created=call("POST","/api/patients/"+patient+"/incidents",input);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(call("POST","/api/patients/"+patient+"/incidents",input).statusCode()).isEqualTo(409);
        String incident=json(created).get("id").asText();
        var response=call("POST","/api/incidents/"+incident+"/responses","{\"userId\":\""+user+"\",\"status\":\"ACKNOWLEDGED\"}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        String id=json(response).get("id").asText();String ack=json(response).get("acknowledgedAt").asText();
        String path="/api/incidents/"+incident+"/responses/"+id;
        var responding=call("PUT",path,"{\"status\":\"RESPONDING\",\"note\":\"방문\"}");
        assertThat(responding.statusCode()).isEqualTo(200);
        assertThat(json(responding).get("acknowledgedAt").asText()).isEqualTo(ack);
        assertThat(json(responding).get("respondingAt").isNull()).isFalse();
        assertThat(call("PUT",path,"{\"status\":\"NOTIFIED\"}").statusCode()).isEqualTo(400);
        var resolved=call("PATCH","/api/incidents/"+incident+"/resolution","{\"resolution\":\"CAREGIVER_OK\",\"resolvedByUserId\":\""+user+"\",\"resolutionNote\":\"확인\"}");
        assertThat(resolved.statusCode()).as(resolved.body()).isEqualTo(200);
        assertThat(json(resolved).get("resolvedAt").isNull()).isFalse();
        assertThat(call("DELETE","/api/incidents/"+incident,"").statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("select count(*) from incident_responses where incident_id=?",Integer.class,UUID.fromString(incident))).isZero();
    }
}
