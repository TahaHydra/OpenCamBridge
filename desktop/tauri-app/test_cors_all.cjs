const http = require('http');

function testCors(path, method, headers) {
  const options = {
    hostname: '127.0.0.1',
    port: 8080,
    path: path,
    method: 'OPTIONS',
    headers: {
      'Origin': 'http://localhost:1420',
      'Access-Control-Request-Method': method,
      'Access-Control-Request-Headers': headers
    }
  };

  const req = http.request(options, (res) => {
    console.log(`[${method} ${path}] STATUS: ${res.statusCode}`);
    console.log(res.headers);
  });
  req.on('error', (e) => console.error(e.message));
  req.end();
}

testCors('/health', 'GET', '');
testCors('/api/camera/status', 'GET', '');
testCors('/api/settings', 'POST', 'Content-Type');
testCors('/api/camera/list', 'GET', '');
