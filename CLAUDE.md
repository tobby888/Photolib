# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

PhotoLib is a photo workstation for a campus photography department: projects/requests, multi-person claiming, upload/management, adoption, worklogs, statistics export, in-app notifications, and admin. React 19 SPA + Spring Boot 4 backend; Maven packages the frontend into the backend JAR.

**`AGENTS.md` is the authoritative handoff doc** — it records per-module business rules, invariants, and "what breaks if you touch this." Read the relevant section there before modifying any module (audit export, notifications, storage/OSS, photo compression/cleanup, legacy migration, branding, worklogs, permissions). `README.md` covers deployment (OSS setup, systemd, Linux). This file is the quick-start; don't duplicate those.

## Commands

Frontend (repo root):
```powershell
npm ci
npm run dev        # Vite dev server on :5173, proxies /api -> localhost:8080
npm run build      # tsc -b && vite build  (this is the frontend "typecheck + build")
npm run lint       # eslint .
```

Backend (`cd backend`, use `.\mvnw.cmd` on Windows / `./mvnw` on macOS/Linux):
```powershell
.\mvnw.cmd spring-boot:run                 # run backend on :8080
.\mvnw.cmd test                            # all backend tests
.\mvnw.cmd -Dtest=AuditLogMapperTests test # single test class
.\mvnw.cmd clean package                   # full build incl. frontend -> fat JAR
```
`clean package` runs `npm ci` + the React production build via a Maven-managed Node (no global Node needed) and writes `dist/` into the JAR's `static` dir.

Full end-to-end business-flow QA (permissions/state transitions): `.\scripts\qa_full_flow.ps1` — never point it at prod DB, real OSS, or real mail recipients.

Requires Node 20+, Java 21, MySQL 8. Local backend needs a `backend/.env` with `SPRING_PROFILES_ACTIVE=local` (disk storage instead of OSS) — see README §本地运行.

## Architecture

**Frontend** — Vite + React 19 + Ant Design 6, in `src/`. Uses **Hash Router** (`/#/projects`) so the SPA survives being served from the backend JAR without SPA-fallback config. Flat structure: pages in `src/pages/`, all API types in `src/types.ts`, request layer in `src/api.ts` (the `api` wrapper unwraps standard JSON envelopes; use the raw `http` axios instance only for Blob downloads, and always `URL.revokeObjectURL` after).

**Backend** — `backend/src/main/java/cn/photolib/`, one package per domain (`project`, `request`, `photo`, `adoption`, `worklog`, `statistics`, `notification`, `audit`, `storage`, `migration`, `auth`, `admin`, `campus`, `user`). Standard layering per package: **Controller** (params, authz, HTTP) → **Service** (business rules) → **Mapper** (MyBatis-Plus queries). Cross-cutting code lives in `common/` (`api/ApiResponse`, `api/PageResponse`, `error/BusinessException` + `ErrorCode` + `GlobalExceptionHandler`).

Conventions that matter across the codebase:
- All APIs are prefixed `/api/v1`. Controllers return `ApiResponse<T>` (paged: `PageResponse<T>`); binary/file-download responses are the exception. Signal expected errors with `BusinessException` + `ErrorCode`, not bare runtime exceptions.
- Auth: access + refresh tokens, `AccessTokenFilter`/`SecurityConfig` in `auth/`. Three roles — `ADMIN`, `MINISTER`, `CAMPUS_MANAGER`. **The backend is the authorization boundary**; frontend button-hiding is not access control. Campus managers are scoped to their authorized campus and own participation.
- Storage is abstracted behind `ObjectStorageService` (in `storage/`) — never call the OSS SDK from business code. Local profile = disk; prod = private OSS via short-lived presigned URLs. Upload `Content-Type` must exactly match the value used when the upload signature was generated.
- **Schema changes require a new Flyway migration** in `backend/src/main/resources/db/migration/` (`V1..V8` present) — never edit a released migration script.
- Times use `Asia/Shanghai`; date filters must be explicit about inclusive/exclusive bounds (audit export treats `to` as "before next-day midnight" to include the whole end day).
- Write operations should consider optimistic locking, soft delete, role boundaries, and audit logging. The audit interceptor records **write operations only**; when adding a write endpoint, verify resource type / resource ID / request ID / details are captured.

## Verification expectations

Run verification proportional to the change. Frontend changes: `npm run build` + `npm run lint`. Backend changes: targeted `-Dtest=...` then `.\mvnw.cmd test`. Anything touching the full business chain, permissions, or state transitions: `qa_full_flow.ps1`. Before delivering, run `clean package` if the change could affect the frontend-in-JAR packaging. See `AGENTS.md` §5–6 for the per-area checklist (notifications: verify in-app + unread badge + mail-failure fallback; photos: verify both disk and OSS impls, don't delete originals or adopted photos; exports: verify Chinese/special chars/nulls/filters/large data).

## OSS integration tests

OSS integration tests must only run when valid credentials are explicitly provided — ordinary local/CI runs must not connect to a real bucket. Never commit AccessKeys, private bucket endpoints, signing secrets, or `.env`.
