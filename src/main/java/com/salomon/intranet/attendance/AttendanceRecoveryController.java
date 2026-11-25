package com.salomon.intranet.attendance;
import com.salomon.intranet.auth.StaffAuthController;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/attendance/recovery")
public class AttendanceRecoveryController {
 private final JdbcTemplate jdbc;
 private final StaffAuthController auth;
 private final RestClient http;
 private final String token,base;
 private final boolean enabled;
 public AttendanceRecoveryController(JdbcTemplate jdbc,StaffAuthController auth,
   @Value("${MARCACIONES_CENTRAL_TOKEN:}") String token,
   @Value("${MARCACIONES_CENTRAL_ENABLED:false}") boolean enabled,
   @Value("${MARCACIONES_CENTRAL_URL:http://marcaciones-central-api:8080}") String base){
  this.jdbc=jdbc;this.auth=auth;this.token=token;this.enabled=enabled;this.base=base.replaceAll("/+$","");
  var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
  factory.setReadTimeout(Duration.ofSeconds(7));http=RestClient.builder().requestFactory(factory).build();
 }
 private String serial(String authorization,long deviceId){
  if(!"SA".equals(auth.me(authorization).role()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  if(!enabled||token.isBlank())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Modo central no habilitado");
  var rows=jdbc.query("SELECT serial_number FROM intra.biometric_devices WHERE id=? AND active",(r,i)->r.getString(1),deviceId);
  if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Equipo no registrado o inactivo");
  return rows.get(0);
 }
 public record Request(LocalDate from,LocalDate to){}
 @GetMapping(produces=MediaType.APPLICATION_JSON_VALUE)
 public String list(@RequestHeader(value="Authorization",required=false) String authorization,@RequestParam long deviceId){
  String sn=serial(authorization,deviceId);
  try{return http.get().uri(base+"/internal/recovery?company=SALOMON&serial={sn}",sn).header("X-Central-Token",token).retrieve().body(String.class);}
  catch(RestClientResponseException ex){throw new ResponseStatusException(HttpStatus.valueOf(ex.getStatusCode().value()),"No se pudo consultar recuperación");}
  catch(Exception ex){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"API central no disponible");}
 }
 @PostMapping(produces=MediaType.APPLICATION_JSON_VALUE)
 public String request(@RequestHeader(value="Authorization",required=false) String authorization,@RequestParam long deviceId,@RequestBody Request request){
  String sn=serial(authorization,deviceId);
  try{return http.post().uri(base+"/internal/recovery?company=SALOMON&serial={sn}",sn).header("X-Central-Token",token).contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(String.class);}
  catch(RestClientResponseException ex){throw new ResponseStatusException(HttpStatus.valueOf(ex.getStatusCode().value()),"No se pudo solicitar recuperación");}
  catch(Exception ex){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"API central no disponible");}
 }
}
