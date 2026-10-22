package io.github.burpmcp.extension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Owner-only file hardening shared by the session descriptor and the history index. */
final class FilePermissions {
    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private FilePermissions() {
    }

    /** Fails closed with {@link IOException} when the platform offers no mechanism. */
    static void restrictToCurrentUser(Path target) throws IOException {
        if (Files.getFileStore(target).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(target, OWNER_ONLY);
            return;
        }
        AclFileAttributeView view = Files.getFileAttributeView(target, AclFileAttributeView.class);
        if (view == null) {
            throw new IOException("Cannot enforce owner-only permissions on " + target.getFileName());
        }
        UserPrincipal owner = Files.getOwner(target);
        if (owner == null) {
            UserPrincipalLookupService lookup = target.getFileSystem().getUserPrincipalLookupService();
            owner = lookup.lookupPrincipalByName(System.getProperty("user.name"));
        }
        AclEntry ownerEntry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build();
        view.setAcl(List.of(ownerEntry));
    }

    /** Best-effort variant for disposable caches: a failure must never take down the bridge. */
    static void restrictToCurrentUserBestEffort(Path target) {
        try {
            restrictToCurrentUser(target);
        } catch (IOException | RuntimeException ignored) {
            // The file stays usable; hardening is defence in depth for caches.
        }
    }
}
