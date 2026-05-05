# DuckLake Access Manager

Tjänst för automatisk generering och hantering av åtkomstnycklar till DuckLake (PostgreSQL + Garage) på cbhcloud.

Istället för att dela ut credentials manuellt kan användare besöka webbgränssnittet och få ett färdigt DuckDB-anslutningsscript på några sekunder.

### Typiskt användningsfall

DuckLake fungerar som ett centralt data lake för kursen — alla datasets lagras där. Studenter genererar egna credentials via den här tjänsten och använder dem från ett eget deployment på cbhcloud:

```
ducklake-access-manager  →  credentials
        ↓
studentens deployment på cbhcloud
  ├── läser data från DuckLake (PostgreSQL + Garage S3)
  └── tränar modeller med GPU (PyTorch, TensorFlow, etc.)
```

Deploymentet kan vara JupyterLab, VS Code Server eller ett Python-skript — med eller utan GPU beroende på workload. GPU är relevant när studenten vill träna ML-modeller på data från DuckLake.

**Produktions-URL:** `https://ducklake-access-manager.app.cloud.cbh.kth.se`

---

## Hur studenter använder tjänsten

```
┌─────────────────────────────────────────────────────────────────┐
│                        cbhcloud cluster                         │
│                                                                 │
│  ┌──────────────────────┐        ┌──────────────────────────┐   │
│  │  ducklake-access-    │        │   Studentens deployment  │   │
│  │  manager             │        │   (Jupyter / Python)     │   │
│  │                      │        │                          │   │
│  │  1. Student besöker  │        │  3. Student kör          │   │
│  │     webbgränssnittet │        │     DuckDB-scriptet här  │   │
│  │                      │        │          │               │   │
│  │  2. Kopierar scriptet│        │          ▼               │   │
│  │     med nycklarna    │        │   ducklake-catalog:5432  │   │
│  └──────────────────────┘        │   ducklake-garage:3900   │   │
│                                  └──────────────────────────┘   │
│                                                                 │
│  ┌─────────────────────┐   ┌──────────────────────────────┐     │
│  │  ducklake-catalog   │   │  ducklake-garage             │     │
│  │  (PostgreSQL)       │   │  (S3 / Garage)               │     │
│  └─────────────────────┘   └──────────────────────────────┘     │
└─────────────────────────────────────────────────────────────────┘

     Lokal dator
  ┌──────────────┐
  │  Webbläsare  │──── besöker access manager UI ────▶ (steg 1–2)
  └──────────────┘

  ⚠️  DuckDB-scriptet körs INTE lokalt — det körs från ett
      deployment på kthcloud, där ducklake-catalog är nåbar.
```

**Steg för steg:**

1. Besök `https://ducklake-access-manager.app.cloud.cbh.kth.se/` i webbläsaren
2. Välj bucket och behörighet → klicka **Generate Key** → kopiera DuckDB-scriptet
3. Skapa ett eget deployment på kthcloud (se [Student deployment guide](#student-deployment-guide) nedan)
4. Kör DuckDB-scriptet **inifrån det deploymentet** — inte lokalt på din dator

Hostname `ducklake-catalog` är bara nåbar inom cbhcloud-clustret.

---

## Student deployment guide

För att ansluta till DuckLake måste koden köras **inifrån cbhcloud-clustret** — `ducklake-catalog` och `ducklake-garage` är interna Kubernetes-services som inte är nåbara utifrån (t.ex. från en lokal dator eller Google Colab).

Det enklaste sättet är att skapa ett eget deployment på cbhcloud med valfri Python-miljö — JupyterLab, VS Code Server, eller ett vanligt Python-skript. Nedan visas JupyterLab som exempel.

### 1. Skapa ett nytt deployment på cbhcloud

Gå till [cloud.cbh.kth.se](https://cloud.cbh.kth.se) och skapa ett nytt deployment med följande inställningar:

| Fält | Värde |
|---|---|
| Image tag | se Dockerfile nedan |
| PORT | `8888` |
| Visibility | `Public` |
| Health check | `/lab` |

Bygg en egen image med följande `Dockerfile`:

```dockerfile
FROM quay.io/jupyter/base-notebook:latest

RUN pip install duckdb jupyterlab

USER root
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*
USER ${NB_UID}
```

```bash
docker build -t ghcr.io/<ditt-användarnamn>/ducklake-student:latest .
docker push ghcr.io/<ditt-användarnamn>/ducklake-student:latest
```

> DuckDB-extensionerna `ducklake` och `postgres` installeras automatiskt första gången du kör `INSTALL ducklake` / `INSTALL postgres` i DuckDB.

**Viktigt — cbhcloud-specifika krav:**

- **PORT måste vara `8888`** — cbhcloud skickar trafik till porten i PORT-variabeln. JupyterLab lyssnar på 8888; fel port ger 503.
- **Health check måste vara `/lab`** (gemener) — cbhcloud kräver att sökvägen returnerar HTTP 200 för att markera deploymentet som friskt. `/lab` fungerar; `/healthz` eller `/Lab` ger 404.
- **`--allow-root` krävs** — cbhcloud kör containrar som root. JupyterLab 4.x vägrar starta som root utan denna flagg, vilket också ger 503 trots att containern är "Running".

### 2. Öppna JupyterLab

När deploymentet är igång, klicka **Visit** i cbhcloud — det öppnar JupyterLab i webbläsaren.

### 3. Hämta dina nycklar

Besök `https://ducklake-access-manager.app.cloud.cbh.kth.se/`, generera en nyckel och kopiera DuckDB-scriptet.

### 4. Kör scriptet

Skapa en ny notebook i JupyterLab och kör följande i en cell. Varje `con.execute()` måste vara ett eget anrop — DuckDB:s Python-API accepterar bara en SQL-sats per `execute()`-anrop.

```python
import duckdb

con = duckdb.connect()

# Installera och ladda extensions
con.execute("INSTALL ducklake")
con.execute("INSTALL postgres")
con.execute("LOAD ducklake")
con.execute("LOAD postgres")

# PostgreSQL-secret (ersätt med dina värden från access manager)
con.execute("""
    CREATE OR REPLACE SECRET (
        TYPE postgres,
        HOST 'ducklake-catalog',
        PORT 5432,
        DATABASE ducklake,
        USER 'dl_ro_xxxxxxxx',
        PASSWORD 'ditt-lösenord'
    )
""")

# Garage S3-secret
con.execute("""
    CREATE OR REPLACE SECRET garage_secret (
        TYPE s3,
        PROVIDER config,
        KEY_ID 'GKxxxxxxxx',
        SECRET 'din-secret',
        REGION 'garage',
        ENDPOINT 'ducklake-garage:3900',
        URL_STYLE 'path',
        USE_SSL false
    )
""")

# Anslut till DuckLake
con.execute("""
    ATTACH 'ducklake:postgres:dbname=ducklake' AS my_ducklake (
        DATA_PATH 's3://ducklake/'
    )
""")

# Kör queries
print(con.execute("SHOW ALL TABLES").df())
print(con.execute("SELECT * FROM my_ducklake.titanic LIMIT 10").df())
```

---

## Vad tjänsten gör

När en användare begär en nyckel sker tre saker automatiskt:

1. En S3-nyckel skapas i Garage med rätt behörighet på bucketen
2. En PostgreSQL-användare skapas med rätt behörighet på databasen
3. Ett färdigt DuckDB-script returneras — kopiera och kör inifrån ett kthcloud-deployment

```json
{
  "s3Key": {
    "keyId": "GKxxxxxxxxxxxx",
    "secretKey": "...",
    "bucketName": "ducklake",
    "permission": "read",
    "endpoint": "ducklake-garage:3900",
    "region": "garage",
    "pgUsername": "dl_ro_7df3023f"
  },
  "dbCredentials": {
    "username": "dl_ro_7df3023f",
    "password": "...",
    "host": "ducklake-catalog",
    "port": 5432,
    "database": "ducklake",
    "permission": "read"
  },
  "duckdbScript": "INSTALL ducklake;\n..."
}
```

---

## Arkitektur

```
ducklake-access-manager  →  ducklake-garage:3900/v2/*  (Garage Admin API via nginx)
ducklake-access-manager  →  ducklake-catalog:5432      (PostgreSQL via JDBC)
```

Garage Admin API körs internt på port 3903 men är inte åtkomlig direkt mellan deployments på cbhcloud (NetworkPolicy). En nginx reverse proxy i `ducklake-garage`-containern tar emot trafik på port 3900 och vidarebefordrar `/v2/*` till port 3903.

Se [`garage-cbhcloud-quickstart`](https://github.com/WildRelation/garage-cbhcloud-quickstart) för detaljer om Garage-deploymentet.

---

## Gränssnitt

### Webb-UI

Öppna `https://ducklake-access-manager.app.cloud.cbh.kth.se/` i webbläsaren.

Tre flikar:

| Flik | Vem ser den | Innehåll |
|---|---|---|
| **Buckets** | Alla | Buckets man har tillgång till. Klicka Generate Keys för att generera credentials. |
| **My Keys** | Alla | Egna aktiva nycklar. Admin ser alla nycklar med Created By-kolumn. |
| **Admin** | Bara admin | Bucket-hantering och grant-hantering. |

**Admin-panel — Buckets:**
- Lista visar alla buckets som finns i Garage
- "Create in Garage" skapar en ny bucket direkt i Garage
- Rödmarkerad papperskorg raderar bucketen permanent från Garage (kräver att bucketen är tom)

**Admin-panel — Grants:**
- Tilldela en student (via e-post) tillgång till en specifik bucket
- Sök på e-post för att se en students nuvarande tilldelningar
- Klicka × på en grant för att återkalla den

### REST API

Alla endpoints kräver `Authorization: Bearer <token>`.

**Generera nyckel**
```
POST /api/keys/generate
Content-Type: application/json

{"bucketName": "ducklake", "permission": "read"}
```

**Lista nycklar**
```
GET /api/keys
```
Admins ser alla nycklar. Studenter ser bara sina egna.

**Ta bort nyckel**
```
DELETE /api/keys/{keyId}?pgUsername=dl_ro_xxxxxxxx
```
`pgUsername` är valfri — om den utelämnas tas bara Garage-nyckeln bort.

**Lista buckets (aktuell användare)**
```
GET /api/buckets
```
Admin: alla Garage-buckets. Student: bara tilldelade buckets.

**Admin — lista alla buckets**
```
GET /api/admin/buckets
```

**Admin — skapa bucket i Garage**
```
POST /api/admin/buckets
Content-Type: application/json

{"name": "ducklake-ml"}
```

**Admin — radera bucket från Garage**
```
DELETE /api/admin/buckets/{name}
```
Returnerar 409 om bucketen inte är tom.

**Admin — lista grants**
```
GET /api/admin/grants
GET /api/admin/grants?email=student@kth.se
```

**Admin — tilldela bucket-tillgång**
```
POST /api/admin/grants
Content-Type: application/json

{"studentEmail": "student@kth.se", "bucketName": "ducklake-ml"}
```

**Admin — återkalla bucket-tillgång**
```
DELETE /api/admin/grants
Content-Type: application/json

{"studentEmail": "student@kth.se", "bucketName": "ducklake-ml"}
```

**Hälsokontroll**
```
GET /healthz  →  200 OK
```

---

## Behörigheter

| Behörighet | Garage (S3) | PostgreSQL |
|---|---|---|
| `read` | GET på bucket | SELECT |
| `readwrite` | GET + PUT + DELETE | SELECT, INSERT, UPDATE, DELETE |

PostgreSQL-användare skapas med prefix `dl_ro_` (read) eller `dl_rw_` (readwrite).
Endast användare med dessa prefix kan tas bort — admin-kontot skyddas.

---

## Kodstruktur

```
src/main/java/com/ducklake/accessmanager/
├── api/
│   ├── AdminController.java                 # /api/admin/** (bucket + grant-hantering, kräver admin)
│   ├── BucketController.java                # /api/buckets (bucket-lista per användare)
│   ├── KeyController.java                   # /api/keys (generera, lista, ta bort nycklar)
│   └── HealthController.java                # /healthz
├── config/
│   └── SecurityConfig.java                  # OAuth2 JWT-validering, isAdmin(), endpoint-skydd
├── service/
│   ├── ObjectStoreAccessTokenManager.java   # Interface: listBuckets, createBucket, deleteBucket,
│   │                                        #   createReadOnlyKey, createReadWriteKey, deleteKey, listKeys
│   ├── DatabaseAccessTokenManager.java      # Interface: createReadOnlyUser, createReadWriteUser, deleteUser
│   ├── KeyMappingService.java               # Interface: saveMapping, findKeyIdsForUser, findOwner, ...
│   └── impl/
│       ├── GarageAccessTokenManager.java    # Garage Admin API v2 (HTTP mot port 3900)
│       ├── PostgresAccessTokenManager.java  # JDBC: skapar/tar bort dl_ro_/dl_rw_-användare
│       ├── PostgresKeyMappingService.java   # key_user_mapping-tabell i PostgreSQL
│       └── GrantService.java                # student_grants-tabell: grant/revoke/hasGrant per email+bucket
├── infrastructure/
│   └── garage/
│       ├── GarageBucketResponse.java        # DTO för GetBucketInfo + ListBuckets
│       ├── GarageKeyListItem.java           # DTO för ListKeys
│       └── GarageKeyResponse.java           # DTO för CreateKey
└── model/
    ├── AccessKey.java
    ├── Bucket.java
    ├── BucketGrant.java
    ├── DbCredentials.java
    ├── GeneratedCredentials.java
    ├── KeyListItem.java
    └── KeyRequest.java

src/main/resources/
├── static/index.html                        # Webb-UI (React + Babel standalone, ingen byggprocess)
└── application.properties
```

### Databasschema (PostgreSQL)

Tabellerna skapas automatiskt vid uppstart via `CREATE TABLE IF NOT EXISTS`.

```sql
-- Ägarskapsregister: kopplar Garage-nyckel-ID till Keycloak-användare
key_user_mapping (garage_key_id, keycloak_sub, display_name, created_at)

-- Bucket-tilldelningar: vilken student har tillgång till vilken bucket
student_grants (student_email, bucket_name, granted_at)
```

Bucket-listan hämtas direkt från Garage via `GET /v2/ListBuckets` — det finns ingen separat bucket-tabell i PostgreSQL.

---

## Lokal testning

Se [IMPLEMENTATION.md](IMPLEMENTATION.md) Fas 5 för fullständiga instruktioner med SSH-tunnlar.

Snabbstart:
```bash
# Öppna tunnlar i separata terminaler
# OBS: tunnel mot port 3900 (nginx), inte 3903 — 3903 är blockerad av NetworkPolicy
ssh -L 5433:localhost:5432 ducklake-catalog@deploy.cloud.cbh.kth.se
ssh -L 3900:localhost:3900 ducklake-garage@deploy.cloud.cbh.kth.se

# Konfigurera miljövariabler
cp .env.example .env  # fyll i värden

# Starta
export $(cat .env | grep -v '^#' | grep -v '^$' | xargs)
mvn spring-boot:run
```

---

## Driftsättning på cbhcloud

```bash
# Använd alltid en ny versionstagg — överskrivning av befintlig tag
# triggar INTE ny pull om noden har den cachad (imagePullPolicy: IfNotPresent)
docker build --network=host -t ghcr.io/wildrelation/ducklake-access-manager:v7 .
docker push ghcr.io/wildrelation/ducklake-access-manager:v7
```

Uppdatera sedan image-taggen i cbhcloud-deploymentet till den nya versionen.

**Miljövariabler:**

| Variabel | Värde |
|---|---|
| `POSTGRES_HOST` | `ducklake-catalog` |
| `POSTGRES_DB` | `ducklake` |
| `POSTGRES_ADMIN_USER` | `ducklake` |
| `POSTGRES_ADMIN_PASSWORD` | `cbhcloud` |
| `POSTGRES_PUBLIC_HOST` | publikt hostname för PostgreSQL i det genererade DuckDB-scriptet (valfri, standard: `POSTGRES_HOST`) |
| `GARAGE_ADMIN_URL` | `http://ducklake-garage:3900` |
| `GARAGE_ADMIN_TOKEN` | token från `/tmp/garage.toml` i ducklake-garage |
| `GARAGE_S3_ENDPOINT` | `ducklake-garage:3900` |
| `GARAGE_S3_REGION` | `garage` |
| `KEYCLOAK_ISSUER_URI` | `https://iam.cloud.cbh.kth.se/realms/cloud` (valfri, detta är standard) |
| `PORT` | `8080` |

> `POSTGRES_PORT` är hårdkodad till `5432` i koden — sätt den inte som miljövariabel (Kubernetes injicerar `POSTGRES_PORT=tcp://...` för services med samma namn, vilket korrumperar värdet).
> `GARAGE_ADMIN_URL` pekar på port **3900** (nginx), inte 3903.
> `GARAGE_S3_ENDPOINT` ska vara `host:port` utan protokoll — DuckDB lägger till http/https baserat på `USE_SSL`.
> `GARAGE_S3_REGION` måste matcha `s3_region` i `garage.toml` (standard: `garage`).

---

## Autentisering

Alla `/api/keys`-endpoints kräver ett giltigt JWT-token i `Authorization`-headern:

```
Authorization: Bearer <token>
```

Tokens valideras mot cbhclouds Keycloak-instans (`https://iam.cloud.cbh.kth.se/realms/cloud`). Realm och issuer-URI är konfigurerbara via `KEYCLOAK_ISSUER_URI`.

### Inloggningsflöde

Frontenden använder **OAuth2 Authorization Code Flow med PKCE** (public client, inget client secret):

1. Användaren klickar **Sign in with KTH**
2. Webbläsaren dirigeras till Keycloak med en PKCE code challenge
3. Efter inloggning skickas en `code` tillbaka till frontenden
4. Frontenden byter ut `code` mot ett access token via Keycloaks token-endpoint
5. Tokenet lagras i `sessionStorage` och skickas med alla API-anrop

**Keycloak-klient:**
- Realm: `cloud`
- Client ID: `ducklake`
- Public client (inget client_secret)
- Redirect URI: `https://ducklake-access-manager.app.cloud.cbh.kth.se/`

### Åtkomstregler

| Endpoint | Student | Admin |
|---|---|---|
| `GET /api/buckets` | Bara tilldelade buckets | Alla Garage-buckets |
| `POST /api/keys/generate` (read) | ✅ (kräver grant) | ✅ |
| `POST /api/keys/generate` (readwrite) | ❌ 403 | ✅ |
| `GET /api/keys` | Bara egna nycklar | Alla nycklar |
| `DELETE /api/keys/{keyId}` | Bara egna nycklar | Alla nycklar |
| `GET /api/admin/**` | ❌ 403 | ✅ |
| `POST /api/admin/**` | ❌ 403 | ✅ |
| `DELETE /api/admin/**` | ❌ 403 | ✅ |

En student kan bara generera nycklar till en bucket om hen har fått en grant via admin-panelen. Utan grant returneras 403 även för read-behörighet.

### Admin-roll

Admin avgörs av Keycloak-JWT-claimet `resource_access.ducklake.roles` — om listan innehåller `"admin"` behandlas användaren som admin. Det är en **client role** (inte realm role), vilket ger fin kontroll: admin här betyder admin specifikt för `ducklake`-klienten. Rollen tilldelas i cbhclouds Keycloak-konsol.

### Ägarskapsregistret

Vid generering sparas `(garage_key_id, keycloak_sub, display_name)` i tabellen `key_user_mapping` i PostgreSQL. `keycloak_sub` är Keycloaks interna UUID för användaren — oföränderlig även om e-postadressen ändras. `display_name` är e-postadressen från JWT (`email`-claimet, med fallback till `preferred_username`) och används för att visa ett läsbart namn i nyckellistan. Tabellen skapas automatiskt om den inte finns. Den används för:
- Filtrera `GET /api/keys` per användare
- Kontrollera ägarskap vid `DELETE`
- Visa skapare (display_name) i nyckellistan

Admins ser en extra kolumn **Created By** i nyckellistan som visar vem som skapat varje nyckel. Vanliga användare ser bara sina egna nycklar.

---

## Teknisk stack

| Del | Teknologi |
|---|---|
| Backend | Java 17 + Spring Boot 3.2.5 |
| Autentisering | Spring Security + OAuth2 Resource Server (JWT/JWKS) |
| PostgreSQL | JdbcTemplate (DDL direkt) |
| Garage | REST mot Admin API v2 |
| Frontend | Vanilla HTML/CSS/JS (ingen byggprocess) |
| Containerisering | Docker, ghcr.io |

---

## Kända problem och lösningar

### S3-fel vid körning av DuckDB-script inifrån kthcloud

**Symptom:** `AuthorizationHeaderMalformed: unexpected scope: .../local/s3/aws4_request` eller liknande signaturfel när man kör `SELECT` mot data i Garage.

**Orsak:** Tre separata konfigurationsfel i det genererade scriptet:

| Fel | Förklaring |
|---|---|
| `REGION 'local'` | Måste matcha `s3_region` i `garage.toml` — för denna instans är det `'garage'` |
| `ENDPOINT 'https://...'` | DuckDB accepterar inte protokoll i `ENDPOINT` — ska vara `host:port` utan `https://` |
| Publikt hostname | Det publika DNS-namnet för Garage löser inte upp inifrån kthcloud-clustret |

**Lösning:** Sätt miljövariablerna i `ducklake-access-manager`-deploymentet korrekt:

```
GARAGE_S3_ENDPOINT = ducklake-garage:3900    ← host:port, inget protokoll, internt namn
GARAGE_S3_REGION   = garage                  ← måste matcha s3_region i garage.toml
```

Det genererade scriptet ser då ut så här (korrekt):

```sql
CREATE OR REPLACE SECRET garage_secret (
    TYPE s3,
    PROVIDER config,
    KEY_ID 'GKxxxxxxxx',
    SECRET '...',
    REGION 'garage',           -- matchar s3_region i garage.toml
    ENDPOINT 'ducklake-garage:3900',  -- internt hostname, inget protokoll
    URL_STYLE 'path',
    USE_SSL false
);
```

**Varför fungerar det bara inifrån kthcloud?** `ducklake-garage` är ett internt Kubernetes-servicename som bara löser upp inom cbhcloud-clustret. Scriptet är avsett att köras från ett eget deployment på kthcloud (t.ex. JupyterLab), inte lokalt.

### Schema-fel vid uppgradering: `null value in column "keycloak_user"`

**Symptom:** `ERROR: null value in column "keycloak_user" of relation "key_user_mapping" violates not-null constraint` när en nyckel genereras efter en uppdatering av tjänsten.

**Orsak:** Tabellen `key_user_mapping` skapades i en äldre version med kolumnnamnet `keycloak_user`. Nyare versioner av koden använder `keycloak_sub`. `CREATE TABLE IF NOT EXISTS` lägger inte till nya kolumner i en befintlig tabell — den hoppar över hela satsen om tabellen redan finns.

**Lösning:** Droppa tabellen i produktion så att den återskapas med rätt schema vid nästa uppstart:

```bash
# SSH in i PostgreSQL-deploymentet
ssh ducklake-catalog@deploy.cloud.cbh.kth.se

# Anslut till psql
psql -U ducklake

# Droppa tabellen (data om nyckelägare försvinner, men Garage-nycklarna berörs inte)
DROP TABLE key_user_mapping;
\q
```

Starta sedan om `ducklake-access-manager`-deploymentet. Tabellen återskapas automatiskt med rätt kolumner (`garage_key_id`, `keycloak_sub`, `display_name`, `created_at`).

> **OBS:** Befintliga Garage-nycklar och PostgreSQL-användare påverkas inte — bara ägarskapsdata (vem som skapat vilken nyckel) försvinner. Nycklar som skapas efter omstarten registreras korrekt igen.

---

### 403 Invalid signature från Garage (nginx Host-header)

**Symptom:** `AccessDenied: Forbidden: Invalid signature` trots rätt KEY_ID, SECRET, region och endpoint. Reproducerbart med boto3.

**Orsak:** nginx:s standardvärde `proxy_set_header Host $host` skickar bara hostname utan port (`ducklake-garage`). S3 signature v4 inkluderar `Host`-headern i det signerade strängen. Klienten signerar med `Host: ducklake-garage:3900` (med port) men Garage tar emot `Host: ducklake-garage` (utan port) → signaturen matchar inte → 403.

**Lösning:** `proxy_set_header Host $http_host` i nginx.conf — bevarar hela headern inklusive port. Dokumenterat i [`garage-cbhcloud-quickstart`](https://github.com/WildRelation/garage-cbhcloud-quickstart).

---

### NoSuchBucket vid nyckelgenerering — bucket finns i katalogen men inte i Garage

**Symptom:** `HTTP 500` vid "Generate Keys". Logg: `NoSuchBucket: Bucket not found: <namn>` från `GetBucketInfo`.

**Orsak (v1–v3):** Bucket-katalogen lagrades i en separat PostgreSQL-tabell (`buckets`). Att lägga till en bucket i admin-panelen skapade bara en rad i databasen — inte bucketen i Garage. `GetBucketInfo?globalAlias=<namn>` returnerade 404 eftersom bucketen inte existerade i Garage.

**Lösning (v4+):** Bucket-listan hämtas direkt från Garage via `GET /v2/ListBuckets`. PostgreSQL-katalogen är borttagen. Alla Garage-buckets syns automatiskt i admin-panelen. Knappen "Create in Garage" i admin-panelen skapar bucketen via `POST /v2/CreateBucket`. Grants lagras i tabellen `student_grants` med bucket-namn (inte UUID FK).

**Manuell fix (om bucketen saknas i Garage):** SSH in i Garage-containern och kör:
```bash
garage bucket create <bucket-namn>
```

---

### Deployment kör gammal kod trots ny image pushad (imagePullPolicy-cache)

**Symptom:** Buggar kvarstår trots att ny image är byggd och pushad. `Creating Garage bucket:`-loggrader (tillagda som debug) syns aldrig. Deployment beter sig som om gammal kod körs.

**Orsak:** Kubernetes standard-`imagePullPolicy` är `IfNotPresent` — om imagen med given tag redan finns cachad på noden dras den inte om, även om en ny version pushats med samma tag. Att pusha `:v3` igen gav inte en ny pull.

**Lösning:** Använd en ny tag för varje release (`:v4`, `:v5` osv.) istället för att överskriva befintlig tag. Alternativt sätt `imagePullPolicy: Always` i deployment-manifestet och kör `kubectl rollout restart deployment/<namn>`.

---

### My Keys visar fel bucket-namn och fel behörighet (v6 och tidigare)

**Symptom:** Bucket-kolumnen i My Keys visar `key-ducklake` istället för `ducklake`. Permission-kolumnen visar alltid "Read-only", även för readwrite-nycklar.

**Orsak (bugg 1 — bucket-namn):** `parseKeyItem` i `GarageAccessTokenManager` strippade inte `key-`-prefixet från nyckelnamnet. Nyckelnamn har formatet `key-{bucket}|{pgUsername}` men bara delen efter `|` parsades, inte prefixet.

**Orsak (bugg 2 — permission):** Garage `ListKeys` returnerar inte behörighetsnivå per nyckel. `parseKeyItem` returnerade alltid `null` för `permission`, vilket fick UI:t att alltid visa "Read-only".

**Lösning (v7+):** Nyckelnamnsformatet utökades till `key-{bucket}|{pgUsername}|{permission}`. `parseKeyItem` strippar nu `key-`-prefixet och läser permission ur det tredje fältet. Gamla nycklar utan permission-fält hanteras bakåtkompatibelt (visas som null/"Read-only").

---

## Återstående arbete

- **Java-tutorial** — lägg till ett avsnitt i Student deployment guide som visar hur man ansluter till DuckLake från ett Java-deployment på cbhcloud (AWS SDK v2 för S3, JDBC för PostgreSQL)
