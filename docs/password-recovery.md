# Password recovery

The sign-in page offers email recovery for verified accounts. Users paste the emailed recovery code and enter their new password. Unknown and unverified email addresses receive the same public response.

- Codes use 32 random bytes, are stored only as BCrypt hashes, expire after 10 minutes, and are consumed on success.
- Requests for a given account are limited to one per minute. A successful resend replaces the previous code.
- Registration OTPs cannot reset passwords. Recovery does not verify unverified accounts.
- Password updates and code consumption run in one transaction with a user-row lock. Existing refresh tokens are revoked; already-issued access tokens remain valid until their normal expiry (15 minutes).
- SMTP delivery failures are logged without the code and preserve any previous code. The public response stays generic to avoid disclosing account existence.

Deploy the updated backend alongside the frontend. The repository's Hibernate `ddl-auto=update` setting adds the three nullable user columns automatically; environments with managed schemas should apply `V11__password_recovery.sql` before rollout. Email uses the existing SMTP configuration. Verify delivery with a dedicated test account after deployment; automated tests mock email delivery.

Public POST endpoints (also available with `/api/auth` prefix):

- `/forgot-password`: `{ "email": "user@example.com" }`
- `/reset-password`: `{ "email": "user@example.com", "token": "<43-character emailed code>", "password": "<new password>" }`
