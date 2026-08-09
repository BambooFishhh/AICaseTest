---
name: "version-iteration"
description: "Executes a full version iteration cycle for AICaseTest project: PRD + tech review docs + implementation + verification + GitHub push. Invoke when user asks to start/plan/implement a new version iteration or says '迭代' or '新版本'."
---

# Version Iteration Workflow

This skill encapsulates the complete iteration workflow for the AICaseTest project, based on the established collaboration pattern from v1.0 through v1.4.

## When to Invoke

- User asks to start a new version iteration (e.g., "开始 v1.5", "迭代新版本")
- User asks to plan a version (e.g., "规划 v1.5", "写个方案")
- User says "迭代" or "新版本"
- User asks to implement a version that has already been planned

## Complete Iteration Steps

Each version iteration MUST follow these steps in order:

### Phase 1: Documentation (文档阶段)

1. **Research current codebase** — Read relevant existing files to understand current state before writing any docs
2. **Write PRD** — Create `docs/v{version}/PRD_v{version}_{theme}.md`
   - Iteration background & pain points analysis
   - Scope (In/Out of scope)
   - Feature details
   - Acceptance criteria
   - Risks & mitigations
   - Deliverables checklist
3. **Write backend tech review** — Create `docs/v{version}/后端技术评审_v{version}.md`
   - Change points with code snippets
   - File change list
   - API contract changes
   - Backward compatibility
   - Test verification
4. **Write frontend tech review** — Create `docs/v{version}/前端技术评审_v{version}.md`
   - Change points with template/script snippets
   - props/emit changes
   - Data flow
   - Backward compatibility
   - Test verification
5. **Skip review notification** — Do NOT use `NotifyUser`; proceed directly to implementation after docs are written

### Phase 2: Backend Implementation (后端实现)

6. **Implement backend changes** based on tech review
7. **Compile verification**:
   ```powershell
   $env:JAVA_HOME="C:\Users\DislikeTomato\.jdks\ms-17.0.18"
   $env:Path="$env:JAVA_HOME\bin;$env:Path"
   cd backend; mvn compile "-Dmaven.repo.local=../.m2-repo" 2>&1 | Select-Object -Last 5
   ```
8. **Fix compilation errors** if any, re-compile until BUILD SUCCESS

### Phase 3: Frontend Implementation (前端实现)

9. **Implement frontend changes** based on tech review
10. **Build verification**:
    ```powershell
    cd frontend; npm run build 2>&1 | Select-Object -Last 10
    ```
11. **Fix build errors** if any, re-build until success

### Phase 4: Documentation Update (文档更新)

12. **Update CHANGELOG** — Add new version section at the top of `docs/CHANGELOG.md` with:
    - Date, baseline, theme
    - Backend changes table (file | change | purpose)
    - Frontend changes table
    - Verification results
13. **Update README** — Add new version section with bullet points + update roadmap table status to ✅ + update API overview if new endpoints

### Phase 5: Git Commit & Push (提交推送)

14. **Stage all changes**:
    ```powershell
    cd e:\java_project\AICaseTest; git add -A
    ```
15. **Verify staged files** (check no .env or sensitive files):
    ```powershell
    git status --short | Select-Object -First 15
    ```
16. **Commit** with format:
    ```powershell
    git commit -m "v{version}: {theme} - {key changes summary}"
    ```
17. **Push to GitHub**:
    ```powershell
    git push origin main
    ```

## Key Rules

### JDK Setup
- JDK 17 path: `C:\Users\DislikeTomato\.jdks\ms-17.0.18`
- Must set `JAVA_HOME` and `Path` before `mvn` commands
- Maven repo: `../.m2-repo` (local, gitignored)

### Git Safety
- NEVER commit `.env` file (contains API keys)
- NEVER use `git add -A` without checking status first
- NEVER force push
- Always verify staged files before commit

### Documentation Principles
- Every iteration MUST have PRD + backend tech review + frontend tech review
- Tech review docs should note version in header: "一旦确定尽量不要轻易改动"
- README must be updated every iteration (user requirement)
- CHANGELOG must be updated every iteration

### Communication
- Use Chinese for all documentation and responses
- Do NOT use `NotifyUser` — proceed directly to implementation after docs
- Use `TodoWrite` to track progress throughout
- Summarize changes table at the end (file | change | purpose)
- **Auto-continue to next version**: After completing a version, self-evaluate what the next version should focus on and start the next iteration automatically
- **Only ask user (via AskUserQuestion) in these cases**:
  1. Every 5 versions (e.g., before starting v6.0 if currently at v5.x) — confirm direction
  2. Before starting AI 用例执行 (v2.0 or equivalent) — confirm approach
  3. Otherwise, no need to ask — just proceed autonomously

### File Paths
- Project root: `e:\java_project\AICaseTest`
- Backend: `backend/src/main/java/com/testagent/`
- Frontend: `frontend/src/`
- Docs: `docs/v{version}/`
- CHANGELOG: `docs/CHANGELOG.md`
- README: `README.md`

### Version Naming
- PRD: `PRD_v{version}_{theme}.md`
- Backend review: `后端技术评审_v{version}.md`
- Frontend review: `前端技术评审_v{version}.md`
- Commit message: `v{version}: {theme} - {summary}`
