# The Migration to Central Portal Guide

## Important Notice

**OSSRH shut down on June 30, 2025!** All publishing to Maven Central must migrate to the new Central Portal.

## The Change at a Glance

### The Old Way (OSSRH - deprecated)

```
developer → s01.oss.sonatype.org (OSSRH) → manual Close/Release → Maven Central
```

Characteristics:
- [An account registered on JIRA was required]
- [Manual operation through the Nexus UI]
- [Manual Close and Release required]
- [Complex configuration]

### The New Way (Central Portal - current)

```
developer → central.sonatype.com (Portal) → automatic validation → one-click Publish → Maven Central
```

Characteristics:
- [Sign in with a GitHub account]
- [A modern web UI]
- [Automatic validation, one-click publishing]
- [Simple configuration]

## Migration Steps

### Step 1: Register a Central Portal Account

1. Visit https://central.sonatype.com
2. Click **Sign in with GitHub**
3. Authorize the GitHub OAuth

### Step 2: Verify Namespace Ownership

If you previously had a namespace on OSSRH:

1. Sign in to the Central Portal
2. Open **Namespaces**
3. Click **Migrate Namespace**
4. Follow the prompts to finish the migration

For a new namespace:

1. Apply for the namespace in the Central Portal
2. Verify ownership as required (a GitHub repository or domain DNS)

### Step 3: Generate a User Token

1. Sign in to the Central Portal
2. Click the avatar at the top right → **View Account**
3. Click **Generate User Token**
4. **Save the Username and Token immediately** (they are shown only once!)

### Step 4: Update Local Configuration

Edit `~/.gradle/gradle.properties`:

Remove the old entries:
```properties
# ❌ remove these
ossrhUsername=...
ossrhPassword=...
```

Add the new entries:
```properties
# ✅ add these (note: the Central Portal receives the User Token in the password field)
centralPortalUsername=YOUR_USERNAME_FROM_TOKEN_PAGE
centralPortalPassword=YOUR_USER_TOKEN_STRING
```

### Step 5: Update build.gradle

**Old configuration** (remove):
```groovy
repositories {
    maven {
        name = "OSSRH"
        url = "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
        credentials {
            username = project.findProperty("ossrhUsername")
            password = project.findProperty("ossrhPassword")
        }
    }
}
```

**New configuration** (use):
```groovy
repositories {
    maven {
        name = "CentralPortal"
        url = "https://central.sonatype.com/api/v1/publisher/upload"
        credentials {
            username = project.findProperty("centralPortalUsername")
            password = project.findProperty("centralPortalPassword")
        }
    }
}
```

### Step 6: Update GitHub Secrets

In the GitHub repository settings:

Remove the old secrets:
- [`OSSRH_USERNAME`]
- [`OSSRH_PASSWORD`]

Add the new secrets:
- [`CENTRAL_PORTAL_USERNAME` (the username)]
- [`CENTRAL_PORTAL_TOKEN` (the User Token; mapped to PASSWORD in the workflow)]

### Step 7: Update the GitHub Actions Workflow

**Old configuration** (remove):
```yaml
env:
  OSSRH_USERNAME: ${{ secrets.OSSRH_USERNAME }}
  OSSRH_PASSWORD: ${{ secrets.OSSRH_PASSWORD }}
```

**New configuration** (use):
```yaml
env:
  CENTRAL_PORTAL_USERNAME: ${{ secrets.CENTRAL_PORTAL_USERNAME }}
  CENTRAL_PORTAL_PASSWORD: ${{ secrets.CENTRAL_PORTAL_TOKEN }}
```

> Note: the Vanniktech official plugin reads `CENTRAL_PORTAL_USERNAME` and `CENTRAL_PORTAL_PASSWORD` in CENTRAL_PORTAL mode (or the Gradle properties `centralPortalUsername` / `centralPortalPassword`). The User Token is passed as the "password"; do not name the variable `CENTRAL_PORTAL_TOKEN`, or publishing fails with 404.

### Step 8: Test the Publishing

```bash
# 1. test locally
./gradlew publishToMavenLocal

# 2. publish to the Central Portal (the plugin task is recommended)
./gradlew -Pversion=0.1.0 publishAllPublicationsToMavenCentralRepository

# 3. inspect it in the Central Portal
# visit https://central.sonatype.com
# Publishing → Deployments
```

## How the Publishing Flow Changed

### The Old Flow (OSSRH)

1. Upload with `./gradlew -Pversion=0.1.0 publishAllPublicationsToMavenCentralRepository`
2. Sign in at https://s01.oss.sonatype.org
3. Find the repository under Staging Repositories
4. Click **Close** manually
5. Wait for validation (1-2 minutes)
6. Click **Release** manually
7. Wait for sync to Maven Central (10-30 minutes)

### The New Flow (Central Portal)

1. Upload with `./gradlew -Pversion=0.1.0 publishAllPublicationsToMavenCentralRepository`
2. Sign in at https://central.sonatype.com
3. Check the status under **Publishing** → **Deployments**
4. Once the status is **VALIDATED**, click **Publish**
5. Wait for sync to Maven Central (10-30 minutes)

What got simpler:
- [No more Close operation]
- [Automatic validation, faster]
- [A friendlier UI]

## FAQ

### Q1: What happens to my old OSSRH account?

A: The old account remains valid, for migrating namespaces. After migration it can be retired.

### Q2: Are already-published old versions affected?

A: No. Versions already published to Maven Central remain valid forever.

### Q3: How do SNAPSHOT versions publish?

A: SNAPSHOT versions still go to the snapshots repository:
```
https://s01.oss.sonatype.org/content/repositories/snapshots/
```

### Q4: Do GPG keys need re-verification?

A: No. The GPG signing configuration is unchanged.

### Q5: How soon after migration can I publish?

A: Immediately after the namespace migration — no waiting.

## Verifying the Migration

### 1. Check the Central Portal

Sign in at https://central.sonatype.com and confirm:
- [the namespace is listed]
- [a User Token can be generated]
- [Deployments are visible]

### 2. Test a local publish

```bash
./gradlew publishToMavenLocal
```

It should succeed without errors.

### 3. Test a remote publish

```bash
./gradlew -Pversion=0.1.0 publishAllPublicationsToMavenCentralRepository
```

Check that the output contains:
```
> Task :module:publishMavenPublicationToCentralPortalRepository
```

### 4. Check the Portal

Visit https://central.sonatype.com/publishing/deployments — the just-uploaded content should appear.

## Rollback Plan

If problems appear after migration:

1. Keep a backup of the old configuration:
   ```bash
   cp ~/.gradle/gradle.properties ~/.gradle/gradle.properties.backup
   ```

2. **Temporary restore** (while OSSRH is still reachable):
   - restore `ossrhUsername` and `ossrhPassword`
   - restore the old URL in `build.gradle`

3. Contact support:
   - Central Portal: https://central.sonatype.com/support
   - GitHub Issues: https://github.com/sonatype/central-portal/issues

## Migration Checklist

- [ ] Register a Central Portal account
- [ ] Migrate the namespace (if any)
- [ ] Generate a User Token
- [ ] Update `~/.gradle/gradle.properties`
- [ ] Update `build.gradle`
- [ ] Update the GitHub Secrets
- [ ] Update the GitHub Actions workflow
- [ ] Test a local publish
- [ ] Test a remote publish
- [ ] Verify in the Portal
- [ ] Update the docs

## References

- The official migration guide: https://central.sonatype.org/pages/ossrh-eol/
- Central Portal docs: https://central.sonatype.org/
- Namespace migration: https://central.sonatype.org/register/central-portal/
- API docs: https://central.sonatype.org/publish/publish-portal-api/

---

After migration, publishing goes through central.sonatype.com's automatic validation and one-click Publish — no more manual Close/Release.
