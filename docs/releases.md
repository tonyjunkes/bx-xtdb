# Release operation

PR verification builds the distribution once and installs that ZIP on BoxLang
1.18.0, latest stable, and snapshot. All three must pass. The release workflow
reuses the same checks and publishes their exact artifact.

Before merging the release-readiness PR, add a repository Actions secret named
`FORGEBOX_API_TOKEN` for an account permitted to publish `bx-xtdb`. CI database
credentials are disposable and unrelated to that token. No publishing secret is
needed by pull requests.

On a main push or a manual run from main, the release workflow:

1. Verifies the tested commit, package identity, ZIP checksum, and packaged manifest.
2. Checks ForgeBox state; a missing entry is a valid first publication. API failures stop publication.
3. Creates an immutable version tag and draft GitHub release, uploads the ZIP/checksum, then makes the release public.
4. Publishes the compiled package to ForgeBox, whose location points to the public GitHub ZIP.

The initial module version is `0.1.0`. Update the version in `box.json`,
`gradle.properties`, and `src/main/bx/ModuleConfig.bx`, plus the manifest's ZIP URL,
for a new release. The build checks their consistency.

If a main commit retains an earlier ancestor's version tag, publication is
skipped. A tag that is not an ancestor causes failure. Existing tags are never
moved. Re-running the original workflow run can complete missing release assets
or a failed ForgeBox publication. Existing assets must match the tested artifact
byte for byte; they are never replaced. An already-published ForgeBox version is
not republished. Releasing an intentionally changed package requires a new version.

Release-recovery tests run without credentials or network access:

```sh
python3 -m unittest discover -s tests/ci
```

Publishing is performed by GitHub Actions after merge. Opening these PRs does
not create a release or publish a ForgeBox package.
