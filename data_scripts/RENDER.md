# Render scheduled jobs

The root `render.yaml` mirrors the job logic of the original GitHub workflows,
using Python 3.13 and `data_scripts/requirements.txt`, with these Render schedules:

| Job | UTC schedule | Singapore time | Command |
| --- | --- | --- | --- |
| Daily ingestion and both predictors | Daily 21:00 | Daily 05:00 next day | `bash data_scripts/render_cron.sh` |
| Weekly tuning of both models and targets | Sunday 22:00 | Monday 06:00 | `bash data_scripts/render_weekly_tune.sh` |

GitHub intentionally retains earlier schedules: daily at 19:30 UTC and Sunday
at 20:30 UTC, to compensate for observed queue delays. Those workflow schedules
are unchanged; Render uses the intended start times above.

Daily ingestion exits 0 (success) or 2 (partial data) allow both predictors to
run. Any other ingestion exit stops the job. Both predictors are attempted even
if one fails; either failure makes the job fail. Weekly tuning propagates
`update.py`'s exit status, including its reference-RMSE refresh failure.

## Deployment and cutover

1. Push these files to the branch you intend to deploy and select that branch
   when creating the Render Blueprint. Keep the service root at the repository
   root: the scripts also import code from `model/`.
2. Supply `DATABASE_URL` to both services and `GRIDSTATUS_API_KEY` / `EIA_API_KEY`
   to the daily service. Use the same database and keys as the existing workflows.
   Existing active `model_artifacts`, seeded `validation_state`, required schema
   migrations, and sufficient historical data must be present in that database.
3. Choose compute with enough memory for PyTorch and weekly model fitting; the
   Blueprint does not select a compute size. Validate actual runtime and peak
   memory on Render. Cron runs have a 12-hour maximum.
4. Coordinate the first manual run with the existing GitHub jobs. Inspect Render
   logs and database outputs. A weekly `DRY_RUN=true` run scores candidates
   without promoting artifacts or advancing the validation cursor.
5. After successful verification, disable the two GitHub workflows or remove
   their automatic triggers. Until then, both platforms still schedule runs.
   The daily GitHub workflow also has a push trigger on `model/retraining`.
   Render builds on pushes but does not reproduce that immediate pipeline run.

Render serializes scheduled runs of each individual cron service, but it does
not coordinate with GitHub or the other service. The one-hour daily/weekly gap
does not guarantee ordering. Manually triggering an already-running Render cron
cancels its current run, unlike GitHub's `cancel-in-progress: false` behavior.

## Manual workflow options

Render does not provide GitHub's `workflow_dispatch` input form. The wrappers
accept these optional environment variables instead:

| Service | Variable | Equivalent workflow input | Default |
| --- | --- | --- | --- |
| Daily | `ONLY` | `only` | All datasets |
| Daily | `ANCHOR_DATE` | `anchor_date` | Today UTC |
| Daily | `HORIZON` | `horizon` | `both` |
| Weekly | `TARGET` | `target` | Both targets |
| Weekly | `KIND` | `kind` | Both model kinds |
| Weekly | `DRY_RUN` | `dry_run` | `false` |

Set overrides before a manual run and remove them afterward: Render environment
variables persist and otherwise affect subsequent scheduled runs too. Python
entrypoints validate date, horizon, target, and kind values.

Model artifacts and validation state live in Supabase. Render's temporary local
files are sufficient; no persistent disk is needed. This repository review does
not replace a successful build and run against your database on Render.

References: [Cron jobs](https://render.com/docs/cronjobs),
[Python version](https://render.com/docs/python-version),
[Blueprint specification](https://render.com/docs/blueprint-spec).
