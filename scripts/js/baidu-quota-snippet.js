() => {
  const t = document.body.innerText;
  const idx = t.indexOf("今日");
  return {
    url: location.href,
    snippet: idx >= 0 ? t.slice(Math.max(0, idx - 300), idx + 400) : t.slice(0, 800),
  };
}
