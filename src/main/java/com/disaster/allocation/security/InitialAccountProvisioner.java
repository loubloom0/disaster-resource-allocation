package com.disaster.allocation.security;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.disaster.allocation.repository.AuthRepository;

/** One-time, local bootstrap for the initial global roles. Temporary passwords are never logged. */
@Component
@Profile("!test & !auth-test")
public class InitialAccountProvisioner implements ApplicationRunner {
    private static final String ADMIN_EMAIL = "admin@dras.local";
    private static final String DISPATCHER_EMAIL = "dispatcher@dras.local";
    private static final String ADMIN_NAME = "DRAS System Administrator";
    private static final String DISPATCHER_NAME = "DRAS Dispatcher";

    private final JdbcTemplate jdbc;
    private final AuthRepository users;
    private final PasswordEncoder passwords;
    private final TransactionTemplate transaction;
    private final Path credentialFile;
    private final SecureRandom random = new SecureRandom();

    public InitialAccountProvisioner(JdbcTemplate jdbc, AuthRepository users, PasswordEncoder passwords,
            PlatformTransactionManager transactionManager,
            @Value("${dras.auth.bootstrap.credentials-file:${user.home}/.config/dras/initial-accounts.txt}") String credentialFile) {
        this.jdbc = jdbc;
        this.users = users;
        this.passwords = passwords;
        this.transaction = new TransactionTemplate(transactionManager);
        this.credentialFile = Path.of(credentialFile).toAbsolutePath().normalize();
    }

    @Override
    public void run(ApplicationArguments args) {
        final boolean[] createdFile = { false };
        try {
            ProvisioningResult result = transaction.execute(status -> {
                jdbc.execute("LOCK TABLE app_users IN EXCLUSIVE MODE");
                jdbc.execute("LOCK TABLE auth_bootstrap_state IN EXCLUSIVE MODE");
                if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM auth_bootstrap_state WHERE singleton=true)", Boolean.class))) {
                    return new ProvisioningResult(0, true);
                }

                boolean adminExists = hasGlobalRole("SYSTEM_ADMIN");
                boolean dispatcherExists = hasGlobalRole("DISPATCHER");
                String adminPassword = adminExists ? null : randomPassword();
                String dispatcherPassword = dispatcherExists ? null : randomPassword();

                if (!adminExists && users.emailExists(ADMIN_EMAIL)) {
                    throw new IllegalStateException("Initial administrator email is already assigned to a non-administrator account; resolve it before bootstrap.");
                }
                if (!dispatcherExists && users.emailExists(DISPATCHER_EMAIL)) {
                    throw new IllegalStateException("Initial dispatcher email is already assigned to a non-dispatcher account; resolve it before bootstrap.");
                }

                int accountsToCreate = (adminExists ? 0 : 1) + (dispatcherExists ? 0 : 1);
                if (accountsToCreate > 0) {
                    if (Files.exists(credentialFile)) {
                        throw new IllegalStateException("The initial credential file already exists; refusing to overwrite it or create unrecorded accounts.");
                    }
                    writeCredentialFile(adminPassword, dispatcherPassword);
                    createdFile[0] = true;
                    if (!adminExists) users.insertInitialGlobalAccount(ADMIN_EMAIL, passwords.encode(adminPassword), ADMIN_NAME, "SYSTEM_ADMIN");
                    if (!dispatcherExists) users.insertInitialGlobalAccount(DISPATCHER_EMAIL, passwords.encode(dispatcherPassword), DISPATCHER_NAME, "DISPATCHER");
                    if (!adminExists) users.audit(null, "INITIAL_SYSTEM_ADMIN_PROVISIONED", "USER", ADMIN_EMAIL, "{}");
                    if (!dispatcherExists) users.audit(null, "INITIAL_DISPATCHER_PROVISIONED", "USER", DISPATCHER_EMAIL, "{}");
                }
                jdbc.update("INSERT INTO auth_bootstrap_state(singleton,initialized_at) VALUES (true,now())");
                return new ProvisioningResult(accountsToCreate, false);
            });
            if (result != null && result.createdAccounts() > 0) {
                System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,
                        "Initial privileged accounts were provisioned. One-time credentials were written to the owner-only local credential file; change both passwords at first sign-in.");
            }
        } catch (RuntimeException failure) {
            if (createdFile[0]) {
                try { Files.deleteIfExists(credentialFile); }
                catch (Exception cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            }
            throw failure;
        }
    }

    private boolean hasGlobalRole(String role) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM app_users WHERE global_role=?)", Boolean.class, role));
    }

    private String randomPassword() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void writeCredentialFile(String adminPassword, String dispatcherPassword) {
        try {
            Path parent = credentialFile.getParent();
            Files.createDirectories(parent);
            Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"));
            StringBuilder contents = new StringBuilder("DRAS initial one-time sign-in credentials\n")
                    .append("Change each temporary password at the first sign-in. Do not share this file.\n\n");
            if (adminPassword != null) {
                contents.append("SYSTEM_ADMIN\nLogin: ").append(ADMIN_EMAIL).append("\nTemporary password: ")
                        .append(adminPassword).append("\n\n");
            }
            if (dispatcherPassword != null) {
                contents.append("DISPATCHER\nLogin: ").append(DISPATCHER_EMAIL).append("\nTemporary password: ")
                        .append(dispatcherPassword).append('\n');
            }
            Files.createFile(credentialFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(credentialFile, contents, StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(credentialFile, PosixFilePermissions.fromString("rw-------"));
        } catch (Exception e) {
            try { Files.deleteIfExists(credentialFile); }
            catch (Exception cleanupFailure) { e.addSuppressed(cleanupFailure); }
            throw new IllegalStateException("Could not safely create the owner-only initial credential file at " + credentialFile, e);
        }
    }

    private record ProvisioningResult(int createdAccounts, boolean previouslyInitialized) { }
}
