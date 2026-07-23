# Maven Central Release Setup

This project is configured to publish artifacts to [Maven Central Repository](https://central.sonatype.com/). Follow these steps to complete the setup.

## Prerequisites

### 1. Maven Central Account (OSSRH)

- Create an account at [Sonatype OSSRH](https://central.sonatype.com/publish)
- Request access to the `io.github.baokhang83.mnemo` namespace
  - You may need to verify ownership of the GitHub organization/repository
- Once approved, you'll receive a username and token

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
| `OSSRH_USERNAME` | Your Sonatype OSSRH username | From OSSRH account setup |
| `OSSRH_TOKEN` | Your Sonatype OSSRH token | From OSSRH account setup |
| `GPG_PRIVATE_KEY` | Base64-encoded GPG private key | Generated in GPG key setup |
| `GPG_PASSPHRASE` | Your GPG key passphrase | From GPG key generation |

## Maven Configuration

The pom.xml has been updated with:
- Distribution management pointing to Sonatype OSSRH
- Source and Javadoc JAR generation
- GPG signing configuration
- Nexus staging plugin for automated release management

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
4. Deploy to Maven Central staging repository
5. Automatically release from staging (via nexus-staging-maven-plugin)

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

- Verify OSSRH credentials are correct
- Check that your OSSRH account has access to the namespace
- Ensure the token hasn't expired

### Staging Repository Won't Close

- Check the Sonatype OSSRH portal for validation errors
- Common issues: missing source JARs, missing Javadoc, unsigned artifacts
- All are handled automatically in the release workflow

### Maven Build Failure

- Ensure all tests pass locally before tagging
- Check Java version (Java 21 required)
- Run `mvn clean verify` locally first

## References

- [Sonatype OSSRH Guide](https://central.sonatype.org/publish/publish-guide/)
- [Maven GPG Plugin](https://maven.apache.org/plugins/maven-gpg-plugin/)
- [Maven Release Plugin](https://maven.apache.org/plugins/maven-release-plugin/)
- [Nexus Staging Maven Plugin](https://github.com/sonatype/nexus-maven-plugins/tree/master/staging/maven-plugin)
