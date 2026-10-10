async () => {
  // Run after the user has queried and the result table has settled.
  // Export only public timetable fields; never export booking secrets or row HTML.
  const value = (selector) => document.querySelector(selector)?.value || '';
  const queryDate = value('#train_date');
  const from = value('#fromStation');
  const to = value('#toStation');
  if (!/^\d{4}-\d{2}-\d{2}$/.test(queryDate) || !/^[A-Z]{3}$/.test(from) || !/^[A-Z]{3}$/.test(to)) {
    throw new Error('Query form must contain an explicit date and valid station codes');
  }
  const readDom = () => Array.from(document.querySelectorAll('#queryLeftTable tr[id^="ticket_"]')).map((row) => {
    const stations = Array.from(row.querySelectorAll('.cdz strong'));
    const times = Array.from(row.querySelectorAll('.cds strong'));
    const readText = (element) => (element?.textContent || '').trim();
    const dates = new Set();
    for (const cell of row.querySelectorAll('td[hbdata], td[hbid]')) {
      for (const attribute of ['hbdata', 'hbid']) {
        // Extract only the public date prefix; discard the rest of the attribute.
        const date = /^(\d{4}-\d{2}-\d{2})#/.exec(cell.getAttribute(attribute) || '');
        if (date) {
          dates.add(date[1]);
        }
      }
    }
    if (dates.size > 1) {
      throw new Error('Conflicting boarding dates in a rendered row');
    }
    return {
      rowId: row.id,
      boardingDate: dates.size === 1 ? [...dates][0] : null,
      number: readText(row.querySelector('a.number')),
      fromName: readText(stations[0]),
      toName: readText(stations[1]),
      departure: readText(times[0]),
      arrival: readText(times[1]),
      duration: readText(row.querySelector('.ls strong')),
      seats: Array.from(row.querySelectorAll('td[id]')).filter((cell) => /^[A-Z]+_/.test(cell.id)).map((cell) => ({
        code: cell.id.split('_')[0], text: readText(cell), aria: cell.getAttribute('aria-label') || ''
      }))
    };
  });
  const before = readDom();
  const endpoint = typeof window.CLeftTicketUrl === 'string' ? window.CLeftTicketUrl.replace(/^\/?otn\//, '') : 'leftTicket/query';
  if (!/^leftTicket\/query[A-Z]?$/.test(endpoint)) {
    throw new Error('Unsupported query endpoint; inspect the page before changing the adapter');
  }
  const parameters = new URLSearchParams({ 'leftTicketDTO.train_date': queryDate, 'leftTicketDTO.from_station': from, 'leftTicketDTO.to_station': to, purpose_codes: 'ADULT' });
  const response = await fetch('/otn/' + endpoint + '?' + parameters, { credentials: 'same-origin' });
  if (!response.ok) {
    throw new Error('Rail query HTTP request failed');
  }
  const payload = await response.json();
  if (payload.status !== true || !Array.isArray(payload.data?.result)) {
    throw new Error('Rail query did not return a successful result array');
  }
  const stationResponse = await fetch('/otn/resources/js/framework/station_name.js', { credentials: 'same-origin' });
  if (!stationResponse.ok) {
    throw new Error('Station table HTTP request failed');
  }
  const stationTable = await stationResponse.text();
  const after = readDom();
  if (value('#train_date') !== queryDate || value('#fromStation') !== from || value('#toStation') !== to || JSON.stringify(before) !== JSON.stringify(after)) {
    throw new Error('Query form or results changed while collecting; collect again after settling');
  }
  const allowed = new Set([2, 3, 6, 7, 8, 9, 10, 11, 13, 16, 17, 21, 22, 23, 24, 26, 28, 29, 30, 31, 32]);
  const rawRows = payload.data.result.map((row) => row.split('|').map((field, index) => allowed.has(index) ? field : ''));
  const capturedAt = new Date().toISOString();
  const source = (id, path, method) => ({ id, provider: '12306', url: location.origin + path, capturedAt, method });
  return {
    schemaVersion: 1, queryDate,
    sources: [source('rail-api', '/otn/' + endpoint, 'same-origin query'), source('rail-dom', location.pathname, 'DOM aria and text'), source('rail-stations', '/otn/resources/js/framework/station_name.js', 'station table text')],
    rail: { queryDate, domDate: value('#train_date'), from, to, stationTable, rawSourceId: 'rail-api', domSourceId: 'rail-dom', stationSourceId: 'rail-stations', rawRows, domRows: after }
  };
}
