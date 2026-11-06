// Backend API: validated script scoring and JSON-safe errors.
const express = require('express');
const cors = require('cors');
const fs = require('fs');
const path = require('path');
const app = express();
const PORT = Number(process.env.PORT) || 3000;

app.disable('x-powered-by');
app.use(cors());
app.use(express.json({ limit: '32kb' }));

function readJson(name) {
  return JSON.parse(fs.readFileSync(path.join(__dirname, name), 'utf8'));
}

const HOOK = /^(stop|wait|this is why|here'?s why|did you know|what if|how to|how i|why you|the truth about|3\b|5\b|\d+\s)/i;

function scoreScript({ text, seconds, captions, sound }) {
  const words = text.trim().split(/\s+/).filter(Boolean);
  const count = words.length;
  const hasHook = HOOK.test(text.trim());
  const pace = count / seconds;
  let score = 35;
  if (hasHook) score += 20;
  if (count >= 20 && count <= 150) score += 15;
  if (seconds >= 15 && seconds <= 60) score += 15;
  if (captions) score += 10;
  if (sound) score += 5;
  if (pace >= 1.8 && pace <= 3.2) score += 10;

  const tips = [];
  if (!hasHook) tips.push('Open with a specific hook, question, surprising claim, or clear benefit.');
  if (count < 20) tips.push('Add a useful detail or example so the video delivers on its opening promise.');
  if (count > 150) tips.push('Tighten the script or split it into shorter videos.');
  if (seconds < 15 || seconds > 60) tips.push('Consider a 15–60 second cut for a concise short-form video.');
  if (!captions) tips.push('Add captions so viewers can follow along with the sound off.');
  if (!sound) tips.push('Consider relevant background audio that does not compete with speech.');
  if (pace > 3.2) tips.push('The script may be rushed. Remove words or allow more recording time.');
  if (pace < 1.8) tips.push('The script may feel slow. Tighten pauses or add useful detail.');
  if (!tips.length) tips.push('Good structure. Review the first three seconds for clarity.');
  return { score: Math.max(0, Math.min(100, score)), wordCount: count, estimatedWordsPerSecond: Number(pace.toFixed(2)), hasHook, tips };
}

app.get('/', (req, res) => res.json({ app: 'TalkReel AR backend', version: '0.2.0', status: 'ok' }));
app.get('/api/trends', (req, res) => res.json(readJson('trends.json')));
app.get('/api/templates', (req, res) => res.json(readJson('templates.json')));

app.post('/api/viral-score', (req, res) => {
  const body = req.body;
  if (!body || typeof body !== 'object' || Array.isArray(body)) return res.status(400).json({ error: 'Request body must be a JSON object.' });
  if (typeof body.text !== 'string' || !body.text.trim()) return res.status(400).json({ error: 'text must be a non-empty string.' });
  if (body.text.length > 5000) return res.status(400).json({ error: 'text must be 5,000 characters or fewer.' });
  const seconds = body.seconds === undefined ? 30 : body.seconds;
  if (typeof seconds !== 'number' || !Number.isFinite(seconds) || seconds < 5 || seconds > 600) return res.status(400).json({ error: 'seconds must be a number between 5 and 600.' });
  for (const field of ['captions', 'sound']) {
    if (body[field] !== undefined && typeof body[field] !== 'boolean') return res.status(400).json({ error: field + ' must be a boolean.' });
  }
  return res.json(scoreScript({ text: body.text, seconds, captions: body.captions === true, sound: body.sound === true }));
});

app.use((err, req, res, next) => {
  if (res.headersSent) return next(err);
  if (err instanceof SyntaxError && err.status === 400 && 'body' in err) return res.status(400).json({ error: 'Request body contains invalid JSON.' });
  if (err.type === 'entity.too.large') return res.status(413).json({ error: 'Request body must be 32kb or smaller.' });
  console.error('Request failed:', err.message);
  return res.status(500).json({ error: 'Internal server error.' });
});

if (require.main === module) app.listen(PORT, () => console.log('TalkReel AR backend on port ' + PORT));
module.exports = app;
