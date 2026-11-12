# Gmail API email delivery

All verification codes, password recovery codes, and forecast alerts use Gmail's
HTTPS API on port 443. SMTP and Gmail app passwords are no longer used.
The existing MIME templates retain both plain text and HTML.

## Cost and limits

Standard Gmail API usage is available at no additional cost. A billing account or
payment card is not required for standard usage; leave billing disabled on the
project and do not sign up for a Google Cloud free trial or paid quota increases.
Google documents future billing for usage above standard daily thresholds, with
advance notice. This is not an unlimited sending service: the sender account's
normal daily Gmail sending limits still apply, independently of API quotas.

Sources checked November 11, 2026:

- [Gmail pricing and quotas](https://developers.google.com/workspace/gmail/api/reference/quota)
- [Workspace API standard usage and billing changes](https://developers.google.com/workspace/tools-safety)
- [Gmail sending limits and API errors](https://developers.google.com/workspace/gmail/api/guides/handle-errors)

## One-time Google setup

Only the application's sender Gmail account authorizes access. People receiving
GACS emails do not need to connect Google accounts or use Gmail themselves.

1. Open [Google Cloud Console](https://console.cloud.google.com/) and create or
   select a project. Leave its billing account unlinked.
2. In **APIs & Services → Library**, enable **Gmail API**.
3. Configure **Google Auth Platform** branding, audience, and contact email.
   For a personal Gmail account use an **External** audience. While testing, add
   the sender Gmail address as a test user. Request only
   `https://www.googleapis.com/auth/gmail.send` (send email; no inbox access).
4. Create an OAuth client with application type **Web application**. Add this
   exact authorized redirect URI: `https://developers.google.com/oauthplayground`.
   Save its client ID and client secret privately.
5. Open [OAuth 2.0 Playground](https://developers.google.com/oauthplayground/).
   In its settings, enable **Use your own OAuth credentials**, enter the client
   ID and secret from step 4, and use **Offline** access with a consent prompt.
6. In step 1 of the Playground, enter
   `https://www.googleapis.com/auth/gmail.send` and authorize it. Sign in as the
   Gmail account that GACS will send from. In step 2, exchange the authorization
   code for tokens. Save the **refresh token**, not the short-lived access token.
   Use your own client credentials; Playground's shared credentials have their
   own refresh-token restrictions.
7. For ongoing deployment, change the OAuth app's publishing status from
   **Testing** to **In production**, then authorize again and use the new refresh
   token. External apps in Testing receive refresh tokens that expire after
   **7 days** with Gmail scopes. Publishing status and Google verification are
   separate: a sender-only personal app may qualify for a verification exception,
   while publicly distributed OAuth access may require verification. Follow the
   status and requirements shown in your Google console.

See Google's [refresh token expiration rules](https://developers.google.com/identity/protocols/oauth2#expiration)
and [OAuth verification exceptions](https://support.google.com/cloud/answer/13464323).
Tokens can also stop working after revocation or password changes. If that
happens, authorize again and replace the deployed refresh token.

## Configure local development and Render

Set these in the root `.env` locally, and in the **existing backend web service →
Environment** on Render:

```dotenv
GMAIL_SENDER=your-sender@gmail.com
GMAIL_CLIENT_ID=your-client-id.apps.googleusercontent.com
GMAIL_CLIENT_SECRET=your-client-secret
GMAIL_REFRESH_TOKEN=your-refresh-token
```

`GMAIL_SENDER` must be the Gmail account that authorized the refresh token (or a
verified send-as alias on that account). Remove the old `MAIL_USERNAME` and
`MAIL_PASSWORD` variables. Keep credentials in secret environment variables;
never commit them, put them in frontend variables, or paste them into logs.
No Google OAuth callback endpoint is exposed by GACS: the one-time authorization
happens in Google's Playground and only the sender's refresh token is deployed.

Save the environment changes and redeploy the backend. The existing
`render.yaml` defines only a data cron job; Gmail settings belong on the backend
web service, not that cron job.

The backend caches access tokens in memory and refreshes them automatically.
Requests have bounded connect/read timeouts. An explicit HTTP 401 triggers one
token refresh and retry; ambiguous delivery failures are not automatically
retried, to avoid duplicate emails. Missing credentials cause verification and
password reset sends to fail; forecast email failures do not break forecast
retrieval. Codes and provider response bodies are not logged.

## Verify after deployment

Using a dedicated account you control, request registration verification and
password recovery, check inbox/spam and the sender's Sent folder, and confirm
the codes work. Check forecast alerts when a qualifying forecast is available.
Password recovery intentionally returns a generic public response even if email
fails, so check backend logs for the sanitized failure category if no email
arrives. Automated tests mock Google's HTTP endpoints and send no real email.
