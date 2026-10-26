# Minecraft session proof v2

`POST /api/v1/auth/minecraft/challenge` returns `challenge_id`, `server_id`,
`expires_in`, and `proof_version: 2`. The client rejects any other version before
calling Mojang. `expires_in` is an integer from 1 to 60 seconds; values outside
that range are rejected. The `server_id` field is a random seed, not the final proof.

The client generates 32 random bytes after receiving the challenge. It encodes
these as canonical unpadded base64url in `client_nonce` (43 characters). The final
Mojang server ID is the first 20 bytes of SHA-256, encoded as 40 lowercase hex
characters, including leading zeros.

The hash covers these fields in order. Each field starts with its byte length as
an unsigned four-byte big-endian integer:

1. UTF-8 `odyssey/minecraft-session-proof/v2`.
2. UTF-8 `account`.
3. UTF-8 canonical HTTP origin of the configured backend.
4. UTF-8 `challenge_id` (canonical base64url encoding of 32 bytes).
5. The 20 bytes decoded from `server_id`.
6. The 32 bytes decoded from `client_nonce`.

The canonical origin uses a lowercase scheme and hostname, omits the default
port and trailing slash, and includes any nondefault port. IPv6 hosts use the
WHATWG URL representation: bracketed lowercase hexadecimal with compressed zero
runs, including hexadecimal IPv4-mapped segments. The client uses its
configured `BackendOrigin`; the backend uses `PUBLIC_BASE_URL`. Challenge bodies
and forwarding headers cannot select this origin. Development loopback HTTP
origins follow the same rule.

The client calls Mojang with the derived ID and reveals `client_nonce` only after
that call succeeds. `POST /api/v1/auth/minecraft/complete` accepts `challenge_id`,
`username`, and `client_nonce`. Missing or invalid nonces receive HTTP 426 with
`session_proof_required` before the backend consumes the challenge or contacts
Mojang. The backend derives the same ID from the stored seed. No raw-seed fallback
exists. Existing challenge expiry, IP binding, single use, identity linking, and
guild checks still apply.

The backend and mod require a coordinated release. Older mods cannot establish a
new session with the v2 backend. Existing bearer sessions retain their expiry.
Both repositories contain the same proof vectors, including a leading-zero ID.
