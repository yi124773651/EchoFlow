# Frontend Conventions

If this file conflicts with `CLAUDE.md`, `CLAUDE.md` wins.

## 1. Core Rules
- Use Next.js App Router.
- RSC First: fetch and render on the server by default.
- Use `'use client'` only for real browser interaction or client hooks.
- TypeScript strict mode is mandatory.
- `any` is forbidden.

## 2. Structure
Recommended directories:
- `app/`
- `components/ui/`
- `features/`
- `hooks/`
- `services/`
- `types/`
- `lib/`

## 3. API Rules
- Keep API access centralized in `services/`.
- Do not scatter raw `fetch` calls across UI components.
- Backend and frontend contracts must stay aligned.
- Prefer generated or schema-derived types when available.
- If backend DTO/API changes, run `/sync-api`.

## 4. UI Rules
- `components/ui/` is for base ShadcnUI components only.
- Do not put business logic into `components/ui/`.
- Business UI belongs in `features/`.
- Keep page components thin.

## 5. State Rules
- Keep local UI state local.
- Do not put business workflow logic into presentational components.
- Extract reusable client behavior into hooks.
- Server data should stay server-first unless interaction requires client state.

## 6. Forms and Errors
- Validate input near the form boundary.
- Frontend validation never replaces backend validation.
- Handle backend `ProblemDetail` consistently.
- Every async UI must handle:
    - loading
    - empty
    - error

## 7. Streaming Rules
- Polling is forbidden for chat or agent progress.
- Use real streaming/SSE consumption.
- Parse structured JSON events only.
- Support cleanup/cancel on unmount or route change.
- Handle terminal `completed` / `failed` events explicitly.

## 8. Done
A frontend change is not done unless:
- server/client boundary is intentional
- types are strict
- API contract stays aligned
- loading/error states exist
- streaming uses SSE/streaming, not polling