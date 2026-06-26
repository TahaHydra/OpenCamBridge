const http = require('http');

const options = {
  hostname: '127.0.0.1',
  port: 8080,
  path: '/health',
  method: 'OPTIONS',
  headers: {
    'Origin': 'http://localhost:1420',
    'Access-Control-Request-Method': 'GET',
    'Access-Control-Request-Headers': 'Content-Type'
  }
};

const req = http.request(options, (res) => {
  console.log(`STATUS: ${res.statusCode}`);
  console.log('HEADERS:');
  console.log(res.headers);
  res.on('data', (chunk) => {
    console.log(`BODY: ${chunk}`);
  });
});

req.on('error', (e) => {
  console.error(`problem with request: ${e.message}`);
});

req.end();
