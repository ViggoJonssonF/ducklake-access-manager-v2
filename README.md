# DuckLake Access Manager

A web app + REST API for sharing curated datasets with students through a
self-hosted [DuckLake](https://duckdb.org/2025/05/27/ducklake.html). Admins
(typically course teachers) create datasets, populate them with data, and
grant access to individual students, groups of students, or everyone.
Students browse the available datasets and generate the credentials they
need to query the data from their own DuckDB / Python / JupyterLab
environments.

## What it is

DuckLake itself is just a convention for storing tables: the actual data
lives as Parquet files in an S3 bucket, and a Postgres database holds the
catalog (which tables exist, which Parquet files belong to which table,
schema versions, and so on). DuckDB's `ducklake` extension reads both
together to give you ordinary SQL queries over the lake.

The Access Manager is the thin layer on top that handles the parts DuckLake
itself doesn't care about: who sees which datasets, who can write vs only
read, and how the access keys for the underlying S3 bucket and Postgres
database get created and rotated. Without it, a teacher would have to
manually create a Postgres user and an S3 key for every student.

## How the pieces fit together

```
                      ┌─────────────┐
                      │  Keycloak   │   KTH SSO (or local Keycloak in dev)
                      │   (IAM)     │   issues JWT tokens
                      └──────┬──────┘
                             │ JWT
                             ▼
              ┌──────────────────────────────┐
              │    Access Manager            │
              │    Spring Boot + React       │
              │    :8080                     │
              │                              │
              │  • Browse / My Keys / Admin  │
              │  • REST API for keys         │
              │    + datasets + groups       │
              │    + grants                  │
              └──────┬─────────────┬─────────┘
                     │             │
            ┌────────▼──┐    ┌─────▼──────┐
            │ Postgres  │    │   Garage   │  S3-compatible object store
            │ catalog   │    │  (buckets) │
            │           │    │            │
            │ One DB    │    │ One bucket │
            │ per       │    │ per        │
            │ dataset   │    │ dataset    │
            └────────┬──┘    └─────┬──────┘
                     │             │
                     └──────┬──────┘
                            │
                ┌───────────▼────────────┐
                │  Student's environment │
                │  (DuckDB / JupyterLab) │
                │  Runs queries here     │
                └────────────────────────┘
```

Five processes talk to each other:

| Process | What it does |
|---|---|
| **Keycloak** | Issues JWT tokens after a user signs in. Every API call carries one. The `admin` role is a Keycloak client role on the `ducklake` client. |
| **Access Manager** | The Spring Boot app in this repo. Validates JWTs, owns the dataset/group/grant metadata, and orchestrates Postgres + Garage when a key is generated. |
| **Postgres** | Hosts both the access manager's own bookkeeping (datasets, groups, grants, key→user mapping) **and** one DuckLake catalog database per dataset. |
| **Garage** | S3-compatible storage. Each dataset gets its own bucket; Parquet files written by DuckLake land here. |
| **Student's DuckDB** | Runs in JupyterLab, a Python script, or a notebook. Connects to the catalog Postgres + Garage S3 using the credentials the access manager hands out. |

## Concepts

### Dataset

The thing students browse. Created by an admin. Three pieces hang together
under one bucket name:

* **Garage bucket** with the same name as the dataset (e.g. `titanic-2026`).
  Holds the Parquet files.
* **Postgres database** named `dl_<bucket_with_underscores>`
  (e.g. `dl_titanic_2026`). Holds the DuckLake catalog tables for *that
  one dataset*. A user with access to dataset A literally cannot SELECT
  against dataset B's catalog tables — there is no CONNECT privilege.
* **Metadata row** in the `datasets` table — title, description, owner
  email, visibility (`public` or `private`), created\_at.

When an admin creates a dataset, the access manager creates all three
atomically. When an admin deletes a dataset, all three go away (the bucket
must be empty first).

### Group

A named collection of email addresses (e.g. `dd2476-vt26`). Admins manage
membership through the UI. When a group is granted access to a dataset,
every member of the group inherits that access automatically — add a new
student to the group and they have access immediately, no per-student
grant needed.

### Grant

"This principal may use that dataset." A grant has three forms:

* `user` — a specific email.
* `group` — every email in the named group.
* `everyone` — any signed-in user.

Public datasets are accessible to everyone regardless of grants — the
visibility flag is a shortcut for "let anyone in." Private datasets check
grants strictly.

### Key

The credentials a student needs to actually query a dataset. The access
manager creates them in pairs:

* **An S3 access key** in Garage with read (or read+write) on the
  dataset's bucket.
* **A Postgres user** scoped to the dataset's database, with SELECT (and
  optionally INSERT/UPDATE/DELETE) on the public schema.

The web UI hands the user a ready-to-paste DuckDB script that wires both
together. Read/write keys can only be generated by admins — students
always get read-only.

## How data flows: from raw files to student queries

This is the part that surprises people: the access manager has no upload
form. Data gets into a dataset by **running SQL through DuckDB**. That's
deliberate — DuckLake is built for SQL, and once you have a write-key the
entire DuckDB ecosystem (CSV, Parquet, JSON, Arrow, …) is available.

### The full life cycle

```
┌──────────────────────────────────────────────────────────────────────┐
│ 1. Admin creates the dataset (UI)                                    │
│    → Garage bucket + empty Postgres DB + metadata row.               │
│                                                                      │
│ 2. Admin generates a read/write key for themselves (UI)              │
│    → Receives a DuckDB script with credentials.                      │
│                                                                      │
│ 3. Admin runs the script in DuckDB and loads data:                   │
│       USE my_ducklake;                                               │
│       CREATE TABLE passengers AS                                     │
│         SELECT * FROM 'C:/data/titanic.csv';                         │
│    DuckLake writes the rows out as Parquet in the bucket and         │
│    registers them in the catalog tables. First write also            │
│    bootstraps the catalog schema in the Postgres DB.                 │
│                                                                      │
│ 4. Admin shares access (UI):                                         │
│       • Set visibility = public, or                                  │
│       • Grant a specific user / group / @everyone in Admin → Grants. │
│                                                                      │
│ 5. Student logs in, finds the dataset in Browse, clicks Generate     │
│    Keys. Receives a *read-only* DuckDB script.                       │
│                                                                      │
│ 6. Student pastes the script into JupyterLab / Python / DuckDB CLI   │
│    and queries:                                                      │
│       SELECT * FROM passengers WHERE survived = 1;                   │
└──────────────────────────────────────────────────────────────────────┘
```

### Where does the actual data live?

The Parquet files live in **Garage S3**. The `titanic.csv` you loaded in
step 3 doesn't exist in DuckLake as CSV — DuckDB rewrote it as one or more
Parquet files in `s3://titanic-2026/...`. The catalog tables in
`dl_titanic_2026` track which Parquet files belong to which DuckLake
table, version history, schema evolution, etc.

This means:

* You don't need to keep the source CSV around once it's loaded — the
  data is in the lake.
* Students reading the dataset never see the CSV. They see DuckLake
  tables and run SQL against them.
* You can append more rows later by `INSERT INTO passengers SELECT ... FROM 'newfile.parquet'`
  and DuckLake handles the catalog bookkeeping.

### What does a student do with the generated key/script?

Three lines, conceptually:

```python
import duckdb
con = duckdb.connect()
con.execute(generated_script)              # the script the access manager gave them
df = con.execute("SELECT * FROM passengers LIMIT 10").fetchdf()
```

The generated script:

* installs/loads the `ducklake` and `postgres` extensions,
* creates a Postgres SECRET with their per-dataset credentials,
* creates an S3 SECRET pointing at Garage,
* `ATTACH 'ducklake:postgres:dbname=dl_<bucket>' AS my_ducklake (DATA_PATH 's3://<bucket>/')`,
* `USE my_ducklake;`.

After that, every subsequent SQL statement runs against the lake. The
student never has to think about Parquet files or S3 keys directly —
those are abstracted away by the DuckLake extension.

A pre-built [JupyterLab image](Dockerfile.student) is included for
students that don't want to set up a Python environment from scratch:

```bash
docker build -t ducklake-jupyter -f Dockerfile.student .
docker run -p 8888:8888 ducklake-jupyter
```

opens JupyterLab at `http://localhost:8888` with DuckDB and the DuckLake
extension already installed; just paste the generated script into a cell.

### Public vs private — when to use which

* **Public** is for class-wide reference data ("here's a CSV everyone can
  query"). Browse shows it to every signed-in user automatically; no
  grants needed.
* **Private** is for course-specific or sensitive data. Combine with
  groups to give a whole course access in one click, or with `@everyone`
  if you want the same effect as public but want to be able to revoke
  later by deleting the grant.

## Quick start — running locally

This is the path for development and for trying things out without any
KTH cloud access. Everything runs in Docker.

### Prerequisites

* Docker Desktop (Windows/Mac) or Docker Engine + Compose plugin (Linux).
* Git.

### Start the stack

```bash
git clone https://github.com/ViggoJonssonF/ducklake-access-manager-v2.git
cd ducklake-access-manager-v2
docker compose -f compose.local.yaml up --build
```

First run takes 5–10 min while Maven pulls dependencies and the base
images download. Subsequent runs start in seconds.

When you see `Started DucklakeAccessManagerApplication`, open
[http://localhost:8080](http://localhost:8080).

### Pre-seeded accounts

The local Keycloak realm is imported on first start with three accounts
ready to go:

| Username | Password | Role |
|----------|----------|------|
| `admin@local` | `admin` | client role `admin` on `ducklake` |
| `student@local` | `student` | regular user |
| `student2@local` | `student` | regular user |

A `demo-dataset` bucket is created automatically by the Garage bootstrap
container so you have something to look at on first login.

### Stopping

```bash
docker compose -f compose.local.yaml down            # keeps data
docker compose -f compose.local.yaml down -v         # drops all volumes
```

For more local-dev details (network architecture, the Spring/Keycloak
issuer-matching trick, faster mvn dev loop), see [local/README.md](local/README.md).

## User flows in detail

### As an admin (teacher)

1. **Sign in** as an account that has the `admin` client role on the
   `ducklake` Keycloak client. (For local dev: `admin@local` / `admin`.)
2. **Create a dataset** in `Admin → Datasets`:
   * Bucket name: `lab1` (3–60 lowercase chars/digits/hyphens).
   * Title and description: free text.
   * Visibility: `private` to start with — you can flip it to `public`
     later.
3. **Generate a read/write key** for yourself. Go to `Browse`, find your
   new dataset, switch the dropdown above the grid to `Read/Write keys`,
   click `Generate Keys`. Copy the script and the secret key (the secret
   is shown once).
4. **Load data** by running the script in DuckDB and then
   `CREATE TABLE ... AS SELECT * FROM 'file.csv'` (or `parquet` /
   `json` / a Python DataFrame / …). The first `ATTACH` from a writer
   bootstraps the DuckLake catalog tables; after that students can use
   read keys.
5. **Share access** via `Admin → Grants`:
   * Pick a principal type (User / Group / @everyone).
   * Pick a dataset, click Grant.
   * For groups: create them first in `Admin → Groups`, then add member
     emails.

### As a student

1. **Sign in** with KTH (or `student@local` locally).
2. **Browse** datasets. The grid shows public datasets plus any private
   ones you have access to.
3. **Generate Keys** on a dataset you want. The modal contains a DuckDB
   script — copy it; the secret is shown once.
4. **Run the script** in your DuckDB / JupyterLab / Python environment.
   Paste it into a cell and execute.
5. **Query the lake**:
   ```sql
   SELECT * FROM passengers LIMIT 10;
   ```
6. When you no longer need the key, delete it from `My Keys`. The
   Postgres user is dropped automatically.

## REST API reference

Every endpoint requires `Authorization: Bearer <jwt>` unless noted.

### Public

* `GET /api/config` — returns `{ keycloakBase, clientId }` for the
  frontend bootstrap. No auth.
* `GET /healthz` — liveness probe. No auth.

### Datasets

* `GET    /api/datasets` — list visible to caller. Admin sees all;
  others see public + granted.
* `GET    /api/datasets/{bucket}` — single dataset.
* `POST   /api/datasets` *(admin)* — body
  `{bucketName, title, description, visibility}`.
* `PATCH  /api/datasets/{bucket}` *(admin or owner)* — any of
  title/description/visibility.
* `DELETE /api/datasets/{bucket}` *(admin or owner)* — bucket must be
  empty.

### Groups *(all admin)*

* `GET    /api/groups`
* `GET    /api/groups/{name}` — includes member list
* `POST   /api/groups` — `{name, description}`
* `DELETE /api/groups/{name}`
* `POST   /api/groups/{name}/members` — `{email}`
* `DELETE /api/groups/{name}/members` — `{email}`

### Grants *(all admin)*

* `GET    /api/admin/grants` — list all grants in
  `[{principalType, principalId, bucketName, grantedAt}]` shape.
* `POST   /api/admin/grants` — body
  `{principalType, principalId, bucketName}` where `principalType` is
  one of `user` / `group` / `everyone`. `principalId` is omitted for
  `everyone`.
* `DELETE /api/admin/grants` — same body shape.

### Keys

* `POST   /api/keys/generate` — body `{bucketName, permission}` where
  `permission` is `read` (default) or `readwrite` (admin only).
  Returns the S3 key, Postgres credentials, and a ready-to-run DuckDB
  script.
* `GET    /api/keys` — list. Admin sees all keys; users see only their
  own.
* `DELETE /api/keys/{keyId}?pgUsername=<u>` — admin can delete any;
  user only their own.

### Buckets *(admin only — raw Garage view, kept for ops)*

* `GET    /api/admin/buckets`
* `POST   /api/admin/buckets` — `{name}`
* `DELETE /api/admin/buckets/{name}`

## Deploying to production (KTH cloud)

The same image runs against KTH cloud's deployments unchanged. The
difference is just env vars — point Postgres / Garage / Keycloak at the
production hostnames and skip the local Spring profile.

See [.env.example](.env.example) for the full set of variables.

The current cbhcloud `NetworkPolicy` blocks pod-to-pod traffic between
deployments owned by different users, which means students cannot reach
`ducklake-catalog` (Postgres) and `ducklake-garage` (S3) from their own
deployments yet. Until that's fixed by the cbhcloud team — the planned
solution is to expose Postgres as a Helm-chart system service —
generated keys are stored correctly but won't *work* end-to-end from a
student's deployment. The web UI flow (browse, manage grants, generate
keys) all works regardless.

## Project layout

```
src/main/java/com/ducklake/accessmanager/
  api/                   REST controllers
    DatasetController    /api/datasets/**
    GroupController      /api/groups/**
    AdminController      /api/admin/buckets, /api/admin/grants
    KeyController        /api/keys/**
    BucketController     /api/buckets (lists buckets the caller can see)
    ConfigController     /api/config
    HealthController     /healthz
  service/
    DatabaseAccessTokenManager     interface — per-DB user CRUD
    KeyMappingService              interface — key ↔ owner mapping
    ObjectStoreAccessTokenManager  interface — Garage CRUD
    impl/
      AccessService              user/group/everyone grants + lookups
      DatasetService             dataset CRUD + atomic bucket+DB lifecycle
      GroupService               group CRUD + members
      GarageAccessTokenManager   Garage admin API v1 client
      PostgresAccessTokenManager per-dataset PG user creation
      PostgresAdminOps           cluster-level CREATE/DROP DATABASE
      PostgresKeyMappingService  key_user_mapping table
  config/
    SecurityConfig         JWT validation + endpoint authz
  model/
    Bucket, BucketGrant, AccessKey, DbCredentials, Dataset, Group, Grant,
    GeneratedCredentials, KeyListItem, KeyRequest

src/main/resources/
  application.properties        defaults (production-shaped)
  application-local.properties  overlay for SPRING_PROFILES_ACTIVE=local
  static/index.html             single-file React frontend (no build step)

local/
  README.md                     in-depth local-dev guide
  keycloak/realm-cloud.json     pre-seeded realm with admin + student users
  garage/garage.toml            single-node Garage config
  garage/bootstrap.sh           initializes Garage layout + demo bucket
  postgres/init.sql             enables pgcrypto

compose.local.yaml              the whole local stack
Dockerfile                      production image of the access manager
Dockerfile.student              JupyterLab + DuckDB image for students
```

## Troubleshooting

**Login redirects to Keycloak but never comes back, or the token is
rejected with 401.** Make sure `KEYCLOAK_ISSUER_URI` matches what
Keycloak puts in the `iss` claim. Locally that's
`http://localhost:8081/realms/cloud`; in production it's
`https://iam.cloud.cbh.kth.se/realms/cloud`.

**Generate Keys returns 404.** The bucket isn't registered as a dataset.
Either the startup auto-sync didn't reach Garage (look for
`Auto-registered N orphan bucket(s)` in the access manager logs) or
the dataset was never created. As an admin, just create it via
`Admin → Datasets`.

**Generate Keys returns 403.** Either the user isn't an admin trying to
get a read/write key, or they don't have access to a private dataset.
Check `Admin → Grants` for the dataset.

**Bucket delete returns 409 "Bucket is not empty".** Garage refuses to
delete a non-empty bucket. Empty it first via the S3 API (e.g. with
`mc` or `aws s3`), then retry.

**DuckDB ATTACH says "relation … does not exist".** A read-only key was
used before any writer ever ran ATTACH on this dataset, so the
DuckLake catalog tables don't exist yet. Generate a read/write key
as admin, run ATTACH once (e.g. `CREATE TABLE _bootstrap AS SELECT 1;`),
then read keys will work.

**Locally: Postgres port 5432 already allocated.** Some other Postgres is
running on the host. Our compose maps to `5433` to avoid this. The
default DuckDB script generated by the access manager will say
`PORT 5432` because that's what the app sees inside the docker
network — for local-only DuckDB testing edit the script's port to
5433. Production scripts are unaffected (everything is on 5432 there).

## License

See the upstream repository for licensing terms.
