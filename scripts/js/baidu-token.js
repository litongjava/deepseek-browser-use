() => {
  const text = document.body.innerText;
  const m = text.match(/token\s*=\s*([A-Za-z0-9_-]+)/g) || [];
  const url = location.href;
  const site = (document.querySelector("#site-select, input[type=text]") || {}).value || null;
  return { url, site, tokens: m };
}
