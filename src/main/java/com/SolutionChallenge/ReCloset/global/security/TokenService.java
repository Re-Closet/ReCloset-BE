package com.SolutionChallenge.ReCloset.global.security;

import com.SolutionChallenge.ReCloset.app.domain.RoleType;
import com.SolutionChallenge.ReCloset.app.domain.User;
import com.SolutionChallenge.ReCloset.global.exception.model.CustomException;
import com.SolutionChallenge.ReCloset.app.repository.UserRepository;
import com.SolutionChallenge.ReCloset.global.exception.ErrorCode;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import javax.crypto.SecretKey;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.security.Key;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

import static javax.crypto.Cipher.SECRET_KEY;

@Component
@Getter
@Slf4j
public class TokenService {

    private final Key key;
    private final long accessTokenValidityTime;
    private final long refreshTokenValidityTime;
    private final UserRepository userRepository;

    @Value("${jwt.access.header}")
    private String accessHeader;

    @Value("${jwt.refresh.header}")
    private String refreshHeader;

    @Value("${jwt.secret}")
    private String secretKey;

    private static final String BEARER = "Bearer ";
    private static final String ACCESS_TOKEN_SUBJECT = "AccessToken";
    private static final String REFRESH_TOKEN_SUBJECT = "RefreshToken";
    private static final String EMAIL_CLAIM = "email";
    /**
     * 생성자: JWT 키 및 만료 시간 설정
     */
    public TokenService(UserRepository userRepository,
                        @Value("${jwt.access.expiration}") long accessTokenValidityTime,
                        @Value("${jwt.refresh.expiration}") long refreshTokenValidityTime,
                        @Value("${jwt.secret}") String secretKey) {
        byte[] keyBytes = Decoders.BASE64.decode(secretKey);
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.accessTokenValidityTime = accessTokenValidityTime;
        this.refreshTokenValidityTime = refreshTokenValidityTime;
        this.userRepository = userRepository;
    }
    /**
     * Access Token 생성
     */
    public String createAccessToken(String email) {
        Date expirationTime = new Date(System.currentTimeMillis() + accessTokenValidityTime);

        return Jwts.builder()
                .subject(ACCESS_TOKEN_SUBJECT)
                .claim(EMAIL_CLAIM, email)
                .issuedAt(new Date())
                .expiration(expirationTime)
                .signWith(key)
                .compact();
    }
    /**
     * Refresh Token 생성
     */
    public String createRefreshToken() {
        Date expirationTime = new Date(System.currentTimeMillis() + refreshTokenValidityTime);

        return Jwts.builder()
                .subject(REFRESH_TOKEN_SUBJECT)
                .issuedAt(new Date())
                .expiration(expirationTime)
                .signWith(key)
                .compact();
    }
    /**
     * 토큰 검증 (유효성 및 만료 체크)
     */
    public boolean validateToken(String token) {
        try {
            // Google accessToken인지 확인
            if (token.startsWith("ya29.")) {
                log.info("[Google 토큰 검증] Google accessToken 감지됨, 별도 검증 없이 통과");
                return true; // Google accessToken은 별도 검증 불필요
            }
            Jwts.parser()
                    .verifyWith((SecretKey) key)
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.error("[토큰 만료] {}", e.getMessage());
            throw new CustomException(ErrorCode.EXPIRED_TOKEN_EXCEPTION, "토큰이 만료되었습니다.");
        } catch (JwtException | IllegalArgumentException e) {
            log.error("[토큰 검증 실패] {}", e.getMessage());
            throw new CustomException(ErrorCode.INVALID_TOKEN_EXCEPTION, "유효하지 않은 토큰입니다.");
        }
    }
    /**
     * 토큰에서 사용자 이메일 추출
     */
    public Optional<String> extractEmail(String token) {
        try {
            // Google accessToken인지 확인
            if (token.startsWith("ya29.")) {
                log.info("[Google 토큰] Google accessToken 감지됨, UserInfo API에서 이메일 가져오기");
                String email = getEmailFromGoogleToken(token);
                return Optional.ofNullable(email);
            }

            Claims claims = Jwts.parser()
                    .verifyWith((SecretKey) key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            return Optional.ofNullable(claims.get(EMAIL_CLAIM, String.class));
        } catch (ExpiredJwtException e) {
            log.error("[토큰 만료] 이메일 추출 불가: {}", e.getMessage());
            throw new CustomException(ErrorCode.EXPIRED_TOKEN_EXCEPTION, "토큰이 만료되었습니다.");
        } catch (JwtException e) {
            log.error("[토큰 검증 실패] 이메일 추출 불가: {}", e.getMessage());
            throw new CustomException(ErrorCode.INVALID_TOKEN_EXCEPTION, "유효하지 않은 토큰입니다.");
        }
    }

    public Optional<RoleType> extractRole(String token) {
        try {
            // JWT 토큰에서 claims 추출
            Claims claims = Jwts.parser()
                    .setSigningKey(secretKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            // EMAIL_CLAIM에서 이메일 추출
            String email = claims.get(EMAIL_CLAIM, String.class);

            if (email == null || email.isEmpty()) {
                log.error("JWT Token does not contain a valid email.");
                return Optional.empty();
            }

            log.debug("Extracted email from JWT Token: {}", email);

            // 이메일로 사용자 조회
            User user = userRepository.findByEmail(email).orElseThrow(() -> {
                log.error("User not found for email: {}", email);
                return new RuntimeException("User not found");
            });

            log.debug("User found: {} with role {}", user.getEmail(), user.getRoleType());

            // 사용자 역할 반환
            return Optional.of(user.getRoleType());

        } catch (Exception e) {
            log.error("Error extracting role from token", e);
            return Optional.empty();
        }
    }




    // Google OAuth2 토큰 검증 후 사용자 정보 추출
    public String getEmailFromGoogleToken(String accessToken) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + accessToken);

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<Map> response = restTemplate.exchange(
                    "https://www.googleapis.com/oauth2/v2/userinfo",
                    HttpMethod.GET,
                    entity,
                    Map.class
            );

            Map<String, Object> userInfo = response.getBody();
            return userInfo != null ? (String) userInfo.get("email") : null;
        } catch (Exception e) {
            log.error("Google UserInfo API 오류: {}", e.getMessage());
            return null;
        }
    }

    // Google 액세스 토큰을 검증하고 JWT 토큰을 반환
    public String authenticateWithGoogle(String accessToken) {
        // Google 액세스 토큰에서 이메일 가져오기
        String email = getEmailFromGoogleToken(accessToken);
        if (email == null) {
            throw new RuntimeException("유효하지 않은 Google 토큰입니다.");
        }

        // 이메일로 사용자 정보 조회
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        // JWT 토큰 생성
        return createAccessToken(user.getEmail());
    }

    /**
     * Refresh Token을 DB에 저장
     */
    @Transactional
    public void updateRefreshToken(String email, String refreshToken) {
        userRepository.findByEmail(email)
                .ifPresentOrElse(
                        user -> {
                            user.updateRefreshToken(refreshToken);
                            log.info("리프레시 토큰 저장 완료 (이메일: {})", email);
                        },
                        () -> {
                            log.error("[토큰 업데이트 실패] 유저를 찾을 수 없음 (이메일: {})", email);
                            throw new CustomException(ErrorCode.NOT_FOUND_USER_EXCEPTION,
                                    ErrorCode.NOT_FOUND_USER_EXCEPTION.getMessage());
                        }
                );
    }
    /**
     * HTTP 요청에서 AccessToken 추출
     */
    public Optional<String> extractAccessToken(HttpServletRequest request) {
        return Optional.ofNullable(request.getHeader(accessHeader))
                .filter(accessToken -> accessToken.startsWith(BEARER))
                .map(accessToken -> accessToken.replace(BEARER, ""));
    }
    /**
     * HTTP 요청에서 RefreshToken 추출
     */
    public Optional<String> extractRefreshToken(HttpServletRequest request) {
        return Optional.ofNullable(request.getHeader(refreshHeader))
                .filter(refreshToken -> refreshToken.startsWith(BEARER))
                .map(refreshToken -> refreshToken.replace(BEARER, ""));
    }
    /**
     * AccessToken을 HTTP 응답 헤더에 추가
     */
    @Transactional
    public void sendAccessToken(HttpServletResponse response, String accessToken) {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setHeader(accessHeader, BEARER + accessToken);
        log.info("[AccessToken 발급] {}", accessToken);
    }
    /**
     * AccessToken & RefreshToken을 HTTP 응답 헤더에 추가
     */
    @Transactional
    public void sendAccessAndRefreshToken(HttpServletResponse response,
                                          String accessToken, String refreshToken) {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setHeader(accessHeader, BEARER + accessToken);
        response.setHeader(refreshHeader, BEARER + refreshToken);
        log.info("[AccessToken & RefreshToken 발급 완료]");
    }
    /**
     * logout 을 위한 R토큰 제거
     */
    @Transactional
    public void removeRefreshToken(String email) {
        userRepository.findByEmail(email)
                .ifPresent(user -> user.updateRefreshToken(null));
    }
}
