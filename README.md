# CS203 Grid-Aware Compute Scheduler

GACS schedules compute workloads around grid conditions, including electricity prices,
generation data, and weather signals.

## Repository layout

- `backend/`: Spring Boot API and authentication services
- `frontend/`: Next.js application
- `data_scripts/`: EIA, GridStatus, and Open-Meteo data pulls
- `docs/`: API and operational documentation

## Environment setup

Copy the template and fill in local credentials:

```bash
cp .env.example .env
```

The data scripts and backend read the root `.env`. Keep `.env` private and never commit it.

## Data scripts

Install the shared Python dependencies:

```bash
python3 -m pip install -r data_scripts/requirements.txt
```

Run the individual pulls from the repository root:

```bash
python data_scripts/eia_pull.py
python data_scripts/update_weather_data.py
python data_scripts/gridstatus_pull.py
```

Data is written under `data/`, which is intentionally gitignored. The GridStatus puller
supports recent updates, monthly pulls, historical backfills, and alternate ERCOT zones.
See [data_scripts/README.md](data_scripts/README.md) for its options and schema.

Run the offline data-script tests with:

```bash
python3 -m pip install -r data_scripts/requirements-dev.txt
cd data_scripts
pytest
```

## Backend

```bash
cd backend
./mvnw spring-boot:run
```

The backend uses the database and mail settings from the root `.env`.

## Frontend

```bash
cd frontend
npm install
npm run dev
```

The frontend is available at `http://localhost:3000` by default.

## Deployment & CI/CD Documentation

- **[Deployment Runbook & Rollback Procedures](docs/deployment-runbook.md)** (AC4.1): Complete operational guide, environment matrix, health checks, and failure rollback playbooks (Render, Vercel, Database).
- **[Continuous Deployment (CD) Setup Guide](docs/deployment-setup.md)** (AC3.1): Setup guide for Render, Vercel, and GitHub Actions repository secrets.
- **[Branch Workflow](docs/branch-workflow.md)**: Staging-to-main PR workflow and branch protection rules.