# GACS frontend

The frontend uses the Spring Boot API for authentication, jobs, prices, approvals, and saved schedules.
It does not provide demo accounts or substitute fabricated data when a feed is unavailable.

## Local development

1. Run `npm ci` from this directory.
2. Copy `.env.example` to `.env.local` and set `NEXT_PUBLIC_API_BASE_URL` to the backend URL.
3. Run `npm run dev` and open `http://localhost:3000`.

## Production

Set `NEXT_PUBLIC_API_BASE_URL` to the deployed backend HTTPS origin in your hosting build
environment. Next.js embeds this public value at build time; rebuild after changing it. The
legacy `NEXT_PUBLIC_API_URL` variable is also accepted. Without either variable, protected API
requests fail visibly rather than attempting to contact a production user's localhost.

Configure the backend allowed CORS origins for the frontend hostname and its authentication
cookies for your deployment. Public API configuration must not contain credentials.

Run `npm test`, `npm run lint`, and `npm run build` before deployment. Use `npm start` to serve
the production build locally. If Turbopack cannot start its CSS worker in a restricted local
environment, `npm run build -- --webpack` produces a production build using webpack.

Create jobs in **Compute Jobs**, generate a recommendation, and use **Approvals** to approve
and add it to the calendar. Calendar status changes update saved state; they do not operate
compute hardware. Settlement prices and forecasts come from stored API records. Renewable
shares and confidence bands are not shown because the API does not supply those measurements.
