// Signs every publication of a release build (-Prelease); a snapshot is published unsigned.
plugins {
    `maven-publish`
    signing
}

if (!version.toString().endsWith("-SNAPSHOT")) {
    signing {
        // The release workflow passes the key in these variables. Without them the gpg command
        // signs, with the key that gpg.conf selects, as for a release from a developer machine.
        val key = providers.environmentVariable("SIGNING_PGP_PRIVATE_KEY")
        if (key.isPresent) {
            useInMemoryPgpKeys(key.get(), providers.environmentVariable("SIGNING_PGP_PASSPHRASE").orNull)
        } else {
            useGpgCmd()
        }
        sign(publishing.publications)
    }
}
