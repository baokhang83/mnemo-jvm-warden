# Maven Central Release Setup

This project is configured to publish artifacts to [Maven Central Repository](https://central.sonatype.com/). Follow these steps to complete the setup.

## Prerequisites

### 1. Maven Central Account (Central Portal)

- Create an account at [Sonatype Central Portal](https://central.sonatype.com/publish)
- Request access to the `io.github.baokhang83.mnemo` namespace
  - You may need to verify ownership of the GitHub organization/repository
- Once approved, generate a **User Token** from your account (View Account → Generate User Token)
  - This is *not* your account login/password — it's a separate token username/password pair used for API auth

### 2. GPG Key Setup

Generate a GPG key for signing artifacts (if you don't have one):

```bash
gpg --full-generate-key
# Follow the prompts, use your email and a passphrase
```

Export your GPG public key to submit to MIT keyserver:

```bash
gpg --armor --export YOUR_KEY_ID > public-key.asc
# Submit to: https://keyserver.ubuntu.com/
```

Export your GPG private key in base64 format for GitHub Secrets:

```bash
gpg --armor --export-secret-key YOUR_KEY_ID | base64 -w 0
# Copy the entire output (it will be a long single line)
```

## GitHub Secrets Configuration

Add the following secrets to your GitHub repository settings (Settings → Secrets and variables → Actions → New repository secret):

| Secret Name | Value | Notes |
|---|---|---|
| `OSSRH_USERNAME` | Your Central Portal user token username | Generated in Central Portal → Generate User Token |
| `OSSRH_TOKEN` | Your Central Portal user token password | Generated in Central Portal → Generate User Token |
| `GPG_PRIVATE_KEY` | Base64-encoded GPG private key | Generated in GPG key setup |
| `GPG_PASSPHRASE` | Your GPG key passphrase | From GPG key generation |

## Maven Configuration

The pom.xml has been updated with:
- Source and Javadoc JAR generation
- GPG signing configuration
- `central-publishing-maven-plugin` (Central Portal Publisher API) for automated release management
  - `nexus-staging-maven-plugin` doesn't work here — it targets the legacy OSSRH host, which 404s for namespaces provisioned on the new Central Portal

## Releasing a Version

### Step 1: Update Version

Change the version in pom.xml from `0.1.0-SNAPSHOT` to `0.1.0`:

```xml
<version>0.1.0</version>
```

### Step 2: Create a Git Tag

```bash
git add pom.xml
git commit -m "Release 0.1.0"
git tag -s v0.1.0 -m "Release version 0.1.0"
git push origin main
git push origin v0.1.0
```

The workflow will automatically trigger when the tag is pushed and:
1. Build the project with all tests
2. Generate source and Javadoc JARs
3. Sign artifacts with GPG
4. Bundle and upload to the Central Portal Publisher API
5. Automatically publish once validation passes (via central-publishing-maven-plugin, `autoPublish=true`)

### Step 3: Verify Publication

After the workflow completes:
- Check GitHub Actions for success
- Artifacts should appear on [Maven Central](https://central.sonatype.com/search?q=io.github.baokhang83.mnemo) within 10 minutes
- Search for: `io.github.baokhang83.mnemo`

## Post-Release: Update to Next Snapshot

After successful release, update versions for next development cycle:

```bash
# Update pom.xml version to 0.2.0-SNAPSHOT
git add pom.xml
git commit -m "Prepare for next development iteration"
git push origin main
```

## Troubleshooting

### GPG Signature Failed

- Verify `GPG_PASSPHRASE` is set correctly in GitHub Secrets
- Ensure the GPG private key base64 is complete (no truncation)
- Check that the GPG key ID used for export matches your imported key

### Authentication Failed (401/403)

- Verify `OSSRH_USERNAME`/`OSSRH_TOKEN` are a **Central Portal user token** pair, not your account login
- Check that your Central Portal account has access to the namespace
- Ensure the token hasn't expired/been revoked

### Deployment Fails Validation

- Check the Central Portal (central.sonatype.com → Publishing) for validation errors on the deployment
- Common issues: missing source JARs, missing Javadoc, unsigned artifacts
- All are handled automatically in the release workflow

### Maven Build Failure

- Ensure all tests pass locally before tagging
- Check Java version (Java 21 required)
- Run `mvn clean verify` locally first

## References

- [Central Portal Publishing Guide](https://central.sonatype.org/publish/publish-portal-maven/)
- [Maven GPG Plugin](https://maven.apache.org/plugins/maven-gpg-plugin/)
- [Maven Release Plugin](https://maven.apache.org/plugins/maven-release-plugin/)
- [Central Publishing Maven Plugin](https://central.sonatype.org/publish/publish-portal-maven/)
