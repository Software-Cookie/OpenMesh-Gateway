// Downstream Beta: chaos service used to demonstrate circuit breakers and timeouts.
//   /chaos/delay?ms=2500     responds after a delay (gateway times out at 1500 ms -> 504)
//   /chaos/error?status=500  responds with an error status (gateway fallback -> 502)
//   anything else            normal 200 response
const http = require('http');

const PORT = Number(process.env.PORT || 8082);
const MAX_DELAY_MS = 30_000;

function json(res, status, body) {
    res.writeHead(status, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(body, null, 2));
}

const server = http.createServer((req, res) => {
    req.resume();
    const url = new URL(req.url, `http://localhost:${PORT}`);
    const path = url.pathname;

    if (path.endsWith('/chaos/delay')) {
        const delayMs = Math.min(Math.max(parseInt(url.searchParams.get('ms') || '2000', 10) || 0, 0), MAX_DELAY_MS);
        setTimeout(() => json(res, 200, {
            service: 'downstream-service-beta',
            status: 'DELAYED_RESPONSE',
            delayMs,
            timestamp: new Date().toISOString(),
        }), delayMs);
        return;
    }

    if (path.endsWith('/chaos/error')) {
        let status = parseInt(url.searchParams.get('status') || '500', 10);
        if (!(status >= 400 && status <= 599)) status = 500;
        json(res, status, {
            service: 'downstream-service-beta',
            status: 'ERROR',
            simulatedStatusCode: status,
            timestamp: new Date().toISOString(),
        });
        return;
    }

    json(res, 200, {
        service: 'downstream-service-beta',
        status: 'UP',
        method: req.method,
        path: req.url,
        tenantId: req.headers['x-tenant-id'] || null,
        userId: req.headers['x-user-id'] || null,
        timestamp: new Date().toISOString(),
    });
});

server.listen(PORT, '0.0.0.0', () => console.log(`downstream-service-beta listening on ${PORT}`));
