package com.salomon.intranet.auth;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.*;
import java.time.Instant;
import java.util.*;

final class StaffJwt {
    static final int ACCESS_SECONDS=900;
    private static final String ISSUER="urn:salomon:intranet";
    private static final String AUDIENCE="salomon-intra-api";
    private static final String KID="salomon-staff-v1";
    private final JdbcTemplate jdbc;
    private volatile KeyPair keys;
    StaffJwt(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private synchronized KeyPair keyPair(){
        if(keys!=null)return keys;
        try{
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM intra.staff_jwt_key WHERE id=1)",Boolean.class))){
                var generator=KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                var candidate=generator.generateKeyPair();
                jdbc.update("INSERT INTO intra.staff_jwt_key(id,private_key,public_key) VALUES(1,?,?) ON CONFLICT(id) DO NOTHING",
                    candidate.getPrivate().getEncoded(),candidate.getPublic().getEncoded());
            }
            keys=jdbc.queryForObject("SELECT private_key,public_key FROM intra.staff_jwt_key WHERE id=1",(rs,i)->{
                try{
                    var factory=KeyFactory.getInstance("RSA");
                    return new KeyPair(factory.generatePublic(new X509EncodedKeySpec(rs.getBytes(2))),
                        factory.generatePrivate(new PKCS8EncodedKeySpec(rs.getBytes(1))));
                }catch(GeneralSecurityException e){throw new IllegalStateException("Invalid signing key",e);}
            });
            return Objects.requireNonNull(keys);
        }catch(GeneralSecurityException e){throw new IllegalStateException("JWT signing unavailable",e);}
    }
    String issue(String sid,StaffAuthController.StaffUser user){
        try{
            var now=Instant.now();
            var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(new JOSEObjectType("at+jwt")).keyID(KID).build(),
                new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE).subject(Long.toString(user.id()))
                    .claim("sid",sid).jwtID(UUID.randomUUID().toString()).issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(ACCESS_SECONDS))).build());
            jwt.sign(new RSASSASigner((RSAPrivateKey)keyPair().getPrivate()));
            return jwt.serialize();
        }catch(JOSEException e){throw new IllegalStateException("JWT signing unavailable",e);}
    }
    record Claims(String sid,long userId){}
    Claims verify(String token){
        try{
            var jwt=SignedJWT.parse(token);
            var header=jwt.getHeader();
            if(!JWSAlgorithm.RS256.equals(header.getAlgorithm())||!KID.equals(header.getKeyID())||
                !new JOSEObjectType("at+jwt").equals(header.getType())||
                !jwt.verify(new RSASSAVerifier((RSAPublicKey)keyPair().getPublic())))throw invalid();
            var c=jwt.getJWTClaimsSet();
            var now=Instant.now();
            if(!ISSUER.equals(c.getIssuer())||!List.of(AUDIENCE).equals(c.getAudience())||
                c.getExpirationTime()==null||!c.getExpirationTime().toInstant().isAfter(now)||
                c.getIssueTime()==null||c.getIssueTime().toInstant().isAfter(now.plusSeconds(30))||
                c.getExpirationTime().toInstant().isAfter(c.getIssueTime().toInstant().plusSeconds(ACCESS_SECONDS))||
                (c.getNotBeforeTime()!=null&&c.getNotBeforeTime().toInstant().isAfter(now)))throw invalid();
            String sid=c.getStringClaim("sid");
            long id=Long.parseLong(c.getSubject());
            if(sid==null||!sid.matches("[a-f0-9]{64}")||id<=0)throw invalid();
            return new Claims(sid,id);
        }catch(java.text.ParseException|JOSEException|NumberFormatException e){throw invalid();}
    }
    Map<String,Object> jwks(){
        var key=new RSAKey.Builder((RSAPublicKey)keyPair().getPublic()).keyID(KID).algorithm(JWSAlgorithm.RS256).build();
        return Map.of("keys",List.of(key.toJSONObject()));
    }
    private static ResponseStatusException invalid(){return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sesión no válida");}
}
