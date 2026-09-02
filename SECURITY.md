# Security Policy

## Supported versions

Security fixes are applied to the latest release line and to `main` while it is preparing the next release.

| Version | Supported |
|---|---|
| Latest release | Yes |
| `main` | Pre-release, best effort |
| Older releases | No |

## Intended security boundary

Radar Correction Explorer is an unauthenticated, local inspection tool.

- The HTTP server binds to `127.0.0.1` by default.
- Synthetic demo mode is the default and requires no credentials.
- The application exposes read-only query endpoints to the local browser.
- Non-loopback deployment is not supported without an authentication, authorization, TLS, and network-access layer supplied by the operator.

Changing `RADAR_VIEWER_HOST` to a shared or public interface changes the threat model. Do not make that change merely to provide remote access.

## External database safety

When PostgreSQL mode is enabled:

- Use a dedicated database role that can only `SELECT` the required view or table.
- Limit the role to the required schema and network origin.
- Treat JDBC read-only mode as a driver hint, not an authorization control.
- Supply the password through `RADAR_DB_PASSWORD` or an equivalent secret mechanism for the current process.
- Keep `viewer.config.json` out of version control, release archives, issue attachments, logs, and screenshots.
- Use a non-sensitive display label; do not expose hostnames or topology in the UI.
- Review the configured map tile provider before inspecting location-sensitive data.

The project must not contain operational coordinates, captured API responses, credentials, private certificate material, or populated local configuration.

## Reporting a vulnerability

Use GitHub's private vulnerability reporting feature:

1. Open the repository's **Security** tab.
2. Select **Advisories** and **Report a vulnerability**.
3. Include the affected revision or release, impact, reproduction steps, and a minimal proof of concept.
4. Use synthetic data. Do not attach real radar data, database contents, credentials, or private network details.

If private vulnerability reporting is unavailable, contact the maintainer through a private channel listed on the maintainer's GitHub profile. Do not open a public issue containing an unpatched vulnerability.

The maintainer aims to acknowledge a complete report within five business days. Validation, remediation, and disclosure timing depend on impact and complexity. Please allow a reasonable remediation window before public disclosure.

## In-scope examples

- Default configuration unexpectedly exposing the service beyond loopback.
- SQL injection or identifier-validation bypass.
- Cross-site scripting from database-provided or API-provided values.
- Secrets being returned by an API, written to logs, or included in release artifacts.
- Path traversal or unsafe file handling in configuration and packaging.
- Dependency vulnerabilities that affect the packaged runtime.
- A crafted query that bypasses documented server-side safety limits in a remotely reachable deployment.

## Usually out of scope

- Availability of a third-party map tile service.
- Reports that require a deliberately unsupported public bind with no additional security layer, unless the application makes the risk unclear or leaks more data than documented.
- Denial of service performed by the same local user who controls the process and configuration.
- Synthetic demo coordinates being mistaken for operational data when the synthetic label is visible.
- Vulnerabilities in unsupported releases that are already fixed in the latest release.

## Maintainer release checklist

Before publishing a release:

- Run the full Linux and Windows CI jobs.
- Review dependency and CodeQL alerts.
- Confirm that the archive contains `LICENSE`, [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md), relevant license texts, and a CycloneDX SBOM.
- Scan the source and archive for secrets, populated local configuration, private paths, and unexpected binaries.
- Verify the SHA-256 digest of the final archive.
- Start the archive with no configuration and confirm that it uses synthetic demo mode on loopback.
