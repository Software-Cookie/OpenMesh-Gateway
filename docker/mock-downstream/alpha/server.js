// Downstream Alpha: echo service that shows what the gateway forwarded and
// verifies the gateway's HMAC identity signature (X-Gateway-Signature).
const http = require('http');
const crypto = require('crypto');

const PORT = Number(process.env.PORT || 8081);
const SECRET = process.env.IDENTITY_SIGNING_SECRET || 'dev-only-identity-signing-secret-change-me';
const MAX_CLOCK_SKEW_MS = 60_000;

function verifySignature(headers) {
    const signature = headers['x-gateway-signature'];
    if (!signature) return { valid: false, reason: 'missing signature' };

    const timestamp = headers['x-gateway-timestamp'] || '';
    if (Math.abs(Date.now() - Number(timestamp)) > MAX_CLOCK_SKEW_MS) {
        return { valid: false, reason: 'timestamp outside allowed window' };
    }

    // Must match IdentityHeaderSigner on the gateway
    const canonical = [
        headers['x-user-id'] || '',
        headers['x-tenant-id'] || '',
        headers['x-user-roles'] || '',
        headers['x-user-scopes'] || '',
        headers['x-auth-method'] || '',
        timestamp,
    ].join('\n');
    const expected = 'v1=' + crypto.createHmac('sha256', SECRET).update(canonical).digest('base64url');

    const a = Buffer.from(signature);
    const b = Buffer.from(expected);
    const valid = a.length === b.length && crypto.timingSafeEqual(a, b);
    return { valid, reason: valid ? 'ok' : 'signature mismatch' };
}

const server = http.createServer((req, res) => {
    req.resume();
    req.on('end', () => {
        const body = {
            service: 'downstream-service-alpha',
            status: 'UP',
            timestamp: new Date().toISOString(),
            method: req.method,
            path: req.url,
            identity: {
                userId: req.headers['x-user-id'] || null,
                tenantId: req.headers['x-tenant-id'] || null,
                roles: req.headers['x-user-roles'] || null,
                scopes: req.headers['x-user-scopes'] || null,
                authMethod: req.headers['x-auth-method'] || null,
                gatewayVerified: req.headers['x-gateway-verified'] || null,
            },
            signature: verifySignature(req.headers),
            traceparent: req.headers['traceparent'] || null,
            requestId: req.headers['x-request-id'] || null,
            apiKeyForwarded: Boolean(req.headers['x-api-key']),
        };
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(body, null, 2));
    });
});

server.listen(PORT, '0.0.0.0', () => console.log(`downstream-service-alpha listening on ${PORT}`));
