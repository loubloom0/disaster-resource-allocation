package com.disaster.allocation.controller;

import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.disaster.allocation.model.AuthDto.LoginInput;
import com.disaster.allocation.model.AuthDto.ChangePasswordInput;
import com.disaster.allocation.model.AuthDto.Me;
import com.disaster.allocation.model.AuthDto.OrganizationMatch;
import com.disaster.allocation.model.AuthDto.RegisterInput;
import com.disaster.allocation.service.AuthService;
import com.disaster.allocation.service.AuthService.Issued;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final boolean secureCookie;
    private final long refreshDays;

    public AuthController(AuthService auth,
                          @Value("${dras.auth.cookie-secure:false}") boolean secureCookie,
                          @Value("${dras.auth.refresh-token-days:14}") long refreshDays) {
        this.auth = auth;
        this.secureCookie = secureCookie;
        this.refreshDays = refreshDays;
    }

    @GetMapping("/organizations")
    public java.util.List<Map<String, Object>> organizations() { return auth.organizationsForRegistration(); }

    @GetMapping("/organization-matches")
    public java.util.List<OrganizationMatch> organizationMatches(@org.springframework.web.bind.annotation.RequestParam String name) {
        return auth.organizationMatches(name);
    }

    @PostMapping("/register")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> register(@Valid @RequestBody RegisterInput input) {
        auth.register(input);
        return Map.of("status", "PENDING_APPROVAL", "message", "Registration submitted for administrator approval");
    }

    @PostMapping("/login")
    public com.disaster.allocation.model.AuthDto.Session login(@Valid @RequestBody LoginInput input,
                                                                HttpServletResponse response) {
        Issued issued = auth.login(input.email(), input.password());
        setRefreshCookie(response, issued.refreshToken());
        return issued.session();
    }

    @PostMapping("/refresh")
    public com.disaster.allocation.model.AuthDto.Session refresh(jakarta.servlet.http.HttpServletRequest request,
                                                                  HttpServletResponse response) {
        String token = cookie(request, "dras_refresh");
        Issued issued = auth.refresh(token);
        setRefreshCookie(response, issued.refreshToken());
        return issued.session();
    }

    @PostMapping("/change-password")
    public com.disaster.allocation.model.AuthDto.Session changePassword(@Valid @RequestBody ChangePasswordInput input,
            @AuthenticationPrincipal Jwt jwt, HttpServletResponse response) {
        Issued issued = auth.changePassword(Long.parseLong(jwt.getSubject()), input);
        setRefreshCookie(response, issued.refreshToken());
        return issued.session();
    }

    @PostMapping("/logout")
    public Map<String, String> logout(jakarta.servlet.http.HttpServletRequest request,
                                      HttpServletResponse response,
                                      @AuthenticationPrincipal Jwt jwt) {
        auth.logout(cookie(request, "dras_refresh"), jwt == null ? null : Long.valueOf(jwt.getSubject()));
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("").maxAge(java.time.Duration.ZERO).build().toString());
        return Map.of("status", "OK");
    }

    @GetMapping("/me")
    public Me me(@AuthenticationPrincipal Jwt jwt) {
        long id = Long.parseLong(jwt.getSubject());
        return new Me(auth.me(id), auth.rolesFor(id));
    }

    private void setRefreshCookie(HttpServletResponse response, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(token).maxAge(java.time.Duration.ofDays(refreshDays)).build().toString());
    }

    private ResponseCookie.ResponseCookieBuilder cookie(String value) {
        return ResponseCookie.from("dras_refresh", value).httpOnly(true).secure(secureCookie).sameSite("Strict")
                .path("/api/auth");
    }

    private static String cookie(jakarta.servlet.http.HttpServletRequest request, String name) {
        if (request == null || request.getCookies() == null) return null;
        for (jakarta.servlet.http.Cookie c : request.getCookies()) if (name.equals(c.getName())) return c.getValue();
        return null;
    }
}
