import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import vm from 'node:vm';
import { parseMoneyMinor, parseAvailability, parseStationTable, resolveStation, railTimes, parseRailSeat, parseInput, validateAndSummarize, assertStationTotals } from '../results.mjs';

const fixtureUrl = new URL('./fixtures/synthetic.json', import.meta.url);
const cli = fileURLToPath(new URL('../cli.mjs', import.meta.url));
const snapshot = readFileSync(new URL('../../js/tickets-rail-snapshot.js', import.meta.url), 'utf8');
const fixture = () => JSON.parse(readFileSync(fixtureUrl, 'utf8'));
const rejects = (fn, code) => assert.throws(fn, error => error.code === code);
const evidence = { sourceId: 'test', path: 'cell' };

for (const [input, expected] of [['0', 0], ['0.01', 1], ['10.10', 1010], ['￥525.5元', 52550], ['90071992547409.91', Number.MAX_SAFE_INTEGER]]) {
  test('money uses exact integer fen: ' + input, () => {
    assert.equal(parseMoneyMinor(input), expected);
  });
}
for (const input of [0.1, '-1', '1.001', '1e3', '01', 'NaN', '1,000', '￥450起']) {
  test('reject malformed monetary value: ' + input, () => {
    rejects(() => parseMoneyMinor(input), 'INVALID_MONEY');
  });
}
test('reject money above safe integer boundary', () => {
  rejects(() => parseMoneyMinor('90071992547409.92'), 'UNSAFE_MONEY');
});

for (const [input, status] of [['有', 'AVAILABLE'], ['3', 'LIMITED'], ['候补', 'WAITLIST'], ['无', 'SOLD_OUT'], ['0', 'SOLD_OUT'], ['--', 'NOT_OFFERED'], ['', 'UNKNOWN'], ['加载中', 'UNKNOWN']]) {
  test('explicit availability: ' + JSON.stringify(input), () => {
    assert.equal(parseAvailability(input).status, status);
  });
}
test('station parser validates official codes and rejects SHG without guessing', () => {
  const table = parseStationTable(fixture().rail.stationTable);
  assert.deepEqual(resolveStation(table, '上海'), { code: 'SHH', name: '上海' });
  assert.deepEqual(resolveStation(table, 'VNP'), { code: 'VNP', name: '北京南' });
  rejects(() => resolveStation(table, 'SHG'), 'UNKNOWN_STATION');
  rejects(() => parseStationTable('@a|甲|AAA|a|a|@b|乙|AAA|b|b|'), 'STATION_CONFLICT');
  rejects(() => resolveStation(parseStationTable('@a|同名|AAA|a|a|@b|同名|BBB|b|b|'), '同名'), 'UNKNOWN_STATION');
  rejects(() => parseStationTable('globalThis.secret = true'), 'INVALID_STATION_TABLE');
});
test('actual seat name and decimal fare come from aria, not RW abbreviation', () => {
  const result = parseRailSeat({ code: 'RW', text: '有', aria: 'D7次列车，二等卧票价123.45元，余票有' }, evidence);
  assert.equal(result.name, '二等卧');
  assert.equal(result.amountMinor, 12345);
  assert.equal(result.cabin, 'ECONOMY');
  assert.equal(parseRailSeat({ code: 'RW', text: '有' }, evidence).name, null);
  assert.equal(parseRailSeat({ code: 'ZE', text: '--', headerName: '二等座' }, evidence).status, 'NOT_OFFERED');
  rejects(() => parseRailSeat({ code: 'ZE', text: '有', aria: 'G1次列车，二等座票价1.001元，余票有' }, evidence), 'INVALID_MONEY');
  rejects(() => parseRailSeat({ code: 'ZE', text: '有', aria: 'G1次列车，二等座票价1元，余票候补' }, evidence), 'SEAT_CONFLICT');
});
test('cross-year multi-day rail duration and explicit arrival validation', () => {
  assert.equal(railTimes('2030-12-31', '23:00', '01:00', '26:00').arrival, '2031-01-02T01:00:00+08:00');
  rejects(() => railTimes('2026-02-29', '23:00', '01:00', '02:00'), 'INVALID_DATE');
  rejects(() => railTimes('2026-10-11', '23:00', '01:01', '02:00'), 'TIME_MISMATCH');
  rejects(() => railTimes('2026-10-11', '23:00', '01:00', '02:00', '2026-10-11'), 'DATE_MISMATCH');
});
test('same train, different boarding station means two options and one service', () => {
  const result = validateAndSummarize(fixture());
  assert.equal(result.summary.optionCount, 3);
  assert.equal(result.summary.serviceCount, 2);
  assert.equal(result.options[0].serviceId, result.options[1].serviceId);
  assert.notEqual(result.options[0].optionId, result.options[1].optionId);
  assert.equal(result.options[0].arrival, '2026-10-12T00:30:00+08:00');
  assert.equal(result.options[1].fromName, '北京南');
  assert.equal(result.summary.departures['rail:BJP'], 1);
  assert.equal(result.summary.arrivals['rail:SHH'], 2);
});
test('minima exclude waitlist, business and unknown; tax bases are not mixed', () => {
  const result = validateAndSummarize(fixture());
  const minima = result.summary.lowestBuyableEconomy;
  assert.equal(minima.length, 3);
  assert.equal(minima.find(entry => entry.mode === 'rail').amountMinor, 1001);
  assert.equal(minima.find(entry => entry.taxBasis === 'UNKNOWN').amountMinor, 45050);
  assert.equal(minima.find(entry => entry.taxBasis === 'UNKNOWN').isTotal, false);
  assert.equal(minima.find(entry => entry.taxBasis === 'INCLUDED').amountMinor, 50000);
  const offer = result.options[2].offers[0];
  assert.equal(offer.evidence.amountMinor.sourceId, 'flight-other');
  assert.equal(offer.evidence.cabin.sourceId, 'flight-page');
});
test('booking-disabled trains never enter buyable minima', () => {
  const input = fixture();
  delete input.flights;
  input.rail.rawRows.forEach(row => { row[11] = 'N'; });
  assert.equal(validateAndSummarize(input).summary.lowestBuyableEconomy.length, 0);
});
test('station aggregates must sum to option count', () => {
  rejects(() => assertStationTotals({ departures: { BJP: 1 }, arrivals: { SHH: 2 } }, 2), 'STATION_TOTAL_MISMATCH');
});
test('declared JSON layers preserve slashes, quotes and literal escapes', () => {
  const input = { quoted: 'a "quoted" value', path: String.raw`tmp\backslash\n` };
  assert.deepEqual(parseInput(JSON.stringify(input)), input);
  assert.deepEqual(parseInput(JSON.stringify({ ok: true, data: { result: JSON.stringify(input) } }), 'dsb'), input);
  assert.deepEqual(parseInput(JSON.stringify({ ok: true, data: { result: input } }), 'dsb'), input);
  rejects(() => parseInput(JSON.stringify(JSON.stringify(input))), 'INVALID_INPUT');
  rejects(() => parseInput(JSON.stringify({ data: { result: JSON.stringify(JSON.stringify(input)) } }), 'dsb'), 'INVALID_INPUT');
  rejects(() => parseInput('{bad json'), 'INVALID_JSON');
  rejects(() => parseInput(JSON.stringify({ ok: false, data: { result: input } }), 'dsb'), 'INVALID_ENVELOPE');
});

const invalidCases = [
  ['wrong expected date', input => input, 'DATE_MISMATCH', '2026-10-12'],
  ['switched DOM date', input => { input.rail.domDate = '2026-10-12'; }, 'DATE_MISMATCH'],
  ['old rendered rows with new form date and unchanged timetable/inventory', input => { input.rail.domRows[0].boardingDate = '2026-10-10'; }, 'DATE_MISMATCH'],
  ['flight on wrong date', input => { input.flights[0].departure = '2026-10-12T00:00:00+08:00'; }, 'DATE_MISMATCH'],
  ['missing field evidence', input => { delete input.flights[0].offers[0].fieldSources.amount; }, 'UNKNOWN_SOURCE'],
  ['unknown tax basis', input => { input.flights[0].offers[0].taxBasis = 'MAYBE'; }, 'INVALID_TAX'],
  ['invalid flight date', input => { input.flights[0].arrival = '2026-02-30T01:00:00+08:00'; }, 'INVALID_DATE'],
  ['flight arrival before departure', input => { input.flights[0].arrival = '2026-10-11T22:00:00+08:00'; }, 'TIME_MISMATCH'],
  ['stale DOM route', input => { input.rail.domRows[1].fromName = '北京'; }, 'DOM_RAW_MISMATCH'],
  ['stale DOM counts', input => { input.rail.rawRows[1][30] = '2'; }, 'SEAT_CONFLICT'],
  ['duplicate rail option', input => { input.rail.rawRows.push(input.rail.rawRows[0]); }, 'DUPLICATE_OPTION'],
  ['duplicate DOM row', input => { input.rail.domRows.push(input.rail.domRows[0]); }, 'DUPLICATE_DOM'],
  ['unknown station', input => { input.rail.to = 'SHG'; }, 'UNKNOWN_STATION'],
  ['secret-bearing source URL', input => { input.sources[0].url += '?token=FAKE'; }, 'UNSAFE_SOURCE_URL'],
  ['incompatible currency', input => { input.flights[0].offers[0].currency = 'USD'; }, 'INVALID_CURRENCY'],
  ['truncated rail row', input => { input.rail.rawRows[0] = 'short'; }, 'INVALID_RAIL_ROW'],
  ['invalid limited count', input => { input.flights[0].offers[0].status = 'LIMITED'; }, 'INVALID_COUNT']
];
for (const [name, change, code, expectedDate] of invalidCases) {
  test('reject ' + name, () => {
    const input = fixture();
    change(input);
    rejects(() => validateAndSummarize(input, expectedDate), code);
  });
}
test('missing public row dates are explicitly unverified, not inferred from form', () => {
  const input = fixture();
  delete input.rail.domRows[0].boardingDate;
  const result = validateAndSummarize(input);
  assert.equal(result.warnings[0].code, 'UNVERIFIED_DOM_DATE');
});

test('missing DOM cannot invent a price or seat name', () => {
  const input = fixture();
  input.rail.domRows = [];
  delete input.flights;
  const result = validateAndSummarize(input);
  assert.equal(result.summary.lowestBuyableEconomy.length, 0);
  assert.equal(result.warnings.length, 2);
});
test('empty valid station query is not sold out', () => {
  const input = fixture();
  input.rail.rawRows = [];
  input.rail.domRows = [];
  delete input.flights;
  const result = validateAndSummarize(input);
  assert.equal(result.summary.optionCount, 0);
  assert.equal(result.warnings[0].code, 'EMPTY_RAIL_RESULT');
});

test('CLI accepts file and stdin, reports errors, and runs outside repository', () => {
  const run = (...args) => spawnSync(process.execPath, [cli, ...args], { encoding: 'utf8', cwd: tmpdir() });
  const help = run('--help');
  assert.equal(help.status, 0, help.stderr);
  assert.match(help.stdout, /dsb-tickets/);
  const result = run('--input', fileURLToPath(fixtureUrl), '--date', '2026-10-11', '--summary');
  assert.equal(result.status, 0, result.stderr + result.stdout);
  const output = JSON.parse(result.stdout);
  assert.equal(output.summary.optionCount, 3);
  assert.equal(Object.hasOwn(output, 'options'), false);
  const stdin = spawnSync(process.execPath, [cli, '--summary'], { input: readFileSync(fixtureUrl), encoding: 'utf8', cwd: tmpdir() });
  assert.equal(stdin.status, 0, stdin.stderr);
  assert.equal(JSON.parse(stdin.stdout).valid, true);
  const invalid = run('--input', fileURLToPath(fixtureUrl), '--date', '2026-10-12');
  assert.equal(invalid.status, 1);
  assert.equal(JSON.parse(invalid.stdout).error.code, 'DATE_MISMATCH');
  assert.equal(run('--bad').status, 1);
});
test('CLI out is UTF-8 and contains parseable Chinese data without stdout noise', () => {
  const directory = mkdtempSync(join(tmpdir(), 'dsb-ticket-test-'));
  try {
    const output = join(directory, 'result.json');
    const run = spawnSync(process.execPath, [cli, '--input', fileURLToPath(fixtureUrl), '--out', output], { encoding: 'utf8' });
    assert.equal(run.status, 0, run.stderr);
    assert.equal(run.stdout, '');
    assert.equal(JSON.parse(readFileSync(output, 'utf8')).options[0].fromName, '北京');
  } finally {
    rmSync(resolve(directory), { recursive: true, force: true });
  }
});

test('browser snapshot integrates offline without start/end classes and strips private columns', async () => {
  const input = fixture();
  const textNode = value => ({ textContent: value });
  const rows = input.rail.domRows.map(row => ({
    id: row.rowId,
    querySelector(selector) {
      return textNode(selector === 'a.number' ? row.number : row.duration);
    },
    querySelectorAll(selector) {
      if (selector === '.cdz strong') {
        return [textNode(row.fromName), textNode(row.toName)];
      }
      if (selector === '.cds strong') {
        return [textNode(row.departure), textNode(row.arrival)];
      }
      if (selector === 'td[hbdata], td[hbid]') {
        return [{ getAttribute: () => row.boardingDate + '#SYNTHETIC_SECRET_NOT_FOR_EXPORT' }];
      }
      if (selector === 'td[id]') {
        return row.seats.map(cell => ({ id: cell.code + '_TEST', textContent: cell.text, getAttribute: () => cell.aria }));
      }
      throw new Error('Unexpected selector: ' + selector);
    }
  }));
  const queryValues = { '#train_date': input.queryDate, '#fromStation': 'BJP', '#toStation': 'SHH' };
  const document = { querySelector: selector => ({ value: queryValues[selector] }), querySelectorAll: () => rows };
  const rawRows = input.rail.rawRows.map(fields => {
    const copy = [...fields];
    copy[0] = 'SYNTHETIC_SECRET_NOT_FOR_EXPORT';
    copy[12] = 'SYNTHETIC_BOOKING_TOKEN';
    return copy.join('|');
  });
  const fetched = [];
  const fetch = async url => {
    fetched.push(url);
    return { ok: true, json: async () => ({ status: true, data: { result: rawRows } }), text: async () => input.rail.stationTable };
  };
  const collect = vm.runInNewContext('(' + snapshot + ')', { document, window: {}, location: { origin: 'https://example.test', pathname: '/otn/leftTicket/init' }, fetch, URLSearchParams });
  const collected = JSON.parse(JSON.stringify(await collect()));
  assert.equal(fetched.length, 2);
  assert.match(fetched[0], /train_date=2026-10-11/);
  assert.equal(JSON.stringify(collected).includes('SYNTHETIC_SECRET'), false);
  assert.equal(JSON.stringify(collected).includes('SYNTHETIC_BOOKING_TOKEN'), false);
  assert.equal(validateAndSummarize(collected).summary.optionCount, 2);
});
