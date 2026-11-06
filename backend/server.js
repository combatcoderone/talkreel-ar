const express = require('express');
const cors = require('cors');
const fs = require('fs');
const path = require('path');
const app = express();
app.use(cors());
app.use(express.json());
const PORT = process.env.PORT || 3000;

function readJson(name) {
  return JSON.parse(fs.readFileSync(path.join(__dirname, name), 'utf8'));
}

app.get('/', (req, res) => {
  res.json({ app: 'TalkReel AR backend', version: '0.1.0', status: 'ok' });
});

app.get('/api/trends', (req, res) => {
  res.json(readJson('trends.json'));
});

app.get('/api/templates', (req, res) => {
  res.json(readJson('templates.json'));
});

app.post('/api/viral-score', (req, res) => {
  const text = String(req.body.text || '');
  const seconds = Number(req.body.seconds || 30);
  const hasHook = /^(stop|wait|this|how|why|3 |5 )/i.test(text.trim());
  let score = 50;
  if (hasHook) score += 15;
  if (text.length > 40) score += 10;
  if (seconds >= 15 && seconds <= 60) score += 15;
  if (req.body.captions) score += 5;
  if (req.body.sound) score += 5;
  score = Math.max(0, Math.min(100, score));
  res.json({
    score,
    tips: [
      'Start with a hook in the first 3 seconds.',
      'Keep the video between 15 and 60 seconds.',
      'Add captions and a trending sound.'
    ]
  });
});

app.listen(PORT, () => {
  console.log('TalkReel AR backend on port ' + PORT);
});
