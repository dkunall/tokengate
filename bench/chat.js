// k6 run -e API_KEY=... -e MODE=hit|semantic|miss bench/chat.js
// hit:      the same prompt every time, served from the in-process L1 cache after the first call
// semantic: a near-duplicate prompt per iteration; exact lookups miss, the pgvector lookup hits
// miss:     a random word-salad prompt per iteration (run with a strict semantic threshold), so each call goes
//           through limiter, PII scan, embedding, pgvector lookup, the mock provider, cache writes and the outbox
import http from 'k6/http';
import { check } from 'k6';

const MODE = __ENV.MODE || 'miss';
const URL = (__ENV.GATEWAY || 'http://localhost:9080') + '/v1/chat/completions';
const EXPECTED = { hit: 'l1_hit', semantic: 'semantic_hit', miss: 'miss' }[MODE];
const WORDS = ('river quantum ledger falcon basalt orchid lantern cipher meadow turbine saffron glacier harbor velvet ' +
  'canyon pixel sonnet copper maple tundra beacon prism walnut nebula anchor thistle cobalt marble violin ember ' +
  'compass juniper quartz harvest pepper lagoon summit willow granite comet bamboo tavern oyster raven ginger ' +
  'atlas cedar dynamo fjord hammock indigo jasmine kettle lemur mosaic nutmeg obsidian parsley quiver rhubarb').split(' ');

function salad(n) {
  const out = [];
  for (let i = 0; i < n; i++) out.push(WORDS[Math.floor(Math.random() * WORDS.length)]);
  return out.join(' ');
}

export const options = {
  vus: Number(__ENV.VUS || 20),
  duration: __ENV.DURATION || '30s',
  summaryTrendStats: ['avg', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  let content;
  if (MODE === 'hit') content = 'How does a token bucket rate limiter work?';
  else if (MODE === 'semantic') content = `How does a token bucket rate limiter work? variant ${__VU}-${__ITER}-${Date.now()}`;
  else content = `${salad(14)} ${__VU}-${__ITER}`;
  const res = http.post(URL, JSON.stringify({
    model: 'gpt-4o-mini',
    temperature: 0,
    messages: [{ role: 'user', content }],
  }), { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${__ENV.API_KEY}` } });
  check(res, {
    'status 200': (r) => r.status === 200,
    'expected cache result': (r) => r.headers['X-Tokengate-Cache'] === EXPECTED,
  });
}
