const fs = require('node:fs');
const path = require('node:path');

const [inputFile, outputDir] = process.argv.slice(2);
if (!inputFile || !outputDir) throw new Error('Usage: node export-santoryu.js model.bbmodel output-dir');
const input = JSON.parse(fs.readFileSync(inputFile, 'utf8'));
const supported = new Set(['body', 'torso', 'head', 'right_arm', 'left_arm', 'right_leg', 'left_leg', 'right_item', 'left_item', 'right_item2', 'left_item2']);
const result = {};
const num = v => { const n = Number(v); return Number.isFinite(n) ? n : 0; };
const bezier = (a, b, c, d, t) => (1-t)**3*a+3*(1-t)**2*t*b+3*(1-t)*t*t*c+t**3*d;
const catmull = (p0,p1,p2,p3,t) => .5*((2*p1)+(-p0+p2)*t+(2*p0-5*p1+4*p2-p3)*t*t+(-p0+3*p1-3*p2+p3)*t*t*t);
const axes = ['x', 'y', 'z'];

function sampleBezier(key, next, start, end, axis, span, t) {
  const right = Math.min(span, Math.max(0, num(key.bezier_right_time?.[axis])));
  const left = Math.max(0, span + Math.min(0, num(next.bezier_left_time?.[axis])));
  let lo = 0, hi = 1;
  for (let i = 0; i < 18; i++) {
    const u = (lo + hi) / 2;
    if (bezier(0, right, left, span, u) < span * t) lo = u;
    else hi = u;
  }
  return bezier(start, start + num(key.bezier_right_value?.[axis]),
    end + num(next.bezier_left_value?.[axis]), end, (lo + hi) / 2);
}

for (const animation of input.animations || []) {
  const loop = animation.loop === 'loop';
  const bones = {};
  let lastKeyTime = 0;
  for (const animator of Object.values(animation.animators || {})) {
    const boneName = animator.name === 'torso' ? 'body' : animator.name;
    if (!supported.has(boneName)) continue;
    const channels = {};
    for (const channel of ['rotation', 'position', 'scale']) {
      const keys = (animator.keyframes || []).filter(k => k.channel === channel).sort((a, b) => a.time - b.time);
      if (!keys.length) continue;
      lastKeyTime = Math.max(lastKeyTime, ...keys.map(key => num(key.time)));
      const track = {};
      for (let i = 0; i < keys.length; i++) {
        const key = keys[i], point = key.data_points?.[0] || {};
        const vector = axes.map(axis => num(point[axis]));
        const next = keys[i + 1];
        if (next && key.interpolation === 'step') {
          track[Math.max(num(key.time), num(next.time) - 1e-5).toFixed(6)] = vector;
        } else if (next) {
          // Blockbench selects Catmull-Rom or Bezier using both ends of a segment.
          const modes = [key.interpolation, next.interpolation];
          const spline = modes.includes('catmullrom');
          if (spline || modes.includes('bezier')) {
            const span = num(next.time) - num(key.time);
            const steps = Math.max(spline ? 1 : 10, Math.ceil(span * 60));
            const wrap = loop && keys.length >= 3;
            const end = next.data_points?.[0] || {};
            const previous = (keys[i - 1] ?? (wrap ? keys.at(-2) : key)).data_points?.[0] || point;
            const after = (keys[i + 2] ?? (wrap ? keys[1] : next)).data_points?.[0] || end;
            for (let s = 1; s < steps; s++) {
              const t = s / steps;
              track[(num(key.time) + span * t).toFixed(6)] = axes.map((axis, index) => spline
                ? catmull(num(previous[axis]), num(point[axis]), num(end[axis]), num(after[axis]), t)
                : sampleBezier(key, next, num(point[axis]), num(end[axis]), index, span, t));
            }
          }
        }
        track[num(key.time)] = key.interpolation === 'step' ? { pre: vector, post: vector } : vector;
      }
      channels[channel] = track;
    }
    if (Object.keys(channels).length) bones[boneName] = channels;
  }
  // A few Blockbench clips have keyframes beyond their declared duration. Keep
  // those authored keys reachable instead of silently clamping the runtime clip.
  const length = Math.max(num(animation.length), lastKeyTime);
  const upper = {}, legs = {};
  for (const [bone, channels] of Object.entries(bones)) (bone.endsWith('_leg') ? legs : upper)[bone] = channels;
  result[`fa_${animation.name}`] = { animation_length: length, loop, bones };
  result[`fa_${animation.name}_upper`] = { animation_length: length, loop, bones: upper };
  result[`fa_${animation.name}_legs`] = { animation_length: length, loop, bones: legs };
}

fs.mkdirSync(outputDir, { recursive: true });
fs.writeFileSync(path.join(outputDir, 'santoryu.json'), JSON.stringify({ format_version: '1.8.0', animations: result }, null, 2));
console.log(`Exported ${Object.keys(result).length / 3} clips as ${Object.keys(result).length} full/upper/legs tracks.`);
