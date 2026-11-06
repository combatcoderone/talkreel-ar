const test = require('node:test');
const assert = require('node:assert/strict');
const app = require('./server');

let server;
let baseUrl;

test.before(async () => {
  server = app.listen(0);
  await new Promise(resolve => server.once('listening', resolve));
  baseUrl = 'http://127.0.0.1:' + server.address().port;
});

test.after(async () => {
  await new Promise((resolve, reject) => server.close(err => err ? reject(err) : resolve()));
});

async function postScore(payload) {
  return fetch(baseUrl + '/api/viral-score', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload)
  });
}

test('health endpoint returns service status', async () => {
  const response = await fetch(baseUrl + '/');
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.status, 'ok');
  assert.equal(body.version, '0.2.0');
});

test('viral score returns metrics and script-specific advice', async () => {
  const response = await postScore({
    text: 'Stop scrolling. Here are three practical ways to improve your next short video with a clearer opening, useful examples, and a strong final takeaway.',
    seconds: 30,
    captions: true,
    sound: true
  });
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.ok(body.score >= 0 && body.score <= 100);
  assert.equal(body.hasHook, true);
  assert.ok(body.wordCount > 0);
  assert.ok(body.estimatedWordsPerSecond > 0);
  assert.ok(Array.isArray(body.tips));
});

test('rejects missing, blank, and non-string script text', async () => {
  for (const payload of [{}, { text: '   ' }, { text: 42 }]) {
    assert.equal((await postScore(payload)).status, 400);
  }
});

test('rejects invalid duration and option types', async () => {
  for (const payload of [
    { text: 'A valid script', seconds: 0 },
    { text: 'A valid script', seconds: 601 },
    { text: 'A valid script', seconds: '30' },
    { text: 'A valid script', captions: 'yes' },
    { text: 'A valid script', sound: 1 }
  ]) {
    assert.equal((await postScore(payload)).status, 400);
  }
});

test('rejects oversized scripts', async () => {
  assert.equal((await postScore({ text: 'x'.repeat(5001) })).status, 400);
});

test('returns a controlled error for malformed JSON', async () => {
  const response = await fetch(baseUrl + '/api/viral-score', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: '{invalid'
  });
  assert.equal(response.status, 400);
  assert.deepEqual(await response.json(), { error: 'Request body contains invalid JSON.' });
});
