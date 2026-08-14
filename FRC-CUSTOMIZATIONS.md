# Adding the FRC backend customizations to a new DSpace instance

Three custom features, packaged together on **one branch** so you can pull them
all in a single operation:

| Feature | What it does |
|---|---|
| **Group-aware metadata visibility** | Lets named DSpace groups — not just administrators — see fields hidden by `metadata.hide.*` |
| **CleanMetadata curation task** | Normalizes smart quotes, double hyphens and stray whitespace across Item metadata |
| **LogAnalyser Solr date fix** | Fixes the invalid-date error that broke `dspace stat-general` / healthcheck runs |

Branch: **`feature/frc-customizations`** in
`https://github.com/OtCloudCompany/DSpace.git`

Works on DSpace **9.x and 10.x**. The steps are identical for both.

Time: about 5 minutes plus a build.

---

## Step 1 — Add the fork as a remote and fetch

From your DSpace source directory:

```bash
git remote add otcloud https://github.com/OtCloudCompany/DSpace.git
git fetch otcloud feature/frc-customizations
```

## Step 2 — Merge the branch

Start from whatever you are deploying (`dspace-9_x`, `dspace-10_x`, a release
tag, or your own fork's branch):

```bash
git checkout -b frc-custom dspace-9.3     # or dspace-10.0, or your release tag

git merge otcloud/feature/frc-customizations
```

That brings in all three features — three commits, six files, 514 added lines.
It merges cleanly, with nothing to fix by hand.

The branch is based on the `dspace-9.2` release tag, which is an ancestor of
every later 9.x and 10.x release, so the merge carries only these three commits
and no unrelated history.

### ⚠️ If you are building from `dspace-9_x` or `dspace-10_x` instead of a release

Those two branches are unreleased snapshots (9.4-SNAPSHOT and 10.1-SNAPSHOT),
and upstream has **already landed the LogAnalyser fix** on them. Merging our
version on top gives a duplicate method and the build fails with:

```
LogAnalyser.java: method convertDate(LocalDate,boolean) is already defined
```

Note the merge itself succeeds silently — the failure only shows up at compile
time. Check before you merge:

```bash
grep -c convertDate dspace-api/src/main/java/org/dspace/app/statistics/LogAnalyser.java
```

If that prints anything other than `0`, you already have the fix. Take only the
first two commits:

```bash
git cherry-pick -x ad1bb5e1b6 94c34d7143
```

Every DSpace **release** through 9.3 and 10.0 lacks the fix, so the plain
`git merge` above is correct for them. The fix is expected in 9.4 and 10.1.

### If you want only some of the features

They are separate commits, so cherry-pick instead of merging:

```bash
git cherry-pick -x ad1bb5e1b6     # group-aware metadata visibility only
git cherry-pick -x 94c34d7143     # CleanMetadata curation task only
git cherry-pick -x cb61ae3677     # LogAnalyser Solr date fix only
```

### Older branches — do not use

`feature/metadata-hide-groups` and `feature/cleanMetadata` are where this work
was originally developed. **Do not pull from them.** Their tips have failed
builds at various points, they interleave all three features in one line with
no way to take them separately, and they contain import-ordering bugs that fail
DSpace's checkstyle. Everything is already fixed and separated on
`feature/frc-customizations`.

## Step 3 — Build

```bash
mvn -pl dspace-api checkstyle:check    # should pass
mvn package                            # or your usual build command
```

DSpace 10 requires **JDK 21**; DSpace 9 builds on JDK 17.

Then deploy/install as you normally would (`ant update`, container rebuild,
etc.) and restart Tomcat.

---

# Feature 1 — Group-aware metadata visibility

## Step 4 — Configure

Edit `dspace/config/local.cfg` (not `dspace.cfg`). Declare which fields are
hidden as usual, then add the new group settings:

```properties
# which fields are hidden (stock DSpace)
metadata.hide.dc.description.provenance = true
metadata.hide.person.email              = true

# NEW: groups that may see hidden fields anyway
metadata.hide.groups = Librarians, Reviewers
```

Optionally, override the list for a single field:

```properties
metadata.hide.person.email.groups = HRTeam
```

Restart Tomcat after changing these.

## Step 5 — Check it works

1. Create a group (e.g. `Librarians`) and add a **non-admin** test user to it.
2. Fetch an item over REST and look at the `metadata` block:

| Requesting as | `dc.description.provenance` |
|---|---|
| anonymous | absent |
| ordinary logged-in user | absent |
| member of `Librarians` | **present** |
| site administrator | present |

If the field is absent for your `Librarians` user, check Step 6 first — a
mistyped group name silently behaves as "no access".

## Step 6 — Things to know when configuring

- **Group names must match exactly** as shown in the group administration
  screen. Matching is case-sensitive. Spaces around the commas are fine.
- **A typo fails silently.** An unrecognised group name just means "hidden",
  with no error in the logs. Always confirm with a real test account.
- **Never use `Anonymous`.** DSpace treats everyone as a member of `Anonymous`,
  so `metadata.hide.groups = Anonymous` would expose the field to the entire
  internet, including logged-out visitors.
- **A per-field list replaces the global list — it does not add to it.** With
  the example config above, `Librarians` can see
  `dc.description.provenance` but **not** `person.email`, because
  `metadata.hide.person.email.groups` overrides the global list for that one
  field. If `Librarians` need both, list them explicitly:
  ```properties
  metadata.hide.person.email.groups = HRTeam, Librarians
  ```
- **Restart after changing `metadata.hide.X = true`.** The set of hidden fields
  is cached at startup.
- **Keep the group lists short** (a handful, not dozens). The check runs for
  every metadata value on every request.

## What this feature touches

Only one class —
`dspace-api/src/main/java/org/dspace/app/util/MetadataExposureServiceImpl.java`
— plus commented documentation in `dspace/config/dspace.cfg`.

Because that class is the single place DSpace decides whether a metadata field
is visible, the feature automatically covers the REST API, OAI-PMH, IIIF
manifests and RDF output. There is no per-interface configuration to do.

---

# Feature 2 — CleanMetadata curation task

Normalizes Item metadata so that Solr indexing and display stay consistent:

1. **Quotes** — curly/smart quote variants → straight ASCII quotes (all fields)
2. **Dashes** — `--` → em dash, in configured fields only. Runs of three or
   more hyphens (`---`) are preserved, since they are often legal-document
   placeholders.
3. **Whitespace** — trims leading/trailing spaces, collapses internal runs of
   two or more spaces (all fields)

## Step 7 — Register the task

Add to `dspace/config/modules/curate.cfg` (or `local.cfg`):

```properties
plugin.named.org.dspace.curate.CurationTask = \
    org.dspace.ctask.general.CleanMetadata = cleanmetadata
```

To make it selectable in the admin UI, also add it to the task list:

```properties
curate.ui.tasknames = cleanmetadata = Clean Metadata
```

Restart Tomcat.

## Step 8 — Configure

The merge installs `dspace/config/modules/cleanmetadata.cfg` with working
defaults. All three fixes are on, and dash conversion is limited to a short
field list:

```properties
cleanmetadata.fix.quotes     = true
cleanmetadata.fix.dashes     = true
cleanmetadata.fix.whitespace = true

cleanmetadata.dash.fields = dc.description.abstract, \
                            dc.description.lawtext, \
                            dc.description.summary, \
                            dc.title
```

Adjust `cleanmetadata.dash.fields` to match your metadata. URL fields and
structured fields (`dc.description.provenance`, `dc.relation.*`) are
deliberately excluded — em-dashing those would break links and DSpace-generated
text.

## Step 9 — Run it

```bash
# whole repository
[dspace]/bin/dspace curate -t cleanmetadata -i all

# a single item
[dspace]/bin/dspace curate -t cleanmetadata -i hdl:123456789/1

# a collection — the task is @Distributive, so this covers every item inside
[dspace]/bin/dspace curate -t cleanmetadata -i hdl:123456789/2
```

**This rewrites metadata in place. Back up your database first**, and do a trial
run on a single item or a test collection before running against `all`.

Return codes: `CURATE_SUCCESS` (item processed), `CURATE_SKIP` (not an Item —
Communities and Collections are skipped), `CURATE_ERROR`.

## What this feature touches

New files only — no stock DSpace file is modified:

- `dspace-api/src/main/java/org/dspace/ctask/general/CleanMetadata.java`
- `dspace-api/src/test/java/org/dspace/ctask/general/CleanMetadataTest.java`
- `dspace/config/modules/cleanmetadata.cfg`

---

# Feature 3 — LogAnalyser Solr date fix

## What it fixes

`dspace stat-general` / `stat-report-general` (and healthcheck runs that invoke
them) count items by querying Solr for a `dc.date.accessioned_dt` range built
from the `--start` and `--end` options. DSpace passed a bare `YYYY-MM-DD`
string, which Solr does not accept as a date, so the query was rejected and the
run failed with an invalid date error.

The fix widens each bound to a full instant — start of day for the lower bound,
last moment of the day for the upper bound, so both endpoints stay inclusive —
which renders as ISO-8601 UTC and Solr accepts.

## Configuration

None. It changes one method in
`dspace-api/src/main/java/org/dspace/app/statistics/LogAnalyser.java` and takes
effect as soon as you deploy.

## Check it works

```bash
[dspace]/bin/dspace stat-general --start 2026-01-01 --end 2026-12-31
```

It should complete and report item counts rather than failing with a Solr date
parsing error.

## Note on this commit

This is a **backport of an upstream DSpace fix**, not original FRC work.
Upstream has the identical fix on the `dspace-9_x` and `dspace-10_x` branches,
so it will arrive in DSpace 9.4 and 10.1. It is missing from every release up
to and including 9.3 and 10.0, which is why it is carried here.

Once you upgrade to 9.4 or 10.1, **drop this commit** — keeping it will break
the build with a duplicate-method error. See the warning under Step 2.

Only the functional change is carried here. The FRC development branch also
reformatted about 200 unrelated lines of this stock file (comment spacing,
argument wrapping); that noise is deliberately excluded so the file keeps
merging cleanly on future upgrades.

---

# Notes on DSpace 10

No extra work is needed for either feature.

DSpace 10 marks `MetadataExposureServiceImpl` as deprecated in favour of
`MetadataSecurityService`, but that new service still delegates to it, so the
group check stays on the live request path and behaves identically to 9.x.

If a future release removes `MetadataExposureServiceImpl` outright, the group
logic needs to move into `MetadataSecurityServiceImpl` at the point where it
currently calls `isHidden(...)`. The logic is self-contained and the
configuration property names would not change.

# Verification status

What was actually tested when this branch was built:

| Target | Merge | `checkstyle:check` | `mvn test-compile` |
|---|---|---|---|
| `dspace-9.3` (release) | clean | pass | **pass** |
| `dspace-10.0` (release) | clean | pass | not verified — see below |
| `dspace-9_x` (9.4-SNAPSHOT) | clean | pass | **fails** — duplicate `convertDate`, see Step 2 |
| `dspace-10_x` (10.1-SNAPSHOT) | clean | pass | not verified; same duplicate expected |

The 10.x builds were not verified because they require **JDK 21** and the build
machine had only JDK 17 — the build stops while compiling stock
`dspace-services`, before reaching any custom code. The merge and checkstyle
results on 10.x are real. **Run a full build on JDK 21 before deploying to
DSpace 10.**

The `dspace-9_x` failure is expected and is not a defect in these features — it
is the upstream LogAnalyser fix colliding with our backport of it. Dropping
commit `cb61ae3677` resolves it; the other two features build fine there.

Automated test coverage is limited to `CleanMetadataTest`. There is no test for
the group-visibility logic or the LogAnalyser date bounds — worth adding if any
of this ever goes upstream.
