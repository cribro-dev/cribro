// Applied to the root project: uploads the publications of cribro-core and cribro-maven-plugin to
// Maven Central as one deployment, with ./gradlew publishAggregationToCentralPortal. The Gradle
// plugin goes to the Gradle Plugin Portal instead.
plugins {
    id("com.gradleup.nmcp.aggregation")
}

// The aggregation resolves the jar its tasks run from, com.gradleup.nmcp:nmcp-tasks, from here.
repositories {
    mavenCentral()
}

nmcpAggregation {
    centralPortal {
        // A token from https://central.sonatype.com/account, not the account's own password.
        username = providers.environmentVariable("CENTRAL_PORTAL_USERNAME")
        password = providers.environmentVariable("CENTRAL_PORTAL_PASSWORD")
        // USER_MANAGED stops the deployment at VALIDATED, until someone presses Publish in the
        // portal. The release workflow passes -PcentralPortalPublishingType=AUTOMATIC.
        publishingType = providers.gradleProperty("centralPortalPublishingType").orElse("USER_MANAGED")
    }
}

dependencies {
    nmcpAggregation(project(":cribro-core"))
    nmcpAggregation(project(":cribro-maven-plugin"))
}
