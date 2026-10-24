const mode = process.argv[2] ?? 'plain';
if (process.argv.length > 3 || !['plain', 'custom'].includes(mode)) {
  console.error('FAIL: use plain or custom mode.');
  process.exit(2);
}

const custom = mode === 'custom';
const expected = custom
  ? { APP_MESSAGE: 'doppler-example', APP_NUMBER: '42' }
  : { EXAMPLE_MESSAGE: 'doppler-example', EXAMPLE_NUMBER: '42' };
const valuesMatch = Object.entries(expected).every(([name, value]) => process.env[name] === value);
const originalNamesAbsent = !custom || ['EXAMPLE_MESSAGE', 'EXAMPLE_NUMBER']
  .every((name) => process.env[name] === undefined);

if (!valuesMatch || !originalNamesAbsent) {
  console.error('FAIL: example variables are missing, unexpected, or still present under their original names.');
  process.exit(1);
}
console.log('PASS: both Doppler example variables reached Node.js.');