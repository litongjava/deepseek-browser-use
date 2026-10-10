/**
 * Offline ticket normalization. No requests, eval, cookies, or booking operations.
 * @typedef {'AVAILABLE'|'LIMITED'|'WAITLIST'|'SOLD_OUT'|'NOT_OFFERED'|'UNKNOWN'} Availability
 * @typedef {'INCLUDED'|'EXCLUDED'|'UNKNOWN'|'NOT_APPLICABLE'} TaxBasis
 * @typedef {{sourceId:string, path:string}} Evidence
 * @typedef {{id:string, provider:string, url:string, capturedAt:string, method:string}} Source
 * @typedef {{code:string, name:string|null, cabin:string, status:Availability,
 * count:number|null, amountMinor:number|null, currency:string, taxBasis:TaxBasis,
 * evidence:Record<string,Evidence>}} Offer
 * @typedef {{mode:string, serviceId:string, optionId:string, number:string,
 * fromCode:string, fromName:string, toCode:string, toName:string,
 * departure:string, arrival:string, durationMinutes:number,
 * offers:Offer[], evidence:Record<string,Evidence>}} TicketOption
 * @typedef {{code:string, text:string, aria?:string, headerName?:string}} RailDomSeat
 * @typedef {{rowId:string, number:string, fromName:string, toName:string,
 * departure:string, arrival:string, duration:string, arrivalDate?:string, boardingDate?:string|null,
 * seats:RailDomSeat[]}} RailDomRow
 * @typedef {{queryDate:string, domDate:string, from:string, to:string,
 * stationTable:string, stationSourceId:string, rawSourceId:string, domSourceId:string,
 * rawRows:Array<string|string[]>, domRows:RailDomRow[]}} RailSnapshot
 * @typedef {{code:string, name:string, cabin:string, status:Availability, count?:number,
 * amount:string|null, currency:'CNY', taxBasis:TaxBasis,
 * fieldSources:Record<string,string>}} FlightOfferInput
 * @typedef {{number:string, operatingNumber:string, fromCode:string, toCode:string,
 * departure:string, arrival:string, durationMinutes?:number,
 * fieldSources:Record<string,string>, offers:FlightOfferInput[]}} FlightInput
 * @typedef {{schemaVersion:1, queryDate:string, sources:Source[], rail?:RailSnapshot,
 * flights?:FlightInput[]}} TicketInput
 */

export class TicketValidationError extends Error {
  constructor(code, message) {
    super(message);
    this.name = 'TicketValidationError';
    this.code = code;
  }
}

function requireValue(condition, code, message) {
  if (!condition) {
    throw new TicketValidationError(code, message);
  }
}

function text(value, label) {
  requireValue(typeof value === 'string' && value.trim().length > 0, 'INVALID_FIELD', label + ' must be a nonempty string');
  return value.trim();
}

/** Parse only a declared JSON layer. Never repair escapes or recursively decode strings. */
export function parseInput(jsonText, format = 'input') {
  requireValue(['input', 'dsb'].includes(format), 'INVALID_FORMAT', 'Use input or dsb format');
  let value;
  try {
    value = JSON.parse(jsonText.replace(/^\uFEFF/, ''));
    if (format === 'dsb') {
      requireValue(value.ok !== false && value.data && Object.hasOwn(value.data, 'result'), 'INVALID_ENVELOPE', 'Expected successful dsb data.result');
      value = value.data.result;
      if (typeof value === 'string') {
        value = JSON.parse(value);
      }
    }
  } catch (error) {
    if (error instanceof TicketValidationError) {
      throw error;
    }
    throw new TicketValidationError('INVALID_JSON', 'Input is not valid JSON for the selected format');
  }
  requireValue(value !== null && typeof value === 'object' && !Array.isArray(value), 'INVALID_INPUT', 'Expected an object, not another encoded JSON string');
  return value;
}

/** Decimal CNY text to integer fen; do not multiply a floating-point amount by 100. */
export function parseMoneyMinor(value) {
  requireValue(typeof value === 'string', 'INVALID_MONEY', 'Amounts must be decimal strings');
  const match = /^(?:[¥￥]\s*)?(0|[1-9]\d*)(?:\.(\d{1,2}))?(?:\s*元)?$/.exec(value.trim());
  requireValue(match !== null, 'INVALID_MONEY', 'Amount must be a nonnegative decimal with at most two fraction digits');
  const amount = BigInt(match[1]) * 100n + BigInt((match[2] || '').padEnd(2, '0'));
  requireValue(amount <= BigInt(Number.MAX_SAFE_INTEGER), 'UNSAFE_MONEY', 'Amount exceeds the safe integer range');
  return Number(amount);
}

export function parseAvailability(value) {
  const raw = typeof value === 'string' ? value.trim() : '';
  if (raw === '有') {
    return { status: 'AVAILABLE', count: null };
  }
  if (raw === '候补') {
    return { status: 'WAITLIST', count: null };
  }
  if (['无', '售罄', '0'].includes(raw)) {
    return { status: 'SOLD_OUT', count: null };
  }
  if (['--', '—', '不提供', '不售'].includes(raw)) {
    return { status: 'NOT_OFFERED', count: null };
  }
  if (/^[1-9]\d*$/.test(raw) && Number.isSafeInteger(Number(raw))) {
    return { status: 'LIMITED', count: Number(raw) };
  }
  return { status: 'UNKNOWN', count: null };
}

/** Read station_name.js as data, never as executable JavaScript. */
export function parseStationTable(value) {
  text(value, 'stationTable');
  const assignment = /^\s*(?:var|let|const)\s+station_names\s*=\s*(['"])([\s\S]*)\1\s*;?\s*$/.exec(value);
  const raw = assignment ? assignment[2] : value;
  requireValue(raw.startsWith('@'), 'INVALID_STATION_TABLE', 'Expected station_names assignment or @-delimited station records');
  const byCode = new Map();
  const byName = new Map();
  for (const record of raw.split('@').slice(1)) {
    const fields = record.split('|');
    const name = fields[1];
    const code = fields[2];
    requireValue(fields.length >= 5 && /^[A-Z]{3}$/.test(code || '') && typeof name === 'string' && name.length > 0, 'INVALID_STATION_TABLE', 'Malformed station record');
    requireValue(!byCode.has(code) || byCode.get(code) === name, 'STATION_CONFLICT', 'Station code maps to conflicting names');
    byCode.set(code, name);
    const codes = byName.get(name) || new Set();
    codes.add(code);
    byName.set(name, codes);
  }
  return { byCode, byName };
}

export function resolveStation(table, value) {
  text(value, 'station');
  if (table.byCode.has(value)) {
    return { code: value, name: table.byCode.get(value) };
  }
  const codes = table.byName.get(value);
  requireValue(codes && codes.size === 1, 'UNKNOWN_STATION', 'Station must match a known unique name or telecode');
  const code = [...codes][0];
  return { code, name: table.byCode.get(code) };
}

function dateMillis(date) {
  requireValue(typeof date === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(date), 'INVALID_DATE', 'Date must use YYYY-MM-DD');
  const parsed = Date.parse(date + 'T00:00:00Z');
  requireValue(Number.isFinite(parsed) && new Date(parsed).toISOString().slice(0, 10) === date, 'INVALID_DATE', 'Date does not exist');
  return parsed;
}

function clockMinutes(clock, duration = false) {
  const match = (duration ? /^(\d{2,3}):([0-5]\d)$/ : /^([01]\d|2[0-3]):([0-5]\d)$/).exec(clock || '');
  requireValue(match, 'INVALID_TIME', 'Invalid clock or duration');
  return Number(match[1]) * 60 + Number(match[2]);
}

export function railTimes(date, departure, arrival, duration, claimedArrivalDate) {
  const base = dateMillis(date);
  const start = clockMinutes(departure);
  const elapsed = clockMinutes(duration, true);
  requireValue(elapsed > 0, 'INVALID_DURATION', 'Duration must be positive');
  const end = start + elapsed;
  requireValue(end % 1440 === clockMinutes(arrival), 'TIME_MISMATCH', 'Arrival clock conflicts with departure and duration');
  const arrivalDate = new Date(base + Math.floor(end / 1440) * 86400000).toISOString().slice(0, 10);
  if (claimedArrivalDate !== undefined) {
    dateMillis(claimedArrivalDate);
    requireValue(claimedArrivalDate === arrivalDate, 'DATE_MISMATCH', 'Arrival date conflicts with duration');
  }
  return { departure: date + 'T' + departure + ':00+08:00', arrival: arrivalDate + 'T' + arrival + ':00+08:00', durationMinutes: elapsed };
}

function cabinFromRailName(name) {
  if (!name) {
    return 'UNKNOWN';
  }
  if (/商务/.test(name)) {
    return 'BUSINESS';
  }
  if (/一等|特等|高级/.test(name)) {
    return 'FIRST';
  }
  if (/无座/.test(name)) {
    return 'STANDING';
  }
  if (/二等|硬座|软座|硬卧|软卧|动卧/.test(name)) {
    return 'ECONOMY';
  }
  return 'UNKNOWN';
}

/** Keep the actual label from aria/header, never translate RW or YW to a seat name. */
export function parseRailSeat(cell, evidence) {
  text(cell.code, 'seat.code');
  const aria = typeof cell.aria === 'string' ? cell.aria : '';
  const match = /次列车[，,]\s*(.+?)票价\s*([0-9]+(?:\.[0-9]+)?)\s*元[，,]\s*余票\s*(.*?)\s*$/.exec(aria);
  const name = match ? match[1].trim() : (typeof cell.headerName === 'string' ? cell.headerName.trim() || null : null);
  const availability = parseAvailability(cell.text);
  if (match) {
    const ariaAvailability = parseAvailability(match[3]);
    requireValue(ariaAvailability.status === availability.status && ariaAvailability.count === availability.count, 'SEAT_CONFLICT', 'Seat text and aria availability disagree');
  }
  return {
    code: cell.code, name, cabin: cabinFromRailName(name), ...availability,
    amountMinor: match ? parseMoneyMinor(match[2]) : null,
    currency: 'CNY', taxBasis: 'NOT_APPLICABLE',
    evidence: {
      name: { ...evidence, path: evidence.path + (match ? '.aria' : '.headerName') },
      cabin: { ...evidence, path: evidence.path + (match ? '.aria' : '.headerName') },
      status: { ...evidence, path: evidence.path + '.text' },
      count: { ...evidence, path: evidence.path + '.text' },
      amountMinor: { ...evidence, path: evidence.path + '.aria' },
      currency: evidence, taxBasis: evidence
    }
  };
}

function evidence(sourceId, path, sources) {
  requireValue(sources.has(sourceId), 'UNKNOWN_SOURCE', 'Every evidence reference must identify a declared source');
  return { sourceId, path: text(path, 'evidence.path') };
}

const RAW_SEATS = { SWZ: 32, ZY: 31, ZE: 30, GR: 21, RW: 23, YW: 28, RZ: 24, YZ: 29, WZ: 26, QT: 22 };

function railOptions(input, sources, warnings) {
  const rail = input.rail;
  if (!rail) {
    return [];
  }
  requireValue(rail.queryDate === input.queryDate && rail.domDate === input.queryDate, 'DATE_MISMATCH', 'Rail response and rendered form dates must equal queryDate');
  const table = parseStationTable(rail.stationTable);
  resolveStation(table, rail.from);
  resolveStation(table, rail.to);
  const stationEvidence = evidence(rail.stationSourceId, 'stationTable', sources);
  requireValue(Array.isArray(rail.rawRows) && Array.isArray(rail.domRows), 'INVALID_RAIL', 'Rail rawRows and domRows must be arrays');
  const dom = new Map();
  for (const row of rail.domRows) {
    text(row.rowId, 'dom.rowId');
    requireValue(!dom.has(row.rowId), 'DUPLICATE_DOM', 'Duplicate DOM row identity');
    dom.set(row.rowId, row);
  }
  const usedDom = new Set();
  const options = rail.rawRows.map((raw, index) => {
    const fields = typeof raw === 'string' ? raw.split('|') : raw;
    requireValue(Array.isArray(fields) && fields.length >= 33, 'INVALID_RAIL_ROW', 'Rail rows need at least 33 pipe fields');
    const trainId = text(fields[2], 'trainId');
    const number = text(fields[3], 'trainNumber');
    requireValue(/^[A-Za-z0-9]+$/.test(trainId) && /^[A-Za-z0-9]+$/.test(number), 'INVALID_ID', 'Invalid train identity');
    const originDate = fields[13];
    requireValue(/^\d{8}$/.test(originDate || ''), 'INVALID_DATE', 'Rail service origin date must use YYYYMMDD');
    const originIso = originDate.slice(0, 4) + '-' + originDate.slice(4, 6) + '-' + originDate.slice(6);
    requireValue(dateMillis(originIso) <= dateMillis(input.queryDate), 'DATE_MISMATCH', 'Service origin date cannot be after boarding date');
    const from = resolveStation(table, fields[6]);
    const to = resolveStation(table, fields[7]);
    requireValue(/^\d+$/.test(fields[16] || '') && /^\d+$/.test(fields[17] || '') && Number(fields[16]) < Number(fields[17]), 'INVALID_SEQUENCE', 'Boarding sequence must precede arrival sequence');
    const rowId = 'ticket_' + trainId + '_' + fields[16] + '_' + fields[17];
    const rendered = dom.get(rowId);
    const rawEvidence = evidence(rail.rawSourceId, 'rawRows[' + index + ']', sources);
    const domEvidence = evidence(rail.domSourceId, 'domRows[' + rowId + ']', sources);
    if (rendered) {
      usedDom.add(rowId);
      if (rendered.boardingDate !== undefined && rendered.boardingDate !== null) {
        dateMillis(rendered.boardingDate);
        requireValue(rendered.boardingDate === input.queryDate, 'DATE_MISMATCH', 'Rendered row boarding date differs from queryDate');
      } else {
        warnings.push({ code: 'UNVERIFIED_DOM_DATE', option: rowId, message: 'No public boarding date found in rendered row; form date alone does not verify result date' });
      }
      requireValue(rendered.number === number && rendered.fromName === from.name && rendered.toName === to.name && rendered.departure === fields[8] && rendered.arrival === fields[9] && rendered.duration === fields[10], 'DOM_RAW_MISMATCH', 'DOM identity, stations, or timetable differs from raw response');
      requireValue(Array.isArray(rendered.seats), 'INVALID_SEATS', 'DOM seats must be an array');
    } else {
      warnings.push({ code: 'MISSING_DOM', option: rowId });
    }
    const offers = [];
    const seenCodes = new Set();
    for (const cell of rendered?.seats || []) {
      requireValue(!seenCodes.has(cell.code), 'DUPLICATE_SEAT', 'Duplicate seat code within a row');
      seenCodes.add(cell.code);
      const offer = parseRailSeat(cell, { ...domEvidence, path: domEvidence.path + '.seats[' + cell.code + ']' });
      const rawIndex = RAW_SEATS[cell.code];
      if (rawIndex !== undefined && fields[rawIndex] !== '') {
        const rawState = parseAvailability(fields[rawIndex]);
        const waitlistFromSoldOut = rawState.status === 'SOLD_OUT' && offer.status === 'WAITLIST';
        requireValue(waitlistFromSoldOut || (rawState.status === offer.status && rawState.count === offer.count), 'SEAT_CONFLICT', 'Raw and DOM seat availability disagree');
      }
      if (fields[11] !== 'Y' && ['AVAILABLE', 'LIMITED'].includes(offer.status)) {
        warnings.push({ code: 'RAIL_BOOKING_DISABLED', option: rowId });
      }
      offers.push(offer);
    }
    for (const [code, rawIndex] of Object.entries(RAW_SEATS)) {
      if (!seenCodes.has(code)) {
        offers.push({ code, name: null, cabin: 'UNKNOWN', ...parseAvailability(fields[rawIndex]), amountMinor: null, currency: 'CNY', taxBasis: 'NOT_APPLICABLE', evidence: { status: rawEvidence, count: rawEvidence } });
      }
    }
    const serviceId = ['rail', originDate, trainId].join(':');
    const optionId = [serviceId, input.queryDate, from.code, to.code, fields[16], fields[17]].join(':');
    return { mode: 'rail', bookable: fields[11] === 'Y', serviceId, optionId, number, fromCode: from.code, fromName: from.name, toCode: to.code, toName: to.name,
      ...railTimes(input.queryDate, fields[8], fields[9], fields[10], rendered?.arrivalDate), offers,
      evidence: { serviceId: rawEvidence, number: rawEvidence, fromCode: rawEvidence, toCode: rawEvidence, fromName: stationEvidence, toName: stationEvidence, departure: rawEvidence, arrival: rawEvidence, durationMinutes: rawEvidence } };
  });
  requireValue(usedDom.size === dom.size, 'UNMATCHED_DOM', 'DOM includes rows absent from the raw response');
  if (options.length === 0) {
    warnings.push({ code: 'EMPTY_RAIL_RESULT', message: 'Empty results do not prove sold-out status' });
  }
  return options;
}

function flightOptions(input, sources) {
  requireValue(input.flights === undefined || Array.isArray(input.flights), 'INVALID_FLIGHTS', 'flights must be an array');
  return (input.flights || []).map((flight, index) => {
    const base = 'flights[' + index + ']';
    const refs = {};
    for (const field of ['number', 'operatingNumber', 'fromCode', 'toCode', 'departure', 'arrival']) {
      text(flight[field], field);
      refs[field] = evidence(flight.fieldSources?.[field], base + '.' + field, sources);
    }
    requireValue(/^[A-Z]{3}$/.test(flight.fromCode) && /^[A-Z]{3}$/.test(flight.toCode), 'INVALID_AIRPORT', 'Use three-letter airport codes');
    for (const field of ['departure', 'arrival']) {
      requireValue(/^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d:00\+08:00$/.test(flight[field]), 'INVALID_TIME', 'Domestic flight timestamps must have date and +08:00 offset');
      dateMillis(flight[field].slice(0, 10));
    }
    requireValue(flight.departure.slice(0, 10) === input.queryDate, 'DATE_MISMATCH', 'Flight departure date differs from queryDate');
    const durationMinutes = (Date.parse(flight.arrival) - Date.parse(flight.departure)) / 60000;
    requireValue(durationMinutes > 0, 'TIME_MISMATCH', 'Flight arrival must be after departure');
    if (flight.durationMinutes !== undefined) {
      requireValue(flight.durationMinutes === durationMinutes, 'TIME_MISMATCH', 'Flight duration conflicts with timestamps');
    }
    requireValue(Array.isArray(flight.offers), 'INVALID_OFFERS', 'Flight offers must be an array');
    const offers = flight.offers.map((offer, offerIndex) => {
      const offerRefs = {};
      for (const field of ['name', 'cabin', 'status', 'amount', 'currency', 'taxBasis']) {
        offerRefs[field === 'amount' ? 'amountMinor' : field] = evidence(offer.fieldSources?.[field], base + '.offers[' + offerIndex + '].' + field, sources);
      }
      requireValue(['ECONOMY', 'PREMIUM_ECONOMY', 'BUSINESS', 'FIRST', 'UNKNOWN'].includes(offer.cabin), 'INVALID_CABIN', 'Explicit cabin is required');
      requireValue(['AVAILABLE', 'LIMITED', 'WAITLIST', 'SOLD_OUT', 'NOT_OFFERED', 'UNKNOWN'].includes(offer.status), 'INVALID_STATUS', 'Explicit availability status is required');
      if (offer.status === 'LIMITED') {
        requireValue(Number.isSafeInteger(offer.count) && offer.count > 0, 'INVALID_COUNT', 'Limited availability requires a positive integer count');
        offerRefs.count = evidence(offer.fieldSources?.count, base + '.offers[' + offerIndex + '].count', sources);
      }
      requireValue(['INCLUDED', 'EXCLUDED', 'UNKNOWN'].includes(offer.taxBasis), 'INVALID_TAX', 'Flight tax basis must be explicit');
      requireValue(offer.currency === 'CNY', 'INVALID_CURRENCY', 'This domestic adapter currently supports CNY only');
      return { code: text(offer.code, 'offer.code'), name: text(offer.name, 'offer.name'), cabin: offer.cabin, status: offer.status, count: offer.status === 'LIMITED' ? offer.count : null,
        amountMinor: offer.amount === null ? null : parseMoneyMinor(offer.amount), currency: offer.currency, taxBasis: offer.taxBasis, evidence: offerRefs };
    });
    const serviceId = ['flight', input.queryDate, flight.operatingNumber, flight.fromCode, flight.toCode, flight.departure].join(':');
    return { mode: 'flight', serviceId, optionId: serviceId, number: flight.number, operatingNumber: flight.operatingNumber,
      fromCode: flight.fromCode, fromName: flight.fromCode, toCode: flight.toCode, toName: flight.toCode,
      departure: flight.departure, arrival: flight.arrival, durationMinutes, offers, evidence: refs };
  });
}

export function assertStationTotals(statistics, expected) {
  for (const side of ['departures', 'arrivals']) {
    const values = Object.values(statistics[side] || {});
    requireValue(values.every(value => Number.isSafeInteger(value) && value >= 0) && values.reduce((sum, value) => sum + value, 0) === expected,
      'STATION_TOTAL_MISMATCH', side + ' station total must equal option count');
  }
}

/** Keep minima separated by mode, currency and tax basis. Unknown taxes are not totals. */
export function summarize(options) {
  const departures = Object.create(null);
  const arrivals = Object.create(null);
  const minima = new Map();
  for (const option of options) {
    const from = option.mode + ':' + option.fromCode;
    const to = option.mode + ':' + option.toCode;
    departures[from] = (departures[from] || 0) + 1;
    arrivals[to] = (arrivals[to] || 0) + 1;
    for (const offer of option.offers) {
      if (option.bookable === false) {
        continue;
      }
      if (offer.cabin !== 'ECONOMY' || !['AVAILABLE', 'LIMITED'].includes(offer.status) || offer.amountMinor === null) {
        continue;
      }
      const key = [option.mode, offer.currency, offer.taxBasis].join(':');
      const candidate = { optionId: option.optionId, offerCode: offer.code, evidence: offer.evidence };
      const current = minima.get(key);
      if (!current || offer.amountMinor < current.amountMinor) {
        minima.set(key, { mode: option.mode, currency: offer.currency, taxBasis: offer.taxBasis, amountMinor: offer.amountMinor, isTotal: ['INCLUDED', 'NOT_APPLICABLE'].includes(offer.taxBasis), candidates: [candidate] });
      } else if (offer.amountMinor === current.amountMinor) {
        current.candidates.push(candidate);
      }
    }
  }
  assertStationTotals({ departures, arrivals }, options.length);
  return { optionCount: options.length, serviceCount: new Set(options.map(option => option.serviceId)).size,
    departures, arrivals, lowestBuyableEconomy: [...minima.values()] };
}

/** @param {TicketInput} input */
export function validateAndSummarize(input, expectedDate = input.queryDate) {
  requireValue(input.schemaVersion === 1, 'INVALID_SCHEMA', 'schemaVersion must be 1');
  dateMillis(input.queryDate);
  dateMillis(expectedDate);
  requireValue(input.queryDate === expectedDate, 'DATE_MISMATCH', 'Input date differs from requested date');
  requireValue(Array.isArray(input.sources), 'INVALID_SOURCES', 'sources must be an array');
  const sources = new Map();
  const cleanSources = [];
  for (const source of input.sources) {
    const id = text(source.id, 'source.id');
    requireValue(!sources.has(id), 'DUPLICATE_SOURCE', 'Source ids must be unique');
    const url = new URL(text(source.url, 'source.url'));
    requireValue(['https:', 'http:'].includes(url.protocol) && !url.username && !url.password && !url.search && !url.hash, 'UNSAFE_SOURCE_URL', 'Source URLs must omit credentials, query strings and fragments');
    requireValue(typeof source.capturedAt === 'string' && /(?:Z|[+-]\d{2}:\d{2})$/.test(source.capturedAt) && Number.isFinite(Date.parse(source.capturedAt)), 'INVALID_CAPTURE_TIME', 'capturedAt must have an explicit timezone');
    sources.set(id, source);
    cleanSources.push({ id, provider: text(source.provider, 'source.provider'), url: url.href, capturedAt: source.capturedAt, method: text(source.method, 'source.method') });
  }
  const warnings = [];
  const options = [...railOptions(input, sources, warnings), ...flightOptions(input, sources)];
  const seen = new Set();
  for (const option of options) {
    requireValue(!seen.has(option.optionId), 'DUPLICATE_OPTION', 'Duplicate option; merge source evidence explicitly instead of silently deduplicating');
    seen.add(option.optionId);
  }
  return { valid: true, schemaVersion: 1, queryDate: input.queryDate, sources: cleanSources, options, summary: summarize(options), warnings };
}
