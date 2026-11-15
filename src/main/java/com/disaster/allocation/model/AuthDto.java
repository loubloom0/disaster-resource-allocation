package com.disaster.allocation.model;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDto {
    private AuthDto() { }

    public record RegisterInput(
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Email @Size(max = 160) String email,
            @NotBlank @Size(min = 12, max = 72) String password,
            @Size(min = 12, max = 72) String confirmPassword,
            @Size(max = 200) String contactInfo,
            @Pattern(regexp = "SYSTEM_ADMIN|DISPATCHER|ORGANIZATION_MANAGER|ORGANIZATION_MEMBER") String requestedRole,
            Long organizationId,
            @Size(max = 120) String requestedOrganizationName) { }

    public record LoginInput(
            @NotBlank @Email @Size(max = 160) String email,
            @NotBlank @Size(max = 72) String password) { }

    public record ChangePasswordInput(
            @NotBlank @Size(max = 72) String currentPassword,
            @NotBlank @Size(min = 12, max = 72) String newPassword,
            @NotBlank @Size(min = 12, max = 72) String confirmPassword) { }

    public record PublicUser(long id, String email, String fullName, String status,
                             String role, Long organizationId, boolean mustChangePassword) { }

    public record Session(String accessToken, String tokenType, long expiresIn,
                          PublicUser user) { }

    public record ApprovalInput(String organizationRole, Long organizationId, String reviewNote) { }

    public record NewOrganizationApprovalInput(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = "HOSPITAL|SHELTER|RESCUE_TEAM") String orgType,
            @NotBlank @Size(max = 120) String location,
            @Size(max = 200) String contactInfo,
            String organizationRole,
            @Size(max = 300) String reviewNote) { }

    public record OrganizationMatch(long id, String name, String orgType, String matchKind) { }

    public record RoleInput(String globalRole, String organizationRole, Long organizationId) { }

    public record UserSummary(long id, String email, String fullName, String status,
                              String globalRole, String organizationRole, Long organizationId,
                              Instant createdAt) { }

    public record OrganizationMemberSummary(long id, String email, String fullName, String accountStatus,
                                            String membershipStatus, String role, Instant createdAt) { }

    public record MemberStatusInput(@NotBlank @Pattern(regexp = "ACTIVE|DISABLED") String status) { }

    public record ApprovalSummary(long id, long userId, String email, String fullName, String contactInfo,
                                  Long requestedOrganizationId, String requestedOrganizationName,
                                  Long matchedOrganizationId, String requestedRole,
                                  List<OrganizationMatch> possibleMatches, Instant createdAt) { }

    public record Me(PublicUser user, List<String> authorities) { }
}
